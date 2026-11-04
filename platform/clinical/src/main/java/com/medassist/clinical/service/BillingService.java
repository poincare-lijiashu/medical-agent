package com.medassist.clinical.service;

import com.medassist.clinical.domain.ClinicalModel.Admission;
import com.medassist.clinical.domain.ClinicalModel.Charge;
import com.medassist.clinical.domain.ClinicalModel.DailyPaidTotal;
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
import com.medassist.clinical.web.ClinicalDtos.DailyReversalResult;
import com.medassist.clinical.web.ClinicalDtos.OutpatientDailyView;
import com.medassist.clinical.web.ClinicalDtos.OutpatientTrendPoint;
import com.medassist.clinical.web.ClinicalDtos.OutpatientTrendView;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
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
        // 口径必须与 settle 一致（P1-8）：结算按 amount - 已冲减(applied) 入账，
        // 预览若按 amount 全额相加，退过药的医嘱会把"要收多少"显示得偏高。
        BigDecimal pendingAmount = pending.stream()
                .map(o -> {
                    BigDecimal applied = repo.sumAppliedOrderRefund(o.id());
                    BigDecimal net = o.amount().subtract(applied == null ? BigDecimal.ZERO : applied);
                    return net.signum() < 0 ? BigDecimal.ZERO : net;
                })
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
        // 退药冲减（B3）：未结算前已退的药必须从医嘱金额里扣掉，否则会把退回的药再收一次钱。
        // 全额退回的医嘱在退药时已作废，这里只保留净额 > 0 的行。
        List<OrderNet> billable = new ArrayList<>();
        for (VisitOrder o : pending) {
            BigDecimal net = o.amount().subtract(repo.sumAppliedOrderRefund(o.id()))
                    .setScale(2, RoundingMode.HALF_UP);
            if (net.signum() > 0) {
                billable.add(new OrderNet(o, net));
            }
        }
        if (charge == null) {
            Charge latest = repo.findLatestCharge(visitId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.CHARGE_NOT_FOUND));
            if (billable.isEmpty()) {
                audit(actor, "CHARGE_SETTLE_IDEMPOTENT", visitId.toString(),
                        Map.of("visitNo", visit.visitNo(), "chargeNo", latest.chargeNo()));
                return toView(latest);
            }
            // 中途结算后产生的新费用：续开一张收费单
            charge = newCharge(visit);
        }

        BigDecimal addAmount = BigDecimal.ZERO;
        List<UUID> chargedOrderIds = new ArrayList<>();
        for (OrderNet n : billable) {
            VisitOrder o = n.order();
            boolean inserted = repo.insertChargeItem(UUID.randomUUID(), charge.id(), o.id(),
                    "ORDER:" + o.id(), o.itemCode(), o.itemName(), categoryOf(o), n.amount());
            if (inserted) {
                addAmount = addAmount.add(n.amount());
            }
            chargedOrderIds.add(o.id());
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
        int charged = repo.markOrdersCharged(chargedOrderIds);
        if (charged != chargedOrderIds.size()) {
            // 有医嘱在结算过程中被改成非 PLACED（例如并发撤销）：整笔回滚，不能"钱收了医嘱还挂着"
            throw new BusinessException(ErrorCode.CONFLICT,
                    "有 " + (chargedOrderIds.size() - charged) + " 条医嘱状态已变化，本次结算已撤销，请刷新后重试");
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
            // 退药冲减（P0-1）：长嘱退药先记 applied，必须在这里消费掉，否则
            // applied 永远不减、退药只回库存不退钱。当日退满则整行跳过不计费。
            BigDecimal net = o.amount().subtract(repo.sumAppliedOrderRefund(o.id(), billDate))
                    .setScale(2, RoundingMode.HALF_UP);
            if (net.signum() <= 0) {
                skipped.add(o.orderNo());
                continue;
            }
            String sourceKey = "DAILY:" + o.id() + ":" + billDate;
            if (repo.insertChargeItem(UUID.randomUUID(), charge.id(), o.id(), sourceKey,
                    o.itemCode(), o.itemName(), categoryOf(o), net)) {
                addAmount = addAmount.add(net);
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

    // ---------------------------------------------------------------- 按日计费红冲（P1-1）

    /**
     * 按日计费红冲：把某长嘱某日已计入的金额以负向明细冲回。
     *
     * <p>为什么必须有这个入口：护理把当日改动为"未执行/患者拒绝"时，钱已经计过了；
     * 没有红冲就只能"多收"，医保飞检直接扣分。红冲金额一律取**当日实际入账额**
     * （已扣退药 applied 的净额），不采信客户端传值。
     *
     * <p>幂等键 {@code DAILY_RETURN:{orderId}:{date}}：重复调用返回 {@code idempotent=true}，
     * 不会二次冲减。已结算（PAID）的收费单不允许直接红冲，必须走退费流程——
     * 否则会出现"已支付但总额被改小"的账实不一致。
     */
    @Transactional
    public DailyReversalResult reverseDaily(UUID visitId, UUID orderId, LocalDate serviceDate,
                                            String reason, boolean confirmed, String actor) {
        String why = reason == null ? "" : reason.trim();
        if (!confirmed) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "红冲需要二次确认（confirmed=true）");
        }
        if (why.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "必须填写红冲原因");
        }
        LocalDate date = serviceDate == null ? BusinessTime.today() : serviceDate;
        if (date.isAfter(BusinessTime.today())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "不能对未来日期红冲");
        }
        // 与 dailyBill / settle 同锁：避免"红冲读金额的同时按日计费又入账"
        Visit visit = repo.lockVisit(visitId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VISIT_NOT_FOUND));
        if (!visit.active()) {
            throw new BusinessException(ErrorCode.VISIT_STATUS_INVALID, "就诊已结束，不能红冲");
        }
        VisitOrder order = repo.findOrder(visitId, orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_ITEM_NOT_FOUND,
                        "医嘱不存在或不属于该就诊"));
        if (!order.longTerm()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "只有长嘱的按日计费可以红冲");
        }
        if (repo.existsDailyReturn(orderId, date)) {
            return new DailyReversalResult(orderId, date, BigDecimal.ZERO, true, "该日已红冲");
        }
        ClinicalRepository.DailyCharge daily = repo.findDailyCharge(orderId, date)
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "该医嘱 " + date + " 没有按日计费记录，无需红冲"));
        Charge charge = repo.lockCharge(daily.chargeId())
                .orElseThrow(() -> new BusinessException(ErrorCode.CHARGE_NOT_FOUND));
        if (!Charge.UNPAID.equals(charge.status())) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "该日费用已结算（" + charge.status() + "），不能直接红冲；请走退费流程");
        }
        boolean inserted = repo.insertChargeItem(UUID.randomUUID(), charge.id(), orderId,
                "DAILY_RETURN:" + orderId + ":" + date, daily.itemCode(),
                "按日计费红冲 " + daily.itemName(), daily.category(), daily.amount().negate());
        if (!inserted) {
            // 并发下另一请求已插入红字明细：按幂等返回，不再重复减总额
            return new DailyReversalResult(orderId, date, BigDecimal.ZERO, true, "该日已红冲");
        }
        if (!repo.addChargeAmount(charge.id(), daily.amount().negate(), charge.version())) {
            throw new BusinessException(ErrorCode.CONFLICT, "收费单状态已变化，请刷新后重试");
        }
        audit(actor, "DAILY_CHARGE_REVERSE", visitId.toString(), Map.of(
                "orderNo", order.orderNo(), "serviceDate", date.toString(),
                "reversedAmount", daily.amount().toPlainString(), "reason", why));
        return new DailyReversalResult(orderId, date, daily.amount(), false, why);
    }

    // ---------------------------------------------------------------- 门诊日结（G1）

    /**
     * 门诊日结（分账）：当日已收笔数/金额，按科室与支付方式拆开。
     *
     * <p>口径（与财务对账一致）：按**收款时刻**归日，不按就诊日期——
     * 昨天挂号、今天补缴的费用属于今天的现金流水。退费额单独列出、不与实收相抵，
     * 否则"收了 100 又退 100"会显示为 0，掩盖当班发生过的资金动作。
     *
     * <p>只统计 {@code OUTPATIENT} 就诊：住院费用走预交金与中途结算，不进门诊日报。
     */
    public OutpatientDailyView outpatientDaily(LocalDate date) {
        LocalDate day = date == null ? BusinessTime.today() : date;
        if (day.isAfter(BusinessTime.today())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "不能对未来日期做日结");
        }
        var from = day.atStartOfDay(BusinessTime.zone()).toOffsetDateTime();
        var to = day.plusDays(1).atStartOfDay(BusinessTime.zone()).toOffsetDateTime();

        var summary = repo.outpatientPaidSummary(from, to);
        List<OutpatientDailyView.DeptTotal> byDept = repo.outpatientPaidByDept(from, to).stream()
                .map(d -> new OutpatientDailyView.DeptTotal(d.deptId(), d.deptCode(), d.deptName(),
                        d.visitCount(), d.amount()))
                .toList();
        List<OutpatientDailyView.MethodTotal> byMethod = repo.outpatientPaidByMethod(from, to).stream()
                .map(m -> new OutpatientDailyView.MethodTotal(m.paymentMethod(), m.chargeCount(),
                        m.amount()))
                .toList();
        return new OutpatientDailyView(day, summary.visitCount(), summary.paidChargeCount(),
                summary.totalPaid(), summary.refundedAmount(), byDept, byMethod);
    }

    /**
     * 门诊实收趋势（G13）：按收款日聚合，缺日补 0，并给出上一等长周期的合计用于环比。
     *
     * <p>默认最近 14 天（含今天）；单次最多 92 天，避免一次查询拖垮库。
     * 未来日期直接拒绝——报表不能出现"还没发生"的现金流。
     */
    public OutpatientTrendView outpatientTrend(LocalDate from, LocalDate to) {
        LocalDate today = BusinessTime.today();
        LocalDate end = to == null ? today : to;
        LocalDate start = from == null ? end.minusDays(13) : from;
        if (end.isAfter(today)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "不能查询未来日期的趋势");
        }
        if (start.isAfter(end)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "开始日期不能晚于结束日期");
        }
        long days = ChronoUnit.DAYS.between(start, end) + 1;
        if (days > 92) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "单次趋势查询最多 92 天，请缩短区间后重试");
        }
        var zone = BusinessTime.zone();
        OffsetDateTime fromTs = start.atStartOfDay(zone).toOffsetDateTime();
        OffsetDateTime toTs = end.plusDays(1).atStartOfDay(zone).toOffsetDateTime();
        Map<LocalDate, DailyPaidTotal> byDay = repo.outpatientPaidTrend(fromTs, toTs, zone.getId())
                .stream()
                .collect(Collectors.toMap(DailyPaidTotal::day, Function.identity(),
                        (a, b) -> a));

        List<OutpatientTrendPoint> points = new ArrayList<>((int) days);
        long totalVisits = 0;
        int totalPaidCharges = 0;
        BigDecimal totalPaid = BigDecimal.ZERO;
        BigDecimal totalRefunded = BigDecimal.ZERO;
        for (LocalDate day = start; !day.isAfter(end); day = day.plusDays(1)) {
            DailyPaidTotal row = byDay.get(day);
            int visits = row == null ? 0 : row.visitCount();
            int charges = row == null ? 0 : row.paidChargeCount();
            BigDecimal paid = row == null ? BigDecimal.ZERO : row.totalPaid();
            BigDecimal refunded = row == null ? BigDecimal.ZERO : row.refundedAmount();
            points.add(new OutpatientTrendPoint(day, visits, charges, paid, refunded));
            totalVisits += visits;
            totalPaidCharges += charges;
            totalPaid = totalPaid.add(paid);
            totalRefunded = totalRefunded.add(refunded);
        }

        // 环比：紧邻当前区间之前、等长的上一周期
        LocalDate prevEnd = start.minusDays(1);
        LocalDate prevStart = prevEnd.minusDays(days - 1);
        var prev = repo.outpatientPaidSummary(
                prevStart.atStartOfDay(zone).toOffsetDateTime(),
                start.atStartOfDay(zone).toOffsetDateTime());
        return new OutpatientTrendView(start, end, points, totalVisits, totalPaidCharges,
                totalPaid, totalRefunded, prev.visitCount(), prev.totalPaid());
    }

    // ---------------------------------------------------------------- 内部

    /** 结算行：医嘱 + 扣除退药冲减后的净额（&gt; 0 才入账）。 */
    private record OrderNet(VisitOrder order, BigDecimal amount) {
    }

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
