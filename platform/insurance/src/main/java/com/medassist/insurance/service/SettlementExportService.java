package com.medassist.insurance.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.time.BusinessTime;
import com.medassist.insurance.domain.InsuranceModel.BillLine;
import com.medassist.insurance.domain.InsuranceModel.ExportBatch;
import com.medassist.insurance.domain.InsuranceModel.ExportMessageTemplate;
import com.medassist.insurance.domain.InsuranceModel.SettlementBill;
import com.medassist.insurance.domain.InsuranceModel.TemplateField;
import com.medassist.insurance.repo.InsuranceRepository;
import com.medassist.insurance.web.InsuranceDtos.ExportBatchSummary;
import com.medassist.insurance.web.InsuranceDtos.ExportReceiptReq;
import org.springframework.dao.DataIntegrityViolationException;
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
 * 医保结算清单导出批次与省局回执（N5 第二批 B）。
 *
 * <p>设计要点：
 * <ul>
 *   <li>只导出 **CONFIRMED** 清单（DRAFT/VOIDED 不导出），按 `confirmed_at` 落在期间内筛选；</li>
 *   <li>批次固化 XML 报盘与 SHA-256，事后核对不依赖目录/参保参数的当前值；</li>
 *   <li>回执登记单向：GENERATED → ACCEPTED / REJECTED，用"期望状态 + 版本号"CAS；</li>
 *   <li>单批上限 500 张、期间上限 92 天：避免一次生成超大报文拖垮服务与省局前置机。</li>
 * </ul>
 *
 * <p>报文格式为**院内标准导出格式**（`settlementBatch version="1"`），
 * 省局适配层可在此基础上做字段映射；不在此处伪造省局私有协议。
 */
@Service
public class SettlementExportService {

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    private static final int MAX_BILLS = 500;
    private static final int MAX_PERIOD_DAYS = 92;

    private final InsuranceRepository repo;
    private final ObjectMapper objectMapper;

    public SettlementExportService(InsuranceRepository repo, ObjectMapper objectMapper) {
        this.repo = repo;
        this.objectMapper = objectMapper;
    }

    /**
     * 生成批次的结果：批次本体 + 被跳过的已报盘张数 + 本期间仍未报盘的剩余张数。
     *
     * <p>两个数量都是刻意回显的：操作员需要知道"本期间还有几张没进这批"（第四轮 MF-01），
     * 以及"超过单批上限、需要再生成一批"的部分（第四轮 SF-02）。
     */
    public record ExportCreateResult(ExportBatch batch, int excludedAlreadyExported,
                                     int remainingPending) {
    }

    // ---------------------------------------------------------------- 生成批次

    @Transactional
    public ExportCreateResult create(LocalDate from, LocalDate to, String actor) {
        return create(from, to, "", actor);
    }

