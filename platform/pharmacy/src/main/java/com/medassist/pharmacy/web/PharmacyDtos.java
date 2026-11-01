package com.medassist.pharmacy.web;

import com.medassist.pharmacy.domain.PharmacyModel.Dispense;
import com.medassist.pharmacy.domain.PharmacyModel.DispenseItem;
import com.medassist.pharmacy.domain.PharmacyModel.DispenseReturn;
import com.medassist.pharmacy.domain.PharmacyModel.Drug;
import com.medassist.pharmacy.domain.PharmacyModel.DrugBatch;
import com.medassist.pharmacy.domain.PharmacyModel.Prescription;
import com.medassist.pharmacy.domain.PharmacyModel.PrescriptionAudit;
import com.medassist.pharmacy.domain.PharmacyModel.PrescriptionItem;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 药房请求/响应契约。
 *
 * <p>约束：请求体只接受"业务意图"（药品编码、数量、效期），
 * <b>不接受价格、金额、库存余量</b>——价格来自药品字典，库存由服务端扣减。
 */
public final class PharmacyDtos {

    private PharmacyDtos() {
    }

    // ---------------------------------------------------------------- 药品字典

    /** 新建药品（含用药安全属性：日剂量上限、儿童禁用、老年慎用）。 */
    public record CreateDrugReq(
            @NotBlank @Size(max = 64) String drugCode,
            @NotBlank @Size(max = 128) String name,
            @Size(max = 128) String tradeName,
            @NotBlank @Size(max = 128) String spec,
            @NotBlank @Pattern(regexp = "TABLET|CAPSULE|INJECTION|GRANULE|LIQUID|OINTMENT|OTHER",
                    message = "剂型不合法") String dosageForm,
            @NotBlank @Size(max = 16) String unit,
            @Size(max = 16) String packageUnit,
            @Min(1) Integer packageSize,
            @NotNull @DecimalMin(value = "0.00", message = "价格不能为负") BigDecimal price,
            @Size(max = 32) String insuranceCode,
            @Pattern(regexp = "A|B|C|NONE", message = "医保类别只能是 A/B/C/NONE") String insuranceCategory,
            @Min(0) Integer antibioticLevel,
            Boolean narcotic,
            @DecimalMin(value = "0.001", message = "日剂量上限必须大于 0") BigDecimal maxDailyDose,
            Boolean pediatricContraindicated,
            Boolean elderlyCaution) {
    }

    /** 调价（历史处方保存快照，不受影响）。 */
    public record UpdatePriceReq(
            @NotNull @DecimalMin(value = "0.00", message = "价格不能为负") BigDecimal price,
            @NotNull Long version) {
    }

    /** 启停用。 */
    public record ToggleDrugReq(
            @NotNull Boolean enabled,
            @NotNull Long version) {
    }

    // ---------------------------------------------------------------- 库存

    /** 入库（一个批次一次）。 */
    public record ReceiveStockReq(
            @NotBlank @Size(max = 64) String batchNo,
            @NotNull LocalDate expireDate,
            @Min(1) int quantity,
            @Size(max = 128) String supplier) {
    }

    /** 库存预警条目。 */
    public record StockWarning(String drugCode, String drugName, String batchNo,
                               LocalDate expireDate, int quantity, boolean expired) {
    }

    // ---------------------------------------------------------------- 处方

    /**
     * 处方药品行。
     *
     * <p>结构化剂量（singleDose + doseUnit + timesPerDay）可选；三项要么全给、要么全不给。
     * 只给自由文本 "0.5g tid" 时，日剂量上限规则跳过（不猜测文本语义）。
     */
    public record PrescriptionLine(
            @NotBlank String drugCode,
            @Min(1) int quantity,
            @Size(max = 64) String dosage,
            @Size(max = 32) String frequency,
            @Min(1) Integer days,
            @DecimalMin(value = "0.001", message = "单次剂量必须大于 0") BigDecimal singleDose,
            @Size(max = 16) String doseUnit,
            @Min(1) @jakarta.validation.constraints.Max(24) Integer timesPerDay) {
    }

