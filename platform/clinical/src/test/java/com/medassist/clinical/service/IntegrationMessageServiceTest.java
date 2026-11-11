package com.medassist.clinical.service;

import com.medassist.clinical.domain.IntegrationMessage;
import com.medassist.clinical.integration.IntegrationConfig.IntegrationSender;
import com.medassist.clinical.integration.IntegrationProperties;
import com.medassist.clinical.integration.IntegrationSendException;
import com.medassist.clinical.repo.ClinicalRepository;
import com.medassist.clinical.repo.IntegrationRepository;
import com.medassist.clinical.web.IntegrationDtos.IntegrationEnqueueReq;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 集成消息台账：登记、重投状态机、退避失败与状态汇总。
 */
class IntegrationMessageServiceTest {

    private IntegrationRepository repo;
    private ClinicalRepository clinicalRepo;
    private IntegrationSender sender;
    private IntegrationMessageService service;

    @BeforeEach
    void setUp() {
        repo = mock(IntegrationRepository.class);
        clinicalRepo = mock(ClinicalRepository.class);
        sender = mock(IntegrationSender.class);
        IntegrationProperties properties = new IntegrationProperties(
                Map.of(), 8, 30, 1800, 5000);
        service = new IntegrationMessageService(repo, clinicalRepo, sender, properties,
                JsonMapper.builder().build(), mock(PlatformTransactionManager.class));
    }

    @Test
    @DisplayName("登记：对端编码统一大写、报文去空白，状态 PENDING，并写审计")
    void enqueueNormalizesAndAudits() {
        service.enqueue(new IntegrationEnqueueReq(" his ", "ADT_A01", " P001 ",
                "  MSH|^~\\&|HIS  "), "his01");

        ArgumentCaptor<IntegrationMessage> captor = ArgumentCaptor.forClass(IntegrationMessage.class);
        verify(repo).insert(captor.capture());
        IntegrationMessage saved = captor.getValue();
        assertThat(saved.systemCode()).isEqualTo("HIS");
        assertThat(saved.messageType()).isEqualTo("ADT_A01");
        assertThat(saved.businessKey()).isEqualTo("P001");
        assertThat(saved.payload()).isEqualTo("MSH|^~\\&|HIS");
        assertThat(saved.status()).isEqualTo(IntegrationMessage.PENDING);
        assertThat(saved.attempts()).isZero();
        verify(clinicalRepo).logChange(eq("his01"), eq("INTEGRATION_ENQUEUE"),
                eq("INTEGRATION_MESSAGE"), eq(saved.id().toString()), anyString());
    }

