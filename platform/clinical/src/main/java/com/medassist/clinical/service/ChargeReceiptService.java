package com.medassist.clinical.service;

import com.medassist.clinical.domain.ClinicalModel.Charge;
import com.medassist.clinical.domain.ClinicalModel.ChargeReceipt;
import com.medassist.clinical.repo.ClinicalRepository;
import com.medassist.clinical.web.ReceiptDtos.VoidReceiptReq;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 收费票据（G14）。
 *
 * <p>生命周期：已结算收费单 → 开票（ISSUED）→ 重打（次数累加）或作废（VOID）。
 * 作废后可换开新票；"一张收费单最多一张有效票据"由部分唯一索引兜底。
 *
 * <p>金额与支付方式取开票时快照：后续冲减/退费不回头改写已交给患者的凭证。
 */
@Service
public class ChargeReceiptService {

    private final ClinicalRepository repo;
    private final ObjectMapper objectMapper;

    public ChargeReceiptService(ClinicalRepository repo, ObjectMapper objectMapper) {
        this.repo = repo;
        this.objectMapper = objectMapper;
    }

    /** 开票：只有 PAID 收费单可开，且同一收费单只能有一张有效票据。 */
    @Transactional
    public ChargeReceipt issue(UUID chargeId, String actor) {
        String operator = requireActor(actor);
        Charge charge = repo.findCharge(chargeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHARGE_NOT_FOUND));
        if (!Charge.PAID.equals(charge.status())) {
            throw new BusinessException(ErrorCode.CHARGE_RECEIPT_CHARGE_NOT_PAID,
                    "收费单当前状态为 " + charge.status() + "，只有已结算的收费单才能开票");
        }
        if (repo.findActiveReceiptByCharge(chargeId).isPresent()) {
            throw new BusinessException(ErrorCode.CHARGE_RECEIPT_EXISTS);
        }
        ChargeReceipt receipt = new ChargeReceipt(UUID.randomUUID(), repo.nextReceiptNo(),
                charge.id(), charge.visitId(), charge.totalAmount(), charge.paymentMethod(),
                ChargeReceipt.ISSUED, operator, Instant.now(), null, null, null, 0, 0L,
                Instant.now());
        try {
            repo.insertReceipt(receipt);
        } catch (DataIntegrityViolationException e) {
            // 并发开票时部分唯一索引兜底：另一请求已开出有效票据
            throw new BusinessException(ErrorCode.CHARGE_RECEIPT_EXISTS);
        }
        audit(operator, "CHARGE_RECEIPT_ISSUE", receipt.id().toString(), Map.of(
                "receiptNo", receipt.receiptNo(),
                "chargeId", chargeId.toString(),
                "amount", receipt.amount().toPlainString()));
        return view(receipt.id());
    }

    /**
     * 票据所属就诊（G51）。
     *
     * <p>票据是 visit 级财务数据，接口必须先拿到 visitId 再做
     * {@link VisitAccessGuard} 校验；不存在时仍按"票据不存在"抛错，不泄露存在性。
     */
    public UUID visitIdOfReceipt(UUID receiptId) {
        return repo.findReceipt(receiptId)
                .map(ChargeReceipt::visitId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHARGE_RECEIPT_NOT_FOUND));
    }

    /** 收费单所属就诊；开票前先校验归属（G51）。 */
    public UUID visitIdOfCharge(UUID chargeId) {
        return repo.findCharge(chargeId)
                .map(Charge::visitId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHARGE_NOT_FOUND));
    }

    public List<ChargeReceipt> list(UUID visitId, Integer limit) {
        if (visitId == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "查询票据必须指定就诊");
        }
        int size = limit == null || limit <= 0 ? 50 : Math.min(limit, 200);
        return repo.listReceipts(visitId, size);
    }

    public ChargeReceipt detail(UUID id) {
        return view(id);
    }

    /** 重打：不换号，只累加次数（作废票据不可重打）。 */
    @Transactional
    public ChargeReceipt reprint(UUID id, String actor) {
        String operator = requireActor(actor);
        ChargeReceipt receipt = lockReceipt(id);
        requireActive(receipt, "重打");
        if (!repo.countReprint(id, receipt.version())) {
            throw new BusinessException(ErrorCode.CONFLICT, "票据已变化，请刷新后重试");
        }
        audit(operator, "CHARGE_RECEIPT_REPRINT", id.toString(), Map.of(
                "receiptNo", receipt.receiptNo(),
                "reprintCount", receipt.reprintCount() + 1));
        return view(id);
    }

    /** 作废：必须写原因；作废后可为同一收费单换开新票。 */
    @Transactional
    public ChargeReceipt voidReceipt(UUID id, VoidReceiptReq req, String actor) {
        String operator = requireActor(actor);
        String reason = req == null || req.reason() == null || req.reason().isBlank()
                ? null : req.reason().trim();
        if (reason == null) {
            throw new BusinessException(ErrorCode.CHARGE_RECEIPT_VOID_REASON_REQUIRED);
        }
        ChargeReceipt receipt = lockReceipt(id);
        requireActive(receipt, "作废");
        if (!repo.voidReceipt(id, receipt.version(), operator, reason)) {
            throw new BusinessException(ErrorCode.CONFLICT, "票据已变化，请刷新后重试");
        }
        audit(operator, "CHARGE_RECEIPT_VOID", id.toString(), Map.of(
                "receiptNo", receipt.receiptNo(), "reason", reason));
        return view(id);
    }

    // ---------------------------------------------------------------- 内部

    private ChargeReceipt lockReceipt(UUID id) {
        return repo.lockReceipt(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHARGE_RECEIPT_NOT_FOUND));
    }

    private ChargeReceipt view(UUID id) {
        return repo.findReceipt(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHARGE_RECEIPT_NOT_FOUND));
    }

    private static void requireActive(ChargeReceipt receipt, String action) {
        if (!receipt.active()) {
            throw new BusinessException(ErrorCode.CHARGE_RECEIPT_STATUS_INVALID,
                    "票据当前状态为 " + receipt.status() + "，不能" + action);
        }
    }

    private static String requireActor(String actor) {
        if (actor == null || actor.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "缺少操作人，不能执行票据操作");
        }
        return actor.trim();
    }

    private void audit(String actor, String action, String targetId, Map<String, Object> detail) {
        try {
            repo.logChange(actor, action, "CHARGE_RECEIPT", targetId,
                    objectMapper.writeValueAsString(new LinkedHashMap<>(detail)));
        } catch (JacksonException e) {
            repo.logChange(actor, action, "CHARGE_RECEIPT", targetId,
                    "{\"_detail_serialize_failed\":true}");
        }
    }
}
