package com.medassist.material.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 物资耗材领域模型（不可变记录）。
 *
 * <p>约定与药品域一致：状态/类别用字符串常量 + 数据库 CHECK 双保险；金额一律 {@link BigDecimal}；
 * 库存只能通过原子 UPDATE 变更，模型本身不提供 setter。
 */
public final class MaterialModel {

    private MaterialModel() {
    }

    /**
     * 耗材目录。
     *
     * <p>类别决定合规强度：
     * <ul>
     *   <li>{@code GENERAL}：一般耗材，可无注册证、可无效期；</li>
     *   <li>{@code HIGH_VALUE}：高值耗材，必须注册证 + 效期，出库后必须追溯患者；</li>
     *   <li>{@code IMPLANT}：植入类，要求与高值一致，且召回影响最大。</li>
     * </ul>
     */
    public record Consumable(UUID id, String code, String name, String spec, String unit,
                             String category, BigDecimal price, String manufacturer,
                             String registrationNo, boolean enabled, long version,
                             Instant createdAt) {

        public static final String GENERAL = "GENERAL";
        public static final String HIGH_VALUE = "HIGH_VALUE";
        public static final String IMPLANT = "IMPLANT";

        /** 高值/植入类必须能追溯到患者与批次。 */
        public boolean traceRequired() {
            return HIGH_VALUE.equals(category) || IMPLANT.equals(category);
        }

        /** 高值/植入类必须有有效期（无菌/植入器械的硬性要求）。 */
        public boolean expiryRequired() {
            return traceRequired();
        }

        /** 高值/植入类必须有医疗器械注册证号。 */
        public boolean registrationRequired() {
            return traceRequired();
        }
    }

    /** 批次库存；{@code expireDate} 为空表示该耗材不设效期（仅一般耗材允许）。 */
    public record ConsumableBatch(UUID id, UUID consumableId, String consumableCode,
                                  String consumableName, String batchNo, LocalDate expireDate,
                                  int quantity, String supplier, BigDecimal unitPrice,
                                  long version) {

        public boolean expiredOn(LocalDate today) {
            return expireDate != null && expireDate.isBefore(today);
        }

        public boolean availableOn(LocalDate today) {
            return quantity > 0 && !expiredOn(today);
        }
    }

    /** 出库单（库房 → 科室/手术/门诊）。 */
    public record ConsumableIssue(UUID id, String issueNo, UUID deptId, String deptName,
                                  String purpose, String requestId, String operator,
                                  String remark, BigDecimal totalAmount, Instant createdAt) {

        public static final String DEPT_STOCK = "DEPT_STOCK";
        public static final String SURGERY = "SURGERY";
        public static final String CLINIC = "CLINIC";
    }

    /** 出库明细：一行对应一个批次，{@code tracedQuantity} 是该行已登记的使用量。 */
    public record ConsumableIssueItem(UUID id, UUID issueId, UUID consumableId,
                                      String consumableCode, String consumableName, UUID batchId,
                                      String batchNo, LocalDate expireDate, int quantity,
                                      int tracedQuantity, BigDecimal unitPrice, BigDecimal amount) {

        public int untraced() {
            return quantity - tracedQuantity;
        }
    }

    /**
     * 使用追溯：一条 = 某批次耗材用在某位患者（可关联手术）。
     *
     * <p>{@code consumableCode/Name} 是登记时快照：字典后续改名或停用，
     * 召回时仍按当时的名称呈现，避免"历史记录被字典改造"。
     */
    public record ConsumableTrace(UUID id, UUID issueItemId, UUID consumableId,
                                  String consumableCode, String consumableName, String batchNo,
                                  LocalDate expireDate, int quantity, UUID patientId,
                                  String patientNo, UUID visitId, UUID surgeryId, String operator,
                                  Instant usedAt, String remark, Instant createdAt) {
    }

    /**
     * 科室二级库余额（科室 + 耗材 + 批次）。
     *
     * <p>出库时累加，退回审批通过时扣减。这条余额回答"科室手上还剩什么"，
     * 与中心批次库存相互独立：同一件耗材在任一时点只属于其中一侧。
     */
    public record DeptConsumableStock(UUID id, UUID deptId, UUID consumableId,
                                      String consumableCode, String consumableName, String spec,
                                      String unit, UUID batchId, String batchNo,
                                      LocalDate expireDate, int quantity, long version) {
    }

    /**
     * 未使用耗材退回单（科室 → 中心库房）。
     *
     * <p>状态机：SUBMITTED → APPROVED / REJECTED；SUBMITTED 可撤回。
     * 审批通过才扣科室库并回冲中心批次，提交/驳回/撤回都不动库存。
     */
    public record ConsumableReturn(UUID id, String returnNo, String status, UUID deptId,
                                   String reason, String createdBy, String reviewedBy,
                                   Instant reviewedAt, String reviewNote, String cancelledBy,
                                   Instant cancelledAt, String cancelReason, long version,
                                   Instant createdAt) {

        public static final String SUBMITTED = "SUBMITTED";
        public static final String APPROVED = "APPROVED";
        public static final String REJECTED = "REJECTED";
        public static final String CANCELLED = "CANCELLED";

        public boolean reviewable() {
            return SUBMITTED.equals(status);
        }

        public boolean cancellable() {
            return SUBMITTED.equals(status);
        }
    }

    /** 退回明细（一行 = 一个批次）。 */
    public record ConsumableReturnItem(UUID id, UUID returnId, UUID consumableId,
                                       String consumableCode, String consumableName, String spec,
                                       String unit, UUID batchId, String batchNo,
                                       LocalDate expireDate, int quantity) {
    }
}
