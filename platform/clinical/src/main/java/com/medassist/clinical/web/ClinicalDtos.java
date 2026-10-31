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

    /** 一条医嘱。 */
    public record OrderLine(
            @NotBlank String itemCode,
            @Min(1) int quantity,
            String dose,
            String frequency) {
    }

    /** 批量开立医嘱。 */
    public record PlaceOrdersReq(@NotEmpty List<@Valid OrderLine> items) {
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
}
