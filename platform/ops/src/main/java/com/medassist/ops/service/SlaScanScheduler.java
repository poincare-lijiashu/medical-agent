package com.medassist.ops.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * SLA 超时扫描。
 *
 * <p>为什么不做成"查询时现算"：超时是需要被<b>通知和统计</b>的事实。
 * 只现算的话，工单解决后所有痕迹都消失，没人能证明"这个月超时率是多少"。
 * 扫描把超时落成事件，且只允许 false→true 一次，重复扫描不会重复记。
 */
@Component
public class SlaScanScheduler {

    private static final Logger log = LoggerFactory.getLogger(SlaScanScheduler.class);

    private final TicketService ticketService;

    public SlaScanScheduler(TicketService ticketService) {
        this.ticketService = ticketService;
    }

    @Scheduled(fixedDelayString = "${medassist.ops.sla-scan-ms:60000}",
            initialDelayString = "${medassist.ops.sla-scan-initial-delay-ms:15000}")
    public void scan() {
        try {
            var summary = ticketService.scanSlaScheduled();
            if (((Number) summary.getOrDefault("responseBreached", 0)).longValue() > 0
                    || ((Number) summary.getOrDefault("resolveBreached", 0)).longValue() > 0) {
                log.warn("ops.sla.scan newResponse={} newResolve={}",
                        summary.get("responseBreached"), summary.get("resolveBreached"));
            }
        } catch (Exception e) {
            // 定时任务不能把异常抛到调度线程导致任务被停掉；下一轮继续
            log.error("ops.sla.scan.failed", e);
        }
    }
}
