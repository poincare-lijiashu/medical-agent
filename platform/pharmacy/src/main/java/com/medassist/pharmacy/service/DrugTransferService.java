package com.medassist.pharmacy.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.pharmacy.domain.PharmacyModel.DeptDrugStock;
import com.medassist.pharmacy.domain.PharmacyModel.DrugBatch;
import com.medassist.pharmacy.domain.PharmacyModel.DrugTransfer;
import com.medassist.pharmacy.domain.PharmacyModel.DrugTransferItem;
import com.medassist.pharmacy.repo.PharmacyRepository;
import com.medassist.pharmacy.web.DrugTransferDtos.CreateDrugTransferReq;
import com.medassist.pharmacy.web.DrugTransferDtos.DrugTransferView;
import com.medassist.pharmacy.web.DrugTransferDtos.TransferLine;
import com.medassist.pharmacy.web.DrugTransferDtos.TransferReasonReq;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 药品科室调拨：中心药房 → 科室二级库。
 *
 * <p>库存口径：发出即扣中心批次库存；科室接收才计入二级库；拒收原路退回中心。
 * 这样"在途"期间的药品只存在于调拨单上，不会同时出现在两个库存里。
 *
 * <p>硬约束：
 * <ul>
 *   <li>同一调拨单同一批次只能出现一次；</li>
 *   <li>提交时预校验批次与余量，发出时再用原子 UPDATE 扣减（quantity >= qty）；</li>
 *   <li>状态机 CAS：SUBMITTED → DISPATCHED → ACCEPTED/REJECTED，SUBMITTED 可撤回；</li>
 *   <li>拒收/撤回必须写原因；关闭后明细库级禁止修改。</li>
 * </ul>
 */
@Service
public class DrugTransferService {

    private static final Set<String> STATUSES = Set.of(
            DrugTransfer.SUBMITTED, DrugTransfer.DISPATCHED, DrugTransfer.ACCEPTED,
            DrugTransfer.REJECTED, DrugTransfer.CANCELLED);

    private final PharmacyRepository repo;
    private final ObjectMapper objectMapper;

    public DrugTransferService(PharmacyRepository repo, ObjectMapper objectMapper) {
        this.repo = repo;
        this.objectMapper = objectMapper;
    }

