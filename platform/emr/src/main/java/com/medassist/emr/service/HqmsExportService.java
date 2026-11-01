package com.medassist.emr.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.time.BusinessTime;
import com.medassist.emr.domain.EmrModel.Homepage;
import com.medassist.emr.domain.EmrModel.HqmsExportBatch;
import com.medassist.emr.repo.EmrRepository;
import com.medassist.emr.web.EmrDtos.HqmsExportBatchSummary;
import com.medassist.emr.web.EmrDtos.HqmsReceiptReq;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 病案首页 HQMS 上报批次（与医保报盘同一套治理思路）。
 *
 * <p>设计要点：
 * <ul>
 *   <li>只导出 **ARCHIVED** 首页，按出院日期落在期间内筛选；DRAFT/SUBMITTED 不导出；</li>
 *   <li>批次固化 XML 与 SHA-256，事后核对不依赖首页当前值；</li>
 *   <li>一个首页同时只能在一个有效批次（GENERATED/ACCEPTED）——期间 advisory lock + 数据库触发器双保险；</li>
 *   <li>回执与撤销都是单向迁移；退回（REJECTED）/撤销（CANCELLED）会释放首页，可重新上报。</li>
 * </ul>
 *
 * <p>报文为**院内标准导出格式**（`homepageBatch version="1"`），省/市 HQMS 前置机适配层
 * 可在此基础上做字段映射；不在此伪造地方私有协议。
 */
@Service
public class HqmsExportService {

    private static final int MAX_HOMEPAGES = 500;
    private static final int MAX_PERIOD_DAYS = 92;

    private final EmrRepository repo;
    private final ObjectMapper objectMapper;

    public HqmsExportService(EmrRepository repo, ObjectMapper objectMapper) {
        this.repo = repo;
        this.objectMapper = objectMapper;
    }

    /**
     * 生成批次的结果。
     *
     * @param excludedAlreadyExported 期间内已上报、本次被排除的首页数
     * @param remainingPending        超过单批上限、仍需再报的首页数
     */
    public record CreateResult(HqmsExportBatch batch, int excludedAlreadyExported,
                               int remainingPending) {
    }

    // ---------------------------------------------------------------- 生成批次

