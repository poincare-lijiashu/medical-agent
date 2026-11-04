package com.medassist.clinical.service;

import com.medassist.common.time.BusinessTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalTime;

/**
 * 报表投递定时任务（G19 / G25）：每分钟检查一次。
 *
 * <p>发送时段由每份订阅的 {@code send_time} 决定：到点（或应用晚启动时已过点）才生成
 * 前一日投递占位；生成按 (订阅, 日期, 报表) 幂等，重启不会重复投递。
 * 失败重试最多 3 次；异常只记日志，管理员可手动补发。
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

    @Scheduled(cron = "${medassist.report-delivery.cron:0 * * * * *}")
    public void deliverDaily() {
        try {
            LocalTime now = LocalTime.now(BusinessTime.zone());
            int created = service.enqueueDue(BusinessTime.today().minusDays(1), now, ACTOR);
            int sent = service.retryPending(RETRY_BATCH);
            log.info("clinical.report_delivery.done enqueued={} sent={}", created, sent);
        } catch (RuntimeException e) {
            log.warn("clinical.report_delivery.failed reason={}", e.getMessage());
        }
    }
}
