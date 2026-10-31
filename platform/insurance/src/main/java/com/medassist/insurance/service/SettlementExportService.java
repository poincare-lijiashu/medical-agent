package com.medassist.insurance.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.time.BusinessTime;
import com.medassist.insurance.domain.InsuranceModel.BillLine;
import com.medassist.insurance.domain.InsuranceModel.ExportBatch;
import com.medassist.insurance.domain.InsuranceModel.SettlementBill;
import com.medassist.insurance.repo.InsuranceRepository;
import com.medassist.insurance.web.InsuranceDtos.ExportBatchSummary;
import com.medassist.insurance.web.InsuranceDtos.ExportReceiptReq;
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

    // ---------------------------------------------------------------- 生成批次

    @Transactional
    public ExportBatch create(LocalDate from, LocalDate to, String actor) {
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
        List<SettlementBill> bills = repo.listConfirmedBills(from, to, MAX_BILLS + 1);
        if (bills.isEmpty()) {
            throw new BusinessException(ErrorCode.INSURANCE_EXPORT_EMPTY);
        }
        if (bills.size() > MAX_BILLS) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "本期间已确认清单超过 " + MAX_BILLS + " 张，请缩短期间分批导出");
        }

        String batchNo = repo.nextExportBatchNo();
        String payload = buildPayload(batchNo, from, to, bills);
        BigDecimal total = sum(bills, SettlementBill::totalAmount);
        BigDecimal insurance = sum(bills, SettlementBill::insuranceAmount);
        BigDecimal selfPay = sum(bills, SettlementBill::selfPayAmount);

        ExportBatch batch = new ExportBatch(UUID.randomUUID(), batchNo, from, to, bills.size(),
                total, insurance, selfPay, payload, sha256(payload), ExportBatch.GENERATED,
                null, null, null, actor, 0L, Instant.now());
        repo.insertExportBatch(batch);
        for (SettlementBill bill : bills) {
            repo.insertExportItem(batch.id(), bill.id(), bill.billNo());
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("batchNo", batchNo);
        detail.put("periodFrom", from.toString());
        detail.put("periodTo", to.toString());
        detail.put("billCount", bills.size());
        detail.put("totalAmount", total.toPlainString());
        detail.put("insuranceAmount", insurance.toPlainString());
        detail.put("payloadHash", batch.payloadHash());
        audit(actor, "EXPORT_BATCH_CREATE", batchNo, detail);
        return repo.findExportBatch(batch.id()).orElse(batch);
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

    // ---------------------------------------------------------------- 查询

    public List<ExportBatchSummary> list(Integer limit) {
        int size = limit == null || limit <= 0 ? 20 : Math.min(limit, 100);
        return repo.listExportBatches(size).stream().map(SettlementExportService::summary).toList();
    }

    public ExportBatchSummary summary(UUID id) {
        return summary(find(id));
    }

    /** 报盘 XML 原文（下载）；哈希与批次记录一致，便于对账。 */
    public ExportBatch payload(UUID id) {
        return find(id);
    }

    private ExportBatch find(UUID id) {
        return repo.findExportBatch(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.INSURANCE_EXPORT_NOT_FOUND));
    }

    private static ExportBatchSummary summary(ExportBatch b) {
        return new ExportBatchSummary(b.id(), b.batchNo(), b.periodFrom(), b.periodTo(),
                b.billCount(), b.totalAmount(), b.insuranceAmount(), b.selfPayAmount(),
                b.payloadHash(), b.status(), b.receiptNo(), b.receiptAt(), b.receiptMessage(),
                b.createdBy(), b.version(), b.createdAt());
    }

    // ---------------------------------------------------------------- 报文

    private String buildPayload(String batchNo, LocalDate from, LocalDate to,
                                List<SettlementBill> bills) {
        BigDecimal total = sum(bills, SettlementBill::totalAmount);
        BigDecimal insurance = sum(bills, SettlementBill::insuranceAmount);
        BigDecimal selfPay = sum(bills, SettlementBill::selfPayAmount);
        StringBuilder xml = new StringBuilder(4096);
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<settlementBatch version=\"1\" batchNo=\"").append(escape(batchNo))
                .append("\" periodFrom=\"").append(from)
                .append("\" periodTo=\"").append(to)
                .append("\" generatedAt=\"").append(Instant.now())
                .append("\">\n");
        xml.append("  <summary billCount=\"").append(bills.size())
                .append("\" totalAmount=\"").append(money(total))
                .append("\" insuranceAmount=\"").append(money(insurance))
                .append("\" selfPayAmount=\"").append(money(selfPay))
                .append("\"/>\n");
        for (SettlementBill bill : bills) {
            xml.append("  <bill billNo=\"").append(escape(bill.billNo()))
                    .append("\" visitId=\"").append(bill.visitId())
                    .append("\" patientNo=\"").append(escape(bill.patientNo()))
                    .append("\" totalAmount=\"").append(money(bill.totalAmount()))
                    .append("\" eligibleAmount=\"").append(money(bill.eligibleAmount()))
                    .append("\" deductible=\"").append(money(bill.deductible()))
                    .append("\" insuranceAmount=\"").append(money(bill.insuranceAmount()))
                    .append("\" selfPayAmount=\"").append(money(bill.selfPayAmount()))
                    .append("\" confirmedBy=\"").append(escape(bill.confirmedBy()))
                    .append("\" confirmedAt=\"").append(bill.confirmedAt())
                    .append("\">\n");
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
