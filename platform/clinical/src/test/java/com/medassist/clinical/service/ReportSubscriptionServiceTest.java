package com.medassist.clinical.service;

import com.medassist.clinical.domain.ClinicalModel.ReportDelivery;
import com.medassist.clinical.domain.ClinicalModel.ReportSnapshot;
import com.medassist.clinical.domain.ClinicalModel.ReportSubscription;
import com.medassist.clinical.repo.ClinicalRepository;
import com.medassist.clinical.web.ReportSubscriptionDtos.SaveSubscriptionReq;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.time.BusinessTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 报表订阅：渠道校验（含 SSRF 白名单）、CAS 冲突、手动投递与定时入队。
 */
class ReportSubscriptionServiceTest {

    private ClinicalRepository repo;
    private ReportSnapshotService snapshotService;
    private ReportSubscriptionService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        repo = mock(ClinicalRepository.class);
        snapshotService = mock(ReportSnapshotService.class);
        service = new ReportSubscriptionService(repo, snapshotService, objectMapper, "", 1000);
        when(repo.listReportSnapshots(any(), any(), any())).thenReturn(List.of(
                new ReportSnapshot(BusinessTime.today().minusDays(1), "OUTPATIENT_PAID_AMOUNT",
                        new BigDecimal("10.00"), null, "SCHEDULED", Instant.now())));
    }

    @Test
    @DisplayName("新建 LOG 订阅：目标地址归零、写入并返回")
    void createsLogSubscription() {
        UUID id = UUID.randomUUID();
        when(repo.findReportSubscription(any())).thenReturn(Optional.of(
                new ReportSubscription(id, "运营日报", "DAILY_OPS", "LOG", null, true, 0,
                        "admin01", Instant.now())));

        var view = service.create(new SaveSubscriptionReq(" 运营日报 ", "log", "https://x/y",
                null, null), "admin01");

        assertThat(view.channel()).isEqualTo("LOG");
        assertThat(view.target()).isNull();
        verify(repo).insertReportSubscription(any());
    }

    @Test
    @DisplayName("WEBHOOK：未配置白名单 → 422；域名不在白名单 → 422；命中 → 通过")
    void validatesWebhookHost() {
        assertThatThrownBy(() -> service.create(new SaveSubscriptionReq("h1", "WEBHOOK",
                "https://hooks.example.com/x", true, null), "admin01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);

        var allow = new ReportSubscriptionService(repo, snapshotService, objectMapper,
                "hooks.example.com", 1000);
        when(repo.findReportSubscription(any())).thenReturn(Optional.of(
                new ReportSubscription(UUID.randomUUID(), "h2", "DAILY_OPS", "WEBHOOK",
                        "https://hooks.example.com/x", true, 0, "admin01", Instant.now())));

        assertThatThrownBy(() -> allow.create(new SaveSubscriptionReq("h3", "WEBHOOK",
                "https://evil.example.com/x", true, null), "admin01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);

        var view = allow.create(new SaveSubscriptionReq("h2", "WEBHOOK",
                "https://hooks.example.com/x", true, null), "admin01");
        assertThat(view.target()).isEqualTo("https://hooks.example.com/x");
    }

    @Test
    @DisplayName("更新：版本冲突 → 409")
    void updateRejectsStaleVersion() {
        when(repo.findReportSubscription(any())).thenReturn(Optional.of(
                new ReportSubscription(UUID.randomUUID(), "运营日报", "DAILY_OPS", "LOG", null,
                        true, 3, "admin01", Instant.now())));
        when(repo.updateReportSubscription(any(), anyString(), anyString(), any(), eq(true), eq(3L)))
                .thenReturn(false);

        assertThatThrownBy(() -> service.update(UUID.randomUUID(),
                new SaveSubscriptionReq("运营日报", "LOG", null, true, 3L), "admin01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    @Test
    @DisplayName("手动投递 LOG：占位后投递成功，记录 SENT")
    void sendsLogDelivery() {
        UUID subId = UUID.randomUUID();
        UUID deliveryId = UUID.randomUUID();
        LocalDate day = BusinessTime.today().minusDays(1);
        var sub = new ReportSubscription(subId, "运营日报", "DAILY_OPS", "LOG", null, true, 0,
                "admin01", Instant.now());
        var pending = new ReportDelivery(deliveryId, subId, "运营日报", "DAILY_OPS", day,
                "PENDING", 0, null, Instant.now(), null);
        var sent = new ReportDelivery(deliveryId, subId, "运营日报", "DAILY_OPS", day,
                "SENT", 1, null, Instant.now(), Instant.now());
        when(repo.findReportSubscription(subId)).thenReturn(Optional.of(sub));
        when(repo.insertReportDeliveryIfAbsent(any(), eq(subId), eq("DAILY_OPS"), eq(day), any()))
                .thenReturn(true);
        when(repo.findReportDelivery(eq(subId), eq("DAILY_OPS"), eq(day)))
                .thenReturn(Optional.of(pending), Optional.of(sent));
        when(repo.reportDeliveryPayload(deliveryId)).thenReturn("{\"report\":\"DAILY_OPS\"}");

        var view = service.send(subId, day, "admin01");

        assertThat(view.status()).isEqualTo("SENT");
        assertThat(view.attempts()).isEqualTo(1);
        verify(repo).markReportDelivery(deliveryId, "SENT", 1, null);
    }

    @Test
    @DisplayName("手动投递：未来日期 → 422")
    void sendRejectsFuture() {
        UUID subId = UUID.randomUUID();
        when(repo.findReportSubscription(subId)).thenReturn(Optional.of(
                new ReportSubscription(subId, "x", "DAILY_OPS", "LOG", null, true, 0,
                        "admin01", Instant.now())));

        assertThatThrownBy(() -> service.send(subId, BusinessTime.today().plusDays(1), "admin01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("定时入队：只为启用订阅建占位，并幂等跳过已存在")
    void enqueueOnlyEnabledSubscriptions() {
        LocalDate day = BusinessTime.today().minusDays(1);
        var enabled = new ReportSubscription(UUID.randomUUID(), "a", "DAILY_OPS", "LOG", null,
                true, 0, "admin01", Instant.now());
        var disabled = new ReportSubscription(UUID.randomUUID(), "b", "DAILY_OPS", "LOG", null,
                false, 0, "admin01", Instant.now());
        when(repo.listReportSubscriptions()).thenReturn(List.of(enabled, disabled));
        when(repo.insertReportDeliveryIfAbsent(any(), eq(enabled.id()), anyString(), eq(day), any()))
                .thenReturn(true);

        int created = service.enqueueDaily(day, "scheduler");

        assertThat(created).isEqualTo(1);
        verify(repo, org.mockito.Mockito.never())
                .insertReportDeliveryIfAbsent(any(), eq(disabled.id()), anyString(), any(), any());
    }

    @Test
    @DisplayName("投递查询：limit 归一化到 1–200")
    void clampsDeliveryLimit() {
        service.listDeliveries(null, 9999);
        verify(repo).listReportDeliveries(isNull(), eq(200));
        service.listDeliveries(" - ", 0);
        verify(repo).listReportDeliveries(eq(" - "), eq(1));
    }
}
