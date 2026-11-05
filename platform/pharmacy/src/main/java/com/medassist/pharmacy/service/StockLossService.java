package com.medassist.pharmacy.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.time.BusinessTime;
import com.medassist.pharmacy.domain.PharmacyModel.DrugBatch;
import com.medassist.pharmacy.domain.PharmacyModel.ExpiredBatchRow;
import com.medassist.pharmacy.domain.PharmacyModel.StockLoss;
import com.medassist.pharmacy.domain.PharmacyModel.StockLossItem;
import com.medassist.pharmacy.repo.PharmacyRepository;
import com.medassist.pharmacy.web.StockLossDtos.AutoDraftResult;
import com.medassist.pharmacy.web.StockLossDtos.CreateStockLossReq;
import com.medassist.pharmacy.web.StockLossDtos.LossLine;
import com.medassist.pharmacy.web.StockLossDtos.ReviewStockLossReq;
import com.medassist.pharmacy.web.StockLossDtos.StockLossView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 药品报损：提交即锁定 → 审批扣减库存 / 驳回。
 *
 * <p>与盘点的分工：盘点发现"账实不符"，报损解释"药为什么减少"。
 * 两者最终都写同一条追加式库存调整流水，库存变化只有一个可追溯出口。
 *
 * <p>硬约束：
 * <ul>
 *   <li>同一报损单同一批次只能出现一次；</li>
 *   <li>提交时校验数量 ≤ 当前余量，审批时再次在行锁内校验并原子扣减；</li>
 *   <li>审批人 ≠ 申请人（四眼原则）；驳回必须写原因；</li>
 *   <li>过期批次允许报损（这正是有效期报废的合法出口）。</li>
 * </ul>
 */
@Service
public class StockLossService {

    private static final Logger log = LoggerFactory.getLogger(StockLossService.class);

    private static final Set<String> STATUSES =
            Set.of(StockLoss.DRAFT, StockLoss.SUBMITTED, StockLoss.APPROVED, StockLoss.REJECTED);

    private static final int AUTO_DRAFT_BATCH_LIMIT = 500;

    private final PharmacyRepository repo;
    private final ObjectMapper objectMapper;

    public StockLossService(PharmacyRepository repo, ObjectMapper objectMapper) {
        this.repo = repo;
        this.objectMapper = objectMapper;
    }

