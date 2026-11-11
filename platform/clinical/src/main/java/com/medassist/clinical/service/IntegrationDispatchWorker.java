package com.medassist.clinical.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 出站集成消息投递 worker（第 257 批）。
 *
 * <p>默认每 30 秒扫一批（最多 20 条）。失败不抛出到调度线程：
 * 消息状态与错误都落库，运维可在台账里看到，下一轮继续按退避时间重试。
 */
@Component
public class IntegrationDispatchWorker {

    private static final Logger log = LoggerFactory.getLogger(IntegrationDispatchWorker.class);
    private static final int BATCH = 20;

    private final IntegrationMessageService service;

    public IntegrationDispatchWorker(IntegrationMessageService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${medassist.integration.dispatch-ms:30000}",
            initialDelayString = "${medassist.integration.dispatch-initial-ms:20000}")
    public void dispatch() {
        try {
            int acked = service.dispatchDue(BATCH);
            if (acked > 0) {
                log.info("clinical.integration.dispatch acked={}", acked);
            }
        } catch (RuntimeException e) {
            log.warn("clinical.integration.dispatch_failed err={}", e.getMessage());
        }
    }
}
