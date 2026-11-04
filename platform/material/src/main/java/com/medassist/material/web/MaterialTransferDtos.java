package com.medassist.material.web;

import com.medassist.material.domain.MaterialModel.DeptTransfer;
import com.medassist.material.domain.MaterialModel.DeptTransferItem;
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
 * 耗材跨科室调拨契约。
 *
 * <p>明细只接受批次与数量：耗材由服务端按批次回填；
 * 数量上限以调出科室二级库余额为准，接收时才真正扣减。
 */
public final class MaterialTransferDtos {

    private MaterialTransferDtos() {
    }

    public record CreateDeptTransferReq(
            @NotNull UUID fromDeptId,
            @NotNull UUID toDeptId,
            @NotBlank @Size(max = 200) String reason,
            @Size(max = 200) String note,
            @NotEmpty(message = "调拨明细不能为空") @Size(max = 100) List<@Valid TransferLine> items) {
    }

    /** 一行 = 一个批次；同一单同一批次只能出现一次。 */
    public record TransferLine(
            @NotNull UUID batchId,
            @NotNull @Min(value = 1, message = "调拨数量必须大于 0") @Max(1000000) Integer quantity) {
    }

    /** 拒收 / 撤回原因。 */
    public record TransferReasonReq(@NotBlank @Size(max = 200) String reason) {
    }

    public record DeptTransferView(DeptTransfer deptTransfer, List<DeptTransferItem> items) {
    }
}