    /**
     * 开方。
     *
     * <p>{@code billingMode}：{@code ORDER}（默认）= 费用由临床医嘱计价，药房只发药；
     * {@code DISPENSE} = 发药时计价（必须带 visitId，用于未关联临床医嘱的自费处方）。
     */
    public record CreatePrescriptionReq(
            @NotNull UUID patientId,
            UUID visitId,
            @Size(max = 64) String visitNo,
            UUID deptId,
            @Pattern(regexp = "ORDER|DISPENSE", message = "计费模式只能是 ORDER/DISPENSE") String billingMode,
            @NotEmpty List<@Valid PrescriptionLine> items) {
    }

    /**
     * 临床医嘱转处方（仅内网接口使用）。
     *
     * <p>与手工开方的差别：必须带 {@code orderId}（幂等键），计费模式固定 ORDER
     * （费用已在临床医嘱计价），{@code prescriber} 为下医嘱的医师。
     */
    public record CreateFromOrderReq(
            @NotNull UUID orderId,
            @NotNull UUID patientId,
            UUID visitId,
            @Size(max = 64) String visitNo,
            UUID deptId,
            @Size(max = 64) String prescriber,
            @NotEmpty List<@Valid PrescriptionLine> items) {
    }

    /**
     * 医嘱转处方结果。
     *
     * @param duplicate true=该医嘱此前已生成过处方（幂等返回，未新建）
     */
    public record OrderPrescriptionView(UUID prescriptionId, String prescriptionNo, UUID orderId,
                                        boolean duplicate, BigDecimal totalAmount, String status) {
    }

    /**
     * 按医嘱作废处方（仅内网接口使用，医嘱撤销/停嘱联动）。
     *
     * <p>找不到对应处方视为"该医嘱从未转送"（不报错）；已作废幂等返回。
     */
    public record CancelFromOrderReq(
            @NotNull UUID orderId,
            @Size(max = 256) String reason,
            @Size(max = 64) String actor) {
    }

    /**
     * 处方作废结果。
     *
     * @param cancelled true=本次或此前已作废；false=该医嘱没有处方（未转送过）
     */
    public record PrescriptionCancelView(UUID prescriptionId, String prescriptionNo,
                                         String status, boolean cancelled) {
    }

    /** 审核（approve=false 时 reason 必填）。 */
    public record ReviewReq(
            @NotNull Boolean approve,
            @Size(max = 256) String reason) {
    }

    /** 发药（麻醉/精神类必须提供核对人，且不得与发药人相同）。 */
    public record DispenseReq(
            @Size(max = 64) String checker) {
    }

    /** 退药。 */
    public record ReturnReq(
            @NotNull UUID dispenseItemId,
            @Min(1) int quantity,
            @NotBlank @Size(max = 256) String reason) {
    }

    /** 处方视图（含明细）。 */
    public record PrescriptionView(Prescription prescription, List<PrescriptionItem> items) {
    }

    /** 发药视图（含按批次的明细与退药记录）。 */
    public record DispenseView(Dispense dispense, List<DispenseItem> items, List<DispenseReturn> returns) {
    }

    /**
     * 审方结果视图。
     *
     * @param blocked 存在 BLOCK 级问题（审核不可通过）
     */
    public record PrescriptionAuditView(UUID prescriptionId, List<PrescriptionAudit> audits,
                                        boolean blocked, boolean warned) {

        public static PrescriptionAuditView of(UUID prescriptionId, List<PrescriptionAudit> audits) {
            boolean blocked = audits.stream()
                    .anyMatch(a -> PrescriptionAudit.LEVEL_BLOCK.equals(a.level()));
            boolean warned = audits.stream()
                    .anyMatch(a -> PrescriptionAudit.LEVEL_WARN.equals(a.level()));
            return new PrescriptionAuditView(prescriptionId, audits, blocked, warned);
        }
    }

    /** 药品视图（附带当前总库存，便于药房窗口判断）。 */
    public record DrugView(Drug drug, int totalStock) {
    }

    /** 批次视图。 */
    public record BatchView(DrugBatch batch) {
    }
}
