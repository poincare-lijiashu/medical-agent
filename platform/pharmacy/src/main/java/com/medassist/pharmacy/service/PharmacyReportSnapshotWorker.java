package com.medassist.pharmacy.service;

import com.medassist.common.time.BusinessTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 药事日快照定时任务（G18）：默认每天 00:10 采集昨天的发药指标。
 *
 * <p>幂等覆盖；失败只记日志，等下一次或管理员手动补采。
 */
@Component
@ConditionalOnProperty(prefix = "medassist.report-snapshot", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class PharmacyReportSnapshotWorker {

    private static final Logger log = LoggerFactory.getLogger(PharmacyReportSnapshotWorker.class);
    private static final String ACTOR = "scheduler";

    private final PharmacyReportSnapshotService service;

    public PharmacyReportSnapshotWorker(PharmacyReportSnapshotService service) {
        this.service = service;
    }

    @Scheduled(cron = "${medassist.report-snapshot.cron:0 10 0 * * *}")
    public void captureYesterday() {
        try {
            var result = service.capture(BusinessTime.today().minusDays(1), ACTOR, "SCHEDULED");
            log.info("pharmacy.report_snapshot.captured date={} metrics={}",
                    result.date(), result.metrics());
        } catch (RuntimeException e) {
            log.warn("pharmacy.report_snapshot.failed reason={}", e.getMessage());
        }
    }
}
