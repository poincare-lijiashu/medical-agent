package com.medassist.pharmacy.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 药房领域模型（不可变记录）。
 *
 * <p>约定与临床核心一致：状态用字符串常量 + 数据库 CHECK 双保险；金额一律 {@link BigDecimal}；
 * 库存只能通过原子 UPDATE 变更，模型本身不提供 setter。
 */
public final class PharmacyModel {

    private PharmacyModel() {
    }

    /** 药品字典。 */
    public record Drug(UUID id, String drugCode, String name, String tradeName, String spec,
                       String dosageForm, String unit, String packageUnit, Integer packageSize,
                       BigDecimal price, String insuranceCode, String insuranceCategory,
                       int antibioticLevel, boolean narcotic, boolean enabled, long version,
                       BigDecimal maxDailyDose, boolean pediatricContraindicated,
                       boolean elderlyCaution) {

        public static final String INSURANCE_A = "A";
        public static final String INSURANCE_B = "B";
        public static final String INSURANCE_C = "C";
        public static final String INSURANCE_NONE = "NONE";
    }

    /** 库存批次。 */
    public record DrugBatch(UUID id, UUID drugId, String drugCode, String drugName, String batchNo,
                            LocalDate expireDate, int quantity, String supplier, long version) {

        /** 是否已过效期（按业务日判断：expire_date < today 即不可发）。 */
        public boolean expiredOn(LocalDate today) {
            return expireDate.isBefore(today);
        }

        public boolean availableOn(LocalDate today) {
            return quantity > 0 && !expiredOn(today);
        }
    }

    /**
     * 处方。
     *
     * <p>{@code supplyDate} 只在"医嘱转处方"时有值：与 {@code sourceOrderId} 组成幂等键，
     * 长期医嘱每天最多一张当日处方（按日请领，B2 后续）。
     */
    public record Prescription(UUID id, String prescriptionNo, UUID patientId, String patientNo,
                               UUID visitId, String visitNo, UUID deptId, String prescriber,
                               String status, String reviewer, Instant reviewedAt, String rejectReason,
                               BigDecimal totalAmount, long version, Instant createdAt,
                               String billingMode, UUID sourceOrderId, LocalDate supplyDate) {

        public static final String PENDING_REVIEW = "PENDING_REVIEW";
        public static final String APPROVED = "APPROVED";
        public static final String REJECTED = "REJECTED";
        public static final String DISPENSED = "DISPENSED";
        public static final String CANCELLED = "CANCELLED";

        /** 费用由临床医嘱计价（默认；药房只发药，不产生第二条计费链）。 */
        public static final String BILLING_ORDER = "ORDER";
        /** 发药时计价（必须带 visitId；用于未关联临床医嘱的自费处方）。 */
        public static final String BILLING_DISPENSE = "DISPENSE";

        public boolean dispensable() {
            return APPROVED.equals(status);
        }

        /** 发药是否需要在收费域记账。 */
        public boolean chargeOnDispense() {
            return BILLING_DISPENSE.equals(billingMode);
        }
    }

    /** 处方明细（含价格快照；结构化剂量可选，缺失时剂量规则跳过）。 */
    public record PrescriptionItem(UUID id, UUID prescriptionId, UUID drugId, String drugCode,
                                   String drugName, String spec, int quantity, String unit,
                                   String dosage, String frequency, Integer days,
                                   BigDecimal unitPrice, BigDecimal amount,
                                   BigDecimal singleDose, String doseUnit, Integer timesPerDay,
                                   BigDecimal dailyDose) {
    }

    /**
     * 审方记录（追加式）。
     *
     * @param phase CREATE=开方时评估 / REVIEW=审核时复评（药品字典可能已变）
     */
    public record PrescriptionAudit(UUID id, UUID prescriptionId, String phase, String level,
                                    String ruleCode, String message, Instant createdAt) {

        public static final String LEVEL_BLOCK = "BLOCK";
        public static final String LEVEL_WARN = "WARN";
        public static final String LEVEL_INFO = "INFO";
        public static final String PHASE_CREATE = "CREATE";
        public static final String PHASE_REVIEW = "REVIEW";
    }

    /** 发药单。 */
    public record Dispense(UUID id, String dispenseNo, UUID prescriptionId, String operator,
                           String checker, String status, BigDecimal totalAmount,
                           Instant dispensedAt, long version, String billingStatus) {

        public static final String DISPENSED = "DISPENSED";
        public static final String PARTIAL_RETURNED = "PARTIAL_RETURNED";
        public static final String RETURNED = "RETURNED";

        /** 未启用发药计费（处方 billing_mode=ORDER）。 */
        public static final String BILLING_NONE = "NONE";
        /** 已提交收费服务、等待确认（afterCommit 执行期间）。 */
        public static final String BILLING_PENDING = "PENDING";
        public static final String BILLING_CHARGED = "CHARGED";
        public static final String BILLING_CHARGE_FAILED = "CHARGE_FAILED";
        public static final String BILLING_REFUND_PENDING = "REFUND_PENDING";
        public static final String BILLING_REFUND_FAILED = "REFUND_FAILED";

        public boolean billingAbnormal() {
            return BILLING_PENDING.equals(billingStatus)
                    || BILLING_CHARGE_FAILED.equals(billingStatus)
                    || BILLING_REFUND_FAILED.equals(billingStatus);
        }
    }

    /** 发药明细（按批次）。 */
    public record DispenseItem(UUID id, UUID dispenseId, UUID prescriptionItemId, UUID drugId,
                               UUID batchId, String batchNo, LocalDate expireDate, int quantity,
                               int returnedQuantity, BigDecimal unitPrice, BigDecimal amount) {

        public int returnable() {
            return quantity - returnedQuantity;
        }
    }

    /** 退药记录（追加式）。 */
    public record DispenseReturn(UUID id, UUID dispenseId, UUID dispenseItemId, int quantity,
                                 String reason, String operator, Instant createdAt) {
    }

    /**
     * 麻醉药品处方权名单（医务科授予/撤销）。
     *
     * <p>注意：这不是"能不能开方"的权限点，而是**人事资格**——
     * 医师即使有 {@code pharmacy:prescription:create}，不在本名单内也不能开麻醉药品。
     */
    public record NarcoticPrescriber(UUID id, String prescriber, String licenseNo,
                                     String grantedBy, Instant grantedAt, String revokedBy,
                                     Instant revokedAt, String revokeReason, boolean enabled,
                                     long version) {
    }

    /**
     * 麻醉药品专册登记（一次发药明细一行，追加式）。
     *
     * <p>空安瓿回收状态机：{@code PENDING_RETURN → RETURNED}。
     * 回收数量必须等于发药数量（数据库 CHECK 兜底），未回收前不得再次开具同一药品。
     */
    public record NarcoticRegister(UUID id, UUID dispenseItemId, UUID dispenseId,
                                   UUID prescriptionId, String prescriptionNo, UUID patientId,
                                   String patientNo, UUID drugId, String drugCode, String drugName,
                                   String spec, int quantity, String unit, String batchNo,
                                   String prescriber, String dispenser, String checker,
                                   Instant dispensedAt, int ampouleReturned,
                                   Instant ampouleReturnedAt, String ampouleReceiver,
                                   String status, long version, Instant createdAt) {

        public static final String PENDING_RETURN = "PENDING_RETURN";
        public static final String RETURNED = "RETURNED";

        /** 待回收的空安瓿数量。 */
        public int outstanding() {
            return quantity - ampouleReturned;
        }
    }
}
