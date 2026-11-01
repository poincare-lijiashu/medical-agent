package com.medassist.clinical.service;

import com.medassist.clinical.domain.ClinicalModel.Admission;
import com.medassist.clinical.domain.ClinicalModel.Charge;
import com.medassist.clinical.domain.ClinicalModel.Deposit;
import com.medassist.clinical.domain.ClinicalModel.NursingExecution;
import com.medassist.clinical.domain.ClinicalModel.Visit;
import com.medassist.clinical.domain.ClinicalModel.VisitOrder;
import com.medassist.clinical.repo.ClinicalRepository;
import com.medassist.common.time.BusinessTime;
import com.medassist.clinical.web.ClinicalDtos.BillingPreview;
import com.medassist.clinical.web.ClinicalDtos.ChargeItemView;
import com.medassist.clinical.web.ClinicalDtos.ChargeView;
import com.medassist.clinical.web.ClinicalDtos.DailyBillResult;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 计费与收费。
 *
 * <p>幂等设计（这是收费系统最容易出事故的地方）：
 * <ul>
 *   <li>结算前先 {@code SELECT ... FOR UPDATE} 锁就诊，同一就诊的并发结算串行化；</li>
 *   <li>没有待计内容时直接返回最近一张收费单（重复提交收费请求不会多收一次）；</li>
 *   <li>费用明细 {@code source_key} 有唯一约束，即使锁失效也不会为同一医嘱生成第二条明细；</li>
 *   <li>金额用增量累加（{@code total_amount = total_amount + ?}），不覆盖写，避免并发丢更新。</li>
 * </ul>
 *
 * <p>住院中途结算：结算会把当前收费单置为 PAID；后续产生的费用在下次计费/收费时
 * <b>自动续开新单</b>（数据库层保证每个就诊最多一张 UNPAID 收费单），出院时要求不存在
 * 有金额的未结单。
 */
@Service
public class BillingService {

    private final ClinicalRepository repo;
    private final ObjectMapper objectMapper;

    public BillingService(ClinicalRepository repo, ObjectMapper objectMapper) {
        this.repo = repo;
        this.objectMapper = objectMapper;
    }

