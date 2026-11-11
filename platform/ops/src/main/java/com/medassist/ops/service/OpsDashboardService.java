package com.medassist.ops.service;

import com.medassist.ops.repo.OpsRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 运营看板：把"现在积压多少、超时多少、处理多快"用同一口径算出来。
 *
 * <p>只统计最近 30 天的首响/解决时长：全院历史平均会被上线初期的异常单永久拉偏，
 * 运营需要看"当前团队的真实水平"。
 */
@Service
public class OpsDashboardService {

    private static final int WINDOW_DAYS = 30;

    private final OpsRepository repo;
    private final Clock clock;

    public OpsDashboardService(OpsRepository repo, Clock clock) {
        this.repo = repo;
        this.clock = clock;
    }

    public Map<String, Object> dashboard() {
        Instant now = clock.instant();
        Instant since = now.minus(Duration.ofDays(WINDOW_DAYS));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("generatedAt", now);

        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (Map<String, Object> row : repo.countByStatus()) {
            byStatus.put(String.valueOf(row.get("status")), asLong(row.get("n")));
        }
        result.put("byStatus", byStatus);

        Map<String, Long> byPriority = new LinkedHashMap<>();
        for (Map<String, Object> row : repo.countOpenByPriority()) {
            byPriority.put(String.valueOf(row.get("priority")), asLong(row.get("n")));
        }
        result.put("openByPriority", byPriority);
        result.put("openByService", repo.countOpenByService());

        long open = repo.countOpen();
        long overdueResponse = repo.countOverdueResponse();
        long overdueResolve = repo.countOverdueResolve();
        result.put("open", open);
        result.put("overdueResponse", overdueResponse);
        result.put("overdueResolve", overdueResolve);

        long created = repo.countCreatedSince(since);
        long breached = repo.countBreachedSince(since);
        result.put("windowDays", WINDOW_DAYS);
        result.put("createdInWindow", created);
        result.put("breachedInWindow", breached);
        result.put("breachRate", created == 0 ? 0.0
                : round(BigDecimal.valueOf(breached * 100.0 / created)));

        result.put("avgFirstResponseMinutes", round(repo.avgFirstResponseMinutes(since)));
        result.put("avgResolveMinutes", round(repo.avgResolveMinutes(since)));
        result.put("avgSatisfaction", round(repo.avgSatisfaction(since)));

        Map<String, Long> incidentsBySeverity = new LinkedHashMap<>();
        for (Map<String, Object> row : repo.countOpenIncidentsBySeverity()) {
            incidentsBySeverity.put(String.valueOf(row.get("severity")), asLong(row.get("n")));
        }
        result.put("openIncidents", repo.countOpenIncidents());
        result.put("openIncidentsBySeverity", incidentsBySeverity);
        result.put("trend", repo.dailyTrend(14));
        return result;
    }

    public List<Map<String, Object>> trend(int days) {
        return repo.dailyTrend(Math.min(Math.max(days, 1), 90));
    }

    private static long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static double round(BigDecimal value) {
        if (value == null) {
            return 0.0;
        }
        return value.setScale(1, RoundingMode.HALF_UP).doubleValue();
    }
}
