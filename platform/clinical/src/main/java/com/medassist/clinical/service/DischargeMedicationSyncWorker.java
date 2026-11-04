package com.medassist.clinical.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 出院带药发药结果同步（G30）。
 *
 * <p>每 5 分钟扫描已提交且已生成处方的单据，向药房查询状态并回写 DISPENSED。
 * 异常只记日志：单条失败不影响其余单据，下一轮会重试（同步本身幂等）。
 */
@Component
@ConditionalOnProperty(prefix = "medassist.discharge-med", name = "sync-enabled",
        havingValue = "true", matchIfMissing = true)
public class DischargeMedicationSyncWorker {

    private static final Logger log = LoggerFactory.getLogger(DischargeMedicationSyncWorker.class);
    private static final int BATCH_LIMIT = 50;

    private final DischargeMedicationService service;

    public DischargeMedicationSyncWorker(DischargeMedicationService service) {
        this.service = service;
    }

    @Scheduled(cron = "${medassist.discharge-med.sync-cron:0 */5 * * * *}")
    public void sync() {
        try {
            int synced = service.syncSubmitted(BATCH_LIMIT);
            if (synced > 0) {
                log.info("clinical.discharge_med_sync.done synced={}", synced);
            }
        } catch (RuntimeException e) {
            log.warn("clinical.discharge_med_sync.failed err={}", e.getMessage());
        }
    }
}
