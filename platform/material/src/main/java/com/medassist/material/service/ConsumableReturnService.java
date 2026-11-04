package com.medassist.material.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.material.domain.MaterialModel.ConsumableBatch;
import com.medassist.material.domain.MaterialModel.ConsumableReturn;
import com.medassist.material.domain.MaterialModel.ConsumableReturnItem;
import com.medassist.material.domain.MaterialModel.DeptConsumableStock;
import com.medassist.material.repo.MaterialRepository;
import com.medassist.material.web.MaterialReturnDtos.ConsumableReturnView;
import com.medassist.material.web.MaterialReturnDtos.CreateReturnReq;
import com.medassist.material.web.MaterialReturnDtos.ReturnLine;
import com.medassist.material.web.MaterialReturnDtos.ReturnReasonReq;
import com.medassist.material.web.MaterialReturnDtos.ReviewReturnReq;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 耗材未使用退回：科室二级库 → 中心库房。
 *
 * <p>库存口径：提交不动库存；审批通过时先扣科室二级库、再回冲中心批次；
 * 驳回与撤回都不动库存，避免"审批未通过库先变"。
 *
 * <p>硬约束：
 * <ul>
 *   <li>同一退回单同一批次只能出现一次；</li>
 *   <li>建单时按科室二级库余额预校验，审批时再用原子 UPDATE 扣减（quantity >= qty）；</li>
 *   <li>状态机 CAS：SUBMITTED → APPROVED / REJECTED，SUBMITTED 可撤回；</li>
 *   <li>审批人不得是申请人（四眼），驳回/撤回必须写原因；关闭后明细库级禁止修改。</li>
 * </ul>
 */
@Service
public class ConsumableReturnService {

    private static final Set<String> STATUSES = Set.of(
            ConsumableReturn.SUBMITTED, ConsumableReturn.APPROVED,
            ConsumableReturn.REJECTED, ConsumableReturn.CANCELLED);

    private final MaterialRepository repo;
    private final ObjectMapper objectMapper;

    public ConsumableReturnService(MaterialRepository repo, ObjectMapper objectMapper) {
        this.repo = repo;
        this.objectMapper = objectMapper;
    }

    /** 建单（SUBMITTED）：按科室二级库余额预校验，不扣库存。 */
    @Transactional
    public ConsumableReturnView create(CreateReturnReq req, String actor) {
        String operator = requireActor(actor);
        Set<UUID> seen = new HashSet<>();
        for (ReturnLine line : req.items()) {
            if (!seen.add(line.batchId())) {
                throw new BusinessException(ErrorCode.CONSUMABLE_RETURN_ITEM_INVALID,
                        "同一批次在退回单中重复出现，请合并数量");
            }
        }
        // 先全部校验再落库，避免半张单
        for (ReturnLine line : req.items()) {
            ConsumableBatch batch = repo.findBatch(line.batchId())
                    .orElseThrow(() -> new BusinessException(
                            ErrorCode.CONSUMABLE_RETURN_BATCH_NOT_FOUND,
                            "批次 " + line.batchId() + " 不存在"));
            Optional<DeptConsumableStock> stock = repo.findDeptConsumableStock(req.deptId(),
                    batch.consumableId(), batch.id());
            int available = stock.map(DeptConsumableStock::quantity).orElse(0);
            if (line.quantity() > available) {
                throw new BusinessException(ErrorCode.CONSUMABLE_DEPT_STOCK_INSUFFICIENT,
                        "耗材 " + batch.consumableName() + " 批号 " + batch.batchNo()
                                + " 科室余量 " + available + "，退回 " + line.quantity() + " 超出");
            }
        }
        ConsumableReturn header = new ConsumableReturn(UUID.randomUUID(), repo.nextReturnNo(),
                ConsumableReturn.SUBMITTED, req.deptId(), req.reason().trim(), operator,
                null, null, null, null, null, null, 0L, Instant.now());
        repo.insertReturn(header);
        for (ReturnLine line : req.items()) {
            ConsumableBatch batch = repo.findBatch(line.batchId()).orElseThrow();
            repo.insertReturnItem(new ConsumableReturnItem(UUID.randomUUID(), header.id(),
                    batch.consumableId(), batch.consumableCode(), batch.consumableName(), null,
                    null, batch.id(), batch.batchNo(), batch.expireDate(), line.quantity()));
        }
        audit(operator, "CONSUMABLE_RETURN_CREATE", header.id().toString(), Map.of(
                "returnNo", header.returnNo(),
                "deptId", header.deptId().toString(),
                "items", req.items().size()));
        return view(header.id());
    }

    public List<ConsumableReturn> list(String status, UUID deptId, Integer limit) {
        String normalized = normalizeStatus(status);
        int size = limit == null || limit <= 0 ? 50 : Math.min(limit, 200);
        return repo.listReturns(normalized, deptId, size);
    }

    public ConsumableReturnView detail(UUID id) {
        return view(id);
    }

