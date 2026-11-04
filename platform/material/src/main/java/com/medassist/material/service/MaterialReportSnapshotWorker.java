package com.medassist.material.service;

import com.medassist.common.time.BusinessTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 物资日快照定时任务（G18）：默认每天 00:12 采集昨天的出库指标。
 *
 * <p>与药事/临床错开一分钟，避免同一时刻集中扫库；幂等覆盖，失败只记日志。
 */
@Component
@ConditionalOnProperty(prefix = "medassist.report-snapshot", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class MaterialReportSnapshotWorker {

    private static final Logger log = LoggerFactory.getLogger(MaterialReportSnapshotWorker.class);
    private static final String ACTOR = "scheduler";

    private final MaterialReportSnapshotService service;

    public MaterialReportSnapshotWorker(MaterialReportSnapshotService service) {
        this.service = service;
    }

    @Scheduled(cron = "${medassist.report-snapshot.material-cron:0 12 0 * * *}")
    public void captureYesterday() {
        try {
            var result = service.capture(BusinessTime.today().minusDays(1), ACTOR, "SCHEDULED");
            log.info("material.report_snapshot.captured date={} metrics={}",
                    result.date(), result.metrics());
        } catch (RuntimeException e) {
            log.warn("material.report_snapshot.failed reason={}", e.getMessage());
        }
    }
}
