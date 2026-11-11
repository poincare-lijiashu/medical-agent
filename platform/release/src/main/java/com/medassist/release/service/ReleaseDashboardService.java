package com.medassist.release.service;

import com.medassist.release.repo.ReleaseRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 发布看板：版本状态、未关闭补丁、部署成功率与冻结窗口。
 */
@Service
public class ReleaseDashboardService {

    private static final int WINDOW_DAYS = 30;

    private final ReleaseRepository repo;
    private final Clock clock;

    public ReleaseDashboardService(ReleaseRepository repo, Clock clock) {
        this.repo = repo;
        this.clock = clock;
    }

    public Map<String, Object> dashboard() {
        Instant now = clock.instant();
        Instant since = now.minus(Duration.ofDays(WINDOW_DAYS));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("generatedAt", now);

        Map<String, Long> versions = new LinkedHashMap<>();
        for (Map<String, Object> row : repo.countVersionsByStatus()) {
            versions.put(String.valueOf(row.get("status")), asLong(row.get("n")));
        }
        result.put("versionsByStatus", versions);

        Map<String, Long> patches = new LinkedHashMap<>();
        for (Map<String, Object> row : repo.countOpenPatchesBySeverity()) {
            patches.put(String.valueOf(row.get("severity")), asLong(row.get("n")));
        }
        result.put("openPatchesBySeverity", patches);

        result.put("trainsPlanned", repo.countTrains("PLANNED"));
        result.put("trainsFrozen", repo.countTrains("FROZEN"));
        result.put("trainsReleasedInWindow", repo.countTrains("RELEASED"));
        long deployments = repo.countDeploymentsSince(since);
        long failed = repo.countFailedDeploymentsSince(since);
        result.put("windowDays", WINDOW_DAYS);
        result.put("deploymentsInWindow", deployments);
        result.put("failedDeploymentsInWindow", failed);
        result.put("deploymentSuccessRate", deployments == 0 ? 100.0
                : BigDecimal.valueOf((deployments - failed) * 100.0 / deployments)
                    .setScale(1, RoundingMode.HALF_UP).doubleValue());
        result.put("activeFreezes", repo.countActiveFreezes(now));
        return result;
    }

    private static long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }
}
