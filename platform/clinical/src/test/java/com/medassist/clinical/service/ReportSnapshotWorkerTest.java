package com.medassist.clinical.service;

import com.medassist.clinical.web.ClinicalDtos.SnapshotCaptureView;
import com.medassist.common.time.BusinessTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 定时快照：采集昨天、失败只记日志不抛出。
 */
class ReportSnapshotWorkerTest {

    private ReportSnapshotService service;
    private ReportSnapshotWorker worker;

    @BeforeEach
    void setUp() {
        service = mock(ReportSnapshotService.class);
        worker = new ReportSnapshotWorker(service);
    }

    @Test
    @DisplayName("定时任务采集昨天，来源标记 SCHEDULED")
    void capturesYesterday() {
        var yesterday = BusinessTime.today().minusDays(1);
        when(service.capture(eq(yesterday), eq("scheduler"), eq("SCHEDULED")))
                .thenReturn(new SnapshotCaptureView(yesterday, 10, "SCHEDULED"));

        worker.captureYesterday();

        verify(service).capture(yesterday, "scheduler", "SCHEDULED");
    }

    @Test
    @DisplayName("采集异常不抛给调度器")
    void swallowsException() {
        when(service.capture(eq(BusinessTime.today().minusDays(1)), eq("scheduler"), eq("SCHEDULED")))
                .thenThrow(new IllegalStateException("db down"));

        assertThatCode(() -> worker.captureYesterday()).doesNotThrowAnyException();
    }
}
