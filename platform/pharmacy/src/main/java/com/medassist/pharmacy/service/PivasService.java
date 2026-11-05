package com.medassist.pharmacy.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.time.BusinessTime;
import com.medassist.pharmacy.domain.PharmacyModel.DrugBatch;
import com.medassist.pharmacy.domain.PharmacyModel.PivasItem;
import com.medassist.pharmacy.domain.PharmacyModel.PivasItemBatch;
import com.medassist.pharmacy.domain.PharmacyModel.PivasOrder;
import com.medassist.pharmacy.domain.PharmacyModel.PivasStatusCount;
import com.medassist.pharmacy.domain.PharmacyModel.Prescription;
import com.medassist.pharmacy.domain.PharmacyModel.PrescriptionItem;
import com.medassist.pharmacy.repo.PharmacyRepository;
import com.medassist.pharmacy.web.PivasDtos.CheckPivasReq;
import com.medassist.pharmacy.web.PivasDtos.CreatePivasOrderReq;
import com.medassist.pharmacy.web.PivasDtos.PivasSummary;
import com.medassist.pharmacy.web.PivasDtos.PivasView;
import com.medassist.pharmacy.web.PivasDtos.RejectPivasReq;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 静配中心（G34）：处方建单 → 配置扣批次 → 双人复核生成成品标签。
 *
 * <p>硬约束：
 * <ul>
 *   <li>只有 APPROVED 处方可以进入静配，一张处方只允许一张静配单；</li>
 *   <li>配置按 FEFO 逐批次原子扣减，任一药品不足整单回滚；</li>
 *   <li>复核人 ≠ 配置人（四眼）；配置后驳回会把批次库存原路回补；</li>
 *   <li>复核通过生成唯一成品标签号 bagNo，可按标签追溯到批次/效期/人员/时间。</li>
 * </ul>
 */
@Service
public class PivasService {

    private static final Set<String> PRIORITIES =
            Set.of(PivasOrder.PRIORITY_NORMAL, PivasOrder.PRIORITY_URGENT);
    private static final Set<String> STATUSES =
            Set.of(PivasOrder.PENDING, PivasOrder.PREPARED, PivasOrder.CHECKED,
                    PivasOrder.REJECTED);

    private final PharmacyRepository repo;
    private final ObjectMapper objectMapper;

