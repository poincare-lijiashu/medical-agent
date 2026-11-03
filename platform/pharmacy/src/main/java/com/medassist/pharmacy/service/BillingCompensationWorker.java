package com.medassist.pharmacy.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 发药计费补偿扫描器（P0-D3）。
 *
 * <p>发药计费/退药冲减在本地事务提交后调用收费域，失败置 {@code CHARGE_FAILED}
 * / {@code REFUND_FAILED}；此前唯一重试入口是人工 {@code POST /billing-retry}，
 * 操作员必须先知道 dispenseId，异常单实际处于无人回收状态、账实长期不一致。
 *
 * <p>本 worker 定时扫 {@code ix_dispense_billing_abnormal} 部分索引批量补偿：
 * <ul>
 *   <li>与人工重试同一条 {@link PharmacyBillingService#retry} 路径，临床侧幂等，重复调用收敛；</li>
 *   <li>间隔、批量、开关可配；{@code pharmacy.billing-compensation-enabled=false} 可关（如演练/单机调试）；</li>
 *   <li>单轮只补一批（默认 50），失败单只告警不重试打转，下一轮再试——避免坏单把扫描循环拖死。</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "pharmacy.billing-compensation-enabled", havingValue = "true", matchIfMissing = true)
public class BillingCompensationWorker {

    private static final Logger log = LoggerFactory.getLogger(BillingCompensationWorker.class);

    private final PharmacyBillingService billing;

    /** 单轮补偿批量：太大占住扫描周期，太小收敛慢。 */
    private final int batchSize;

    public BillingCompensationWorker(
            PharmacyBillingService billing,
            @Value("${pharmacy.billing-compensation-batch:50}") int batchSize) {
        this.billing = billing;
        this.batchSize = Math.max(1, batchSize);
    }

    /** 固定延迟 5 分钟（延迟而非频率：上一轮补完再计时，不叠加）。 */
    @Scheduled(
            initialDelayString = "${pharmacy.billing-compensation-initial-delay-ms:60000}",
            fixedDelayString = "${pharmacy.billing-compensation-interval-ms:300000}")
    public void compensate() {
        try {
            int recovered = billing.retryAbandoned(batchSize, "billing-compensation-worker");
            if (recovered > 0) {
                log.info("pharmacy.billing_compensation_recovered count={}", recovered);
            }
        } catch (RuntimeException e) {
            // 扫描器本身绝不能把调度线程打挂：只告警，下一轮继续
            log.warn("pharmacy.billing_compensation_cycle_failed err={}", e.getClass().getSimpleName());
        }
    }
}
