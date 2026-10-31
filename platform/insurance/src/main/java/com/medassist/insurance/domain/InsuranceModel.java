package com.medassist.insurance.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** 医保域领域模型（不可变记录）。 */
public final class InsuranceModel {

    private InsuranceModel() {
    }

    /** 医保目录项目。payCategory：A 甲类 / B 乙类（先自付 selfPayRatio）/ C 丙类（全自费）。 */
    public record InsuranceItem(UUID id, String insuranceCode, String name, String category,
                                String payCategory, BigDecimal selfPayRatio, BigDecimal priceLimit,
                                boolean enabled, long version) {

        public static final String DRUG = "DRUG";
        public static final String SERVICE = "SERVICE";
        public static final String MATERIAL = "MATERIAL";
        public static final String DIAGNOSIS = "DIAGNOSIS";

        public static final String PAY_A = "A";
        public static final String PAY_B = "B";
        public static final String PAY_C = "C";
    }

    /** 院内收费项目 → 医保目录映射。 */
    public record ChargeMapping(UUID id, String chargeItemCode, String insuranceCode,
                                boolean enabled, long version) {
    }

    /** 参保登记（本地快照）。 */
    public record Member(UUID id, UUID patientId, String patientNo, String insuranceType,
                         String region, String personCategory, BigDecimal deductible,
                         BigDecimal reimbursementRatio, BigDecimal cap,
                         LocalDate effectiveFrom, LocalDate effectiveTo, String status,
                         long version) {

        public static final String ACTIVE = "ACTIVE";
        public static final String DISABLED = "DISABLED";

        /** 指定日期是否处于有效参保期（effective_to 为空表示长期有效）。 */
        public boolean activeOn(LocalDate date) {
            if (!ACTIVE.equals(status)) {
                return false;
            }
            if (effectiveFrom != null && date.isBefore(effectiveFrom)) {
                return false;
            }
            return effectiveTo == null || !date.isAfter(effectiveTo);
        }
    }

    /**
     * 结算清单明细行（含未映射与超限价标记，便于对账追溯）。
     *
     * @param chargeStatus  收费单状态快照（PAID/UNPAID；REFUNDED 不进入清单）
     * @param overLimitAmount 超出医保支付限价、全额自付的金额（无目录或无限价时为 0）
     */
    public record BillLine(String chargeNo, String itemCode, String itemName, String category,
                           String chargeStatus, BigDecimal amount, String insuranceCode,
                           String payCategory, BigDecimal selfPayRatio,
                           BigDecimal overLimitAmount, BigDecimal eligibleAmount, boolean unmapped) {
    }

    /**
     * 医保结算清单。
     *
     * <p>状态机：DRAFT →（确认，校验费用闭合）→ CONFIRMED →（作废）→ VOIDED。
     * 作废后同一就诊可重新生成；drafts 可重算，CONFIRMED 不可改只能作废。
     *
     * <p>snapshotRatio / snapshotCap 与 deductible 列一起构成"生成时的待遇快照"，
     * 确认复核与对账都按快照重算，避免参保参数中途调整导致误判。
     */
    public record SettlementBill(UUID id, String billNo, UUID visitId, UUID patientId,
                                 String patientNo, UUID memberId, BigDecimal totalAmount,
                                 BigDecimal eligibleAmount, BigDecimal deductible,
                                 BigDecimal insuranceAmount, BigDecimal selfPayAmount,
                                 String status, String detail, String createdBy,
                                 String confirmedBy, Instant confirmedAt,
                                 String voidReason, String voidedBy, Instant voidedAt,
                                 BigDecimal snapshotRatio, BigDecimal snapshotCap,
                                 long version, Instant createdAt) {

        public static final String DRAFT = "DRAFT";
        public static final String CONFIRMED = "CONFIRMED";
        /** 作废（红冲）：CONFIRMED 之后发现费用或口径有误时使用，单向不可逆。 */
        public static final String VOIDED = "VOIDED";

        public boolean voided() {
            return VOIDED.equals(status);
        }
    }
}