    public PivasService(PharmacyRepository repo, ObjectMapper objectMapper) {
        this.repo = repo;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public PivasView create(CreatePivasOrderReq req, String actor) {
        String operator = requireActor(actor);
        Prescription rx = repo.lockPrescription(req.prescriptionId())
                .orElseThrow(() -> new BusinessException(ErrorCode.PRESCRIPTION_NOT_FOUND));
        if (!Prescription.APPROVED.equals(rx.status())) {
            throw new BusinessException(ErrorCode.PIVAS_PRESCRIPTION_INVALID,
                    "当前处方状态为 " + rx.status() + "，只有已审核通过的处方可以进入静配中心");
        }
        if (repo.findPivasOrderByPrescription(rx.id()).isPresent()) {
            throw new BusinessException(ErrorCode.PIVAS_ORDER_EXISTS);
        }
        // 出口互斥（G57）：持有处方行锁时判定是否已窗口发药，防止静配与窗口各扣一次库存
        if (repo.findDispenseByPrescription(rx.id()).isPresent()) {
            throw new BusinessException(ErrorCode.PIVAS_PRESCRIPTION_INVALID,
                    "该处方已在药房窗口发药，不能再进入静配中心");
        }
        List<PrescriptionItem> rxItems = repo.listPrescriptionItems(rx.id());
        if (rxItems.isEmpty()) {
            throw new BusinessException(ErrorCode.PIVAS_PRESCRIPTION_INVALID, "处方没有明细，不能进入静配中心");
        }
        String priority = normalizePriority(req.priority());
        if (req.solventVolumeMl() != null && req.solventVolumeMl() <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "溶媒体积必须大于 0");
        }
        String drugSummary = summarize(rxItems);
        PivasOrder order = new PivasOrder(UUID.randomUUID(), repo.nextPivasOrderNo(), rx.id(),
                rx.prescriptionNo(), rx.patientId(), rx.patientNo(), rx.visitId(), rx.visitNo(),
                rx.deptId(), priority, PivasOrder.PENDING, blankToNull(req.solvent()),
                req.solventVolumeMl(), drugSummary, blankToNull(req.planBatch()),
                req.plannedAt(), null, null, null, null, null, null, operator, 0L, Instant.now());
        repo.insertPivasOrder(order);
        List<PivasItem> items = new java.util.ArrayList<>();
        int seq = 1;
        for (PrescriptionItem item : rxItems) {
            PivasItem snapshot = new PivasItem(UUID.randomUUID(), order.id(), seq++,
                    item.drugId(), item.drugCode(), item.drugName(), item.spec(), item.quantity(),
                    item.unit(), item.dosage(), item.frequency());
            repo.insertPivasItem(snapshot);
            items.add(snapshot);
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("prescriptionNo", rx.prescriptionNo());
        detail.put("priority", priority);
        detail.put("items", rxItems.size());
        audit(operator, "PIVAS_CREATE", order.id().toString(), detail);
        return new PivasView(order, items, List.of());
    }

    /**
     * 配置：按 FEFO 扣减药品批次并记录耗用明细。
     *
     * <p>同一事务内逐条扣减；任一条不足抛错，数据库回滚已扣批次。
     */
    @Transactional
    public PivasView prepare(UUID id, String actor) {
        String operator = requireActor(actor);
        PivasOrder order = repo.lockPivasOrder(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.PIVAS_ORDER_NOT_FOUND));
        if (!order.pending()) {
            throw new BusinessException(ErrorCode.PIVAS_STATUS_INVALID,
                    "只有待配置的静配单可以配置，当前：" + order.status());
        }
        List<PivasItem> items = repo.listPivasItems(id);
        LocalDate today = BusinessTime.today();
        int allocations = 0;
        for (PivasItem item : items) {
            int remaining = item.quantity();
            List<DrugBatch> batches = repo.listAvailableBatches(item.drugId(), today);
            for (DrugBatch batch : batches) {
                if (remaining <= 0) {
                    break;
                }
                int take = Math.min(batch.quantity(), remaining);
                if (take <= 0 || !repo.deductBatch(batch.id(), take)) {
                    throw new BusinessException(ErrorCode.PIVAS_STOCK_INSUFFICIENT,
                            "药品 " + item.drugName() + " 批次 " + batch.batchNo()
                                    + " 扣减失败（余量 " + batch.quantity() + "）");
                }
                repo.insertPivasItemBatch(new PivasItemBatch(UUID.randomUUID(), item.id(),
                        item.drugId(), batch.id(), batch.batchNo(), batch.expireDate(), take,
                        Instant.now()));
                remaining -= take;
                allocations++;
            }
            if (remaining > 0) {
                throw new BusinessException(ErrorCode.PIVAS_STOCK_INSUFFICIENT,
                        "药品 " + item.drugName() + " 库存不足，尚缺 " + remaining + " " + nvl(item.unit()));
            }
        }
        if (!repo.markPivasPrepared(id, order.version(), operator)) {
            throw new BusinessException(ErrorCode.CONFLICT, "静配单状态已变化，请刷新后重试");
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("orderNo", order.orderNo());
        detail.put("allocations", allocations);
        audit(operator, "PIVAS_PREPARE", id.toString(), detail);
        return reload(id);
    }

    /**
     * 复核：approve=true 生成成品标签号；false 驳回并原路回补批次库存。
     */
    @Transactional
    public PivasView check(UUID id, CheckPivasReq req, String actor) {
        String operator = requireActor(actor);
        PivasOrder order = repo.lockPivasOrder(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.PIVAS_ORDER_NOT_FOUND));
        if (!order.prepared()) {
            throw new BusinessException(ErrorCode.PIVAS_STATUS_INVALID,
                    "只有已配置的静配单可以复核，当前：" + order.status());
        }
        if (order.preparedBy() != null && order.preparedBy().equalsIgnoreCase(operator)) {
            throw new BusinessException(ErrorCode.PIVAS_SELF_CHECK);
        }
        boolean approve = Boolean.TRUE.equals(req.approve());
        String note = blankToNull(req.note());
        if (!approve) {
            return rejectPrepared(order, note, operator);
        }
        String bagNo = repo.nextPivasBagNo();
        if (!repo.markPivasChecked(id, order.version(), operator, bagNo)) {
            throw new BusinessException(ErrorCode.CONFLICT, "静配单状态已变化，请刷新后重试");
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("orderNo", order.orderNo());
        detail.put("bagNo", bagNo);
        if (note != null) {
            detail.put("note", note);
        }
        audit(operator, "PIVAS_CHECK", id.toString(), detail);
        return reload(id);
    }

    /** 驳回未配置的静配单（配置后的驳回必须走复核不通过，以便回补批次库存）。 */
    @Transactional
    public PivasView reject(UUID id, RejectPivasReq req, String actor) {
        String operator = requireActor(actor);
        PivasOrder order = repo.lockPivasOrder(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.PIVAS_ORDER_NOT_FOUND));
        if (!order.pending()) {
            throw new BusinessException(ErrorCode.PIVAS_STATUS_INVALID,
                    "只有待配置的静配单可以直接驳回；已配置请走复核不通过，当前：" + order.status());
        }
        String reason = blankToNull(req.reason());
        if (reason == null) {
            throw new BusinessException(ErrorCode.PIVAS_REJECT_REASON_REQUIRED);
        }
        if (!repo.markPivasRejected(id, order.version(), reason)) {
            throw new BusinessException(ErrorCode.CONFLICT, "静配单状态已变化，请刷新后重试");
        }
        audit(operator, "PIVAS_REJECT", id.toString(), Map.of(
                "orderNo", order.orderNo(), "reason", reason));
        return reload(id);
    }

