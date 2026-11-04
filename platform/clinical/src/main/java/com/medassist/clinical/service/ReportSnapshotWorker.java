package com.medassist.clinical.service;

import com.medassist.common.time.BusinessTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 运营日快照定时任务（G17）。
 *
 * <p>默认每天 00:10 采集"昨天"的门诊实收与住院周转指标。采集按 (日期, 指标) 幂等覆盖，
 * 因此多副本、重复触发、手动补采都不会产生重复数据；单次失败只记日志，等下一次或人工补采。
 */
@Component
@ConditionalOnProperty(prefix = "medassist.report-snapshot", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class ReportSnapshotWorker {

    private static final Logger log = LoggerFactory.getLogger(ReportSnapshotWorker.class);
    private static final String ACTOR = "scheduler";

    private final ReportSnapshotService service;

    public ReportSnapshotWorker(ReportSnapshotService service) {
        this.service = service;
    }

    @Scheduled(cron = "${medassist.report-snapshot.cron:0 10 0 * * *}")
    public void captureYesterday() {
        try {
            var result = service.capture(BusinessTime.today().minusDays(1), ACTOR, "SCHEDULED");
            log.info("clinical.report_snapshot.captured date={} metrics={}",
                    result.date(), result.metrics());
        } catch (RuntimeException e) {
            // 定时任务不能把异常抛给调度器：记录后等下一天/人工补采
            log.warn("clinical.report_snapshot.failed reason={}", e.getMessage());
        }
    }
}