    /** 建单（SUBMITTED）：预校验批次余量，不扣库存。 */
    @Transactional
    public DrugTransferView create(CreateDrugTransferReq req, String actor) {
        String operator = requireActor(actor);
        List<TransferLine> lines = req.items();
        Set<UUID> seen = new HashSet<>();
        for (TransferLine line : lines) {
            if (!seen.add(line.batchId())) {
                throw new BusinessException(ErrorCode.DRUG_TRANSFER_ITEM_INVALID,
                        "同一批次在调拨单中重复出现，请合并数量");
            }
        }
        // 先全部校验再落库，避免半张单
        for (TransferLine line : lines) {
            DrugBatch batch = repo.findBatch(line.batchId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.DRUG_TRANSFER_BATCH_NOT_FOUND,
                            "批次 " + line.batchId() + " 不存在"));
            if (line.quantity() > batch.quantity()) {
                throw new BusinessException(ErrorCode.DRUG_TRANSFER_EXCEEDS,
                        "批次 " + batch.batchNo() + " 当前余量 " + batch.quantity()
                                + "，调拨 " + line.quantity() + " 超出");
            }
        }
        DrugTransfer transfer = new DrugTransfer(UUID.randomUUID(), repo.nextDrugTransferNo(),
                DrugTransfer.SUBMITTED, req.toDeptId(), blankToNull(req.note()), operator,
                null, null, null, null, null, null, null, null, null, null, 0L, Instant.now());
        repo.insertDrugTransfer(transfer);
        for (TransferLine line : lines) {
            DrugBatch batch = repo.findBatch(line.batchId()).orElseThrow();
            repo.insertDrugTransferItem(UUID.randomUUID(), transfer.id(), batch.drugId(),
                    batch.id(), line.quantity());
        }
        audit(operator, "DRUG_TRANSFER_CREATE", transfer.id().toString(), Map.of(
                "transferNo", transfer.transferNo(), "toDeptId", transfer.toDeptId().toString(),
                "items", lines.size()));
        return view(transfer.id());
    }

    public List<DrugTransfer> list(String status, UUID toDeptId, Integer limit) {
        String normalized = normalizeStatus(status);
        int size = limit == null || limit <= 0 ? 50 : Math.min(limit, 200);
        return repo.listDrugTransfers(normalized, toDeptId, size);
    }

    public DrugTransferView detail(UUID id) {
        return view(id);
    }

    /** 发出：扣中心批次库存（原子），任一批次不足整单回滚。 */
    @Transactional
    public DrugTransferView dispatch(UUID id, String actor) {
        String operator = requireActor(actor);
        DrugTransfer transfer = lockTransfer(id);
        if (!transfer.dispatchable()) {
            throw new BusinessException(ErrorCode.DRUG_TRANSFER_STATUS_INVALID,
                    "调拨单当前状态为 " + transfer.status() + "，不能发出");
        }
        List<DrugTransferItem> items = repo.listDrugTransferItems(id);
        for (DrugTransferItem item : items) {
            if (!repo.deductBatch(item.batchId(), item.quantity())) {
                throw new BusinessException(ErrorCode.DRUG_TRANSFER_STOCK_INSUFFICIENT,
                        "批次 " + item.batchNo() + " 余量不足（需 " + item.quantity()
                                + "），请复核后重新建单");
            }
        }
        if (!repo.dispatchDrugTransfer(id, transfer.version(), operator)) {
            throw new BusinessException(ErrorCode.CONFLICT, "调拨单已变化，请刷新后重试");
        }
        audit(operator, "DRUG_TRANSFER_DISPATCH", id.toString(), Map.of(
                "transferNo", transfer.transferNo(), "items", items.size()));
        return view(id);
    }

    /** 接收：把明细计入科室二级库。 */
    @Transactional
    public DrugTransferView accept(UUID id, String actor) {
        String operator = requireActor(actor);
        DrugTransfer transfer = lockTransfer(id);
        if (!transfer.receivable()) {
            throw new BusinessException(ErrorCode.DRUG_TRANSFER_STATUS_INVALID,
                    "调拨单当前状态为 " + transfer.status() + "，不能接收");
        }
        List<DrugTransferItem> items = repo.listDrugTransferItems(id);
        for (DrugTransferItem item : items) {
            repo.upsertDeptDrugStock(transfer.toDeptId(), item.drugId(), item.batchId(),
                    item.quantity());
        }
        if (!repo.acceptDrugTransfer(id, transfer.version(), operator)) {
            throw new BusinessException(ErrorCode.CONFLICT, "调拨单已变化，请刷新后重试");
        }
        audit(operator, "DRUG_TRANSFER_ACCEPT", id.toString(), Map.of(
                "transferNo", transfer.transferNo(), "toDeptId", transfer.toDeptId().toString(),
                "items", items.size()));
        return view(id);
    }

    /** 拒收：写原因并把已扣库存原路退回中心批次。 */
    @Transactional
    public DrugTransferView reject(UUID id, TransferReasonReq req, String actor) {
        String operator = requireActor(actor);
        String reason = req == null ? null : blankToNull(req.reason());
        if (reason == null) {
            throw new BusinessException(ErrorCode.DRUG_TRANSFER_REJECT_REASON_REQUIRED);
        }
        DrugTransfer transfer = lockTransfer(id);
        if (!transfer.receivable()) {
            throw new BusinessException(ErrorCode.DRUG_TRANSFER_STATUS_INVALID,
                    "调拨单当前状态为 " + transfer.status() + "，不能拒收");
        }
        List<DrugTransferItem> items = repo.listDrugTransferItems(id);
        if (!repo.rejectDrugTransfer(id, transfer.version(), operator, reason)) {
            throw new BusinessException(ErrorCode.CONFLICT, "调拨单已变化，请刷新后重试");
        }
        for (DrugTransferItem item : items) {
            repo.addBatchQuantity(item.batchId(), item.quantity());
        }
        audit(operator, "DRUG_TRANSFER_REJECT", id.toString(), Map.of(
                "transferNo", transfer.transferNo(), "reason", reason,
                "items", items.size()));
        return view(id);
    }

    /** 撤回：仅 SUBMITTED（尚未扣库存）。 */
    @Transactional
    public DrugTransferView cancel(UUID id, TransferReasonReq req, String actor) {
        String operator = requireActor(actor);
        String reason = req == null ? null : blankToNull(req.reason());
        if (reason == null) {
            throw new BusinessException(ErrorCode.DRUG_TRANSFER_CANCEL_REASON_REQUIRED);
        }
        DrugTransfer transfer = lockTransfer(id);
        if (!transfer.cancellable()) {
            throw new BusinessException(ErrorCode.DRUG_TRANSFER_STATUS_INVALID,
                    "调拨单当前状态为 " + transfer.status() + "，不能撤回");
        }
        if (!repo.cancelDrugTransfer(id, transfer.version(), operator, reason)) {
            throw new BusinessException(ErrorCode.CONFLICT, "调拨单已变化，请刷新后重试");
        }
        audit(operator, "DRUG_TRANSFER_CANCEL", id.toString(), Map.of(
                "transferNo", transfer.transferNo(), "reason", reason));
        return view(id);
    }

    /** 科室二级库余额。 */
    public List<DeptDrugStock> deptStock(UUID deptId, String keyword, Integer limit) {
        if (deptId == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "查询科室库存必须指定科室");
        }
        int size = limit == null || limit <= 0 ? 100 : Math.min(limit, 200);
        return repo.listDeptDrugStock(deptId, keyword, size);
    }

    // ---------------------------------------------------------------- 内部

    private DrugTransfer lockTransfer(UUID id) {
        return repo.lockDrugTransfer(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.DRUG_TRANSFER_NOT_FOUND));
    }

    private DrugTransferView view(UUID id) {
        DrugTransfer transfer = repo.findDrugTransfer(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.DRUG_TRANSFER_NOT_FOUND));
        return new DrugTransferView(transfer, repo.listDrugTransferItems(id));
    }

    private static String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        String value = status.trim().toUpperCase();
        if (!STATUSES.contains(value)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "调拨状态只能是 SUBMITTED / DISPATCHED / ACCEPTED / REJECTED / CANCELLED");
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
            repo.logChange(actor, action, "DRUG_TRANSFER", targetId,
                    objectMapper.writeValueAsString(new LinkedHashMap<>(detail)));
        } catch (JacksonException e) {
            repo.logChange(actor, action, "DRUG_TRANSFER", targetId,
                    "{\"_detail_serialize_failed\":true}");
        }
    }
}
