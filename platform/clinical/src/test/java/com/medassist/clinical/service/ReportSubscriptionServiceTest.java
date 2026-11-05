package com.medassist.clinical.service;

import com.medassist.clinical.domain.ClinicalModel.ReportDelivery;
import com.medassist.clinical.domain.ClinicalModel.ReportRecipientGroup;
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
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
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
    private ReportEmailSender emailSender;
    private ReportSubscriptionService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String EMAIL_PAYLOAD = """
            {"report":"DAILY_OPS","date":"2026-11-03","metrics":{
              "OUTPATIENT_PAID_AMOUNT":{"value":10.00,"detail":{"visits":2}},
              "BED_OCCUPANCY_PERCENT":46.8}}
            """;

    @BeforeEach
    void setUp() {
        repo = mock(ClinicalRepository.class);
        snapshotService = mock(ReportSnapshotService.class);
        emailSender = mock(ReportEmailSender.class);
        service = new ReportSubscriptionService(repo, snapshotService, emailSender, objectMapper, "", 1000);
        when(repo.listReportSnapshots(any(), any(), any())).thenReturn(List.of(
                new ReportSnapshot(BusinessTime.today().minusDays(1), "OUTPATIENT_PAID_AMOUNT",
                        new BigDecimal("10.00"), null, "SCHEDULED", Instant.now())));
        // G77：默认由本实例拿到发送权；抢占失败的场景单独用例覆盖
        when(repo.claimReportDelivery(any(), anyInt())).thenReturn(true);
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

        var allow = new ReportSubscriptionService(repo, snapshotService, emailSender, objectMapper,
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
        when(repo.updateReportSubscription(any(), anyString(), anyString(), any(),
                any(LocalTime.class), any(), eq(true), eq(3L))).thenReturn(false);

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
    @DisplayName("手动投递：抢占失败 → 409，且不外发（G77）")
    void sendSkipsWhenClaimLost() {
        UUID subId = UUID.randomUUID();
        UUID deliveryId = UUID.randomUUID();
        LocalDate day = BusinessTime.today().minusDays(1);
        when(repo.findReportSubscription(subId)).thenReturn(Optional.of(
                new ReportSubscription(subId, "邮件日报", "DAILY_OPS", "EMAIL",
                        "ops@example.com", true, 0, "admin01", Instant.now())));
        when(repo.findReportDelivery(eq(subId), eq("DAILY_OPS"), eq(day))).thenReturn(Optional.of(
                new ReportDelivery(deliveryId, subId, "邮件日报", "DAILY_OPS", day,
                        "PENDING", 0, null, Instant.now(), null)));
        when(repo.claimReportDelivery(deliveryId, 0)).thenReturn(false);

        assertThatThrownBy(() -> service.send(subId, day, "admin01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CONFLICT);

        verify(emailSender, org.mockito.Mockito.never())
                .send(any(), any(), any(), any(), any());
        verify(repo, org.mockito.Mockito.never())
                .markReportDelivery(any(), any(), anyInt(), any());
    }

    @Test
    @DisplayName("定时重试：抢不到发送权的投递直接跳过，不重复外发（G77）")
    void retrySkipsWhenClaimLost() {
        UUID subId = UUID.randomUUID();
        UUID deliveryId = UUID.randomUUID();
        LocalDate day = BusinessTime.today().minusDays(1);
        when(repo.listRetryableReportDeliveries(anyInt(), anyInt())).thenReturn(List.of(
                new ReportDelivery(deliveryId, subId, "邮件日报", "DAILY_OPS", day,
                        "FAILED", 1, "smtp down", Instant.now(), null)));
        when(repo.findReportSubscription(subId)).thenReturn(Optional.of(
                new ReportSubscription(subId, "邮件日报", "DAILY_OPS", "EMAIL",
                        "ops@example.com", true, 0, "admin01", Instant.now())));
        when(repo.claimReportDelivery(deliveryId, 1)).thenReturn(false);

        int done = service.retryPending(50);

        assertThat(done).isZero();
        verify(emailSender, org.mockito.Mockito.never())
                .send(any(), any(), any(), any(), any());
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

        int created = service.enqueueDue(day, LocalTime.of(23, 59), "scheduler");

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
        var allow = new ReportSubscriptionService(repo, snapshotService, emailSender, objectMapper,
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

    @Test
    @DisplayName("EMAIL：未配置 SMTP → 503；配置后可创建并规范化收件人")
    void createsEmailSubscription() {
        when(emailSender.configured()).thenReturn(false);
        assertThatThrownBy(() -> service.create(new SaveSubscriptionReq("邮件日报", "EMAIL",
                "ops@example.com", true, null), "admin01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.UPSTREAM_UNAVAILABLE);

        when(emailSender.configured()).thenReturn(true);
        var emailService = new ReportSubscriptionService(repo, snapshotService, emailSender,
                objectMapper, "", 1000);
        when(repo.findReportSubscription(any())).thenReturn(Optional.of(
                new ReportSubscription(UUID.randomUUID(), "邮件日报", "DAILY_OPS", "EMAIL",
                        "ops@example.com,director@example.com", true, 0, "admin01", Instant.now())));

        var view = emailService.create(new SaveSubscriptionReq("邮件日报", "email",
                " ops@example.com ; director@example.com ", true, null), "admin01");

        assertThat(view.channel()).isEqualTo("EMAIL");
        assertThat(view.target()).isEqualTo("ops@example.com,director@example.com");
    }

    @Test
    @DisplayName("EMAIL：收件人非法或超过 10 个 → 422")
    void rejectsInvalidEmailTargets() {
        when(emailSender.configured()).thenReturn(true);
        var emailService = new ReportSubscriptionService(repo, snapshotService, emailSender,
                objectMapper, "", 1000);

        assertThatThrownBy(() -> emailService.create(new SaveSubscriptionReq("bad", "EMAIL",
                "not-an-email", true, null), "admin01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);

        StringBuilder many = new StringBuilder();
        for (int i = 0; i < 11; i++) {
            many.append("u").append(i).append("@example.com,");
        }
        assertThatThrownBy(() -> emailService.create(new SaveSubscriptionReq("many", "EMAIL",
                many.toString(), true, null), "admin01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("邮件正文与 CSV：中文指标名、明细保留、CSV 带 BOM")
    void buildsEmailContent() {
        LocalDate day = LocalDate.of(2026, 11, 3);

        String html = service.buildEmailHtml(day, EMAIL_PAYLOAD);
        String csv = new String(service.buildEmailCsv(day, EMAIL_PAYLOAD), StandardCharsets.UTF_8);

        assertThat(html).contains("运营日报 2026-11-03")
                .contains("门诊实收（元）").contains("床位使用率（%）")
                .contains("46.8").contains("visits");
        assertThat(csv).startsWith("\uFEFF")
                .contains("日期,指标编码,指标名称,数值,明细")
                .contains("门诊实收（元）").contains("46.8");
    }

    @Test
    @DisplayName("EMAIL 投递成功：调用 SMTP 并落 SENT")
    void deliversByEmail() {
        when(emailSender.configured()).thenReturn(true);
        UUID subId = UUID.randomUUID();
        UUID deliveryId = UUID.randomUUID();
        LocalDate day = LocalDate.of(2026, 11, 3);
        when(repo.findReportSubscription(subId)).thenReturn(Optional.of(
                new ReportSubscription(subId, "邮件日报", "DAILY_OPS", "EMAIL",
                        "ops@example.com", true, 0, "admin01", Instant.now())));
        when(repo.reportDeliveryPayload(any())).thenReturn(EMAIL_PAYLOAD);
        when(repo.findReportDelivery(any(), any(), any())).thenReturn(
                Optional.of(new ReportDelivery(deliveryId, subId, "邮件日报", "DAILY_OPS", day,
                        "PENDING", 0, null, Instant.now(), null)),
                Optional.of(new ReportDelivery(deliveryId, subId, "邮件日报", "DAILY_OPS", day,
                        "SENT", 1, null, Instant.now(), Instant.now())));

        var view = service.send(subId, day, "admin01");

        assertThat(view.status()).isEqualTo("SENT");
        verify(emailSender).send(eq("ops@example.com"), contains("2026-11-03"),
                contains("门诊实收"), eq("daily-ops-2026-11-03.csv"), any(byte[].class));
        verify(repo).markReportDelivery(eq(deliveryId), eq("SENT"), eq(1), isNull());
    }

    @Test
    @DisplayName("EMAIL 分组 + 发送时段：允许只选分组")
    void createsEmailSubscriptionWithGroupAndSendTime() {
        when(emailSender.configured()).thenReturn(true);
        UUID groupId = UUID.randomUUID();
        when(repo.findReportRecipientGroup(groupId)).thenReturn(Optional.of(
                new ReportRecipientGroup(groupId, "DEPT_OPS", "运营组", null, true, 0,
                        "admin01", Instant.now())));
        when(repo.findReportSubscription(any())).thenReturn(Optional.of(
                new ReportSubscription(UUID.randomUUID(), "分组邮件", "DAILY_OPS", "EMAIL", null,
                        LocalTime.of(9, 15), groupId, true, 0, "admin01", Instant.now())));

        var view = service.create(new SaveSubscriptionReq("分组邮件", "EMAIL", null, "09:15",
                groupId, true, null), "admin01");

        assertThat(view.sendTime()).isEqualTo("09:15");
        assertThat(view.recipientGroupId()).isEqualTo(groupId);
        assertThat(view.target()).isNull();
        verify(repo).insertReportSubscription(any());
    }

    @Test
    @DisplayName("EMAIL 无收件人/分组 → 422；非 EMAIL 绑定分组 → 422；非法时段 → 422")
    void validatesGroupAndSendTime() {
        when(emailSender.configured()).thenReturn(true);

        assertThatThrownBy(() -> service.create(new SaveSubscriptionReq("空邮件", "EMAIL", null,
                "09:15", null, true, null), "admin01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);

        assertThatThrownBy(() -> service.create(new SaveSubscriptionReq("webhook", "WEBHOOK",
                "https://hooks.example.com/x", "09:15", UUID.randomUUID(), true, null), "admin01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);

        assertThatThrownBy(() -> service.create(new SaveSubscriptionReq("坏时段", "LOG", null,
                "9:5", null, true, null), "admin01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("定时入队：只处理发送时段已到的启用订阅")
    void enqueueDueHonorsSendTime() {
        LocalDate day = BusinessTime.today().minusDays(1);
        var due = new ReportSubscription(UUID.randomUUID(), "早班", "DAILY_OPS", "LOG", null,
                LocalTime.of(9, 0), null, true, 0, "admin01", Instant.now());
        var future = new ReportSubscription(UUID.randomUUID(), "晚班", "DAILY_OPS", "LOG", null,
                LocalTime.of(10, 0), null, true, 0, "admin01", Instant.now());
        when(repo.listReportSubscriptions()).thenReturn(List.of(due, future));
        when(repo.insertReportDeliveryIfAbsent(any(), eq(due.id()), anyString(), eq(day), any()))
                .thenReturn(true);

        int created = service.enqueueDue(day, LocalTime.of(9, 30), "scheduler");

        assertThat(created).isEqualTo(1);
        verify(repo, org.mockito.Mockito.never())
                .insertReportDeliveryIfAbsent(any(), eq(future.id()), anyString(), any(), any());
    }

    @Test
    @DisplayName("EMAIL 投递：直接收件人与分组成员合并去重")
    void mergesGroupMembersWhenDelivering() {
        when(emailSender.configured()).thenReturn(true);
        UUID subId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        UUID deliveryId = UUID.randomUUID();
        LocalDate day = LocalDate.of(2026, 11, 3);
        when(repo.findReportSubscription(subId)).thenReturn(Optional.of(
                new ReportSubscription(subId, "分组邮件", "DAILY_OPS", "EMAIL",
                        "ops@example.com,director@example.com", LocalTime.of(9, 15), groupId,
                        true, 0, "admin01", Instant.now())));
        when(repo.listEnabledReportRecipientEmails(groupId))
                .thenReturn(List.of("ops@example.com", "board@example.com"));
        when(repo.reportDeliveryPayload(any())).thenReturn(EMAIL_PAYLOAD);
        when(repo.findReportDelivery(any(), any(), any())).thenReturn(
                Optional.of(new ReportDelivery(deliveryId, subId, "分组邮件", "DAILY_OPS", day,
                        "PENDING", 0, null, Instant.now(), null)),
                Optional.of(new ReportDelivery(deliveryId, subId, "分组邮件", "DAILY_OPS", day,
                        "SENT", 1, null, Instant.now(), Instant.now())));

        var view = service.send(subId, day, "admin01");

        assertThat(view.status()).isEqualTo("SENT");
        verify(emailSender).send(
                eq("ops@example.com,director@example.com,board@example.com"),
                contains("2026-11-03"), contains("门诊实收"),
                eq("daily-ops-2026-11-03.csv"), any(byte[].class));
    }
}
