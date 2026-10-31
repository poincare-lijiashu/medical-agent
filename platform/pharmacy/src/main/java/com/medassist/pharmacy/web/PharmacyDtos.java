package com.medassist.pharmacy.web;

import com.medassist.pharmacy.domain.PharmacyModel.Dispense;
import com.medassist.pharmacy.domain.PharmacyModel.DispenseItem;
import com.medassist.pharmacy.domain.PharmacyModel.DispenseReturn;
import com.medassist.pharmacy.domain.PharmacyModel.Drug;
import com.medassist.pharmacy.domain.PharmacyModel.DrugBatch;
import com.medassist.pharmacy.domain.PharmacyModel.Prescription;
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

    /** 新建药品。 */
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
            Boolean narcotic) {
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

    /** 处方药品行。 */
    public record PrescriptionLine(
            @NotBlank String drugCode,
            @Min(1) int quantity,
            @Size(max = 64) String dosage,
            @Size(max = 32) String frequency,
            @Min(1) Integer days) {
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

    /** 药品视图（附带当前总库存，便于药房窗口判断）。 */
    public record DrugView(Drug drug, int totalStock) {
    }

    /** 批次视图。 */
    public record BatchView(DrugBatch batch) {
    }
}
