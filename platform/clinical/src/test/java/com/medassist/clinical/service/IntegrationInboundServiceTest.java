package com.medassist.clinical.service;

import com.medassist.clinical.domain.IntegrationInboundMessage;
import com.medassist.clinical.integration.InboundMessageProcessor;
import com.medassist.clinical.integration.IntegrationMetrics;
import com.medassist.clinical.repo.ClinicalRepository;
import com.medassist.clinical.repo.IntegrationInboundRepository;
import com.medassist.clinical.web.IntegrationDtos.InboundAcceptReq;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 入站幂等：同号同文放行、同号异文拒绝、处理器失败保留原文。
 */
class IntegrationInboundServiceTest {

    private IntegrationInboundRepository repo;
    private ClinicalRepository clinicalRepo;
    private IntegrationMetrics metrics;
    private IntegrationInboundService service;

    @BeforeEach
    void setUp() {
        repo = mock(IntegrationInboundRepository.class);
        clinicalRepo = mock(ClinicalRepository.class);
        metrics = mock(IntegrationMetrics.class);
        service = new IntegrationInboundService(repo, clinicalRepo, metrics, List.of(),
                JsonMapper.builder().build());
    }

    @Test
    @DisplayName("首次接收：落库 RECEIVED，返回 AA，不伪造 PROCESSED")
    void acceptsNewMessage() {
        var result = service.accept(req("MSG-1", "MSH|HIS|P001"), "his01");

        ArgumentCaptor<IntegrationInboundMessage> captor =
                ArgumentCaptor.forClass(IntegrationInboundMessage.class);
        verify(repo).insert(captor.capture());
        IntegrationInboundMessage saved = captor.getValue();
        assertThat(saved.systemCode()).isEqualTo("HIS");
        assertThat(saved.externalId()).isEqualTo("MSG-1");
        assertThat(saved.payloadHash()).hasSize(64);
        assertThat(saved.status()).isEqualTo(IntegrationInboundMessage.RECEIVED);
        assertThat(result.duplicate()).isFalse();
        assertThat(result.ackCode()).isEqualTo("AA");
        assertThat(result.status()).isEqualTo(IntegrationInboundMessage.RECEIVED);
        verify(metrics).inbound("accepted", "HIS");
        verify(clinicalRepo).logChange(eq("his01"), eq("INTEGRATION_INBOUND"),
                eq("INTEGRATION_INBOUND"), anyString(), anyString());
    }

    @Test
    @DisplayName("重复推送（同号同文）：返回 duplicate=true + AA，不再处理")
    void duplicateSamePayloadIsIdempotent() {
        IntegrationInboundMessage existing = inbound("MSG-1", IntegrationInboundMessage.PROCESSED);
        doThrow(new DataIntegrityViolationException("dup")).when(repo).insert(any());
        when(repo.findByExternalId("HIS", "MSG-1")).thenReturn(Optional.of(existing));

        var result = service.accept(req("MSG-1", "MSH|HIS|P001"), "his01");

        assertThat(result.duplicate()).isTrue();
        assertThat(result.ackCode()).isEqualTo("AA");
        assertThat(result.id()).isEqualTo(existing.id());
        verify(metrics).inbound("duplicate", "HIS");
        verify(repo, never()).markProcessed(any(), anyString(), any(), anyLong());
    }

    @Test
    @DisplayName("同号异文：409 拒绝覆盖，不写入也不回 AA")
    void sameControlIdDifferentPayloadConflicts() {
        IntegrationInboundMessage existing = inbound("MSG-1", IntegrationInboundMessage.RECEIVED);
        doThrow(new DataIntegrityViolationException("dup")).when(repo).insert(any());
        when(repo.findByExternalId("HIS", "MSG-1")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.accept(req("MSG-1", "MSH|HIS|P999"), "his01"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("报文内容不同")
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CONFLICT);
        verify(metrics).inbound("conflict", "HIS");
    }

    @Test
    @DisplayName("处理器失败：消息标记 FAILED 并回 AE，原文仍保留")
    void processorFailureMarksFailed() {
        InboundMessageProcessor processor = mock(InboundMessageProcessor.class);
        when(processor.supports("HIS", "ADT_A01")).thenReturn(true);
        org.mockito.Mockito.doThrow(new IllegalStateException("患者主索引不可用"))
                .when(processor).process(any());
        when(repo.findById(any())).thenAnswer(invocation -> Optional.of(inbound(
                "MSG-1", IntegrationInboundMessage.FAILED)));
        service = new IntegrationInboundService(repo, clinicalRepo, metrics,
                List.of(processor), JsonMapper.builder().build());

        var result = service.accept(req("MSG-1", "MSH|HIS|P001"), "his01");

        assertThat(result.status()).isEqualTo(IntegrationInboundMessage.FAILED);
        assertThat(result.ackCode()).isEqualTo("AE");
        verify(repo).markProcessed(any(), eq(IntegrationInboundMessage.FAILED), anyString(),
                eq(0L));
        verify(metrics).inbound("failed", "HIS");
    }

    @Test
    @DisplayName("空报文/空控制号：422，不落库")
    void rejectsBlankInput() {
        assertThatThrownBy(() -> service.accept(new InboundAcceptReq("HIS", "ADT_A01", " ", null,
                "MSH|X"), "his01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        verify(repo, never()).insert(any());
    }

    private static InboundAcceptReq req(String externalId, String payload) {
        return new InboundAcceptReq(" his ", "ADT_A01", externalId, " P001 ", payload);
    }

    private static IntegrationInboundMessage inbound(String externalId, String status) {
        String hash = hashOf("MSH|HIS|P001");
        return new IntegrationInboundMessage(UUID.randomUUID(), "HIS", "ADT_A01", externalId,
                "P001", "MSH|HIS|P001", hash, status, null, "his01", Instant.now(),
                Instant.now(), 1L);
    }

    private static String hashOf(String payload) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(
                    digest.digest(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
