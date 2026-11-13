package com.medassist.ops.service;

import com.medassist.common.time.BusinessTime;
import com.medassist.ops.domain.OpsModel.OnCallRotation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 值班自动排班：只补生成 ACTIVE 且已开始的轮换，失败不抛到调度线程。 */
class OnCallSchedulerTest {

    private final OnCallService onCallService = mock(OnCallService.class);
    private final OnCallScheduler scheduler = new OnCallScheduler(onCallService);

    @Test
    @DisplayName("启动补齐：ACTIVE 轮换生成未来 14 天，停用/未开始跳过")
    void generateUpcoming() {
        UUID active = UUID.randomUUID();
        UUID inactive = UUID.randomUUID();
        UUID future = UUID.randomUUID();
        when(onCallService.listRotations()).thenReturn(List.of(
                new OnCallRotation(active, "CLINICAL_ONCALL", "临床值班", "Asia/Shanghai",
                        BusinessTime.today().minusDays(7), 7, List.of("doctor01"),
                        LocalTime.of(8, 0), "ACTIVE", 0),
                new OnCallRotation(inactive, "PHARMACY_ONCALL", "药房值班", "Asia/Shanghai",
                        BusinessTime.today().minusDays(7), 7, List.of("pharm01"),
                        LocalTime.of(8, 0), "INACTIVE", 0),
                new OnCallRotation(future, "TECH_ONCALL", "医技值班", "Asia/Shanghai",
                        BusinessTime.today().plusDays(3), 7, List.of("tech01"),
                        LocalTime.of(8, 0), "ACTIVE", 0)));
        when(onCallService.generate(any(), any(), any(), any(), any(), any()))
                .thenReturn(Map.of("created", 14, "existing", 0));

        scheduler.onReady();

        verify(onCallService).generate(eq(active), any(), any(), eq("system:scheduler"),
                any(), any());
        verify(onCallService, never()).generate(eq(inactive), any(), any(), any(), any(), any());
        verify(onCallService, never()).generate(eq(future), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("单个轮换生成失败不影响其他轮换")
    void failureIsolated() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(onCallService.listRotations()).thenReturn(List.of(
                new OnCallRotation(first, "A_ONCALL", "A", "Asia/Shanghai",
                        BusinessTime.today().minusDays(1), 7, List.of("a"),
                        LocalTime.of(8, 0), "ACTIVE", 0),
                new OnCallRotation(second, "B_ONCALL", "B", "Asia/Shanghai",
                        BusinessTime.today().minusDays(1), 7, List.of("b"),
                        LocalTime.of(8, 0), "ACTIVE", 0)));
        when(onCallService.generate(eq(first), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("boom"));
        when(onCallService.generate(eq(second), any(), any(), any(), any(), any()))
                .thenReturn(Map.of("created", 1, "existing", 0));

        scheduler.onReady();

        verify(onCallService).generate(eq(second), any(), any(), any(), any(), any());
    }
}
