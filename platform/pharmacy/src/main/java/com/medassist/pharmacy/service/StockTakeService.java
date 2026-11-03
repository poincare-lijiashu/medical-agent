package com.medassist.pharmacy.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.pharmacy.domain.PharmacyModel.DrugBatch;
import com.medassist.pharmacy.domain.PharmacyModel.StockAdjustment;
import com.medassist.pharmacy.domain.PharmacyModel.StockTake;
import com.medassist.pharmacy.domain.PharmacyModel.StockTakeItem;
import com.medassist.pharmacy.domain.PharmacyModel.StockTakeSource;
import com.medassist.pharmacy.repo.PharmacyRepository;
import com.medassist.pharmacy.web.StockTakeDtos.CancelStockTakeReq;
import com.medassist.pharmacy.web.StockTakeDtos.CountItemReq;
import com.medassist.pharmacy.web.StockTakeDtos.CreateStockTakeReq;
import com.medassist.pharmacy.web.StockTakeDtos.StockTakeDetail;
import com.medassist.pharmacy.web.StockTakeDtos.TakeSummary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 药品盘点：账面快照 → 实盘录入 → 提交 → 审批校准。
 *
 * <p>核心不变量：
 * <ul>
 *   <li>同一时刻只有一张进行中的盘点单（库级唯一索引 + 服务层预查）；</li>
 *   <li>实盘与账面不一致必须写原因，否则不允许录入；</li>
 *   <li>提交要求全部批次已录入；审批要求审批人 ≠ 建单人（四眼原则）；</li>
 *   <li>审批时若批次当前数量已偏离建单快照（盘点期间又发药/入库），整单 409，
 *       避免把差异重复计算；差异落 stock_adjustment（追加式）。</li>
 * </ul>
 */
@Service
public class StockTakeService {

    private static final Set<String> STATUSES =
            Set.of(StockTake.COUNTING, StockTake.SUBMITTED, StockTake.APPROVED, StockTake.CANCELLED);

    private final PharmacyRepository repo;
    private final ObjectMapper objectMapper;

    public StockTakeService(PharmacyRepository repo, ObjectMapper objectMapper) {
        this.repo = repo;
        this.objectMapper = objectMapper;
    }

    /** 建单：快照当前所有有余量的批次。 */
    @Transactional
    public StockTakeDetail create(CreateStockTakeReq req, String actor) {
        String operator = requireActor(actor);
        StockTake active = repo.findActiveStockTake().orElse(null);
        if (active != null) {
            throw new BusinessException(ErrorCode.STOCK_TAKE_ACTIVE_EXISTS,
                    "盘点单 " + active.takeNo() + " 仍在进行中（" + active.status()
                            + "），请先提交并审批或取消");
        }
        String keyword = req == null ? null : blankToNull(req.keyword());
        List<StockTakeSource> sources = repo.listStockTakeSources(keyword);
        if (sources.isEmpty()) {
            throw new BusinessException(ErrorCode.STOCK_TAKE_EMPTY);
        }
        StockTake take = new StockTake(UUID.randomUUID(), repo.nextStockTakeNo(),
                StockTake.COUNTING, keyword == null ? "全库批次" : "筛选：" + keyword,
                req == null ? null : blankToNull(req.note()), operator,
                null, null, null, null, null, null, null, 0L, Instant.now());
        try {
            repo.insertStockTake(take);
        } catch (DataIntegrityViolationException e) {
            // 并发建单撞唯一索引：事务已中止，不能再查库
            throw new BusinessException(ErrorCode.STOCK_TAKE_ACTIVE_EXISTS);
        }
        for (StockTakeSource s : sources) {
            repo.insertStockTakeItem(UUID.randomUUID(), take.id(), s.drugId(), s.batchId(),
                    s.quantity());
        }
        audit(operator, "STOCK_TAKE_CREATE", take.id().toString(), Map.of(
                "takeNo", take.takeNo(), "scope", take.scopeLabel(), "items", sources.size()));
        return detail(take.id());
    }

    public List<StockTake> list(String status, Integer limit) {
        String normalized = normalizeStatus(status);
        int size = limit == null || limit <= 0 ? 50 : Math.min(limit, 200);
        return repo.listStockTakes(normalized, size);
    }

