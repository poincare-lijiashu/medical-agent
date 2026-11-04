package com.medassist.clinical.service;

import com.medassist.common.time.BusinessTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 报表投递定时任务（G19）：默认每天 00:30 生成前一日投递并重试失败项。
 *
 * <p>生成按 (订阅, 日期, 报表) 幂等，重试最多 3 次；失败只记日志，管理员可手动补发。
 */
@Component
@ConditionalOnProperty(prefix = "medassist.report-delivery", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class ReportSubscriptionWorker {

    private static final Logger log = LoggerFactory.getLogger(ReportSubscriptionWorker.class);
    private static final String ACTOR = "scheduler";
    private static final int RETRY_BATCH = 50;

    private final ReportSubscriptionService service;

    public ReportSubscriptionWorker(ReportSubscriptionService service) {
        this.service = service;
    }

    @Scheduled(cron = "${medassist.report-delivery.cron:0 30 0 * * *}")
    public void deliverDaily() {
        try {
            int created = service.enqueueDaily(BusinessTime.today().minusDays(1), ACTOR);
            int sent = service.retryPending(RETRY_BATCH);
            log.info("clinical.report_delivery.done enqueued={} sent={}", created, sent);
        } catch (RuntimeException e) {
            log.warn("clinical.report_delivery.failed reason={}", e.getMessage());
        }
    }
}
