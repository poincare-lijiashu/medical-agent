package com.medassist.pharmacy.web;

import com.medassist.pharmacy.domain.PharmacyModel.PivasItem;
import com.medassist.pharmacy.domain.PharmacyModel.PivasItemBatch;
import com.medassist.pharmacy.domain.PharmacyModel.PivasOrder;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 静配中心契约（G34）。
 *
 * <p>流程：APPROVED 处方建单（PENDING）→ 配置（FEFO 扣批次，PREPARED）
 * → 另一位药师复核（生成成品标签号 bagNo，CHECKED）。
 */
public final class PivasDtos {

    private PivasDtos() {
    }

    /** 建单：默认普通优先级；溶剂与计划批次可选（仅展示与排班用，不参与库存）。 */
    public record CreatePivasOrderReq(
            @NotNull UUID prescriptionId,
            String priority,
            @Size(max = 64) String solvent,
            Integer solventVolumeMl,
            @Size(max = 32) String planBatch,
            Instant plannedAt) {
    }

    /** 复核：approve=false 为驳回，必须填写 note 作为原因（配置后驳回会原路回补批次库存）。 */
    public record CheckPivasReq(@NotNull Boolean approve, @Size(max = 200) String note) {
    }

    /** 驳回未配置的静配单。 */
    public record RejectPivasReq(@Size(max = 200) String reason) {
    }

    public record PivasView(PivasOrder order, List<PivasItem> items,
                            List<PivasItemBatch> batches) {
    }

    /** 静配看板计数。 */
    public record PivasSummary(int pending, int prepared, int checked, int rejected, int total) {
    }
}
