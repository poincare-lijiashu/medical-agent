package com.medassist.material.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.material.domain.MaterialModel.ConsumableBatch;
import com.medassist.material.domain.MaterialModel.DeptConsumableStock;
import com.medassist.material.domain.MaterialModel.DeptTransfer;
import com.medassist.material.domain.MaterialModel.DeptTransferItem;
import com.medassist.material.repo.MaterialRepository;
import com.medassist.material.web.MaterialTransferDtos.CreateDeptTransferReq;
import com.medassist.material.web.MaterialTransferDtos.DeptTransferView;
import com.medassist.material.web.MaterialTransferDtos.TransferLine;
import com.medassist.material.web.MaterialTransferDtos.TransferReasonReq;
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
 * 耗材跨科室调拨（科室二级库 → 科室二级库）。
 *
 * <p>库存口径：发起与撤回都不动库存；调入科室接收时，同一事务内扣调出科室库、
 * 加调入科室库，任一行不足整单回滚。拒收不动库存。
 *
 * <p>硬约束：
 * <ul>
 *   <li>调出科室不能等于调入科室；同一单同一批次只能出现一次；</li>
 *   <li>建单按调出科室余额预校验，接收时再用原子 UPDATE 扣减（quantity >= qty）；</li>
 *   <li>状态机 CAS：SUBMITTED → ACCEPTED / REJECTED，SUBMITTED 可撤回；</li>
 *   <li>接收/拒收人不得是申请人，拒收/撤回必须写原因；关闭后明细库级禁止修改。</li>
 * </ul>
 */
@Service
public class DeptTransferService {

    private static final Set<String> STATUSES = Set.of(
            DeptTransfer.SUBMITTED, DeptTransfer.ACCEPTED,
            DeptTransfer.REJECTED, DeptTransfer.CANCELLED);

    private final MaterialRepository repo;
    private final ObjectMapper objectMapper;

    public DeptTransferService(MaterialRepository repo, ObjectMapper objectMapper) {
        this.repo = repo;
        this.objectMapper = objectMapper;
    }

    /** 建单（SUBMITTED）：按调出科室余额预校验，不扣库存。 */
    @Transactional
    public DeptTransferView create(CreateDeptTransferReq req, String actor) {
        String operator = requireActor(actor);
        if (req.fromDeptId().equals(req.toDeptId())) {
            throw new BusinessException(ErrorCode.DEPT_TRANSFER_SAME_DEPT);
        }
        Set<UUID> seen = new HashSet<>();
        for (TransferLine line : req.items()) {
            if (!seen.add(line.batchId())) {
                throw new BusinessException(ErrorCode.DEPT_TRANSFER_ITEM_INVALID,
                        "同一批次在调拨单中重复出现，请合并数量");
            }
        }
        // 先全部校验再落库，避免半张单
        for (TransferLine line : req.items()) {
            ConsumableBatch batch = repo.findBatch(line.batchId())
                    .orElseThrow(() -> new BusinessException(
                            ErrorCode.DEPT_TRANSFER_BATCH_NOT_FOUND,
                            "批次 " + line.batchId() + " 不存在"));
            Optional<DeptConsumableStock> stock = repo.findDeptConsumableStock(req.fromDeptId(),
                    batch.consumableId(), batch.id());
            int available = stock.map(DeptConsumableStock::quantity).orElse(0);
            if (line.quantity() > available) {
                throw new BusinessException(ErrorCode.DEPT_TRANSFER_STOCK_INSUFFICIENT,
                        "耗材 " + batch.consumableName() + " 批号 " + batch.batchNo()
                                + " 调出科室余量 " + available + "，调拨 " + line.quantity() + " 超出");
            }
        }
        DeptTransfer header = new DeptTransfer(UUID.randomUUID(), repo.nextDeptTransferNo(),
                DeptTransfer.SUBMITTED, req.fromDeptId(), req.toDeptId(), req.reason().trim(),
                blankToNull(req.note()), operator, null, null, null, null, null, null, null,
                null, 0L, Instant.now());
        repo.insertDeptTransfer(header);
        for (TransferLine line : req.items()) {
            ConsumableBatch batch = repo.findBatch(line.batchId()).orElseThrow();
            repo.insertDeptTransferItem(new DeptTransferItem(UUID.randomUUID(), header.id(),
                    batch.consumableId(), batch.consumableCode(), batch.consumableName(), null,
                    null, batch.id(), batch.batchNo(), batch.expireDate(), line.quantity()));
        }
        audit(operator, "DEPT_TRANSFER_CREATE", header.id().toString(), Map.of(
                "transferNo", header.transferNo(),
                "fromDeptId", header.fromDeptId().toString(),
                "toDeptId", header.toDeptId().toString(),
                "items", req.items().size()));
        return view(header.id());
    }

    public List<DeptTransfer> list(String status, UUID deptId, Integer limit) {
        String normalized = normalizeStatus(status);
        int size = limit == null || limit <= 0 ? 50 : Math.min(limit, 200);
        return repo.listDeptTransfers(normalized, deptId, size);
    }

    public DeptTransferView detail(UUID id) {
        return view(id);
    }