    @Transactional
    public CreateResult create(LocalDate from, LocalDate to, String actor) {
        if (from == null || to == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "请指定上报期间（from/to）");
        }
        if (to.isBefore(from)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "结束日期不能早于开始日期");
        }
        if (from.plusDays(MAX_PERIOD_DAYS).isBefore(to)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "单批上报期间不得超过 " + MAX_PERIOD_DAYS + " 天，请分段上报");
        }
        if (to.isAfter(BusinessTime.today())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "上报期间不能晚于今天");
        }
        // 并发 create 的 NOT EXISTS 会在对方提交前查空，必须按期间串行
        repo.lockHqmsPeriod(from, to);
        long excluded = repo.countHqmsAlreadyExported(from, to);
        long pending = repo.countHqmsPending(from, to);
        if (pending == 0) {
            if (excluded > 0) {
                throw new BusinessException(ErrorCode.EMR_HQMS_EXPORT_EMPTY,
                        "本期间 " + excluded + " 份病案首页均已纳入其它批次，无需重复上报");
            }
            throw new BusinessException(ErrorCode.EMR_HQMS_EXPORT_EMPTY);
        }
        List<Homepage> homepages = repo.listHqmsPending(from, to, MAX_HOMEPAGES);
        if (homepages.isEmpty()) {
            throw new BusinessException(ErrorCode.EMR_HQMS_EXPORT_EMPTY);
        }
        int remaining = (int) Math.max(0, pending - homepages.size());

        String batchNo = repo.nextHqmsBatchNo();
        String payload = buildPayload(batchNo, from, to, homepages);
        BigDecimal total = homepages.stream()
                .map(h -> h.totalAmount() == null ? BigDecimal.ZERO : h.totalAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);

        HqmsExportBatch batch = new HqmsExportBatch(UUID.randomUUID(), batchNo, from, to,
                homepages.size(), total, payload, sha256(payload), HqmsExportBatch.GENERATED,
                null, null, null, null, null, actor, 0L, Instant.now());
        repo.insertHqmsBatch(batch);
        for (Homepage homepage : homepages) {
            repo.insertHqmsItem(batch.id(), homepage);
        }

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("batchNo", batchNo);
        detail.put("periodFrom", from.toString());
        detail.put("periodTo", to.toString());
        detail.put("homepageCount", homepages.size());
        detail.put("excludedAlreadyExported", excluded);
        detail.put("remainingPending", remaining);
        detail.put("totalAmount", total.toPlainString());
        detail.put("payloadHash", batch.payloadHash());
        audit(actor, "HQMS_BATCH_CREATE", batchNo, detail);
        return new CreateResult(repo.findHqmsBatch(batch.id()).orElse(batch),
                (int) excluded, remaining);
    }

    // ---------------------------------------------------------------- 回执 / 撤销

    @Transactional
    public HqmsExportBatch receipt(UUID id, HqmsReceiptReq req, String actor) {
        HqmsExportBatch batch = find(id);
        if (batch.receipted()) {
            throw new BusinessException(ErrorCode.EMR_HQMS_EXPORT_INVALID,
                    "该批次已登记回执（" + batch.status() + "），不能重复登记");
        }
        if (HqmsExportBatch.CANCELLED.equals(batch.status())) {
            throw new BusinessException(ErrorCode.EMR_HQMS_EXPORT_INVALID,
                    "该批次已撤销，不能登记回执");
        }
        String receiptNo = req.receiptNo().trim();
        String message = req.message() == null || req.message().isBlank()
                ? null : req.message().trim();
        if (HqmsExportBatch.REJECTED.equals(req.status()) && message == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "退回（REJECTED）必须填写退回原因");
        }
        if (!repo.updateHqmsReceipt(id, req.status(), receiptNo, message, batch.version())) {
            throw new BusinessException(ErrorCode.CONFLICT, "上报批次已被他人更新，请刷新后重试");
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("batchNo", batch.batchNo());
        detail.put("before", batch.status());
        detail.put("after", req.status());
        detail.put("receiptNo", receiptNo);
        detail.put("message", message);
        audit(actor, "HQMS_BATCH_RECEIPT", batch.batchNo(), detail);
        return find(id);
    }

    /**
     * 撤销未回执的批次（GENERATED → CANCELLED），撤销后首页可在新批次重报。
     *
     * <p>存在的意义：批次一旦生成就把首页"锁住"，若期间选错或报文异常，必须有正规出口；
     * 已回执（ACCEPTED/REJECTED）的批次不可撤销，只能走上级主管部门的冲销流程。
     */
    @Transactional
    public HqmsExportBatch cancel(UUID id, String reason, String actor) {
        HqmsExportBatch batch = find(id);
        if (HqmsExportBatch.CANCELLED.equals(batch.status())) {
            throw new BusinessException(ErrorCode.EMR_HQMS_EXPORT_INVALID, "该批次已撤销");
        }
        if (batch.receipted()) {
            throw new BusinessException(ErrorCode.EMR_HQMS_EXPORT_INVALID,
                    "该批次已登记回执（" + batch.status() + "），不能撤销");
        }
        String normalized = reason == null ? "" : reason.trim();
        if (normalized.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "撤销上报批次必须填写原因");
        }
        if (!repo.cancelHqmsBatch(id, normalized, batch.version())) {
            throw new BusinessException(ErrorCode.CONFLICT, "上报批次已被他人更新，请刷新后重试");
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("batchNo", batch.batchNo());
        detail.put("before", batch.status());
        detail.put("after", HqmsExportBatch.CANCELLED);
        detail.put("reason", normalized);
        detail.put("homepageCount", batch.homepageCount());
        detail.put("payloadHash", batch.payloadHash());
        audit(actor, "HQMS_BATCH_CANCEL", batch.batchNo(), detail);
        return find(id);
    }

    // ---------------------------------------------------------------- 查询 / 下载

    public List<HqmsExportBatchSummary> list(Integer limit) {
        int size = limit == null || limit <= 0 ? 20 : Math.min(limit, 100);
        return repo.listHqmsBatches(size).stream()
                .map(b -> summary(b, 0, 0)).toList();
    }

    public HqmsExportBatchSummary summary(UUID id) {
        return summary(find(id), 0, 0);
    }

    public HqmsExportBatchSummary summary(HqmsExportBatch batch, int excludedAlreadyExported,
                                          int remainingPending) {
        return new HqmsExportBatchSummary(batch.id(), batch.batchNo(), batch.periodFrom(),
                batch.periodTo(), batch.homepageCount(), batch.totalAmount(), batch.payloadHash(),
                batch.status(), batch.receiptNo(), batch.receiptMessage(), batch.receiptAt(),
                batch.cancelledReason(), batch.cancelledAt(), batch.createdBy(), batch.version(),
                batch.createdAt(), excludedAlreadyExported, remainingPending);
    }

    /** 报文原文（下载）：整批含患者姓名/诊断，下载动作必须留痕。 */
    public HqmsExportBatch payload(UUID id, String actor) {
        HqmsExportBatch batch = find(id);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("batchNo", batch.batchNo());
        detail.put("payloadHash", batch.payloadHash());
        detail.put("homepageCount", batch.homepageCount());
        detail.put("status", batch.status());
        audit(actor, "HQMS_PAYLOAD_DOWNLOAD", batch.batchNo(), detail);
        return batch;
    }

    private HqmsExportBatch find(UUID id) {
        return repo.findHqmsBatch(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.EMR_HQMS_NOT_FOUND));
    }

    // ---------------------------------------------------------------- 报文

    private String buildPayload(String batchNo, LocalDate from, LocalDate to,
                                List<Homepage> homepages) {
        BigDecimal total = homepages.stream()
                .map(h -> h.totalAmount() == null ? BigDecimal.ZERO : h.totalAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
        StringBuilder xml = new StringBuilder(8192);
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<homepageBatch version=\"1\" batchNo=\"").append(escape(batchNo))
                .append("\" periodFrom=\"").append(from)
                .append("\" periodTo=\"").append(to)
                .append("\" generatedAt=\"").append(Instant.now())
                .append("\">\n");
        xml.append("  <summary homepageCount=\"").append(homepages.size())
                .append("\" totalAmount=\"").append(money(total)).append("\"/>\n");
        for (Homepage h : homepages) {
            xml.append("  <homepage homepageNo=\"").append(escape(h.homepageNo()))
                    .append("\" visitNo=\"").append(escape(h.visitNo()))
                    .append("\" patientNo=\"").append(escape(h.patientNo()))
                    .append("\" patientName=\"").append(escape(h.patientName()))
                    .append("\" deptName=\"").append(escape(h.deptName()))
                    .append("\" wardName=\"").append(escape(h.wardName()))
                    .append("\" bedNo=\"").append(escape(h.bedNo()))
                    .append("\" admittedAt=\"").append(h.admittedAt())
                    .append("\" dischargedAt=\"").append(h.dischargedAt())
                    .append("\" stayDays=\"").append(h.stayDays())
                    .append("\" admissionWay=\"").append(escape(h.admissionWay()))
                    .append("\" dischargeMode=\"").append(escape(h.dischargeMode()))
                    .append("\" readmitPlan=\"").append(escape(h.readmitPlan()))
                    .append("\" maritalStatus=\"").append(escape(h.maritalStatus()))
                    .append("\" occupation=\"").append(escape(h.occupation()))
                    .append("\" nation=\"").append(escape(h.nation()))
                    .append("\" bloodType=\"").append(escape(h.bloodType()))
                    .append("\" rhFactor=\"").append(escape(h.rhFactor()))
                    .append("\" rescueCount=\"").append(h.rescueCount())
                    .append("\" rescueSuccessCount=\"").append(h.rescueSuccessCount())
                    .append("\" criticalCase=\"").append(h.criticalCase())
                    .append("\" hospitalInfection=\"").append(h.hospitalInfection())
                    .append("\" chiefPhysician=\"").append(escape(h.chiefPhysician()))
                    .append("\" residentPhysician=\"").append(escape(h.residentPhysician()))
                    .append("\" coder=\"").append(escape(h.coder()))
                    .append("\" totalAmount=\"").append(money(h.totalAmount()))
                    .append("\" status=\"").append(escape(h.status()))
                    .append("\">\n");
            xml.append("    <diagnoses>");
            for (Map<String, Object> d : readJsonList(h.diagnoses())) {
                xml.append("<diagnosis code=\"").append(escape(str(d.get("code"))))
                        .append("\" name=\"").append(escape(str(d.get("name"))))
                        .append("\" primary=\"").append(Boolean.TRUE.equals(d.get("primary")))
                        .append("\"/>");
            }
            xml.append("</diagnoses>\n");
            xml.append("    <procedures>");
            for (Map<String, Object> p : readJsonList(h.procedures())) {
                xml.append("<procedure code=\"").append(escape(str(p.get("code"))))
                        .append("\" name=\"").append(escape(str(p.get("name"))))
                        .append("\" date=\"").append(escape(str(p.get("date"))))
                        .append("\" surgeon=\"").append(escape(str(p.get("surgeon"))))
                        .append("\" anesthesia=\"").append(escape(str(p.get("anesthesia"))))
                        .append("\" incisionLevel=\"").append(escape(str(p.get("incisionLevel"))))
                        .append("\"/>");
            }
            xml.append("</procedures>\n");
            xml.append("    <fees>");
            readJsonMap(h.feeSummary()).forEach((category, amount) ->
                    xml.append("<fee category=\"").append(escape(category))
                            .append("\" amount=\"").append(money(amount)).append("\"/>"));
            xml.append("</fees>\n");
            xml.append("  </homepage>\n");
        }
        xml.append("</homepageBatch>\n");
        return xml.toString();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> readJsonList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, List.class);
        } catch (JacksonException e) {
            throw new BusinessException(ErrorCode.EMR_HQMS_EXPORT_INVALID,
                    "病案首页快照损坏，无法生成上报报文");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, BigDecimal> readJsonMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> raw = objectMapper.readValue(json, Map.class);
            Map<String, BigDecimal> result = new LinkedHashMap<>();
            raw.forEach((k, v) -> result.put(k, v == null ? BigDecimal.ZERO
                    : new BigDecimal(String.valueOf(v))));
            return result;
        } catch (JacksonException | NumberFormatException e) {
            throw new BusinessException(ErrorCode.EMR_HQMS_EXPORT_INVALID,
                    "病案首页费用快照损坏，无法生成上报报文");
        }
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static String money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value)
                .setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /** XML 属性转义：报文含患者姓名/诊断，不能假设内容安全。 */
    static String escape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&apos;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String sha256(String payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private void audit(String actor, String action, String targetId, Map<String, Object> detail) {
        try {
            repo.logChange(actor, action, "HQMS_BATCH", targetId,
                    objectMapper.writeValueAsString(new LinkedHashMap<>(detail)));
        } catch (JacksonException e) {
            repo.logChange(actor, action, "HQMS_BATCH", targetId,
                    "{\"_detail_serialize_failed\":true}");
        }
    }
}