    @Test
    @DisplayName("登记：空报文 422，不落库")
    void enqueueRejectsBlankPayload() {
        assertThatThrownBy(() -> service.enqueue(
                new IntegrationEnqueueReq("HIS", "ADT_A01", null, "   "), "his01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        verify(repo, never()).insert(any());
    }

    @Test
    @DisplayName("人工重投：ACKED 409；FAILED 可重置为 PENDING 并留痕")
    void retryOnlyAllowsFailedOrDead() {
        IntegrationMessage acked = message(IntegrationMessage.ACKED, 1, 3L);
        when(repo.findById(acked.id())).thenReturn(Optional.of(acked));
        assertThatThrownBy(() -> service.retry(acked.id(), "ops01"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("只有失败")
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CONFLICT);
        verify(repo, never()).resetForRetry(any(), anyLong());

        IntegrationMessage failed = message(IntegrationMessage.FAILED, 4, 7L);
        IntegrationMessage pending = message(IntegrationMessage.PENDING, 0, 8L);
        when(repo.findById(failed.id())).thenReturn(Optional.of(failed), Optional.of(pending));
        when(repo.resetForRetry(failed.id(), 7L)).thenReturn(true);

        var view = service.retry(failed.id(), "ops01");

        assertThat(view.status()).isEqualTo(IntegrationMessage.PENDING);
        verify(repo).resetForRetry(failed.id(), 7L);
        verify(clinicalRepo).logChange(eq("ops01"), eq("INTEGRATION_RETRY"),
                eq("INTEGRATION_MESSAGE"), eq(failed.id().toString()), anyString());
    }

    @Test
    @DisplayName("汇总：PENDING 与 SENDING 都算待发，按系统分组")
    void summaryMergesPendingAndSending() {
        when(repo.countByStatus()).thenReturn(List.of(
                new IntegrationRepository.StatusCount(IntegrationMessage.PENDING, 3),
                new IntegrationRepository.StatusCount(IntegrationMessage.SENDING, 2),
                new IntegrationRepository.StatusCount(IntegrationMessage.FAILED, 4),
                new IntegrationRepository.StatusCount(IntegrationMessage.DEAD, 1),
                new IntegrationRepository.StatusCount(IntegrationMessage.ACKED, 10)));
        when(repo.countBySystem()).thenReturn(List.of(
                new IntegrationRepository.SystemStatusCount("HIS", IntegrationMessage.PENDING, 2),
                new IntegrationRepository.SystemStatusCount("HIS", IntegrationMessage.SENDING, 1),
                new IntegrationRepository.SystemStatusCount("HIS", IntegrationMessage.FAILED, 4),
                new IntegrationRepository.SystemStatusCount("LIS", IntegrationMessage.DEAD, 1)));

        var summary = service.summary();

        assertThat(summary.pending()).isEqualTo(5);
        assertThat(summary.failed()).isEqualTo(4);
        assertThat(summary.dead()).isEqualTo(1);
        assertThat(summary.acked()).isEqualTo(10);
        assertThat(summary.bySystem()).extracting("systemCode", "pending", "failed", "dead")
                .containsExactly(tuple("HIS", 3, 4, 0), tuple("LIS", 0, 0, 1));
    }

    @Test
    @DisplayName("投递成功：认领后发送，回执号落库，返回确认数")
    void dispatchMarksAcked() {
        IntegrationMessage pending = message(IntegrationMessage.SENDING, 0, 5L);
        when(repo.lockDueIds(20)).thenReturn(List.of(pending.id()));
        when(repo.findByIds(List.of(pending.id()))).thenReturn(List.of(pending));
        when(sender.send(pending)).thenReturn("MSG0001");
        when(repo.markAcked(pending.id(), "MSG0001", 5L)).thenReturn(true);

        int acked = service.dispatchDue(20);

        assertThat(acked).isEqualTo(1);
        verify(repo).markSending(List.of(pending.id()));
        verify(repo).markAcked(pending.id(), "MSG0001", 5L);
    }

    @Test
    @DisplayName("投递失败：指数退避；达到上限进入 DEAD 并写审计")
    void dispatchBacksOffAndDeadLetters() {
        IntegrationMessage first = message(IntegrationMessage.SENDING, 0, 1L);
        when(repo.lockDueIds(20)).thenReturn(List.of(first.id()));
        when(repo.findByIds(List.of(first.id()))).thenReturn(List.of(first));
        when(sender.send(first)).thenThrow(new IntegrationSendException("超时"));

        service.dispatchDue(20);

        verify(repo).markFailed(eq(first.id()), eq(1), any(Instant.class), anyString(),
                eq(false), eq(1L));

        IntegrationMessage last = message(IntegrationMessage.SENDING, 7, 9L);
        when(repo.lockDueIds(20)).thenReturn(List.of(last.id()));
        when(repo.findByIds(List.of(last.id()))).thenReturn(List.of(last));
        when(sender.send(last)).thenThrow(new IntegrationSendException("对端拒绝"));

        service.dispatchDue(20);

        verify(repo).markFailed(eq(last.id()), eq(8), any(Instant.class), anyString(),
                eq(true), eq(9L));
        verify(clinicalRepo).logChange(eq(last.createdBy()), eq("INTEGRATION_DEAD"),
                eq("INTEGRATION_MESSAGE"), eq(last.id().toString()), anyString());
    }

    private static IntegrationMessage message(String status, int attempts, long version) {
        return new IntegrationMessage(UUID.randomUUID(), "HIS", "ADT_A01", "P001",
                "MSH|^~\\&|HIS", status, attempts, Instant.now(), null, null,
                "his01", null, Instant.now(), version);
    }

    private static org.assertj.core.groups.Tuple tuple(Object... values) {
        return org.assertj.core.groups.Tuple.tuple(values);
    }
}