    /**
     * 生成报盘批次（G53）：{@code campusCsv} 为空串表示不限院区。
     *
     * <p>省局报文含批量患者号与逐条费用，必须只纳入调用方可见院区的清单；
     * 否则单院区医保办生成批次即可把其他院区患者明细一起报出去。
     */
    @Transactional
    public ExportCreateResult create(LocalDate from, LocalDate to, String campusCsv, String actor) {
        if (from == null || to == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "请指定导出期间（from/to）");
        }
        if (to.isBefore(from)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "结束日期不能早于开始日期");
        }
        if (from.plusDays(MAX_PERIOD_DAYS).isBefore(to)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "单批导出期间不得超过 " + MAX_PERIOD_DAYS + " 天，请分段导出");
        }
        if (to.isAfter(BusinessTime.today())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "导出期间不能晚于今天");
        }
        // 同期间的并发生成必须互斥：否则两个事务的 NOT EXISTS 都在对方提交前查空，
        // 同一批清单会进入两个 GENERATED 批次 → 省局重复结算（第五轮 R5-M5）
        repo.lockExportPeriod(from, to);
        // 已报盘（GENERATED/ACCEPTED）的清单不再重复纳入；退回/撤销批次可重报
        long excluded = repo.countConfirmedBillsAlreadyExported(from, to, campusCsv);
        long pending = repo.countConfirmedBillsPending(from, to, campusCsv);
        if (pending == 0) {
            if (excluded > 0) {
                throw new BusinessException(ErrorCode.INSURANCE_EXPORT_EMPTY,
                        "本期间 " + excluded + " 张清单均已纳入其它批次，无需重复报盘");
            }
            throw new BusinessException(ErrorCode.INSURANCE_EXPORT_EMPTY);
        }
        // 单批最多 500 张：超出部分不拒绝，而是生成当前批并回显剩余张数，
        // 操作员再次调用即自动接着报（已报盘清单已被排除，天然续批，SF-02）
        List<SettlementBill> bills = repo.listConfirmedBills(from, to, campusCsv, MAX_BILLS);
        if (bills.isEmpty()) {
            throw new BusinessException(ErrorCode.INSURANCE_EXPORT_EMPTY);
        }
        int remaining = (int) Math.max(0, pending - bills.size());

        // G85：已发布的报文模板决定报盘元素名与必填口径；无模板时回落内置 BUILTIN。
        // 模板在生成批次前解析并校验，避免"批次已生成、报文却缺字段"。
        ExportMessageTemplate template = repo.findPublishedExportTemplate().orElse(null);
        List<TemplateField> templateFields = null;
        if (template != null) {
            templateFields = ExportTemplateService.parseFields(objectMapper, template.fields());
            ExportTemplateService.validateFields(templateFields);
            validateTemplateRequired(templateFields, bills);
        }

        String batchNo = repo.nextExportBatchNo();
        String payload = buildPayload(batchNo, from, to, bills, template, templateFields);
        BigDecimal total = sum(bills, SettlementBill::totalAmount);
        BigDecimal insurance = sum(bills, SettlementBill::insuranceAmount);
        BigDecimal selfPay = sum(bills, SettlementBill::selfPayAmount);

        String templateCode = template == null
                ? ExportBatch.BUILTIN_TEMPLATE : template.code();
        int templateVersion = template == null ? 1 : template.version();
        ExportBatch batch = new ExportBatch(UUID.randomUUID(), batchNo, from, to, bills.size(),
                total, insurance, selfPay, payload, sha256(payload), templateCode,
                templateVersion, ExportBatch.GENERATED, null, null, null, null, null, null,
                actor, 0L, Instant.now());
        repo.insertExportBatch(batch);
        try {
            for (SettlementBill bill : bills) {
                repo.insertExportItem(batch.id(), bill.id(), bill.billNo());
            }
        } catch (DataIntegrityViolationException e) {
            // V13 触发器兜底：重叠期间并发/串行生成时，同一清单不得进两个有效批次。
            // 这里整批回滚并返回 409，让操作员刷新后重报（剩余清单会被重新筛选）。
            throw new BusinessException(ErrorCode.INSURANCE_EXPORT_INVALID,
                    "部分结算清单已被其它有效报盘批次包含，本批已回滚，请刷新后重新生成");
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("batchNo", batchNo);
        detail.put("periodFrom", from.toString());
        detail.put("periodTo", to.toString());
        detail.put("billCount", bills.size());
        detail.put("excludedAlreadyExported", excluded);
        detail.put("remainingPending", remaining);
        detail.put("totalAmount", total.toPlainString());
        detail.put("insuranceAmount", insurance.toPlainString());
        detail.put("payloadHash", batch.payloadHash());
        detail.put("templateCode", templateCode);
        detail.put("templateVersion", templateVersion);
        audit(actor, "EXPORT_BATCH_CREATE", batchNo, detail);
        return new ExportCreateResult(repo.findExportBatch(batch.id()).orElse(batch),
                (int) excluded, remaining);
    }

    // ---------------------------------------------------------------- 回执

    @Transactional
    public ExportBatch receipt(UUID id, ExportReceiptReq req, String actor) {
        ExportBatch batch = repo.findExportBatch(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.INSURANCE_EXPORT_NOT_FOUND));
        if (batch.receipted()) {
            throw new BusinessException(ErrorCode.INSURANCE_EXPORT_INVALID,
                    "该批次已登记回执（" + batch.status() + "），不能重复登记");
        }
        String receiptNo = req.receiptNo().trim();
        String message = req.message() == null || req.message().isBlank()
                ? null : req.message().trim();
        if (ExportBatch.REJECTED.equals(req.status()) && message == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "退回（REJECTED）必须填写退回原因");
        }
        if (!repo.updateExportReceipt(id, req.status(), receiptNo, message, batch.version())) {
            throw new BusinessException(ErrorCode.CONFLICT, "导出批次已被他人更新，请刷新后重试");
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("batchNo", batch.batchNo());
        detail.put("before", batch.status());
        detail.put("after", req.status());
        detail.put("receiptNo", receiptNo);
        detail.put("message", message);
        audit(actor, "EXPORT_RECEIPT", batch.batchNo(), detail);
        return repo.findExportBatch(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.INSURANCE_EXPORT_NOT_FOUND));
    }

    // ---------------------------------------------------------------- 撤销

    /**
     * 撤销未回执的批次（GENERATED → CANCELLED）。
     *
     * <p>存在的意义：批次一旦生成就把清单"锁住"（不得重复报盘、不得作废），
     * 若生成后发现有误（期间选错、金额异常），必须有正规出口；
     * 已回执（ACCEPTED/REJECTED）的批次不可撤销，只能走省局冲销流程。
     */
    @Transactional
    public ExportBatch cancel(UUID id, String reason, String actor) {
        ExportBatch batch = find(id);
        if (ExportBatch.CANCELLED.equals(batch.status())) {
            throw new BusinessException(ErrorCode.INSURANCE_EXPORT_INVALID, "该批次已撤销");
        }
        if (batch.receipted()) {
            throw new BusinessException(ErrorCode.INSURANCE_EXPORT_INVALID,
                    "该批次已登记回执（" + batch.status() + "），不能撤销；请按省局流程冲销");
        }
        String normalized = reason == null ? "" : reason.trim();
        if (normalized.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "撤销报盘批次必须填写原因");
        }
        if (!repo.cancelExportBatch(id, normalized, actor, batch.version())) {
            throw new BusinessException(ErrorCode.CONFLICT, "导出批次已被他人更新，请刷新后重试");
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("batchNo", batch.batchNo());
        detail.put("before", batch.status());
        detail.put("after", ExportBatch.CANCELLED);
        detail.put("reason", normalized);
        detail.put("billCount", batch.billCount());
        detail.put("payloadHash", batch.payloadHash());
        audit(actor, "EXPORT_BATCH_CANCEL", batch.batchNo(), detail);
        return find(id);
    }

    // ---------------------------------------------------------------- 查询

    public List<ExportBatchSummary> list(Integer limit) {
        int size = limit == null || limit <= 0 ? 20 : Math.min(limit, 100);
        return repo.listExportBatches(size).stream()
                .map(b -> summary(b, 0, 0)).toList();
    }

    public ExportBatchSummary summary(UUID id) {
        return summary(find(id), 0, 0);
    }

    public ExportBatchSummary summary(ExportBatch batch, int excludedAlreadyExported,
                                       int remainingPending) {
        return new ExportBatchSummary(batch.id(), batch.batchNo(), batch.periodFrom(),
                batch.periodTo(), batch.billCount(), batch.totalAmount(),
                batch.insuranceAmount(), batch.selfPayAmount(), batch.payloadHash(),
                batch.status(), batch.receiptNo(), batch.receiptAt(), batch.receiptMessage(),
                batch.cancelledReason(), batch.cancelledAt(), batch.createdBy(),
                batch.version(), batch.createdAt(), excludedAlreadyExported, remainingPending,
                batch.templateCode(), batch.templateVersion());
    }

    /**
     * 报盘 XML 原文（下载）。
     *
     * <p>整批报文含患者号与项目明细（批量 PHI），下载动作必须留痕：
     * 与病历打印/检索同口径（第三轮 S-07 / 第四轮 MF-02）。
     */
    public ExportBatch payload(UUID id, String actor) {
        return payload(id, "", actor);
    }

    /**
     * 下载报文（G53）：{@code campusCsv} 非空时要求批次内清单全部在可见院区内。
     *
     * <p>越权一律按"批次不存在"返回，避免通过错误码差异探测其他院区的批次号。
     */
    public ExportBatch payload(UUID id, String campusCsv, String actor) {
        ExportBatch batch = find(id);
        if (campusCsv != null && !campusCsv.isBlank()) {
            java.util.Set<UUID> allowed = java.util.Arrays.stream(campusCsv.split(","))
                    .map(String::trim).filter(s -> !s.isEmpty())
                    .map(UUID::fromString)
                    .collect(java.util.stream.Collectors.toSet());
            boolean allVisible = repo.listBatchCampusIds(batch.id()).stream()
                    .allMatch(allowed::contains);
            if (!allVisible) {
                throw new BusinessException(ErrorCode.INSURANCE_EXPORT_NOT_FOUND);
            }
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("batchNo", batch.batchNo());
        detail.put("payloadHash", batch.payloadHash());
        detail.put("billCount", batch.billCount());
        detail.put("status", batch.status());
        audit(actor, "EXPORT_PAYLOAD_DOWNLOAD", batch.batchNo(), detail);
        return batch;
    }

    private ExportBatch find(UUID id) {
        return repo.findExportBatch(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.INSURANCE_EXPORT_NOT_FOUND));
    }

    // ---------------------------------------------------------------- 报文

    private String buildPayload(String batchNo, LocalDate from, LocalDate to,
                                List<SettlementBill> bills, ExportMessageTemplate template,
                                List<TemplateField> templateFields) {
        BigDecimal total = sum(bills, SettlementBill::totalAmount);
        BigDecimal insurance = sum(bills, SettlementBill::insuranceAmount);
        BigDecimal selfPay = sum(bills, SettlementBill::selfPayAmount);
        String templateCode = template == null ? ExportBatch.BUILTIN_TEMPLATE : template.code();
        int templateVersion = template == null ? 1 : template.version();
        StringBuilder xml = new StringBuilder(4096);
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<settlementBatch version=\"1\" batchNo=\"").append(escape(batchNo))
                .append("\" periodFrom=\"").append(from)
                .append("\" periodTo=\"").append(to)
                .append("\" generatedAt=\"").append(Instant.now())
                .append("\" templateCode=\"").append(escape(templateCode))
                .append("\" templateVersion=\"").append(templateVersion)
                .append("\">\n");
        xml.append("  <summary billCount=\"").append(bills.size())
                .append("\" totalAmount=\"").append(money(total))
                .append("\" insuranceAmount=\"").append(money(insurance))
                .append("\" selfPayAmount=\"").append(money(selfPay))
                .append("\"/>\n");
        for (SettlementBill bill : bills) {
            xml.append("  <bill");
            if (templateFields == null) {
                appendBuiltinBillAttributes(xml, bill);
            } else {
                // 模板驱动：元素名与顺序以已发布模板为准（字段名已在导出前校验过）
                for (TemplateField field : templateFields) {
                    xml.append(" ").append(field.element()).append("=\"")
                            .append(escape(sourceValue(bill, field.source()))).append("\"");
                }
            }
            xml.append(">\n");
            // 省局结算清单要求主诊断 + 其他诊断；清单生成时已固化诊断快照（第六轮 MF-15）
            xml.append("    <diagnoses>");
            for (Map<String, Object> d : diagnoses(bill)) {
                xml.append("<diagnosis code=\"").append(escape(str(d.get("code"))))
                        .append("\" name=\"").append(escape(str(d.get("name"))))
                        .append("\" primary=\"").append(Boolean.TRUE.equals(d.get("primary")))
                        .append("\"/>");
            }
            xml.append("</diagnoses>\n");
            for (BillLine line : lines(bill)) {
                xml.append("    <line chargeNo=\"").append(escape(line.chargeNo()))
                        .append("\" itemCode=\"").append(escape(line.itemCode()))
                        .append("\" itemName=\"").append(escape(line.itemName()))
                        .append("\" category=\"").append(escape(line.category()))
                        .append("\" chargeStatus=\"").append(escape(line.chargeStatus()))
                        .append("\" amount=\"").append(money(line.amount()))
                        .append("\" insuranceCode=\"").append(escape(line.insuranceCode()))
                        .append("\" payCategory=\"").append(escape(line.payCategory()))
                        .append("\" selfPayRatio=\"").append(plain(line.selfPayRatio()))
                        .append("\" overLimitAmount=\"").append(plain(line.overLimitAmount()))
                        .append("\" eligibleAmount=\"").append(plain(line.eligibleAmount()))
                        .append("\" unmapped=\"").append(line.unmapped())
                        .append("\"/>\n");
            }
            xml.append("  </bill>\n");
        }
        xml.append("</settlementBatch>\n");
        return xml.toString();
    }

    /** 内置布局（未配置/已停用模板时的默认报盘字段，保持历史报文兼容）。 */
    private static void appendBuiltinBillAttributes(StringBuilder xml, SettlementBill bill) {
        xml.append(" billNo=\"").append(escape(bill.billNo()))
                .append("\" visitId=\"").append(bill.visitId())
                .append("\" patientNo=\"").append(escape(bill.patientNo()))
                .append("\" totalAmount=\"").append(money(bill.totalAmount()))
                .append("\" eligibleAmount=\"").append(money(bill.eligibleAmount()))
                .append("\" deductible=\"").append(money(bill.deductible()))
                .append("\" insuranceAmount=\"").append(money(bill.insuranceAmount()))
                .append("\" selfPayAmount=\"").append(money(bill.selfPayAmount()))
                .append("\" diagnosisCount=\"").append(bill.diagnosisCount())
                .append("\" primaryDiagnosisCode=\"")
                .append(escape(bill.primaryDiagnosisCode()))
                .append("\" primaryDiagnosisName=\"")
                .append(escape(bill.primaryDiagnosisName()))
                .append("\" confirmedBy=\"").append(escape(bill.confirmedBy()))
                .append("\" confirmedAt=\"").append(bill.confirmedAt())
                .append("\"");
    }

    /**
     * 模板必填项取不到值：生成批次前显式失败（422），不能报出一份省局必然退回的报文。
     *
     * <p>典型场景：省局要求主诊断必填，而某张清单未编码诊断（历史数据）。
     */
    private static void validateTemplateRequired(List<TemplateField> fields,
                                                 List<SettlementBill> bills) {
        for (SettlementBill bill : bills) {
            for (TemplateField field : fields) {
                if (!field.isRequired()) {
                    continue;
                }
                String value = sourceValue(bill, field.source());
                if (value == null || value.isBlank()) {
                    throw new BusinessException(ErrorCode.INSURANCE_TEMPLATE_REQUIRED_MISSING,
                            "模板必填字段 " + field.element() + "（来源 " + field.source()
                                    + "）在清单 " + bill.billNo() + " 中无值，无法报盘");
                }
            }
        }
    }

    /** 院内字段取值（未格式化，null 表示该清单没有这个值）。 */
    private static String sourceValue(SettlementBill bill, String source) {
        return switch (source) {
            case "billNo" -> bill.billNo();
            case "visitId" -> bill.visitId() == null ? null : bill.visitId().toString();
            case "patientNo" -> bill.patientNo();
            case "totalAmount" -> money(bill.totalAmount());
            case "eligibleAmount" -> money(bill.eligibleAmount());
            case "deductible" -> money(bill.deductible());
            case "insuranceAmount" -> money(bill.insuranceAmount());
            case "selfPayAmount" -> money(bill.selfPayAmount());
            case "diagnosisCount" -> String.valueOf(bill.diagnosisCount());
            case "primaryDiagnosisCode" -> bill.primaryDiagnosisCode();
            case "primaryDiagnosisName" -> bill.primaryDiagnosisName();
            case "confirmedBy" -> bill.confirmedBy();
            case "confirmedAt" -> bill.confirmedAt() == null ? null : bill.confirmedAt().toString();
            default -> null;
        };
    }

    /** 明细是生成时固化的 JSON 快照；解析失败说明数据损坏，必须显式失败而不是导出残缺报文。 */
    private List<BillLine> lines(SettlementBill bill) {
        try {
            BillLine[] lines = objectMapper.readValue(bill.detail(), BillLine[].class);
            return lines == null ? List.of() : List.of(lines);
        } catch (JacksonException e) {
            throw new BusinessException(ErrorCode.INSURANCE_EXPORT_INVALID,
                    "清单明细损坏，无法导出：" + bill.billNo());
        }
    }

    /** 诊断快照（生成清单时固化）；损坏同样必须显式失败，不能导出缺诊断的报盘。 */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> diagnoses(SettlementBill bill) {
        String json = bill.diagnoses();
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<Map<String, Object>> rows = objectMapper.readValue(json, List.class);
            return rows == null ? List.of() : rows;
        } catch (JacksonException e) {
            throw new BusinessException(ErrorCode.INSURANCE_EXPORT_INVALID,
                    "清单诊断快照损坏，无法导出：" + bill.billNo());
        }
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static BigDecimal sum(List<SettlementBill> bills,
                                  java.util.function.Function<SettlementBill, BigDecimal> getter) {
        BigDecimal total = ZERO;
        for (SettlementBill bill : bills) {
            BigDecimal value = getter.apply(bill);
            total = total.add(value == null ? ZERO : value);
        }
        return total.setScale(2, RoundingMode.HALF_UP);
    }

    private static String money(BigDecimal value) {
        return (value == null ? ZERO : value).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String plain(BigDecimal value) {
        return value == null ? "" : value.toPlainString();
    }

    /** XML 属性转义：报盘包含患者号/项目名，不能假设内容安全。 */
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
            repo.logChange(actor, action, "EXPORT_BATCH", targetId,
                    objectMapper.writeValueAsString(detail));
        } catch (JacksonException e) {
            repo.logChange(actor, action, "EXPORT_BATCH", targetId,
                    "{\"_detail_serialize_failed\":true}");
        }
    }
}
