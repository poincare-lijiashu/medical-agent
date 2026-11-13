package com.medassist.insurance.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.insurance.config.InsuranceMetrics;
import com.medassist.insurance.domain.InsuranceModel.AllocationBillRow;
import com.medassist.insurance.domain.InsuranceModel.AllocationView;
import com.medassist.insurance.domain.InsuranceModel.PayerAllocationLine;
import com.medassist.insurance.domain.InsuranceModel.PayerConfig;
import com.medassist.insurance.domain.InsuranceModel.SettlementAllocation;
import com.medassist.insurance.domain.InsuranceModel.SettlementBill;
import com.medassist.insurance.repo.InsuranceRepository;
import com.medassist.insurance.web.InsuranceDtos.AllocationItemReq;
import com.medassist.insurance.web.InsuranceDtos.AllocationRefundReq;
import com.medassist.insurance.web.InsuranceDtos.CreatePayerReq;
import com.medassist.insurance.web.InsuranceDtos.UpdatePayerReq;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 支付方配置与结算分摊（计划书 W2.6 / Gate G3）。
 *
 * <p>为什么需要它：一张清单的"个人自付"在真实医院里往往由多方承担
 * （自费 + 商保直付 + 医疗救助 + 单位补助），只记一个 self_pay 总额
 * 无法回答"钱最终由谁出、退费退给谁"，财务也无法按支付方出账。
 *
 * <p>财务底线（写进代码而不是流程文档）：
 * <ul>
 *   <li>分摊合计必须等于清单总额（±0.01 舍入容差），不允许"少分/多分"；</li>
 *   <li>BASIC（医保统筹）合计必须等于清单统筹支付，防止把基金支付拆进自费；</li>
 *   <li>同一清单同一支付方只允许一条应收分摊，重复提交天然幂等；</li>
 *   <li>退费只能追加 REFUND 台账且不得超过原分摊余额，台账禁止改删；</li>
 *   <li>清单作废前必须先把分摊全部冲销（由 {@link InsuranceService} 校验）。</li>
 * </ul>
 */
@Service
public class PayerAllocationService {

    private static final BigDecimal CENT = new BigDecimal("0.01");
    private static final int MAX_BILL_PAGE = 200;
    private static final int MAX_REASON = 256;

    private final InsuranceRepository repo;
    private final ObjectMapper json;
    private final InsuranceMetrics metrics;

    public PayerAllocationService(InsuranceRepository repo, ObjectMapper json,
                                  InsuranceMetrics metrics) {
        this.repo = repo;
        this.json = json;
        this.metrics = metrics;
    }

    // ---------------------------------------------------------------- 支付方字典

    public List<PayerConfig> listPayers(boolean onlyEnabled) {
        return repo.listPayers(onlyEnabled);
    }

