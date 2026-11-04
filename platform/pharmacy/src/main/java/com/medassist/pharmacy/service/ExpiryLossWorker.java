package com.medassist.pharmacy.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 效期自动报废任务（G28）：默认每天 01:10 扫描到期批次并生成报损草稿。
 *
 * <p>只生成 DRAFT，不扣库存；人工提交后仍需另一位药剂师审批。
 * 异常只记日志，不影响调度器。
 */
@Component
@ConditionalOnProperty(prefix = "medassist.expiry-loss", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class ExpiryLossWorker {

    private static final Logger log = LoggerFactory.getLogger(ExpiryLossWorker.class);
    private static final String ACTOR = "scheduler";

    private final StockLossService stockLossService;

    public ExpiryLossWorker(StockLossService stockLossService) {
        this.stockLossService = stockLossService;
    }

    @Scheduled(cron = "${medassist.expiry-loss.cron:0 10 1 * * *}")
    public void draftExpired() {
        try {
            var result = stockLossService.autoDraftExpired(ACTOR);
            log.info("pharmacy.expiry_loss.done losses={} items={}",
                    result.losses(), result.items());
        } catch (RuntimeException e) {
            log.warn("pharmacy.expiry_loss.failed reason={}", e.getMessage());
        }
    }
}
