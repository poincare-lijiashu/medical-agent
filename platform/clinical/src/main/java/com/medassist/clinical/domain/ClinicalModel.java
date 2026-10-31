package com.medassist.clinical.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 临床核心领域模型（不可变记录）。
 *
 * <p>约定：
 * <ul>
 *   <li>状态一律用字符串常量 + {@code CHECK} 约束双保险，枚举只用于服务层判断；</li>
 *   <li>{@code version} 为乐观锁版本号，所有更新必须带 CAS；</li>
 *   <li>金额统一 {@link BigDecimal}（禁止 double，避免 0.1+0.2 类误差进收费单）。</li>
 * </ul>
 */
public final class ClinicalModel {

    private ClinicalModel() {
    }

    /** 科室。 */
    public record Dept(UUID id, String deptCode, String name, String type, boolean enabled) {
    }

    /** 收费项目（价格唯一真源）。 */
    public record ChargeItemDef(UUID id, String itemCode, String name, String category,
                                String unit, BigDecimal price, boolean enabled) {
    }

    /** 号源。 */
    public record Slot(UUID id, UUID deptId, String doctorId, LocalDate workDate, String period,
                       int total, int booked, long version) {
        public boolean full() {
            return booked >= total;
        }
    }

    /** 就诊。 */
    public record Visit(UUID id, String visitNo, UUID patientId, String patientNo,
                        UUID deptId, String doctorId, UUID slotId, String visitType,
                        String status, LocalDate visitDate, long version) {

        public static final String REGISTERED = "REGISTERED";
        public static final String IN_TREATMENT = "IN_TREATMENT";
        public static final String FINISHED = "FINISHED";
        public static final String CANCELLED = "CANCELLED";

        public boolean active() {
            return REGISTERED.equals(status) || IN_TREATMENT.equals(status);
        }
    }

    /** 医嘱。 */
    public record VisitOrder(UUID id, String orderNo, UUID visitId, String orderType,
                             String itemCode, String itemName, String dose, String frequency,
                             int quantity, String unit, BigDecimal unitPrice, BigDecimal amount,
                             String status, String orderedBy, long version,
                             String orderMode, LocalDate startDate, LocalDate stopDate) {

        public static final String PLACED = "PLACED";
        public static final String CHARGED = "CHARGED";
        public static final String CANCELLED = "CANCELLED";

        public static final String TEMP = "TEMP";
        public static final String LONG = "LONG";

        public boolean longTerm() {
            return LONG.equals(orderMode);
        }

        /** 某天是否应计费（长嘱在 start~stop 之间有效；停嘱当天仍计费）。 */
        public boolean billableOn(LocalDate date) {
            if (!longTerm() || !PLACED.equals(status)) {
                return false;
            }
            if (startDate != null && date.isBefore(startDate)) {
                return false;
            }
            return stopDate == null || !date.isAfter(stopDate);
        }
    }

    /** 收费单。 */
    public record Charge(UUID id, String chargeNo, UUID visitId, BigDecimal totalAmount,
                         String status, String paymentMethod, Instant paidAt, long version) {

        public static final String UNPAID = "UNPAID";
        public static final String PAID = "PAID";
        public static final String REFUNDED = "REFUNDED";
    }

    /** 病区。 */
    public record Ward(UUID id, String code, String name, UUID deptId, boolean enabled) {
    }

    /** 床位。 */
    public record Bed(UUID id, UUID wardId, String bedNo, String status, long version) {

        public static final String FREE = "FREE";
        public static final String OCCUPIED = "OCCUPIED";
        public static final String RESERVED = "RESERVED";
        public static final String DISABLED = "DISABLED";

        public boolean free() {
            return FREE.equals(status);
        }
    }

    /** 住院登记。 */
    public record Admission(UUID id, String admissionNo, UUID visitId, UUID patientId,
                            String patientNo, UUID wardId, UUID bedId, String attendingDoctor,
                            String status, Instant admittedAt, Instant dischargedAt, long version) {

        public static final String ADMITTED = "ADMITTED";
        public static final String IN_HOSPITAL = "IN_HOSPITAL";
        public static final String DISCHARGED = "DISCHARGED";
        public static final String CANCELLED = "CANCELLED";

        public boolean active() {
            return ADMITTED.equals(status) || IN_HOSPITAL.equals(status);
        }
    }

    /** 预交金流水（DEPOSIT 正数 / REFUND 负数）。 */
    public record Deposit(UUID id, UUID admissionId, BigDecimal amount, String kind,
                          String method, String operator, String remark, Instant createdAt) {

        public static final String DEPOSIT = "DEPOSIT";
        public static final String REFUND = "REFUND";
        /** 结算抵扣（负数流水）。 */
        public static final String SETTLE = "SETTLE";
    }

    /**
     * 护理执行记录（长嘱逐日执行，追加式）。
     *
     * <p>按日计费时以本记录核对：REFUSED / OMITTED 的医嘱当日不计费；
     * 尚无记录时按"已执行"计费，兼容护理站尚未上线的科室。
     */
    public record NursingExecution(UUID id, UUID admissionId, UUID visitId, UUID orderId,
                                   LocalDate execDate, String status, String executor,
                                   String note, Instant createdAt) {

        public static final String EXECUTED = "EXECUTED";
        public static final String REFUSED = "REFUSED";
        public static final String OMITTED = "OMITTED";

        public boolean executed() {
            return EXECUTED.equals(status);
        }
    }

    /**
     * 费用明细行（只读投影）。
     *
     * <p>供医保域生成结算清单时读取；不在此做任何金额计算，避免两份计费口径。
     */
    public record ChargeLine(String chargeNo, String itemCode, String itemName,
                             String category, BigDecimal amount, String chargeStatus) {
        public static final String UNPAID = "UNPAID";
    }
}