    /** 审批通过：扣科室二级库 + 回冲中心批次（同一事务，任一行失败整单回滚）。 */
    @Transactional
    public ConsumableReturnView approve(UUID id, ReviewReturnReq req, String actor) {
        String operator = requireActor(actor);
        ConsumableReturn header = lockReturn(id);
        requireReviewable(header, "审批");
        requireFourEyes(header, operator);

        List<ConsumableReturnItem> items = repo.listReturnItems(id);
        for (ConsumableReturnItem item : items) {
            if (!repo.deductDeptConsumableStock(header.deptId(), item.consumableId(),
                    item.batchId(), item.quantity())) {
                throw new BusinessException(ErrorCode.CONSUMABLE_DEPT_STOCK_INSUFFICIENT,
                        "耗材 " + item.consumableName() + " 批号 " + item.batchNo()
                                + " 科室余量不足（需 " + item.quantity() + "），请复核后退回");
            }
        }
        for (ConsumableReturnItem item : items) {
            repo.addBatchQuantity(item.batchId(), item.quantity());
        }
        String note = req == null ? null : blankToNull(req.note());
        if (!repo.reviewReturn(id, header.version(), operator, true, note)) {
            throw new BusinessException(ErrorCode.CONFLICT, "退回单已变化，请刷新后重试");
        }
        audit(operator, "CONSUMABLE_RETURN_APPROVE", id.toString(), Map.of(
                "returnNo", header.returnNo(),
                "deptId", header.deptId().toString(),
                "items", items.size()));
        return view(id);
    }

    /** 驳回：必须写原因，不动库存。 */
    @Transactional
    public ConsumableReturnView reject(UUID id, ReturnReasonReq req, String actor) {
        String operator = requireActor(actor);
        String reason = req == null ? null : blankToNull(req.reason());
        if (reason == null) {
            throw new BusinessException(ErrorCode.CONSUMABLE_RETURN_REJECT_REASON_REQUIRED);
        }
        ConsumableReturn header = lockReturn(id);
        requireReviewable(header, "驳回");
        requireFourEyes(header, operator);
        if (!repo.reviewReturn(id, header.version(), operator, false, reason)) {
            throw new BusinessException(ErrorCode.CONFLICT, "退回单已变化，请刷新后重试");
        }
        audit(operator, "CONSUMABLE_RETURN_REJECT", id.toString(), Map.of(
                "returnNo", header.returnNo(), "reason", reason));
        return view(id);
    }

    /** 撤回：仅 SUBMITTED（尚未扣库存），必须写原因。 */
    @Transactional
    public ConsumableReturnView cancel(UUID id, ReturnReasonReq req, String actor) {
        String operator = requireActor(actor);
        String reason = req == null ? null : blankToNull(req.reason());
        if (reason == null) {
            throw new BusinessException(ErrorCode.CONSUMABLE_RETURN_CANCEL_REASON_REQUIRED);
        }
        ConsumableReturn header = lockReturn(id);
        if (!header.cancellable()) {
            throw new BusinessException(ErrorCode.CONSUMABLE_RETURN_STATUS_INVALID,
                    "退回单当前状态为 " + header.status() + "，不能撤回");
        }
        if (!repo.cancelReturn(id, header.version(), operator, reason)) {
            throw new BusinessException(ErrorCode.CONFLICT, "退回单已变化，请刷新后重试");
        }
        audit(operator, "CONSUMABLE_RETURN_CANCEL", id.toString(), Map.of(
                "returnNo", header.returnNo(), "reason", reason));
        return view(id);
    }

    /** 科室二级库余额。 */
    public List<DeptConsumableStock> deptStock(UUID deptId, String keyword, Integer limit) {
        if (deptId == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "查询科室库存必须指定科室");
        }
        int size = limit == null || limit <= 0 ? 100 : Math.min(limit, 200);
        return repo.listDeptConsumableStock(deptId, keyword, size);
    }

    // ---------------------------------------------------------------- 内部

    private ConsumableReturn lockReturn(UUID id) {
        return repo.lockReturn(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.CONSUMABLE_RETURN_NOT_FOUND));
    }

    private ConsumableReturnView view(UUID id) {
        ConsumableReturn header = repo.findReturn(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.CONSUMABLE_RETURN_NOT_FOUND));
        return new ConsumableReturnView(header, repo.listReturnItems(id));
    }

    private static void requireReviewable(ConsumableReturn header, String action) {
        if (!header.reviewable()) {
            throw new BusinessException(ErrorCode.CONSUMABLE_RETURN_STATUS_INVALID,
                    "退回单当前状态为 " + header.status() + "，不能" + action);
        }
    }

    /** 四眼：审批/驳回人不能是申请人。 */
    private static void requireFourEyes(ConsumableReturn header, String operator) {
        if (operator.equals(header.createdBy())) {
            throw new BusinessException(ErrorCode.CONSUMABLE_RETURN_SELF_REVIEW);
        }
    }

    private static String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        String value = status.trim().toUpperCase();
        if (!STATUSES.contains(value)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "退回状态只能是 SUBMITTED / APPROVED / REJECTED / CANCELLED");
        }
        return value;
    }

    private static String requireActor(String actor) {
        if (actor == null || actor.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "缺少操作人，不能执行退回操作");
        }
        return actor.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private void audit(String actor, String action, String targetId, Map<String, Object> detail) {
        try {
            repo.logChange(actor, action, "CONSUMABLE_RETURN", targetId,
                    objectMapper.writeValueAsString(new LinkedHashMap<>(detail)));
        } catch (JacksonException e) {
            repo.logChange(actor, action, "CONSUMABLE_RETURN", targetId,
                    "{\"_detail_serialize_failed\":true}");
        }
    }
}
