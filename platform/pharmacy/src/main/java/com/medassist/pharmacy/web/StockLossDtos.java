package com.medassist.pharmacy.web;

import com.medassist.pharmacy.domain.PharmacyModel.StockAdjustment;
import com.medassist.pharmacy.domain.PharmacyModel.StockLoss;
import com.medassist.pharmacy.domain.PharmacyModel.StockLossItem;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * 药品报损契约。
 *
 * <p>明细只接受批次与数量：药品、批号、效期由服务端按批次回填，
 * 客户端不能伪造"这个批号是哪个药"，否则报损会扣错库存。
 */
public final class StockLossDtos {

    private StockLossDtos() {
    }

    public record CreateStockLossReq(
            @NotBlank
            @Pattern(regexp = "EXPIRED|BROKEN|RECALL|OTHER",
                    message = "报损原因只能是 EXPIRED/BROKEN/RECALL/OTHER")
            String reasonCategory,
            @Size(max = 200) String note,
            @NotEmpty @Size(max = 100) List<@Valid LossLine> items) {
    }

    /** 一行 = 一个批次；同一单同一批次只能出现一次。 */
    public record LossLine(@NotNull UUID batchId,
                           @NotNull @Min(1) @Max(1000000) Integer quantity,
                           @Size(max = 200) String reason) {
    }

    /** 审批：approve=false 时为驳回，必须写原因。 */
    public record ReviewStockLossReq(@NotNull Boolean approve, @Size(max = 200) String note) {
    }

    public record StockLossView(StockLoss loss, List<StockLossItem> items,
                                List<StockAdjustment> adjustments) {
    }

    /** 效期自动草稿结果（G28）：单据数与明细批次总数。 */
    public record AutoDraftResult(int losses, int items) {
    }
}
