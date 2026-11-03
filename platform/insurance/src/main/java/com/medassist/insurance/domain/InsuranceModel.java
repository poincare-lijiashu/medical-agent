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
     * 参保变更历史（追加式）。
     *
     * <p>只插入不更新：每次登记/修改/停用/启用都留一条，参数取"变更后"的快照，
     * 配合 changed_at 即可还原任意时点的待遇口径。
     */
    public record MemberHistory(UUID id, UUID memberId, UUID patientId, String patientNo,
                                String insuranceType, String region, String personCategory,
                                BigDecimal deductible, BigDecimal reimbursementRatio, BigDecimal cap,
                                LocalDate effectiveFrom, LocalDate effectiveTo, String status,
                                long version, String changeType, String changedBy,
                                Instant changedAt, String note) {

        public static final String CREATE = "CREATE";
        public static final String UPDATE = "UPDATE";
        public static final String DISABLE = "DISABLE";
        public static final String ENABLE = "ENABLE";
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
                                 String patientNo, java.time.LocalDate serviceDate,
                                 UUID memberId, BigDecimal totalAmount,
                                 BigDecimal eligibleAmount, BigDecimal deductible,
                                 BigDecimal insuranceAmount, BigDecimal selfPayAmount,
                                 String status, String detail, String createdBy,
                                 String confirmedBy, Instant confirmedAt,
                                 String voidReason, String voidedBy, Instant voidedAt,
                                 BigDecimal snapshotRatio, BigDecimal snapshotCap,
                                 long version, Instant createdAt,
                                 String primaryDiagnosisCode, String primaryDiagnosisName,
                                 int diagnosisCount, String diagnoses) {

        public static final String DRAFT = "DRAFT";
        public static final String CONFIRMED = "CONFIRMED";
        /** 作废（红冲）：CONFIRMED 之后发现费用或口径有误时使用，单向不可逆。 */
        public static final String VOIDED = "VOIDED";

        public boolean voided() {
            return VOIDED.equals(status);
        }
    }

    /**
     * 结算清单导出批次（报省局用）。
     *
     * <p>payload 固化生成时的 XML 全文，payload_hash 为 SHA-256：
     * 事后核对"当时报了什么"不依赖目录/参保参数的当前值。
     * 状态机：GENERATED →（登记回执）→ ACCEPTED / REJECTED；GENERATED →（撤销）→ CANCELLED。
     * 撤销后其清单可在新批次重报；已回执批次不可撤销（需走省局冲销）。
     */
    public record ExportBatch(UUID id, String batchNo, LocalDate periodFrom, LocalDate periodTo,
                              int billCount, BigDecimal totalAmount, BigDecimal insuranceAmount,
                              BigDecimal selfPayAmount, String payload, String payloadHash,
                              String status, String receiptNo, Instant receiptAt,
                              String receiptMessage, String cancelledReason, String cancelledBy,
                              Instant cancelledAt, String createdBy, long version,
                              Instant createdAt) {

        public static final String GENERATED = "GENERATED";
        public static final String ACCEPTED = "ACCEPTED";
        public static final String REJECTED = "REJECTED";
        /** 撤销（仅 GENERATED 可撤销）：批次作废，清单可重新报盘。 */
        public static final String CANCELLED = "CANCELLED";

        public boolean receipted() {
            return !GENERATED.equals(status) && !CANCELLED.equals(status);
        }

        public boolean cancelled() {
            return CANCELLED.equals(status);
        }
    }

    /**
     * 医保支付方案（DRG 按权重×费率 / DIP 按分值×点值）。
     *
     * <p>方案与权重来自省局下发，本平台只做院内配置与模拟测算；
     * 费率变更必须携带 version（CAS），避免并发保存静默改变预算口径。
     */
    public record PaymentScheme(String code, String name, String type, BigDecimal rateAmount,
                                LocalDate rateEffectiveFrom, boolean enabled, long version,
                                String updatedBy, Instant updatedAt) {

        public static final String DRG = "DRG";
        public static final String DIP = "DIP";

        public boolean drg() {
            return DRG.equals(type);
        }
    }

    /** 方案下的分组：weight 在 DRG 下是权重、在 DIP 下是分值。 */
    public record PaymentGroup(String schemeCode, String groupCode, String groupName,
                               BigDecimal weight, boolean enabled, long version, Instant updatedAt) {
    }

    /**
     * 入组规则：按 priority 升序取第一个命中项。
     *
     * <p>DIAGNOSIS 只看诊断前缀；PROCEDURE 只看手术/操作前缀；DIAG_AND_PROC 两者都要满足。
     * 前缀匹配用于模拟预分组（ICD 编码前 3~5 位），不是省局正式分组逻辑。
     */
    public record PaymentGroupRule(UUID id, String schemeCode, int priority, String matchType,
                                   String diagPrefix, String procPrefix, String groupCode,
                                   boolean enabled, Instant createdAt) {

        public static final String DIAGNOSIS = "DIAGNOSIS";
        public static final String PROCEDURE = "PROCEDURE";
        public static final String DIAG_AND_PROC = "DIAG_AND_PROC";
    }

    /**
     * 分组模拟快照（含未入组原因），用于"当时为什么算出这个支付标准"的追溯。
     *
     * @param diagnosesSource  诊断来源（MANUAL 请求传入 / CLINICAL 临床域 / NONE）
     * @param proceduresSource 手术来源（MANUAL 请求传入 / HOMEPAGE 病案首页 / NONE）
     */
    public record GroupSimulation(UUID id, UUID visitId, String schemeCode, String groupCode,
                                  boolean matched, String reason, BigDecimal weight,
                                  BigDecimal rateAmount, BigDecimal paymentStandard,
                                  String diagnoses, String procedures,
                                  String diagnosesSource, String proceduresSource,
                                  String inputHash, String createdBy, Instant createdAt) {
    }
}
