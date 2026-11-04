package com.medassist.pharmacy.web;

import com.medassist.pharmacy.domain.PharmacyModel.DeptDrugStock;
import com.medassist.pharmacy.domain.PharmacyModel.DrugTransfer;
import com.medassist.pharmacy.domain.PharmacyModel.DrugTransferItem;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * 药品科室调拨契约。
 *
 * <p>明细只接受批次与数量：药品、批号、效期由服务端按批次回填，
 * 避免客户端把"某批号"挂到错误的药品上。
 */
public final class DrugTransferDtos {

    private DrugTransferDtos() {
    }

    public record CreateDrugTransferReq(@NotNull UUID toDeptId,
                                        @Size(max = 200) String note,
                                        @NotEmpty @Size(max = 100) List<@Valid TransferLine> items) {
    }

    /** 一行 = 一个批次；同一单同一批次只能出现一次。 */
    public record TransferLine(@NotNull UUID batchId,
                               @NotNull @Min(1) @Max(1000000) Integer quantity) {
    }

    /** 拒收 / 撤回原因。 */
    public record TransferReasonReq(@NotBlank @Size(max = 200) String reason) {
    }

    public record DrugTransferView(DrugTransfer transfer, List<DrugTransferItem> items) {
    }

    public record DeptStockView(UUID deptId, List<DeptDrugStock> items) {
    }
}