    @Transactional
    public PayerConfig createPayer(CreatePayerReq req, String actor) {
        String operator = requireActor(actor);
        String code = normalizeCode(req.code());
        PayerConfig payer = new PayerConfig(code, req.name().trim(), req.payerType(), true,
                req.sortNo() == null ? 100 : req.sortNo(), trimNullable(req.remark()),
                0L, operator, Instant.now(), Instant.now());
        if (!repo.insertPayer(payer)) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "支付方编码 " + code + " 已存在，请直接修改或换一个编码");
        }
        audit(operator, "PAYER_CREATE", code, Map.of("name", payer.name(),
                "type", payer.payerType()));
        metrics.payerAction("create_payer", true);
        return repo.findPayer(code).orElse(payer);
    }

    @Transactional
    public PayerConfig updatePayer(String code, UpdatePayerReq req, String actor) {
        String operator = requireActor(actor);
        String normalized = normalizeCode(code);
        PayerConfig current = repo.findPayer(normalized)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "未找到该支付方"));
        PayerConfig updated = new PayerConfig(current.code(), req.name().trim(), req.payerType(),
                current.enabled(), req.sortNo() == null ? current.sortNo() : req.sortNo(),
                trimNullable(req.remark()), req.version(), operator, Instant.now(),
                current.createdAt());
        if (!repo.updatePayer(updated)) {
            throw new BusinessException(ErrorCode.CONFLICT, "支付方已被他人修改，请刷新后重试");
        }
        audit(operator, "PAYER_UPDATE", normalized, Map.of("name", updated.name(),
                "type", updated.payerType()));
        metrics.payerAction("update_payer", true);
        return repo.findPayer(normalized).orElse(updated);
    }

    /** 启用/停用：停用只影响后续分摊，历史台账保持不变。 */
    @Transactional
    public PayerConfig togglePayer(String code, boolean enabled, long version, String actor) {
        String operator = requireActor(actor);
        String normalized = normalizeCode(code);
        repo.findPayer(normalized)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "未找到该支付方"));
        if (!repo.setPayerEnabled(normalized, enabled, version, operator)) {
            throw new BusinessException(ErrorCode.CONFLICT, "支付方已被他人修改，请刷新后重试");
        }
        audit(operator, enabled ? "PAYER_ENABLE" : "PAYER_DISABLE", normalized, Map.of());
        metrics.payerAction(enabled ? "enable_payer" : "disable_payer", true);
        return repo.findPayer(normalized).orElseThrow();
    }

    // ---------------------------------------------------------------- 分摊

    /** 待分摊（pending=true）或最近已分摊的已确认清单。 */
    public List<AllocationBillRow> listBills(boolean pending, int limit) {
        return repo.listBillsForAllocation(pending, Math.max(1, Math.min(limit, MAX_BILL_PAGE)));
    }

    public AllocationView view(UUID billId) {
        SettlementBill bill = repo.findBillById(billId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INSURANCE_BILL_NOT_FOUND));
        return new AllocationView(bill, repo.listAllocations(billId),
                repo.summarizeAllocations(billId));
    }

    /**
     * 首次分摊：一次写入多方金额。
     *
     * <p>只允许已确认清单；同一清单已存在应收分摊时拒绝（唯一索引兜底），
     * 调整必须先对原分摊发起退费，避免"改历史"。
     */
    @Transactional
    public AllocationView allocate(UUID billId, List<AllocationItemReq> items, String actor) {
        String operator = requireActor(actor);
        if (billId == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "必须指定结算清单");
        }
        if (items == null || items.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "分摊至少需要一个支付方");
        }
        SettlementBill bill = repo.findBillById(billId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INSURANCE_BILL_NOT_FOUND));
        if (!SettlementBill.CONFIRMED.equals(bill.status())) {
            throw new BusinessException(ErrorCode.INSURANCE_BILL_STATUS_INVALID,
                    "只有已确认的结算清单可以分摊，当前：" + bill.status());
        }
        if (!repo.listAllocations(billId).isEmpty()) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "该清单已完成分摊；如需调整请先对原分摊发起退费，再重新分摊");
        }

        Map<String, BigDecimal> amounts = new LinkedHashMap<>();
        Map<String, PayerConfig> payers = new LinkedHashMap<>();
        Set<String> seen = new LinkedHashSet<>();
        for (AllocationItemReq item : items) {
            String code = normalizeCode(item.payerCode());
            if (!seen.add(code)) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "支付方 " + code + " 重复出现，请合并后重试");
            }
            PayerConfig payer = repo.findPayer(code)
                    .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED,
                            "支付方 " + code + " 不存在"));
            if (!payer.enabled()) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "支付方 " + payer.name() + " 已停用，不能用于新分摊");
            }
            BigDecimal amount = scale(item.amount());
            if (amount.compareTo(CENT) < 0) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "支付方 " + payer.name() + " 分摊金额必须大于 0");
            }
            amounts.put(code, amount);
            payers.put(code, payer);
        }

        BigDecimal total = amounts.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.subtract(bill.totalAmount()).abs().compareTo(CENT) > 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "分摊合计 " + total.toPlainString() + " 与清单总额 "
                            + bill.totalAmount().toPlainString() + " 不一致，请核对后再提交");
        }
        BigDecimal basic = amounts.entrySet().stream()
                .filter(e -> payers.get(e.getKey()).basic())
                .map(Map.Entry::getValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal insurance = scale(bill.insuranceAmount());
        if (insurance.compareTo(BigDecimal.ZERO) > 0 && basic.compareTo(BigDecimal.ZERO) == 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "清单统筹支付 " + insurance.toPlainString() + " 必须分摊给 BASIC（医保统筹）支付方");
        }
        if (basic.compareTo(insurance) != 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "医保统筹分摊 " + basic.toPlainString() + " 必须等于清单统筹支付 "
                            + insurance.toPlainString());
        }

        try {
            for (Map.Entry<String, BigDecimal> entry : amounts.entrySet()) {
                PayerConfig payer = payers.get(entry.getKey());
                repo.insertAllocation(new SettlementAllocation(UUID.randomUUID(), bill.id(),
                        bill.visitId(), payer.code(), payer.payerType(),
                        SettlementAllocation.CHARGE, null, entry.getValue(), null, operator,
                        Instant.now()));
            }
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "该清单已被他人分摊，请刷新后重试");
        }

        audit(operator, "ALLOCATION_CHARGE", billId.toString(),
                Map.of("billNo", bill.billNo(), "total", total.toPlainString(),
                        "payers", amounts.keySet()));
        metrics.payerAction("allocate", true);
        return view(billId);
    }

    /** 退费：追加 REFUND 台账，不得超过原分摊余额。 */
    @Transactional
    public SettlementAllocation refund(UUID allocationId, AllocationRefundReq req, String actor) {
        String operator = requireActor(actor);
        SettlementAllocation original = repo.findAllocation(allocationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "未找到该分摊记录"));
        if (!SettlementAllocation.CHARGE.equals(original.kind())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "只有应收分摊可以发起退费，退费记录不能再退");
        }
        BigDecimal amount = scale(req.amount());
        BigDecimal refunded = repo.sumRefunds(allocationId);
        BigDecimal remaining = original.amount().subtract(refunded);
        if (amount.compareTo(remaining) > 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "退费金额 " + amount.toPlainString() + " 超过可退余额 "
                            + remaining.toPlainString());
        }
        String reason = req.reason() == null ? "" : req.reason().trim();
        if (reason.length() < 2) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "退费必须填写原因（至少 2 个字），用于事后追溯");
        }
        SettlementAllocation refund = new SettlementAllocation(UUID.randomUUID(),
                original.billId(), original.visitId(), original.payerCode(),
                original.payerType(), SettlementAllocation.REFUND, original.id(), amount,
                truncate(reason), operator, Instant.now());
        repo.insertAllocation(refund);
        audit(operator, "ALLOCATION_REFUND", original.billId().toString(),
                Map.of("allocationId", allocationId.toString(), "payer", original.payerCode(),
                        "amount", amount.toPlainString(), "reason", refund.reason()));
        metrics.payerAction("refund", true);
        return refund;
    }

    // ---------------------------------------------------------------- 内部

    private static String normalizeCode(String raw) {
        String code = raw == null ? "" : raw.trim().toUpperCase();
        if (code.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "支付方编码不能为空");
        }
        return code;
    }

    private static String requireActor(String actor) {
        if (actor == null || actor.isBlank() || "system".equalsIgnoreCase(actor.trim())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "分摊操作必须记录真实操作人");
        }
        return actor.trim();
    }

    private static BigDecimal scale(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }

    private static String trimNullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String truncate(String value) {
        return value.length() <= MAX_REASON ? value : value.substring(0, MAX_REASON);
    }

    private void audit(String actor, String action, String targetId, Map<String, Object> detail) {
        try {
            repo.logChange(actor, action, "SETTLEMENT_ALLOCATION", targetId,
                    json.writeValueAsString(new LinkedHashMap<>(detail)));
        } catch (JacksonException e) {
            repo.logChange(actor, action, "SETTLEMENT_ALLOCATION", targetId,
                    "{\"_detail_serialize_failed\":true}");
        }
    }
}
