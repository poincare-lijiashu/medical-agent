package com.medassist.ops.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.ops.domain.SloModel.Dashboard;
import com.medassist.ops.domain.SloModel.Detail;
import com.medassist.ops.domain.SloModel.Evaluation;
import com.medassist.ops.domain.SloModel.Slo;
import com.medassist.ops.domain.SloModel.Snapshot;
import com.medassist.ops.repo.OpsRepository;
import com.medassist.ops.repo.SloRepository;
import com.medassist.ops.web.OpsDtos;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * SLO 与错误预算（§300）。
 *
 * <p>口径：
 * <ul>
 *   <li>达标率 = good / total；错误预算 = total × (100 - target) / 100；</li>
 *   <li>剩余预算 = 预算 - bad；燃烧率 = bad / 预算（>1 表示已超出窗口预算）；</li>
 *   <li>BREACHED 优先于 AT_RISK；剩余预算 <25% 为 AT_RISK；无快照为 NO_DATA。</li>
 * </ul>
 *
 * <p>快照按幂等键去重；重试同一结果返回原记录，内容不同则拒绝改写。
 */
@Service
public class SloService {

    private final SloRepository repo;
    private final OpsRepository opsRepository;
    private final SlaService slaService;
    private final Clock clock;

    public SloService(SloRepository repo, OpsRepository opsRepository, SlaService slaService,
                      Clock clock) {
        this.repo = repo;
        this.opsRepository = opsRepository;
        this.slaService = slaService;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ SLO

    public List<Detail> list(boolean onlyActive) {
        List<Detail> result = new ArrayList<>();
        for (Slo slo : repo.listSlos(onlyActive)) {
            result.add(new Detail(slo, evaluate(slo), List.of()));
        }
        return result;
    }

    public Detail detail(UUID id) {
        Slo slo = require(id);
        return new Detail(slo, evaluate(slo), repo.listSnapshots(id, 100));
    }

    @Transactional
    public Detail upsert(OpsDtos.SloUpsertReq req, String actor, String clientIp,
                         String traceId) {
        requireActor(actor);
        validate(req);
        slaService.requireService(req.serviceCode());
        Instant now = clock.instant();
        Slo slo = new Slo(UUID.randomUUID(), req.code().trim(), req.name().trim(),
                req.serviceCode().trim(), req.indicator(), req.targetPercent(),
                req.windowDays() == null ? 30 : req.windowDays(), req.latencyThresholdMs(),
                req.owner().trim(), blankToNull(req.description()),
                req.status() == null ? "ACTIVE" : req.status(), 0, actor, now, now);
        repo.upsertSlo(slo);
        Slo saved = repo.findSloByCode(req.code()).orElseThrow();
        audit(actor, "SLO_UPSERT", saved.code(),
                Map.of("service", saved.serviceCode(), "indicator", saved.indicator(),
                        "target", saved.targetPercent(), "windowDays", saved.windowDays()),
                clientIp, traceId);
        return detail(saved.id());
    }

    public Detail changeStatus(UUID id, String status, String actor,
                               String clientIp, String traceId) {
        requireActor(actor);
        Slo slo = require(id);
        if (status.equals(slo.status())) {
            return detail(id);
        }
        if (!repo.updateStatus(id, status)) {
            throw new BusinessException(ErrorCode.OPS_SLO_NOT_FOUND);
        }
        audit(actor, "SLO_STATUS", slo.code(), Map.of("status", status), clientIp, traceId);
        return detail(id);
    }

    private static void validate(OpsDtos.SloUpsertReq req) {
        BigDecimal target = req.targetPercent();
        if (target == null || target.signum() <= 0
                || target.compareTo(BigDecimal.valueOf(100)) >= 0) {
            throw new BusinessException(ErrorCode.OPS_SLO_INVALID, "目标百分比必须在 0 与 100 之间");
        }
        if ("LATENCY".equals(req.indicator()) && req.latencyThresholdMs() == null) {
            throw new BusinessException(ErrorCode.OPS_SLO_INVALID,
                    "延迟类 SLO 必须提供延迟阈值（毫秒）");
        }
        if (!"LATENCY".equals(req.indicator()) && req.latencyThresholdMs() != null) {
            throw new BusinessException(ErrorCode.OPS_SLO_INVALID,
                    "只有延迟类 SLO 可以设置延迟阈值");
        }
    }

    // ------------------------------------------------------------------ 快照

    @Transactional
    public Snapshot recordSnapshot(UUID sloId, OpsDtos.SloSnapshotReq req, String actor,
                                   String clientIp, String traceId) {
        requireActor(actor);
        Slo slo = require(sloId);
        if (!"ACTIVE".equals(slo.status())) {
            throw new BusinessException(ErrorCode.OPS_SLO_NOT_FOUND);
        }
        validateSnapshot(req);
        Optional<Snapshot> existing = repo.findSnapshotByIdempotency(req.idempotencyKey());
        if (existing.isPresent()) {
            Snapshot snapshot = existing.get();
            boolean same = snapshot.sloId().equals(sloId)
                    && snapshot.totalEvents() == req.totalEvents()
                    && snapshot.goodEvents() == req.goodEvents()
                    && snapshot.windowStart().equals(req.windowStart())
                    && snapshot.windowEnd().equals(req.windowEnd());
            if (same) {
                return snapshot;
            }
            throw new BusinessException(ErrorCode.OPS_SLO_SNAPSHOT_CONFLICT,
                    "幂等键 " + req.idempotencyKey() + " 已被另一条快照使用");
        }
        Snapshot snapshot = new Snapshot(UUID.randomUUID(), sloId, req.windowStart(),
                req.windowEnd(), req.totalEvents(), req.goodEvents(), req.source(),
                req.idempotencyKey(), blankToNull(req.note()), actor, clock.instant());
        repo.insertSnapshot(snapshot);
        audit(actor, "SLO_SNAPSHOT", slo.code(),
                Map.of("idempotencyKey", req.idempotencyKey(), "total", req.totalEvents(),
                        "good", req.goodEvents(), "source", req.source()), clientIp, traceId);
        return snapshot;
    }

    private void validateSnapshot(OpsDtos.SloSnapshotReq req) {
        if (!req.windowEnd().isAfter(req.windowStart())) {
            throw new BusinessException(ErrorCode.OPS_SLO_SNAPSHOT_INVALID,
                    "窗口结束时间必须晚于开始时间");
        }
        if (req.goodEvents() > req.totalEvents()) {
            throw new BusinessException(ErrorCode.OPS_SLO_SNAPSHOT_INVALID,
                    "good 事件数不能大于 total 事件数");
        }
        if (req.totalEvents() <= 0) {
            throw new BusinessException(ErrorCode.OPS_SLO_SNAPSHOT_INVALID,
                    "total 事件数必须大于 0（无流量的窗口请勿上报）");
        }
        if (req.windowEnd().isAfter(clock.instant().plus(Duration.ofMinutes(5)))) {
            throw new BusinessException(ErrorCode.OPS_SLO_SNAPSHOT_INVALID,
                    "窗口结束时间不能晚于当前时间 5 分钟以上");
        }
    }

    // ------------------------------------------------------------------ 评估

    public Evaluation evaluate(Slo slo) {
        Instant now = clock.instant();
        Instant since = now.minus(Duration.ofDays(Math.max(slo.windowDays(), 1)));
        Map<String, Object> agg = repo.aggregate(slo.id(), since);
        long total = longOf(agg.get("total"));
        long good = longOf(agg.get("good"));
        long samples = longOf(agg.get("samples"));
        Instant start = instantOf(agg.get("window_start"));
        Instant end = instantOf(agg.get("window_end"));
        if (samples == 0 || total <= 0) {
            return new Evaluation("NO_DATA", total, good, Math.max(total - good, 0),
                    BigDecimal.ZERO.setScale(4), BigDecimal.ZERO.setScale(2),
                    BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2),
                    BigDecimal.ZERO.setScale(4), start == null ? since : start,
                    end == null ? now : end, (int) samples);
        }
        long bad = total - good;
        BigDecimal target = slo.targetPercent();
        BigDecimal ratio = BigDecimal.valueOf(good).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP);
        BigDecimal budget = BigDecimal.valueOf(total)
                .multiply(BigDecimal.valueOf(100).subtract(target))
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        BigDecimal remaining = budget.subtract(BigDecimal.valueOf(bad));
        BigDecimal remainingPct = budget.signum() == 0 ? BigDecimal.ZERO.setScale(2)
                : remaining.multiply(BigDecimal.valueOf(100))
                        .divide(budget, 2, RoundingMode.HALF_UP);
        BigDecimal burnRate = budget.signum() == 0 ? BigDecimal.ZERO.setScale(4)
                : BigDecimal.valueOf(bad).divide(budget, 4, RoundingMode.HALF_UP);
        String status;
        if (ratio.compareTo(target) < 0) {
            status = "BREACHED";
        } else if (remainingPct.compareTo(BigDecimal.valueOf(25)) < 0) {
            status = "AT_RISK";
        } else {
            status = "HEALTHY";
        }
        return new Evaluation(status, total, good, bad, ratio, budget, remaining, remainingPct,
                burnRate, start == null ? since : start, end == null ? now : end, (int) samples);
    }

    public Dashboard dashboard() {
        List<Slo> active = repo.listSlos(true);
        long healthy = 0;
        long atRisk = 0;
        long breached = 0;
        long noData = 0;
        long exhausted = 0;
        BigDecimal sum = BigDecimal.ZERO;
        int counted = 0;
        for (Slo slo : active) {
            Evaluation evaluation = evaluate(slo);
            switch (evaluation.status()) {
                case "HEALTHY" -> healthy++;
                case "AT_RISK" -> atRisk++;
                case "BREACHED" -> breached++;
                default -> noData++;
            }
            if (!"NO_DATA".equals(evaluation.status())) {
                sum = sum.add(evaluation.ratioPercent());
                counted++;
                if (evaluation.budgetRemaining().signum() < 0) {
                    exhausted++;
                }
            }
        }
        BigDecimal avg = counted == 0 ? BigDecimal.ZERO.setScale(2)
                : sum.divide(BigDecimal.valueOf(counted), 2, RoundingMode.HALF_UP);
        return new Dashboard(clock.instant(), active.size(), healthy, atRisk, breached, noData,
                exhausted, avg);
    }

    // ------------------------------------------------------------------ 内部

    private Slo require(UUID id) {
        return repo.findSlo(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.OPS_SLO_NOT_FOUND));
    }

    private void audit(String actor, String action, String targetRef, Map<String, Object> detail,
                       String clientIp, String traceId) {
        opsRepository.audit(actor, action, targetRef, detail, clientIp, traceId);
    }

    private static void requireActor(String actor) {
        if (actor == null || actor.isBlank()) {
            throw new BusinessException(ErrorCode.OPS_ACTOR_REQUIRED);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static long longOf(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return value == null ? 0 : Long.parseLong(String.valueOf(value));
    }

    private static Instant instantOf(Object value) {
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant();
        }
        if (value instanceof Instant instant) {
            return instant;
        }
        return null;
    }

    /** 保留给前端展示的服务目录（避免前端再拼一次目录接口）。 */
    public List<Map<String, Object>> services() {
        return repo.serviceCatalog();
    }
}
