package com.medassist.insurance.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.security.DataScope;
import com.medassist.common.time.BusinessTime;
import com.medassist.insurance.config.InsuranceMetrics;
import com.medassist.insurance.domain.InsuranceModel.ReconBatch;
import com.medassist.insurance.domain.InsuranceModel.ReconBillRow;
import com.medassist.insurance.domain.InsuranceModel.ReconDiff;
import com.medassist.insurance.repo.InsuranceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 医保结算日结/月结对账（计划书 W2.5 / Gate G3）。
 *
 * <p>为什么需要它：单就诊对账只能回答"这一单为什么对不上"，回答不了
 * "昨天/上月一共多少已确认清单没结算成功、金额差多少、谁在处理"。
 * 本服务把期间内的清单与省局回传放在一起，产出**批次 + 差异工单**：
 *
 * <ul>
 *   <li>期间按就诊业务日期 {@code service_date} 归属（与财务结算日一致）；</li>
 *   <li>只对真差异建单：未结算 / 结算失败 / 金额不一致 / 作废后已结算；
 *       省局未回传金额时只计"不可验证"，不伪造差异；</li>
 *   <li>重算幂等：同周期同范围只有一个批次；人工已处理的差异不会被重算覆盖；</li>
 *   <li>关闭即结论：存在未处理差异不允许关闭；关闭后不可重算（数据库触发器兜底）。</li>
 * </ul>
 *
 * <p>安全边界：对账是**全院财务动作**，不接受院区受限账号（避免拿到部分数据当全量结论）；
 * 差异记录不落患者姓名/证件号。
 */
@Service
public class SettlementReconService {

    /** 单批上限：超过说明期间粒度过粗，要求按月或缩小范围，避免一次加载过多清单。 */
    private static final int MAX_BILLS = 10_000;
    private static final int MAX_DIFF_PAGE = 500;
    private static final BigDecimal TOLERANCE = new BigDecimal("0.01");
    private static final int MAX_DETAIL_CHARS = 200;

    private final InsuranceRepository repo;
    private final ObjectMapper json;
    private final InsuranceMetrics metrics;

    public SettlementReconService(InsuranceRepository repo, ObjectMapper json,
                                  InsuranceMetrics metrics) {
        this.repo = repo;
        this.json = json;
        this.metrics = metrics;
    }

    // ---------------------------------------------------------------- 计算