    /** 接收：扣调出科室库 + 加调入科室库（同一事务）。 */
    @Transactional
    public DeptTransferView accept(UUID id, String actor) {
        String operator = requireActor(actor);
        DeptTransfer transfer = lockTransfer(id);
        if (!transfer.receivable()) {
            throw new BusinessException(ErrorCode.DEPT_TRANSFER_STATUS_INVALID,
                    "调拨单当前状态为 " + transfer.status() + "，不能接收");
        }
        requireNotSelf(transfer, operator);
        List<DeptTransferItem> items = repo.listDeptTransferItems(id);
        for (DeptTransferItem item : items) {
            if (!repo.deductDeptConsumableStock(transfer.fromDeptId(), item.consumableId(),
                    item.batchId(), item.quantity())) {
                throw new BusinessException(ErrorCode.DEPT_TRANSFER_STOCK_INSUFFICIENT,
                        "耗材 " + item.consumableName() + " 批号 " + item.batchNo()
                                + " 调出科室余量不足（需 " + item.quantity() + "），请复核后重试");
            }
        }
        for (DeptTransferItem item : items) {
            repo.upsertDeptConsumableStock(transfer.toDeptId(), item.consumableId(),
                    item.batchId(), item.quantity());
        }
        if (!repo.acceptDeptTransfer(id, transfer.version(), operator)) {
            throw new BusinessException(ErrorCode.CONFLICT, "调拨单已变化，请刷新后重试");
        }
        audit(operator, "DEPT_TRANSFER_ACCEPT", id.toString(), Map.of(
                "transferNo", transfer.transferNo(),
                "fromDeptId", transfer.fromDeptId().toString(),
                "toDeptId", transfer.toDeptId().toString(),
                "items", items.size()));
        return view(id);
    }

    /** 拒收：必须写原因，不动库存。 */
    @Transactional
    public DeptTransferView reject(UUID id, TransferReasonReq req, String actor) {
        String operator = requireActor(actor);
        String reason = req == null ? null : blankToNull(req.reason());
        if (reason == null) {
            throw new BusinessException(ErrorCode.DEPT_TRANSFER_REJECT_REASON_REQUIRED);
        }
        DeptTransfer transfer = lockTransfer(id);
        if (!transfer.receivable()) {
            throw new BusinessException(ErrorCode.DEPT_TRANSFER_STATUS_INVALID,
                    "调拨单当前状态为 " + transfer.status() + "，不能拒收");
        }
        requireNotSelf(transfer, operator);
        if (!repo.rejectDeptTransfer(id, transfer.version(), operator, reason)) {
            throw new BusinessException(ErrorCode.CONFLICT, "调拨单已变化，请刷新后重试");
        }
        audit(operator, "DEPT_TRANSFER_REJECT", id.toString(), Map.of(
                "transferNo", transfer.transferNo(), "reason", reason));
        return view(id);
    }

    /** 撤回：仅 SUBMITTED，必须写原因，不动库存。 */
    @Transactional
    public DeptTransferView cancel(UUID id, TransferReasonReq req, String actor) {
        String operator = requireActor(actor);
        String reason = req == null ? null : blankToNull(req.reason());
        if (reason == null) {
            throw new BusinessException(ErrorCode.DEPT_TRANSFER_CANCEL_REASON_REQUIRED);
        }
        DeptTransfer transfer = lockTransfer(id);
        if (!transfer.cancellable()) {
            throw new BusinessException(ErrorCode.DEPT_TRANSFER_STATUS_INVALID,
                    "调拨单当前状态为 " + transfer.status() + "，不能撤回");
        }
        if (!repo.cancelDeptTransfer(id, transfer.version(), operator, reason)) {
            throw new BusinessException(ErrorCode.CONFLICT, "调拨单已变化，请刷新后重试");
        }
        audit(operator, "DEPT_TRANSFER_CANCEL", id.toString(), Map.of(
                "transferNo", transfer.transferNo(), "reason", reason));
        return view(id);
    }

    // ---------------------------------------------------------------- 内部

    private DeptTransfer lockTransfer(UUID id) {
        return repo.lockDeptTransfer(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.DEPT_TRANSFER_NOT_FOUND));
    }

    private DeptTransferView view(UUID id) {
        DeptTransfer header = repo.findDeptTransfer(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.DEPT_TRANSFER_NOT_FOUND));
        return new DeptTransferView(header, repo.listDeptTransferItems(id));
    }

    /** 接收/拒收人不得是申请人（避免自己发起自己签收）。 */
    private static void requireNotSelf(DeptTransfer transfer, String operator) {
        if (operator.equals(transfer.createdBy())) {
            throw new BusinessException(ErrorCode.DEPT_TRANSFER_SELF_ACCEPT);
        }
    }

    private static String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        String value = status.trim().toUpperCase();
        if (!STATUSES.contains(value)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "调拨状态只能是 SUBMITTED / ACCEPTED / REJECTED / CANCELLED");
        }
        return value;
    }

    private static String requireActor(String actor) {
        if (actor == null || actor.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "缺少操作人，不能执行调拨操作");
        }
        return actor.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private void audit(String actor, String action, String targetId, Map<String, Object> detail) {
        try {
            repo.logChange(actor, action, "DEPT_TRANSFER", targetId,
                    objectMapper.writeValueAsString(new LinkedHashMap<>(detail)));
        } catch (JacksonException e) {
            repo.logChange(actor, action, "DEPT_TRANSFER", targetId,
                    "{\"_detail_serialize_failed\":true}");
        }
    }
}
