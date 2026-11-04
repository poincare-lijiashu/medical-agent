package com.medassist.material.web;

import com.medassist.material.domain.MaterialModel.ConsumableReturn;
import com.medassist.material.domain.MaterialModel.ConsumableReturnItem;
import com.medassist.material.domain.MaterialModel.DeptConsumableStock;
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
 * 耗材未使用退回契约。
 *
 * <p>明细只接受批次与数量：耗材由服务端按批次回填，避免把"某批号"挂到错误的耗材上；
 * 退回数量以科室二级库余额为上限，审批通过时才真正扣减。
 */
public final class MaterialReturnDtos {

    private MaterialReturnDtos() {
    }

    public record CreateReturnReq(
            @NotNull UUID deptId,
            @NotBlank @Size(max = 200) String reason,
            @NotEmpty(message = "退回明细不能为空") @Size(max = 100) List<@Valid ReturnLine> items) {
    }

    /** 一行 = 一个批次；同一单同一批次只能出现一次。 */
    public record ReturnLine(
            @NotNull UUID batchId,
            @NotNull @Min(value = 1, message = "退回数量必须大于 0") @Max(1000000) Integer quantity) {
    }

    /** 驳回 / 撤回原因。 */
    public record ReturnReasonReq(@NotBlank @Size(max = 200) String reason) {
    }

    /** 审批意见（可选）。 */
    public record ReviewReturnReq(@Size(max = 200) String note) {
    }

    public record ConsumableReturnView(ConsumableReturn consumableReturn,
                                       List<ConsumableReturnItem> items) {
    }

    public record DeptConsumableStockView(UUID deptId, List<DeptConsumableStock> items) {
    }
}
