package com.medassist.clinical.service;

import com.medassist.common.time.BusinessTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 报表投递定时任务：生成昨天并重试；异常不抛给调度器。
 */
class ReportSubscriptionWorkerTest {

    private ReportSubscriptionService service;
    private ReportSubscriptionWorker worker;

    @BeforeEach
    void setUp() {
        service = mock(ReportSubscriptionService.class);
        worker = new ReportSubscriptionWorker(service);
    }

    @Test
    @DisplayName("定时任务：入队昨天并重试失败项")
    void enqueuesAndRetries() {
        worker.deliverDaily();

        verify(service).enqueueDaily(BusinessTime.today().minusDays(1), "scheduler");
        verify(service).retryPending(anyInt());
    }

    @Test
    @DisplayName("异常不抛给调度器")
    void swallowsException() {
        when(service.enqueueDaily(eq(BusinessTime.today().minusDays(1)), eq("scheduler")))
                .thenThrow(new IllegalStateException("db down"));

        assertThatCode(() -> worker.deliverDaily()).doesNotThrowAnyException();
    }
}