    /** 提交报损单（提交即锁定明细，不允许撤回修改）。 */
    @Transactional
    public StockLossView create(CreateStockLossReq req, String actor) {
        String operator = requireActor(actor);
        List<LossLine> lines = req.items();
        Set<UUID> seen = new HashSet<>();
        for (LossLine line : lines) {
            if (!seen.add(line.batchId())) {
                throw new BusinessException(ErrorCode.STOCK_LOSS_ITEM_INVALID,
                        "同一批次在报损单中重复出现，请合并数量");
            }
        }
        // 先全部校验再落库：避免"前几条已插入、后面失败"的半张单
        for (LossLine line : lines) {
            DrugBatch batch = repo.findBatch(line.batchId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.STOCK_LOSS_BATCH_NOT_FOUND,
                            "批次 " + line.batchId() + " 不存在"));
            if (line.quantity() > batch.quantity()) {
                throw new BusinessException(ErrorCode.STOCK_LOSS_EXCEEDS,
                        "批次 " + batch.batchNo() + " 当前余量 " + batch.quantity()
                                + "，报损 " + line.quantity() + " 超出");
            }
        }
        StockLoss loss = new StockLoss(UUID.randomUUID(), repo.nextStockLossNo(),
                StockLoss.SUBMITTED, req.reasonCategory(), blankToNull(req.note()), operator,
                null, null, null, 0L, Instant.now());
        repo.insertStockLoss(loss);
        for (LossLine line : lines) {
            DrugBatch batch = repo.findBatch(line.batchId()).orElseThrow();
            repo.insertStockLossItem(UUID.randomUUID(), loss.id(), batch.drugId(),
                    batch.id(), line.quantity(), blankToNull(line.reason()));
        }
        audit(operator, "STOCK_LOSS_CREATE", loss.id().toString(), Map.of(
                "lossNo", loss.lossNo(), "category", loss.reasonCategory(),
                "items", lines.size()));
        return view(loss.id());
    }

    /**
     * 效期自动报废（G28）：扫描已过期且仍有库存的批次，按药品聚合生成 DRAFT 报损单。
     *
     * <p>只生成草稿，不扣库存：仍需人工提交并由另一位药剂师审批。
     * 已在 DRAFT/SUBMITTED/APPROVED 报损单中的批次不会重复生成。
     */
    @Transactional
    public AutoDraftResult autoDraftExpired(String actor) {
        String operator = requireActor(actor);
        if (!repo.tryLockExpiryAutoDraft()) {
            // 另一个实例正在扫描（多副本同跑 01:10），本轮跳过，避免生成重复草稿
            log.info("pharmacy.expiry_loss.auto_draft_skipped reason=lock_held");
            return new AutoDraftResult(0, 0);
        }
        LocalDate today = BusinessTime.today();
        List<ExpiredBatchRow> batches =
                repo.findExpiredBatchesForLoss(today, AUTO_DRAFT_BATCH_LIMIT);
        if (batches.isEmpty()) {
            return new AutoDraftResult(0, 0);
        }
        Map<UUID, List<ExpiredBatchRow>> byDrug = new LinkedHashMap<>();
        for (ExpiredBatchRow batch : batches) {
            byDrug.computeIfAbsent(batch.drugId(), key -> new ArrayList<>()).add(batch);
        }
        int losses = 0;
        int items = 0;
        for (List<ExpiredBatchRow> drugBatches : byDrug.values()) {
            String note = "系统自动生成：效期早于 " + today + "，仍需人工提交与审批";
            UUID lossId = UUID.randomUUID();
            repo.insertAutoDraftLoss(lossId, repo.nextStockLossNo(), note, operator);
            for (ExpiredBatchRow batch : drugBatches) {
                repo.insertStockLossItem(UUID.randomUUID(), lossId, batch.drugId(),
                        batch.batchId(), batch.quantity(), "到期报废（效期 " + batch.expireDate() + "）");
                items++;
            }
            losses++;
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("today", today.toString());
        detail.put("losses", losses);
        detail.put("items", items);
        audit(operator, "STOCK_LOSS_AUTO_DRAFT", "batch", detail);
        return new AutoDraftResult(losses, items);
    }

    /**
     * 提交草稿：DRAFT → SUBMITTED，提交前再次校验批次余量，避免"扫描后药已发完"仍提交。
     *
     * <p>提交人同时成为申请人（created_by 改写）：自动草稿的创建人是调度器，
     * 若不改写，提交人就能审批自己提交的单，四眼原则失效。
     * 已提交的草稿幂等返回；已通过/驳回的单据拒绝重复提交。
     */
    @Transactional
    public StockLossView submit(UUID id, String actor) {
        String operator = requireActor(actor);
        StockLoss loss = repo.lockStockLoss(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.STOCK_LOSS_NOT_FOUND));
        if (StockLoss.SUBMITTED.equals(loss.status())) {
            return view(id);
        }
        if (!loss.submittable()) {
            throw new BusinessException(ErrorCode.STOCK_LOSS_STATUS_INVALID,
                    "报损单当前状态为 " + loss.status() + "，不能提交");
        }
        List<StockLossItem> items = repo.listStockLossItems(id);
        for (StockLossItem item : items) {
            DrugBatch batch = repo.findBatch(item.batchId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.STOCK_LOSS_BATCH_NOT_FOUND,
                            "批次 " + item.batchNo() + " 已不存在"));
            if (batch.quantity() < item.quantity()) {
                throw new BusinessException(ErrorCode.STOCK_LOSS_EXCEEDS,
                        "批次 " + batch.batchNo() + " 当前余量 " + batch.quantity()
                                + "，草稿数量 " + item.quantity() + "，请调整后再提交");
            }
        }
        if (!repo.submitStockLoss(id, operator, loss.version())) {
            throw new BusinessException(ErrorCode.CONFLICT, "报损单已变化，请刷新后重试");
        }
        audit(operator, "STOCK_LOSS_SUBMIT", id.toString(), Map.of(
                "lossNo", loss.lossNo(), "items", items.size()));
        return view(id);
    }

    public List<StockLoss> list(String status, Integer limit) {
        String normalized = normalizeStatus(status);
        int size = limit == null || limit <= 0 ? 50 : Math.min(limit, 200);
        return repo.listStockLosses(normalized, size);
    }

    public StockLossView detail(UUID id) {
        return view(id);
    }

    /**
     * 审批 / 驳回。
     *
     * <p>审批在一次事务内完成"锁单 → 逐批锁行校验余量 → 原子扣减 → 写调整流水 → 标记通过"；
     * 任一批次不足都会整单回滚，不会出现"扣了一半"。
     */
    @Transactional
    public StockLossView review(UUID id, ReviewStockLossReq req, String actor) {
        String operator = requireActor(actor);
        StockLoss loss = repo.lockStockLoss(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.STOCK_LOSS_NOT_FOUND));
        if (!loss.reviewable()) {
            throw new BusinessException(ErrorCode.STOCK_LOSS_STATUS_INVALID,
                    "报损单当前状态为 " + loss.status() + "，不能重复审批");
        }
        if (loss.createdBy().equalsIgnoreCase(operator)) {
            throw new BusinessException(ErrorCode.STOCK_LOSS_SELF_REVIEW,
                    "报损单 " + loss.lossNo() + " 由你提交，必须由另一位药剂师审批");
        }
        boolean approve = Boolean.TRUE.equals(req.approve());
        String note = blankToNull(req.note());
        if (!approve && note == null) {
            throw new BusinessException(ErrorCode.STOCK_LOSS_REJECT_REASON_REQUIRED);
        }
        List<StockLossItem> items = repo.listStockLossItems(id);
        if (approve) {
            for (StockLossItem item : items) {
                DrugBatch batch = repo.lockBatch(item.batchId())
                        .orElseThrow(() -> new BusinessException(ErrorCode.STOCK_LOSS_BATCH_NOT_FOUND,
                                "批次 " + item.batchNo() + " 已不存在"));
                if (batch.quantity() < item.quantity()) {
                    throw new BusinessException(ErrorCode.STOCK_LOSS_STOCK_INSUFFICIENT,
                            "批次 " + batch.batchNo() + " 当前余量 " + batch.quantity()
                                    + "，报损 " + item.quantity() + "，请驳回后重新提交");
                }
                if (!repo.deductBatch(item.batchId(), item.quantity())) {
                    throw new BusinessException(ErrorCode.STOCK_LOSS_STOCK_INSUFFICIENT,
                            "批次 " + batch.batchNo() + " 扣减失败（已被并发修改）");
                }
                repo.insertStockLossAdjustment(id, item.drugId(), item.batchId(),
                        batch.quantity(), batch.quantity() - item.quantity(), -item.quantity(),
                        item.reason() == null ? loss.reasonCategory() : item.reason(), operator);
            }
        }
        if (!repo.reviewStockLoss(id, loss.version(), approve, operator, note)) {
            throw new BusinessException(ErrorCode.CONFLICT, "报损单已变化，请刷新后重试");
        }
        audit(operator, approve ? "STOCK_LOSS_APPROVE" : "STOCK_LOSS_REJECT", id.toString(),
                Map.of("lossNo", loss.lossNo(), "items", items.size(),
                        "note", note == null ? "" : note));
        return view(id);
    }

    // ---------------------------------------------------------------- 内部

    private StockLossView view(UUID id) {
        StockLoss loss = repo.findStockLoss(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.STOCK_LOSS_NOT_FOUND));
        return new StockLossView(loss, repo.listStockLossItems(id),
                repo.listStockLossAdjustments(id));
    }

    private static String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        String value = status.trim().toUpperCase();
        if (!STATUSES.contains(value)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "报损状态只能是 DRAFT / SUBMITTED / APPROVED / REJECTED");
        }
        return value;
    }

    private static String requireActor(String actor) {
        if (actor == null || actor.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "缺少操作人，不能执行报损操作");
        }
        return actor.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private void audit(String actor, String action, String targetId, Map<String, Object> detail) {
        try {
            repo.logChange(actor, action, "STOCK_LOSS", targetId,
                    objectMapper.writeValueAsString(new LinkedHashMap<>(detail)));
        } catch (JacksonException e) {
            repo.logChange(actor, action, "STOCK_LOSS", targetId,
                    "{\"_detail_serialize_failed\":true}");
        }
    }
}