    /** 收费前预览：累计已计金额（全部收费单）、待计临时医嘱、当前收费单。 */
    public BillingPreview preview(UUID visitId) {
        Visit visit = repo.findVisit(visitId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VISIT_NOT_FOUND));
        Charge charge = currentCharge(visit)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHARGE_NOT_FOUND,
                        "该就诊没有收费单：" + visit.visitNo()));
        List<VisitOrder> pending = repo.listOrders(visitId, VisitOrder.PLACED).stream()
                .filter(o -> !o.longTerm())
                .toList();
        BigDecimal pendingAmount = pending.stream()
                .map(VisitOrder::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        // 口径与费用看板一致：已计 = 该就诊全部有效收费单之和（中途结算后可能多张；作废单不计）
        BigDecimal billedTotal = repo.listCharges(visitId).stream()
                .filter(c -> !Charge.REFUNDED.equals(c.status()))
                .map(Charge::totalAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new BillingPreview(billedTotal, pendingAmount, pending.size(), toView(charge));
    }

    /**
     * 结算：把待计临时医嘱并入当前收费单并置为已收费。
     *
     * <p>没有待计内容时返回最近一张收费单（HTTP 200），调用方可安全重试。
     *
     * <p>并发要点：
     * <ul>
     *   <li>先锁 visit（与 dailyBill/discharge 串行），再锁未结收费单；</li>
     *   <li>待计医嘱用 {@code FOR UPDATE} 读出，避免"结算中该医嘱被并发撤销却仍被收费"；</li>
     *   <li>预交金抵扣时必须先锁 admission，与退费串行，否则余额可被做成负数；</li>
     *   <li>医嘱置为已计费的条数必须与入账条数一致，否则回滚。</li>
     * </ul>
     */
    @Transactional
    public ChargeView settle(UUID visitId, String paymentMethod, String actor) {
        Visit visit = repo.lockVisit(visitId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VISIT_NOT_FOUND));
        if (Visit.CANCELLED.equals(visit.status())) {
            throw new BusinessException(ErrorCode.VISIT_STATUS_INVALID, "该就诊已取消，不能收费");
        }

        // 长嘱按日计费（dailyBill），结算只处理临时医嘱，避免与日报重复计费
        List<VisitOrder> pending = repo.lockPlacedTempOrders(visitId);

        // 转送失败的药品/检查医嘱不能直接结算：先收钱后补单会形成"已收费、无单据"，
        // 且结算后 transfer-retry 会以"已计费"拒绝，永远无法自愈（第六轮 MF-02）
        for (VisitOrder o : pending) {
            if (VisitOrder.TRANSFER_FAILED.equals(o.transferStatus())
                    && (o.drug() || "EXAM".equals(o.orderType()))) {
                throw new BusinessException(ErrorCode.ORDER_TRANSFER_INCOMPLETE,
                        "医嘱 " + o.orderNo() + "（" + o.itemName() + "）的下游单据未生成，"
                                + "请先重试转送或由下游补开后再结算");
            }
        }

        Charge charge = repo.lockOpenCharge(visitId).orElse(null);
        if (charge == null) {
            Charge latest = repo.findLatestCharge(visitId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.CHARGE_NOT_FOUND));
            if (pending.isEmpty()) {
                audit(actor, "CHARGE_SETTLE_IDEMPOTENT", visitId.toString(),
                        Map.of("visitNo", visit.visitNo(), "chargeNo", latest.chargeNo()));
                return toView(latest);
            }
            // 中途结算后产生的新费用：续开一张收费单
            charge = newCharge(visit);
        }

        BigDecimal addAmount = BigDecimal.ZERO;
        for (VisitOrder o : pending) {
            boolean inserted = repo.insertChargeItem(UUID.randomUUID(), charge.id(), o.id(),
                    "ORDER:" + o.id(), o.itemCode(), o.itemName(), categoryOf(o), o.amount());
            if (inserted) {
                addAmount = addAmount.add(o.amount());
            }
        }

        BigDecimal payable = charge.totalAmount().add(addAmount);
        String method = paymentMethod.trim().toUpperCase();
        if ("DEPOSIT".equals(method)) {
            // 预交金抵扣：仅住院；必须先锁 admission，与退费在同一把锁上串行
            Admission admission = repo.lockActiveAdmissionByVisit(visitId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED,
                            "只有住院就诊可以使用预交金结算"));
            BigDecimal balance = repo.depositBalance(admission.id());
            if (balance.compareTo(payable) < 0) {
                throw new BusinessException(ErrorCode.CONFLICT,
                        "预交金余额不足（余额 " + balance + "，应结 " + payable + "），请先补交");
            }
            if (payable.signum() > 0) {
                repo.insertDeposit(UUID.randomUUID(), admission.id(), payable.negate(),
                        Deposit.SETTLE, "DEPOSIT", actor, "结算 " + charge.chargeNo());
            }
        }

        if (!repo.markChargePaid(charge.id(), addAmount, method, charge.version())) {
            // 版本冲突说明另一个窗口刚结算/退费；交给调用方重试，避免"以为失败其实已扣费"
            throw new BusinessException(ErrorCode.CONFLICT, "收费单状态已变化，请刷新后重试");
        }
        int charged = repo.markOrdersCharged(pending.stream().map(VisitOrder::id).toList());
        if (charged != pending.size()) {
            // 有医嘱在结算过程中被改成非 PLACED（例如并发撤销）：整笔回滚，不能"钱收了医嘱还挂着"
            throw new BusinessException(ErrorCode.CONFLICT,
                    "有 " + (pending.size() - charged) + " 条医嘱状态已变化，本次结算已撤销，请刷新后重试");
        }

        audit(actor, "CHARGE_SETTLE", visitId.toString(), Map.of(
                "visitNo", visit.visitNo(), "chargeNo", charge.chargeNo(),
                "orderCount", pending.size(), "addedAmount", addAmount,
                "paymentMethod", paymentMethod.trim()));
        return toView(repo.findCharge(charge.id()).orElseThrow());
    }

    /**
     * 住院长嘱按日计费：同一长嘱同一天只计一次（source_key 唯一兜底），可安全重跑。
     *
     * <p>只计费"当日有效"的长嘱（start_date ≤ date ≤ stop_date），停嘱当日仍计费；
     * 当日护理执行记录为 REFUSED / OMITTED 的医嘱不计费（执行与计费对账）。
     */
    @Transactional
    public DailyBillResult dailyBill(UUID visitId, LocalDate date, String actor) {
        LocalDate billDate = date == null ? BusinessTime.today() : date;
        Visit visit = repo.lockVisit(visitId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VISIT_NOT_FOUND));
        if (!"INPATIENT".equals(visit.visitType())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "只有住院就诊支持按日计费");
        }
        if (!visit.active()) {
            throw new BusinessException(ErrorCode.VISIT_STATUS_INVALID, "就诊已结束，不能计费");
        }
        if (billDate.isAfter(BusinessTime.today())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "不能对未来日期按日计费");
        }

        Map<UUID, NursingExecution> executions = repo.listNursingExecutions(visitId, billDate).stream()
                .collect(Collectors.toMap(NursingExecution::orderId, Function.identity(), (a, b) -> a));

        List<VisitOrder> billable = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (VisitOrder o : repo.lockBillableLongOrders(visitId, billDate)) {
            if (!o.billableOn(billDate)) {
                continue;
            }
            NursingExecution exec = executions.get(o.id());
            if (exec != null && !exec.executed()) {
                skipped.add(o.orderNo());
                continue;
            }
            billable.add(o);
        }

        Charge charge = repo.lockOpenCharge(visitId).orElse(null);
        if (charge == null) {
            if (billable.isEmpty()) {
                BigDecimal total = repo.findLatestCharge(visitId)
                        .map(Charge::totalAmount).orElse(BigDecimal.ZERO);
                return new DailyBillResult(billDate, 0, BigDecimal.ZERO, total, skipped);
            }
            // 中途结算后继续住院：续开收费单
            charge = newCharge(visit);
        }

        BigDecimal addAmount = BigDecimal.ZERO;
        int billed = 0;
        for (VisitOrder o : billable) {
            String sourceKey = "DAILY:" + o.id() + ":" + billDate;
            if (repo.insertChargeItem(UUID.randomUUID(), charge.id(), o.id(), sourceKey,
                    o.itemCode(), o.itemName(), categoryOf(o), o.amount())) {
                addAmount = addAmount.add(o.amount());
                billed++;
            }
        }
        if (addAmount.signum() > 0
                && !repo.addChargeAmount(charge.id(), addAmount, charge.version())) {
            throw new BusinessException(ErrorCode.CONFLICT, "收费单状态已变化，请刷新后重试");
        }
        Charge latest = repo.findCharge(charge.id()).orElseThrow();

        if (billed > 0 || !skipped.isEmpty()) {
            audit(actor, "DAILY_BILL", visitId.toString(), Map.of(
                    "visitNo", visit.visitNo(), "date", billDate.toString(),
                    "orderCount", billed, "billedAmount", addAmount,
                    "skippedOrderNos", skipped, "chargeTotal", latest.totalAmount()));
        }
        return new DailyBillResult(billDate, billed, addAmount, latest.totalAmount(), skipped);
    }

    /** 最近一张收费单（含明细），兼容"中途结算后存在多张单"。 */
    public ChargeView chargeOf(UUID visitId) {
        Visit visit = repo.findVisit(visitId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VISIT_NOT_FOUND));
        Charge charge = currentCharge(visit)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHARGE_NOT_FOUND));
        return toView(charge);
    }

    // ---------------------------------------------------------------- 内部

    /** 当前收费单：优先未结单，否则最近一张。 */
    private java.util.Optional<Charge> currentCharge(Visit visit) {
        return repo.findOpenCharge(visit.id()).or(() -> repo.findLatestCharge(visit.id()));
    }

    private Charge newCharge(Visit visit) {
        Charge charge = new Charge(UUID.randomUUID(), repo.nextChargeNo(), visit.id(),
                BigDecimal.ZERO, Charge.UNPAID, null, null, 0L);
        repo.insertCharge(charge);
        return charge;
    }

    private String categoryOf(VisitOrder o) {
        return switch (o.orderType()) {
            case "DRUG" -> "DRUG";
            case "TREATMENT" -> "TREATMENT";
            case "EXAM" -> "EXAM";
            default -> "MATERIAL";
        };
    }

    private ChargeView toView(Charge c) {
        List<ChargeItemView> items = repo.listChargeItems(c.id()).stream()
                .map(a -> new ChargeItemView((UUID) a[0], (String) a[1], (String) a[2],
                        (String) a[3], (BigDecimal) a[4]))
                .toList();
        return new ChargeView(c.id(), c.chargeNo(), c.visitId(), c.totalAmount(), c.status(),
                c.paymentMethod(), c.paidAt(), items);
    }

    private void audit(String actor, String action, String targetId, Map<String, Object> detail) {
        try {
            repo.logChange(actor, action, "VISIT", targetId,
                    objectMapper.writeValueAsString(new LinkedHashMap<>(detail)));
        } catch (JacksonException e) {
            repo.logChange(actor, action, "VISIT", targetId, "{\"_detail_serialize_failed\":true}");
        }
    }
}
