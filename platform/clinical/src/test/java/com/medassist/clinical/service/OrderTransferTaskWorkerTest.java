package com.medassist.clinical.service;

import com.medassist.clinical.domain.ClinicalModel.TransferTask;
import com.medassist.clinical.repo.ClinicalRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 转送补偿 worker：必须"先抢占再处理"（P2-7）。
 *
 * <p>为什么单独测：worker 若退回裸查询，多副本会把 attempts 放大 N 倍并误判 FAILED，
 * 单测不覆盖的话只有等线上出现"下游一直不可用"才会暴露。
 */
class OrderTransferTaskWorkerTest {

    private final ClinicalRepository repo = mock(ClinicalRepository.class);
    private final OrderTransferService transfer = mock(OrderTransferService.class);
    private final OrderTransferTaskWorker worker =
            new OrderTransferTaskWorker(repo, transfer, 30);

    private static TransferTask task(String channel) {
        return new TransferTask(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "CANCEL", channel, "停嘱后作废失败", "doctor01", "PENDING", 0, null, Instant.now());
    }

    @Test
    @DisplayName("每轮先按租约抢占，再逐条重试；租约时长来自配置")
    void drainOnceClaimsWithLeaseThenRetriesEachTask() {
        TransferTask pharmacy = task("PHARMACY");
        TransferTask tech = task("TECH");
        when(repo.claimPendingTransferTasks(anyString(), eq(20), eq(30)))
                .thenReturn(List.of(pharmacy, tech));

        int handled = worker.drainOnce();

        assertThat(handled).isEqualTo(2);
        verify(repo).claimPendingTransferTasks(anyString(), eq(20), eq(30));
        verify(transfer).retryTask(pharmacy.id(), pharmacy.orderId(), pharmacy.visitId(),
                "PHARMACY", pharmacy.reason(), 10);
        verify(transfer).retryTask(tech.id(), tech.orderId(), tech.visitId(),
                "TECH", tech.reason(), 10);
    }

    @Test
    @DisplayName("没有可抢占任务时不做任何下游调用")
    void drainOnceWithoutClaimableTasksDoesNothing() {
        when(repo.claimPendingTransferTasks(anyString(), eq(20), eq(30))).thenReturn(List.of());

        assertThat(worker.drainOnce()).isZero();
        verifyNoInteractions(transfer);
    }
}