    public PivasView detail(UUID id) {
        PivasOrder order = repo.findPivasOrder(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.PIVAS_ORDER_NOT_FOUND));
        return view(order);
    }

    /** 成品输液追溯：按标签号查全链路（静配单 + 药品明细 + 批次耗用）。 */
    public PivasView trace(String bagNo) {
        String trimmed = bagNo == null ? "" : bagNo.trim();
        if (trimmed.isEmpty()) {
            throw new BusinessException(ErrorCode.PIVAS_ORDER_NOT_FOUND, "请填写成品输液标签号");
        }
        PivasOrder order = repo.findPivasOrderByBagNo(trimmed)
                .orElseThrow(() -> new BusinessException(ErrorCode.PIVAS_ORDER_NOT_FOUND,
                        "未找到标签号 " + trimmed + " 对应的成品输液"));
        return view(order);
    }

    public List<PivasOrder> list(String status, Integer limit) {
        String normalized = normalizeStatus(status);
        int size = limit == null || limit <= 0 ? 50 : Math.min(limit, 200);
        return repo.listPivasOrders(normalized, size);
    }

    public PivasSummary summary() {
        int pending = 0;
        int prepared = 0;
        int checked = 0;
        int rejected = 0;
        for (PivasStatusCount row : repo.pivasStatusCounts()) {
            switch (row.status()) {
                case PivasOrder.PENDING -> pending = row.count();
                case PivasOrder.PREPARED -> prepared = row.count();
                case PivasOrder.CHECKED -> checked = row.count();
                case PivasOrder.REJECTED -> rejected = row.count();
                default -> {
                }
            }
        }
        return new PivasSummary(pending, prepared, checked, rejected,
                pending + prepared + checked + rejected);
    }

    private PivasView rejectPrepared(PivasOrder order, String reason, String operator) {
        if (reason == null) {
            throw new BusinessException(ErrorCode.PIVAS_REJECT_REASON_REQUIRED,
                    "复核不通过必须填写原因");
        }
        List<PivasItemBatch> batches = repo.listPivasItemBatches(order.id());
        for (PivasItemBatch batch : batches) {
            if (!repo.addBatchQuantity(batch.batchId(), batch.quantity())) {
                throw new BusinessException(ErrorCode.CONFLICT,
                        "批次 " + batch.batchNo() + " 回补失败，请刷新后重试");
            }
        }
        if (!repo.markPivasRejected(order.id(), order.version(), reason)) {
            throw new BusinessException(ErrorCode.CONFLICT, "静配单状态已变化，请刷新后重试");
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("orderNo", order.orderNo());
        detail.put("reason", reason);
        detail.put("restoredBatches", batches.size());
        audit(operator, "PIVAS_REJECT", order.id().toString(), detail);
        return reload(order.id());
    }

    private PivasView view(PivasOrder order) {
        return new PivasView(order, repo.listPivasItems(order.id()),
                repo.listPivasItemBatches(order.id()));
    }

    /** 状态变更后重新读取，保证返回给调用方的是数据库中的最新状态与版本号。 */
    private PivasView reload(UUID id) {
        return view(repo.findPivasOrder(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.PIVAS_ORDER_NOT_FOUND)));
    }

    private static String summarize(List<PrescriptionItem> items) {
        StringBuilder sb = new StringBuilder();
        for (PrescriptionItem item : items) {
            if (sb.length() > 0) {
                sb.append("；");
            }
            sb.append(item.drugName()).append('×').append(item.quantity()).append(nvl(item.unit()));
        }
        return sb.toString();
    }

    private static String normalizePriority(String priority) {
        if (priority == null || priority.isBlank()) {
            return PivasOrder.PRIORITY_NORMAL;
        }
        String value = priority.trim().toUpperCase();
        if (!PRIORITIES.contains(value)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "优先级只能是 NORMAL 或 URGENT");
        }
        return value;
    }

    private static String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        String value = status.trim().toUpperCase();
        if (!STATUSES.contains(value)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "不支持的静配单状态：" + status);
        }
        return value;
    }

    private static String requireActor(String actor) {
        if (actor == null || actor.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "缺少操作人，不能执行静配操作");
        }
        return actor.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String nvl(String value) {
        return value == null ? "" : value;
    }

    private void audit(String actor, String action, String targetId, Map<String, Object> detail) {
        try {
            repo.logChange(actor, action, "PIVAS_ORDER", targetId,
                    objectMapper.writeValueAsString(new LinkedHashMap<>(detail)));
        } catch (JacksonException e) {
            repo.logChange(actor, action, "PIVAS_ORDER", targetId,
                    "{\"_detail_serialize_failed\":true}");
        }
    }
}
