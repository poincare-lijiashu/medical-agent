package com.medassist.clinical.service;

import com.medassist.clinical.domain.ClinicalModel.TransferTask;
import com.medassist.clinical.repo.ClinicalRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 转送补偿 worker：把"停嘱/撤销后下游作废失败"的任务重试到收敛。
 *
 * <p>为什么需要它（第六轮 MF-02/MF-03）：处方已发药时下游 409，医疗决定不能因此回滚；
 * 只能把作废登记成任务，等药房退药后由本 worker 自动完成作废。下游按 orderId 幂等，
 * 多副本同时跑只会重复调用、不会重复建单。
 *
 * <p>超过 {@code maxAttempts} 仍失败则置 FAILED，留给运维/药房人工处理（不再无限重试）。
 */
@Component
@ConditionalOnProperty(prefix = "medassist.transfer-task", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class OrderTransferTaskWorker {

    private static final Logger log = LoggerFactory.getLogger(OrderTransferTaskWorker.class);

    private static final int BATCH_SIZE = 20;
    private static final int MAX_ATTEMPTS = 10;

    private final ClinicalRepository repo;
    private final OrderTransferService transfer;

    public OrderTransferTaskWorker(ClinicalRepository repo, OrderTransferService transfer) {
        this.repo = repo;
        this.transfer = transfer;
    }

    @Scheduled(fixedDelayString = "${medassist.transfer-task.interval-ms:60000}",
            initialDelayString = "${medassist.transfer-task.initial-delay-ms:30000}")
    public void scheduledDrain() {
        try {
            int done = drainOnce();
            if (done > 0) {
                log.info("clinical.transfer_task.drained count={}", done);
            }
        } catch (RuntimeException e) {
            // 定时任务不能把异常抛出去（会被调度器吞掉且掩盖真实故障），只记录
            log.warn("clinical.transfer_task.drain_failed err={}", e.getMessage());
        }
    }

    /**
     * 执行一轮补偿。
     *
     * @return 本轮处理（尝试）的任务数
     */
    public int drainOnce() {
        List<TransferTask> tasks = repo.listPendingTransferTasks(BATCH_SIZE);
        int handled = 0;
        for (TransferTask task : tasks) {
            transfer.retryTask(task.id(), task.orderId(), task.visitId(), task.channel(),
                    task.reason(), MAX_ATTEMPTS);
            handled++;
        }
        return handled;
    }
}
