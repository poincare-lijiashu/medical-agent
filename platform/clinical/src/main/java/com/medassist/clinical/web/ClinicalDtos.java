package com.medassist.clinical.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 临床核心请求/响应契约。
 *
 * <p>约束：请求体只接受"业务意图"（患者、科室、医嘱项目与数量），
 * **不接受金额、单价、状态**——价格来自主数据、状态由服务端状态机推进，
 * 这是收费类系统的防篡改底线。
 */
public final class ClinicalDtos {

    private ClinicalDtos() {
    }

    /** 建号源（排班）。 */
    public record CreateSlotReq(
            @NotNull UUID deptId,
            @NotBlank String doctorId,
            @NotNull LocalDate workDate,
            @NotBlank String period,
            @Min(1) int total) {
    }

    /** 挂号。 */
    public record RegisterReq(
            @NotNull UUID patientId,
            @NotNull UUID deptId,
            @NotBlank String doctorId,
            UUID slotId,
            String visitType,
            LocalDate visitDate) {
    }

    /** 一条医嘱。{@code orderMode} 可空：默认 TEMP（临时医嘱）；住院可传 LONG（长嘱）。 */
    public record OrderLine(
            @NotBlank String itemCode,
            @Min(1) int quantity,
            String dose,
            String frequency,
            String orderMode) {
    }

    /** 批量开立医嘱。 */
    public record PlaceOrdersReq(@NotEmpty List<@Valid OrderLine> items) {
    }

    /** 频次词表项（前端下拉与接口说明用；服务端为唯一真源）。 */
    public record FrequencyView(String code, String label, int timesPerDay, boolean daily) {
    }

    /** 停嘱。 */
    public record StopOrderReq(String reason) {
    }

    /**
     * 下达诊断。
     *
     * <p>{@code primary=true} 表示主诊断；一个就诊只能有一条主诊断（已有主诊断时 409）。
     */
    public record AddDiagnosisReq(
            @NotBlank String code,
            Boolean primary) {
    }

    /** 结算（收费）。 */
    public record SettleReq(@NotBlank String paymentMethod) {
    }

    /** 费用明细视图。 */
    public record ChargeItemView(UUID orderId, String itemCode, String itemName,
                                 String category, BigDecimal amount) {
    }

    /** 收费单视图（含明细，便于收费窗口一次打印）。 */
    public record ChargeView(UUID id, String chargeNo, UUID visitId, BigDecimal totalAmount,
                             String status, String paymentMethod, Instant paidAt,
                             List<ChargeItemView> items) {
    }

    /** 待收费汇总（收费前预览）。 */
    public record BillingPreview(BigDecimal chargedTotal, BigDecimal pendingAmount,
                                 int pendingOrderCount, ChargeView charge) {
    }

    // ---------------------------------------------------------------- 住院（ADT）

    /** 新建病区。 */
    public record CreateWardReq(
            @NotBlank String code,
            @NotBlank String name,
            @NotNull UUID deptId) {
    }

    /** 批量建床（同一病区）。 */
    public record CreateBedsReq(@NotEmpty List<@NotBlank String> bedNos) {
    }

    /** 入院登记（bedId 可空：先登记后分床）。 */
    public record AdmitReq(
            @NotNull UUID patientId,
            @NotNull UUID wardId,
            UUID bedId,
            @NotBlank String attendingDoctor) {
    }

    /** 分床 / 转床。 */
    public record AssignBedReq(
            @NotNull UUID bedId,
            String reason) {
    }

    /** 住院登记视图（含就诊号，便于与医嘱/收费对照）。 */
    public record AdmissionView(UUID id, String admissionNo, UUID visitId, String visitNo,
                                UUID patientId, String patientNo, UUID wardId, String wardName,
                                UUID bedId, String bedNo, String attendingDoctor, String status,
                                java.time.Instant admittedAt, java.time.Instant dischargedAt) {
    }

    /** 按日计费结果（当日新计金额 + 收费单累计 + 因未执行而跳过的医嘱）。 */
    public record DailyBillResult(LocalDate date, int orderCount, BigDecimal billedAmount,
                                  BigDecimal chargeTotal, List<String> skippedOrderNos) {
    }

    /** 预交金操作（amount 必须为正；退还时服务端取负）。 */
    public record DepositReq(
            @NotNull @jakarta.validation.constraints.DecimalMin(value = "0.01", message = "金额必须大于 0")
            BigDecimal amount,
            @NotBlank String method,
            String remark) {
    }

    /** 预交金余额与流水。 */
    public record DepositView(BigDecimal balance, List<com.medassist.clinical.domain.ClinicalModel.Deposit> items) {
    }

    // ---------------------------------------------------------------- 住院费用看板 / 欠费预警

    /**
     * 住院费用看板。
     *
     * <p>口径：{@code billedTotal} 为全部收费单累计（含已结与未结）；
     * {@code dailyLongAmount} 为"当日应计未计"的长期医嘱金额（已按日计费的、被护理标记
     * 未执行的都不计入）；{@code shortfall = max(0, 未结 + 待计 + 当日未计长嘱 − 预交金余额)}，
     * 大于 0 即欠费预警。
     */
    public record InpatientFinanceView(
            UUID admissionId, String admissionNo, UUID visitId, String visitNo,
            String patientNo, UUID wardId, String wardName, UUID bedId, String bedNo, String status,
            java.time.Instant admittedAt,
            BigDecimal billedTotal, BigDecimal settledTotal, BigDecimal unpaidTotal,
            BigDecimal depositBalance,
            BigDecimal pendingAmount, int pendingOrderCount,
            BigDecimal dailyLongAmount, int dailyLongOrderCount,
            BigDecimal todayBilledAmount,
            BigDecimal shortfall, boolean warning) {
    }

    /** 欠费预警条目（仅返回 shortfall &gt; 0 的在院患者）。 */
    public record InpatientAlert(UUID admissionId, String admissionNo, String patientNo,
                                 UUID bedId, String bedNo, BigDecimal unpaidTotal,
                                 BigDecimal pendingAmount, BigDecimal depositBalance,
                                 BigDecimal shortfall) {
    }

    // ---------------------------------------------------------------- 护理执行记录

    /** 护理执行记录提交（execDate 缺省为当天）。 */
    public record NursingExecutionReq(
            @NotNull UUID orderId,
            LocalDate execDate,
            @NotBlank String status,
            String note) {
    }

    /** 护理执行记录视图。 */
    public record NursingExecutionView(UUID id, UUID admissionId, UUID orderId, String orderNo,
                                       String itemName, LocalDate execDate, String status,
                                       String executor, String note, java.time.Instant createdAt) {
    }
}
