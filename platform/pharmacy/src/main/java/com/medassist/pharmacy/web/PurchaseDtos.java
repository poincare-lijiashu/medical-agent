package com.medassist.pharmacy.web;

import com.medassist.pharmacy.domain.PharmacyModel.GoodsReceipt;
import com.medassist.pharmacy.domain.PharmacyModel.GoodsReceiptItem;
import com.medassist.pharmacy.domain.PharmacyModel.PurchaseOrder;
import com.medassist.pharmacy.domain.PharmacyModel.PurchaseOrderItem;
import com.medassist.pharmacy.domain.PharmacyModel.Supplier;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 采购域出入参（供应商 / 采购单 / 到货验收）。
 *
 * <p>金额一律由服务端按药品字典价格快照计算，客户端不传单价——
 * 否则前端可篡改采购价，导致库存成本与财务口径失真。
 */
public final class PurchaseDtos {

    private PurchaseDtos() {
    }

    // ---------------------------------------------------------------- 供应商

    public record CreateSupplierReq(
            @NotBlank @Size(max = 32) String code,
            @NotBlank @Size(max = 128) String name,
            @Size(max = 64) String contact,
            @Size(max = 32) String phone,
            @NotBlank @Size(max = 64) String licenseNo,
            @NotNull LocalDate licenseExpireDate,
            @Size(max = 256) String remark) {
    }

    public record SupplierStatusReq(
            @NotBlank @Pattern(regexp = "ACTIVE|SUSPENDED", message = "状态只能是 ACTIVE 或 SUSPENDED")
            String status,
            @NotNull @Min(0) Long version) {
    }

    /** 供应商视图；{@code licenseExpired} 由服务端按业务日计算，前端不再自行比较日期。 */
    public record SupplierView(Supplier supplier, boolean licenseExpired) {

        public static SupplierView of(Supplier supplier, LocalDate today) {
            return new SupplierView(supplier, !supplier.licensedOn(today));
        }
    }

    // ---------------------------------------------------------------- 采购单

    public record CreatePurchaseOrderReq(
            @NotNull UUID supplierId,
            LocalDate expectedDate,
            @Size(max = 256) String remark,
            @NotEmpty(message = "采购明细不能为空") @Size(max = 200) List<@Valid PurchaseOrderLine> items) {
    }

    public record PurchaseOrderLine(
            @NotNull UUID drugId,
            @Min(value = 1, message = "采购数量必须大于 0") int quantity) {
    }

    public record CancelOrderReq(
            @NotBlank @Size(max = 256) String reason,
            @NotNull @Min(0) Long version) {
    }

    /** 仅携带版本号的动作（提交审批）：版本号缺失或过期都拒绝，避免覆盖他人修改。 */
    public record OrderVersionReq(@NotNull @Min(0) Long version) {
    }

    public record PurchaseOrderSummary(PurchaseOrder order, String supplierName) {
    }

    public record PurchaseOrderView(PurchaseOrder order, String supplierName,
                                    List<PurchaseOrderItem> items) {
    }

    // ---------------------------------------------------------------- 到货验收

    /**
     * 到货验收请求。
     *
     * @param requestId 幂等键：同一采购单内重复提交同一个值只会入库一次（客户端重试/双击）
     */
    public record CreateReceiptReq(
            @NotBlank @Size(max = 64) String requestId,
            @Size(max = 64) String deliveryNo,
            @Size(max = 64) String courier,
            Instant deliveredAt,
            @Size(max = 256) String remark,
            @NotEmpty(message = "验收明细不能为空") @Size(max = 200) List<@Valid ReceiptLine> items) {
    }

    public record ReceiptLine(
            @NotNull UUID poItemId,
            @NotBlank @Size(max = 64) String batchNo,
            @NotNull LocalDate expireDate,
            @Min(value = 1, message = "验收数量必须大于 0") int quantity) {
    }

    public record GoodsReceiptView(GoodsReceipt receipt, List<GoodsReceiptItem> items) {
    }

    // ---------------------------------------------------------------- 汇总

    /** 采购看板：各状态单量 + 在途金额（已提交/部分到货的未到货金额）。 */
    public record ProcurementSummary(Map<String, Long> orderCounts, BigDecimal outstandingAmount,
                                     long pendingReceiptOrders) {
    }
}