    /**
     * 生成或重算对账批次。
     *
     * @param periodType DAY / MONTH
     * @param from       期间开始（DAY 时必须等于结束）
     * @param to         期间结束（MONTH 时必须为该月最后一天）
     */
    @Transactional
    public ReconBatch compute(String periodType, String from, String to, String actor,
                              DataScope scope) {
        requireFullScope(scope);
        requireActor(actor);
        String type = normalizePeriodType(periodType);
        LocalDate start = parseDate(from, "periodFrom");
        LocalDate end = parseDate(to, "periodTo");
        validatePeriod(type, start, end);

        long total = repo.countReconBills(start, end);
        if (total > MAX_BILLS) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "期间内清单 " + total + " 张，超过单批上限 " + MAX_BILLS + "；请按月或缩小范围");
        }

        Optional<ReconBatch> existing = repo.findReconBatch(type, start, end);
        if (existing.isPresent() && existing.get().closed()) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "该期间对账已关闭，不能重算；如需修正请新建下一周期并说明差异");
        }

        List<ReconBillRow> rows = repo.listReconBills(start, end, MAX_BILLS);
        Computed computed = evaluate(rows);
        UUID batchId = existing.map(ReconBatch::id).orElseGet(UUID::randomUUID);
        List<ReconDiff> diffs = computed.withBatch(batchId);

        ReconBatch batch;
        if (existing.isEmpty()) {
            batch = new ReconBatch(batchId, type, start, end, ReconBatch.COMPUTED,
                    computed.billCount, computed.settledCount, computed.notSettledCount,
                    computed.failedCount, computed.mismatchCount, computed.voidedAfterSettleCount,
                    computed.unverifiedCount, diffs.size(), computed.localTotal,
                    computed.localInsurance, computed.localSelfPay, computed.counterpartyInsurance,
                    computed.counterpartySelfPay, computed.diffAmount, actor, java.time.Instant.now(),
                    null, null, 0);
            if (!repo.insertReconBatch(batch)) {
                throw new BusinessException(ErrorCode.CONFLICT, "该期间批次已被他人创建，请刷新后重试");
            }
            diffs.forEach(repo::insertReconDiff);
        } else {
            ReconBatch current = existing.get();
            // 先把本批 OPEN 全部置为 AUTO_CLOSED，再由"仍然存在的差异"重新打开：
            // RESOLVED/IGNORED 是人工结论，任何重算都不推翻。
            repo.autoCloseAllOpenDiffs(batchId);
            for (ReconDiff diff : diffs) {
                repo.insertReconDiff(diff);
                repo.refreshOpenReconDiff(diff);
                repo.reopenAutoClosedReconDiff(diff);
            }
            int open = repo.countOpenReconDiffs(batchId);
            batch = new ReconBatch(current.id(), type, start, end, ReconBatch.COMPUTED,
                    computed.billCount, computed.settledCount, computed.notSettledCount,
                    computed.failedCount, computed.mismatchCount, computed.voidedAfterSettleCount,
                    computed.unverifiedCount, open, computed.localTotal, computed.localInsurance,
                    computed.localSelfPay, computed.counterpartyInsurance,
                    computed.counterpartySelfPay, computed.diffAmount, actor,
                    java.time.Instant.now(), null, null, current.version());
            if (!repo.updateReconBatch(batch)) {
                throw new BusinessException(ErrorCode.CONFLICT,
                        "批次已被他人重算或关闭，请刷新后重试");
            }
        }

        repo.logChange(actor, "RECON_COMPUTE", "RECON_BATCH", batchId.toString(),
                detail(Map.of("periodType", type, "from", start.toString(), "to", end.toString(),
                        "bills", computed.billCount, "diffs", diffs.size(),
                        "diffAmount", computed.diffAmount)));
        metrics.reconAction("compute", true);
        metrics.recordOpenDiffs(batch.openDiffCount());
        return repo.findReconBatchById(batchId).orElse(batch);
    }

    /** 计算结果的内存快照（不落库，便于单测直接断言）。 */
    private record Computed(int billCount, int settledCount, int notSettledCount, int failedCount,
                            int mismatchCount, int voidedAfterSettleCount, int unverifiedCount,
                            BigDecimal localTotal, BigDecimal localInsurance,
                            BigDecimal localSelfPay, BigDecimal counterpartyInsurance,
                            BigDecimal counterpartySelfPay, BigDecimal diffAmount,
                            List<ReconDiff> diffs) {

        List<ReconDiff> withBatch(UUID batchId) {
            return diffs.stream()
                    .map(d -> new ReconDiff(d.id(), batchId, d.billId(), d.billNo(), d.visitId(),
                            d.category(), d.localInsurance(), d.localSelfPay(),
                            d.counterpartyInsurance(), d.counterpartySelfPay(), d.diffAmount(),
                            d.detail(), d.status(), null, null, null, d.createdAt()))
                    .toList();
        }
    }

    private Computed evaluate(List<ReconBillRow> rows) {
        int settled = 0;
        int notSettled = 0;
        int failed = 0;
        int mismatch = 0;
        int voidedAfterSettle = 0;
        int unverified = 0;
        BigDecimal localTotal = BigDecimal.ZERO;
        BigDecimal localInsurance = BigDecimal.ZERO;
        BigDecimal localSelfPay = BigDecimal.ZERO;
        BigDecimal cpInsurance = BigDecimal.ZERO;
        BigDecimal cpSelfPay = BigDecimal.ZERO;
        BigDecimal diffAmount = BigDecimal.ZERO;
        List<ReconDiff> diffs = new ArrayList<>();

        for (ReconBillRow row : rows) {
            BigDecimal localIns = nz(row.insuranceAmount());
            BigDecimal localSelf = nz(row.selfPayAmount());
            boolean confirmed = "CONFIRMED".equals(row.billStatus());
            if (confirmed) {
                localTotal = localTotal.add(nz(row.totalAmount()));
                localInsurance = localInsurance.add(localIns);
                localSelfPay = localSelfPay.add(localSelf);
            }

            if (!confirmed) {
                // 只有 VOIDED + SETTLED 会进入查询；作废后省局已出账，必须先冲销
                voidedAfterSettle++;
                settled++;
                BigDecimal amount = localIns.add(localSelf);
                diffAmount = diffAmount.add(amount);
                diffs.add(diff(row, ReconDiff.VOIDED_AFTER_SETTLE, localIns, localSelf,
                        null, null, amount, "清单已作废但省局已结算成功，须走冲销流程"));
                continue;
            }

            if ("SETTLED".equals(row.settleStatus())) {
                settled++;
                BigDecimal[] cp = counterpartyAmounts(row.responsePayload());
                if (cp == null) {
                    unverified++;
                    continue;
                }
                cpInsurance = cpInsurance.add(cp[0] == null ? BigDecimal.ZERO : cp[0]);
                cpSelfPay = cpSelfPay.add(cp[1] == null ? BigDecimal.ZERO : cp[1]);
                BigDecimal deltaFund = cp[0] == null ? BigDecimal.ZERO : cp[0].subtract(localIns).abs();
                BigDecimal deltaSelf = cp[1] == null ? BigDecimal.ZERO : cp[1].subtract(localSelf).abs();
                BigDecimal delta = deltaFund.add(deltaSelf);
                if (delta.compareTo(TOLERANCE) > 0) {
                    mismatch++;
                    diffAmount = diffAmount.add(delta);
                    diffs.add(diff(row, ReconDiff.AMOUNT_MISMATCH, localIns, localSelf,
                            cp[0], cp[1], delta,
                            "省局回传 fund=" + money(cp[0]) + " psn=" + money(cp[1])));
                }
                continue;
            }

            if ("FAILED".equals(row.settleStatus())) {
                failed++;
                diffAmount = diffAmount.add(localIns);
                diffs.add(diff(row, ReconDiff.SETTLE_FAILED, localIns, localSelf, null, null,
                        localIns, truncate("省局结算失败：" + text(row.lastError()))));
            } else {
                notSettled++;
                diffAmount = diffAmount.add(localIns);
                diffs.add(diff(row, ReconDiff.NOT_SETTLED, localIns, localSelf, null, null,
                        localIns, settleStatusText(row.settleStatus())));
            }
        }
        return new Computed(rows.size(), settled, notSettled, failed, mismatch, voidedAfterSettle,
                unverified, scale(localTotal), scale(localInsurance), scale(localSelfPay),
                scale(cpInsurance), scale(cpSelfPay), scale(diffAmount), diffs);
    }

    private static ReconDiff diff(ReconBillRow row, String category, BigDecimal localIns,
                                  BigDecimal localSelf, BigDecimal cpIns, BigDecimal cpSelf,
                                  BigDecimal amount, String detail) {
        return new ReconDiff(UUID.randomUUID(), null, row.billId(), row.billNo(), row.visitId(),
                category, scale(localIns), scale(localSelf),
                cpIns == null ? null : scale(cpIns), cpSelf == null ? null : scale(cpSelf),
                scale(amount), detail, ReconDiff.OPEN, null, null, null,
                java.time.Instant.now());
    }

    // ---------------------------------------------------------------- 差异处理

    /** 标记差异已处理 / 已忽略；说明至少 2 字，写审计。 */
    @Transactional
    public ReconDiff resolveDiff(UUID diffId, String status, String note, String actor) {
        requireActor(actor);
        String normalized = status == null ? "" : status.trim().toUpperCase();
        if (!ReconDiff.RESOLVED.equals(normalized) && !ReconDiff.IGNORED.equals(normalized)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "差异状态只能是 RESOLVED 或 IGNORED");
        }
        String reason = note == null ? "" : note.trim();
        if (reason.length() < 2) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "处理差异必须填写说明（至少 2 个字），用于事后追溯");
        }
        ReconDiff diff = repo.findReconDiff(diffId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "未找到该差异记录"));
        ReconBatch batch = repo.findReconBatchById(diff.batchId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "未找到对账批次"));
        if (batch.closed()) {
            throw new BusinessException(ErrorCode.CONFLICT, "批次已关闭，差异不可再处理");
        }
        if (!repo.resolveReconDiff(diffId, normalized, actor, truncate(reason))) {
            throw new BusinessException(ErrorCode.CONFLICT, "差异已被他人处理，请刷新后重试");
        }
        int open = repo.countOpenReconDiffs(batch.id());
        repo.updateOpenDiffCount(batch.id(), open);
        repo.logChange(actor, "RECON_RESOLVE", "RECON_DIFF", diffId.toString(),
                detail(Map.of("batch", batch.id().toString(), "status", normalized,
                        "note", truncate(reason))));
        metrics.reconAction("resolve", true);
        metrics.recordOpenDiffs(open);
        return repo.findReconDiff(diffId).orElse(diff);
    }

    /** 关闭批次：必须没有 OPEN 差异；关闭后不可再重算/处理。 */
    @Transactional
    public ReconBatch close(UUID batchId, String actor) {
        requireActor(actor);
        ReconBatch batch = repo.findReconBatchById(batchId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "未找到对账批次"));
        if (batch.closed()) {
            throw new BusinessException(ErrorCode.CONFLICT, "批次已关闭");
        }
        int open = repo.countOpenReconDiffs(batchId);
        if (open > 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "仍有 " + open + " 条未处理差异，不能关闭；请处理或标记忽略后再关闭");
        }
        if (!repo.closeReconBatch(batchId, batch.version(), actor)) {
            throw new BusinessException(ErrorCode.CONFLICT, "批次已被他人重算或关闭，请刷新后重试");
        }
        repo.logChange(actor, "RECON_CLOSE", "RECON_BATCH", batchId.toString(),
                detail(Map.of("from", batch.periodFrom().toString(),
                        "to", batch.periodTo().toString(), "bills", batch.billCount())));
        metrics.reconAction("close", true);
        return repo.findReconBatchById(batchId).orElse(batch);
    }

    // ---------------------------------------------------------------- 查询

    public List<ReconBatch> listBatches(int limit) {
        return repo.listReconBatches(Math.max(1, Math.min(limit, 100)));
    }

    public BatchDetail detail(UUID batchId, String diffStatus, int limit) {
        ReconBatch batch = repo.findReconBatchById(batchId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "未找到对账批次"));
        List<ReconDiff> diffs = repo.listReconDiffs(batchId, diffStatus,
                Math.max(1, Math.min(limit, MAX_DIFF_PAGE)));
        return new BatchDetail(batch, diffs, repo.countOpenReconDiffs(batchId));
    }

    /** 批次详情：批次 + 差异（默认最多 200 条）+ 当前 OPEN 数。 */
    public record BatchDetail(ReconBatch batch, List<ReconDiff> diffs, int openDiffCount) {
    }

    // ---------------------------------------------------------------- 内部

    private static void requireFullScope(DataScope scope) {
        if (scope != null && scope.campusFilter() != null && !scope.campusFilter().isEmpty()) {
            throw new BusinessException(ErrorCode.FORBIDDEN,
                    "对账批次必须使用全院视角（院区受限账号不能生成/查看全量对账结论）");
        }
    }

    private static void requireActor(String actor) {
        if (actor == null || actor.isBlank() || "system".equalsIgnoreCase(actor.trim())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "对账操作必须记录真实操作人");
        }
    }

    private static String normalizePeriodType(String raw) {
        String type = raw == null ? "" : raw.trim().toUpperCase();
        if (!ReconBatch.DAY.equals(type) && !ReconBatch.MONTH.equals(type)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "期间类型只能是 DAY 或 MONTH");
        }
        return type;
    }

    private static LocalDate parseDate(String raw, String field) {
        try {
            return LocalDate.parse(raw == null ? "" : raw.trim());
        } catch (DateTimeParseException | NullPointerException e) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, field + " 需为 yyyy-MM-dd");
        }
    }

    private static void validatePeriod(String type, LocalDate start, LocalDate end) {
        if (end.isBefore(start)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "结束日期不能早于开始日期");
        }
        if (ReconBatch.DAY.equals(type) && !start.equals(end)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "日结对账的开始与结束必须是同一天");
        }
        if (ReconBatch.MONTH.equals(type)) {
            if (start.getDayOfMonth() != 1 || !end.equals(start.withDayOfMonth(start.lengthOfMonth()))) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "月结对账必须覆盖完整自然月（开始为 1 号，结束为月末）");
            }
        }
        if (start.isAfter(BusinessTime.today())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "不能对未来期间做对账");
        }
    }

    /**
     * 从省局响应报文提取"基金支付 / 个人支付"。
     *
     * <p>只认已知字段；缺失返回 {@code null}（调用方按"不可验证"处理）——
     * 不同统筹区回执字段差异很大，猜字段会把"没回传"误判成"金额为 0"。
     */
    private BigDecimal[] counterpartyAmounts(String payload) {
        if (payload == null || payload.isBlank()) {
            return null;
        }
        JsonNode root;
        try {
            root = json.readTree(payload);
        } catch (JacksonException e) {
            return null;
        }
        JsonNode output = root.path("output");
        BigDecimal fund = decimalOrNull(firstPresent(output, root, "fund_pay_sumamt"));
        BigDecimal self = decimalOrNull(firstPresent(output, root, "psn_pay"));
        if (fund == null && self == null) {
            return null;
        }
        return new BigDecimal[]{fund, self};
    }

    private static JsonNode firstPresent(JsonNode output, JsonNode root, String field) {
        JsonNode node = output.path(field);
        if (!node.isMissingNode() && !node.isNull()) {
            return node;
        }
        node = root.path(field);
        return node.isMissingNode() || node.isNull() ? null : node;
    }

    private static BigDecimal decimalOrNull(JsonNode node) {
        if (node == null) {
            return null;
        }
        try {
            if (node.isNumber()) {
                return node.decimalValue();
            }
            String text = node.asText("").trim();
            return text.isEmpty() ? null : new BigDecimal(text);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String settleStatusText(String status) {
        if (status == null || status.isBlank()) {
            return "尚未提交医保结算";
        }
        return switch (status) {
            case "PENDING", "DELIVERING" -> "结算在途（已提交省局，尚未回执）";
            default -> "未结算（状态：" + status + "）";
        };
    }

    private static String money(BigDecimal value) {
        return value == null ? "未回传" : value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static BigDecimal scale(BigDecimal value) {
        return nz(value).setScale(2, RoundingMode.HALF_UP);
    }

    private static String truncate(String value) {
        String text = value == null ? "" : value.trim();
        return text.length() <= MAX_DETAIL_CHARS ? text : text.substring(0, MAX_DETAIL_CHARS);
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    private String detail(Map<String, ?> value) {
        try {
            return json.writeValueAsString(value);
        } catch (JacksonException e) {
            return "{}";
        }
    }
}
