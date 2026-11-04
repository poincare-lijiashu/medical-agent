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

    @Test
    @DisplayName("IM 渠道：钉钉/企微/飞书消息体与可读正文")
    void buildsImChannelBodies() {
        LocalDate day = LocalDate.of(2026, 11, 3);
        String raw = """
                {"report":"DAILY_OPS","date":"2026-11-03","metrics":{
                  "OUTPATIENT_PAID_AMOUNT":12.5,
                  "OUTPATIENT_VISIT_COUNT":3,
                  "INPATIENT_ADMITTED_COUNT":4,
                  "INPATIENT_DISCHARGED_COUNT":1,
                  "INPATIENT_IN_HOSPITAL_COUNT":95,
                  "BED_OCCUPANCY_PERCENT":{"value":46.8,"detail":{"usableBeds":203}},
                  "DISPENSE_COUNT":6,
                  "DISPENSE_AMOUNT":88.0,
                  "ISSUE_COUNT":2,
                  "ISSUE_ITEM_QUANTITY":5,
                  "ISSUE_AMOUNT":24000.0
                }}
                """;

        String text = service.buildMessageText(day, raw);
        assertThat(text).contains("运营日报 2026-11-03")
                .contains("入院 4").contains("在院 95").contains("床位使用率 46.8%")
                .contains("发药 6 笔").contains("出库 2 单");

        assertThat(service.buildChannelBody("DINGTALK", day, raw))
                .contains("\"msgtype\":\"markdown\"")
                .contains("\"title\":\"运营日报 2026-11-03\"");
        assertThat(service.buildChannelBody("WECOM", day, raw))
                .contains("\"msgtype\":\"markdown\"")
                .contains("\"content\"");
        assertThat(service.buildChannelBody("FEISHU", day, raw))
                .contains("\"msg_type\":\"text\"")
                .contains("\"content\"");
        assertThat(service.buildChannelBody("WEBHOOK", day, raw)).isEqualTo(raw);
    }

    @Test
    @DisplayName("IM 正文：缺失的域不写 0，避免把未采集误报为业务量为 0")
    void omitsMissingMetricSections() {
        String raw = """
                {"report":"DAILY_OPS","metrics":{
                  "OUTPATIENT_PAID_AMOUNT":0,
                  "INPATIENT_ADMITTED_COUNT":4
                }}
                """;

        String text = service.buildMessageText(LocalDate.of(2026, 11, 3), raw);

        assertThat(text).contains("门诊实收").contains("入院 4")
                .doesNotContain("药事").doesNotContain("物资");
    }

    @Test
    @DisplayName("IM 渠道：命中白名单时可通过校验")
    void acceptsImChannelWithAllowedHost() {
        var allow = new ReportSubscriptionService(repo, snapshotService, objectMapper,
                "qyapi.weixin.qq.com", 1000);
        UUID id = UUID.randomUUID();
        when(repo.findReportSubscription(any())).thenReturn(Optional.of(
                new ReportSubscription(id, "企微日报", "DAILY_OPS", "WECOM",
                        "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=x", true, 0,
                        "admin01", Instant.now())));

        var view = allow.create(new SaveSubscriptionReq("企微日报", "wecom",
                "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=x", true, null), "admin01");

        assertThat(view.channel()).isEqualTo("WECOM");
        assertThat(view.target()).contains("qyapi.weixin.qq.com");
    }
}