    public StockTakeDetail detail(UUID takeId) {
        StockTake take = repo.findStockTake(takeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.STOCK_TAKE_NOT_FOUND));
        List<StockTakeItem> items = repo.listStockTakeItems(takeId);
        return new StockTakeDetail(take, items, repo.listStockAdjustments(takeId),
                summarize(items));
    }

    /** 录入实盘数量（可反复覆盖，直到提交）。 */
    @Transactional
    public StockTakeItem count(UUID takeId, UUID itemId, CountItemReq req, String actor) {
        String operator = requireActor(actor);
        StockTake take = lockTake(takeId);
        if (!take.countable()) {
            throw new BusinessException(ErrorCode.STOCK_TAKE_STATUS_INVALID,
                    "盘点单当前状态为 " + take.status() + "，不能再录入实盘");
        }
        StockTakeItem item = findItem(takeId, itemId);
        String reason = blankToNull(req.reason());
        if (req.actualQuantity() != item.bookQuantity() && reason == null) {
            throw new BusinessException(ErrorCode.STOCK_TAKE_VARIANCE_REASON_REQUIRED,
                    "批次 " + item.batchNo() + " 账面 " + item.bookQuantity()
                            + "，实盘 " + req.actualQuantity() + "，请填写差异原因");
        }
        boolean updated = repo.countStockTakeItem(itemId, takeId, req.actualQuantity(), reason,
                operator, req.version());
        if (!updated) {
            throw new BusinessException(ErrorCode.CONFLICT, "该明细已被他人修改，请刷新后重新录入");
        }
        audit(operator, "STOCK_TAKE_COUNT", takeId.toString(), Map.of(
                "takeNo", take.takeNo(), "batchNo", item.batchNo(),
                "book", item.bookQuantity(), "actual", req.actualQuantity(),
                "reason", reason == null ? "" : reason));
        return findItem(takeId, itemId);
    }

    /** 提交：全部批次必须已录入实盘。 */
    @Transactional
    public StockTakeDetail submit(UUID takeId, String actor) {
        String operator = requireActor(actor);
        StockTake take = lockTake(takeId);
        if (!take.countable()) {
            throw new BusinessException(ErrorCode.STOCK_TAKE_STATUS_INVALID,
                    "盘点单当前状态为 " + take.status() + "，不能提交");
        }
        int uncounted = repo.countUncountedItems(takeId);
        if (uncounted > 0) {
            throw new BusinessException(ErrorCode.STOCK_TAKE_INCOMPLETE,
                    "还有 " + uncounted + " 个批次未录入实盘数量，不能提交");
        }
        if (!repo.submitStockTake(takeId, take.version(), operator)) {
            throw new BusinessException(ErrorCode.CONFLICT, "盘点单已变化，请刷新后重试");
        }
        audit(operator, "STOCK_TAKE_SUBMIT", takeId.toString(), Map.of(
                "takeNo", take.takeNo(), "items", repo.listStockTakeItems(takeId).size()));
        return detail(takeId);
    }

    /**
     * 审批：按差异校准库存并生成调整流水。
     *
     * <p>同一次事务内完成"校验快照 → 逐批校准 → 标记 APPROVED"，任一批次偏离都会整单回滚，
     * 不会出现"一半批次已改、一半没改"的中间态。
     */
    @Transactional
    public StockTakeDetail approve(UUID takeId, String actor) {
        String operator = requireActor(actor);
        StockTake take = lockTake(takeId);
        if (!take.approvable()) {
            throw new BusinessException(ErrorCode.STOCK_TAKE_STATUS_INVALID,
                    "盘点单当前状态为 " + take.status() + "，不能审批");
        }
        if (take.createdBy().equalsIgnoreCase(operator)) {
            throw new BusinessException(ErrorCode.STOCK_TAKE_SELF_APPROVAL,
                    "盘点单 " + take.takeNo() + " 由你创建，必须由另一位药剂师审批");
        }
        List<StockTakeItem> items = repo.listStockTakeItems(takeId);
        int varianceCount = 0;
        int increase = 0;
        int decrease = 0;
        for (StockTakeItem item : items) {
            int delta = item.variance();
            if (delta == 0) {
                continue;
            }
            DrugBatch batch = repo.lockBatch(item.batchId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.STOCK_TAKE_STOCK_DRIFT,
                            "批次 " + item.batchNo() + " 已不存在，请取消后重新盘点"));
            if (batch.quantity() != item.bookQuantity()) {
                throw new BusinessException(ErrorCode.STOCK_TAKE_STOCK_DRIFT,
                        "批次 " + item.batchNo() + " 建单时账面 " + item.bookQuantity()
                                + "，当前 " + batch.quantity() + "；盘点期间库存已变化，请取消后重新盘点");
            }
            if (!repo.calibrateBatchQuantity(item.batchId(), item.bookQuantity(),
                    item.actualQuantity(), batch.version())) {
                throw new BusinessException(ErrorCode.STOCK_TAKE_STOCK_DRIFT,
                        "批次 " + item.batchNo() + " 库存校准失败（已被并发修改），请取消后重新盘点");
            }
            repo.insertStockAdjustment(new StockAdjustment(UUID.randomUUID(), takeId,
                    item.drugId(), item.batchId(), item.batchNo(), item.bookQuantity(),
                    item.actualQuantity(), delta, item.reason(), operator, Instant.now()));
            varianceCount++;
            if (delta > 0) {
                increase += delta;
            } else {
                decrease += -delta;
            }
        }
        if (!repo.approveStockTake(takeId, take.version(), operator)) {
            throw new BusinessException(ErrorCode.CONFLICT, "盘点单已变化，请刷新后重试");
        }
        audit(operator, "STOCK_TAKE_APPROVE", takeId.toString(), Map.of(
                "takeNo", take.takeNo(), "items", items.size(), "varianceItems", varianceCount,
                "increaseQuantity", increase, "decreaseQuantity", decrease));
        return detail(takeId);
    }

    /** 取消：COUNTING/SUBMITTED 均可，必须写原因。 */
    @Transactional
    public StockTakeDetail cancel(UUID takeId, CancelStockTakeReq req, String actor) {
        String operator = requireActor(actor);
        StockTake take = lockTake(takeId);
        if (!take.cancellable()) {
            throw new BusinessException(ErrorCode.STOCK_TAKE_STATUS_INVALID,
                    "盘点单当前状态为 " + take.status() + "，不能取消");
        }
        String reason = req == null ? null : blankToNull(req.reason());
        if (reason == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "取消盘点必须填写原因");
        }
        if (!repo.cancelStockTake(takeId, take.version(), operator, reason)) {
            throw new BusinessException(ErrorCode.CONFLICT, "盘点单已变化，请刷新后重试");
        }
        audit(operator, "STOCK_TAKE_CANCEL", takeId.toString(), Map.of(
                "takeNo", take.takeNo(), "reason", reason));
        return detail(takeId);
    }

    // ---------------------------------------------------------------- 内部

    private StockTake lockTake(UUID takeId) {
        return repo.lockStockTake(takeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.STOCK_TAKE_NOT_FOUND));
    }

    private StockTakeItem findItem(UUID takeId, UUID itemId) {
        return repo.listStockTakeItems(takeId).stream()
                .filter(i -> i.id().equals(itemId))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.STOCK_TAKE_ITEM_NOT_FOUND));
    }

    private static TakeSummary summarize(List<StockTakeItem> items) {
        int counted = 0;
        int varianceCount = 0;
        int increase = 0;
        int decrease = 0;
        for (StockTakeItem item : items) {
            if (!item.counted()) {
                continue;
            }
            counted++;
            int delta = item.variance();
            if (delta > 0) {
                varianceCount++;
                increase += delta;
            } else if (delta < 0) {
                varianceCount++;
                decrease += -delta;
            }
        }
        return new TakeSummary(items.size(), counted, varianceCount, increase, decrease);
    }

    private static String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        String value = status.trim().toUpperCase();
        if (!STATUSES.contains(value)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "盘点状态只能是 COUNTING / SUBMITTED / APPROVED / CANCELLED");
        }
        return value;
    }

    private static String requireActor(String actor) {
        if (actor == null || actor.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "缺少操作人，不能执行盘点操作");
        }
        return actor.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private void audit(String actor, String action, String targetId, Map<String, Object> detail) {
        try {
            repo.logChange(actor, action, "STOCK_TAKE", targetId,
                    objectMapper.writeValueAsString(new LinkedHashMap<>(detail)));
        } catch (JacksonException e) {
            repo.logChange(actor, action, "STOCK_TAKE", targetId,
                    "{\"_detail_serialize_failed\":true}");
        }
    }
}
