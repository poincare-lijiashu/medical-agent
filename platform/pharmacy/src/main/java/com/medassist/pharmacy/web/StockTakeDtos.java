package com.medassist.pharmacy.web;

import com.medassist.pharmacy.domain.PharmacyModel.StockAdjustment;
import com.medassist.pharmacy.domain.PharmacyModel.StockTake;
import com.medassist.pharmacy.domain.PharmacyModel.StockTakeItem;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 药品盘点契约。
 *
 * <p>建单只接受"筛选关键词"（可选），账面数量由服务端从批次库存快照，客户端不能指定——
 * 否则可以把账面数量改小，让盘亏凭空消失。
 */
public final class StockTakeDtos {

    private StockTakeDtos() {
    }

    /** 建单：keyword 为空表示全库；有值时按药品编码/名称/批号筛选。 */
    public record CreateStockTakeReq(@Size(max = 64) String keyword,
                                     @Size(max = 200) String note) {
    }

    /** 实盘录入：版本号用于并发保护（同一批次被两人同时录入时后到者收到 409）。 */
    public record CountItemReq(@NotNull @Min(0) @Max(1000000) Integer actualQuantity,
                               @Size(max = 200) String reason,
                               @NotNull @Min(0) Long version) {
    }

    public record CancelStockTakeReq(@NotBlank @Size(max = 200) String reason) {
    }

    /** 盘点汇总（由服务端计算，前端只展示）。 */
    public record TakeSummary(int itemCount, int countedCount, int varianceCount,
                              int increaseQuantity, int decreaseQuantity) {
    }

    /** 盘点详情：单头 + 明细 + 已产生的调整流水。 */
    public record StockTakeDetail(StockTake take, List<StockTakeItem> items,
                                  List<StockAdjustment> adjustments, TakeSummary summary) {
    }
}
