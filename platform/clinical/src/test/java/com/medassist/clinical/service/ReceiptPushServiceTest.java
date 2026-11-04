package com.medassist.clinical.service;

import com.medassist.clinical.domain.ClinicalModel.ChargeReceipt;
import com.medassist.clinical.domain.ClinicalModel.ReceiptPush;
import com.medassist.clinical.repo.ClinicalRepository;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 电子票据推送：有效票校验、平台配置、域名白名单与幂等。
 */
class ReceiptPushServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static ChargeReceipt receipt(String status) {
        return new ChargeReceipt(UUID.randomUUID(), "RP20261104000001", UUID.randomUUID(),
                UUID.randomUUID(), new BigDecimal("120.00"), "CASH", status, "admin01",
                Instant.now(), null, null, null, 0, 0L, Instant.now());
    }

    @Test
    @DisplayName("作废票据不可推送 → 422")
    void rejectsVoidReceipt() {
        ClinicalRepository repo = mock(ClinicalRepository.class);
        when(repo.findReceipt(any())).thenReturn(Optional.of(receipt("VOID")));
        var service = new ReceiptPushService(repo, objectMapper,
                "http://127.0.0.1:9098/receipts", "secret", "127.0.0.1", 1000);

        assertThatThrownBy(() -> service.push(UUID.randomUUID(), "admin01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("未配置平台地址 → 503")
    void requiresConfiguredPlatform() {
        ClinicalRepository repo = mock(ClinicalRepository.class);
        when(repo.findReceipt(any())).thenReturn(Optional.of(receipt("ISSUED")));
        var service = new ReceiptPushService(repo, objectMapper, "", "secret", "", 1000);

        assertThatThrownBy(() -> service.push(UUID.randomUUID(), "admin01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.UPSTREAM_UNAVAILABLE);
    }

    @Test
    @DisplayName("平台域名不在白名单 → 422")
    void rejectsHostNotAllowed() {
        ClinicalRepository repo = mock(ClinicalRepository.class);
        when(repo.findReceipt(any())).thenReturn(Optional.of(receipt("ISSUED")));
        var service = new ReceiptPushService(repo, objectMapper,
                "https://evil.example.com/receipts", "secret", "finance.example.com", 1000);

        assertThatThrownBy(() -> service.push(UUID.randomUUID(), "admin01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("已成功推送幂等返回，不重复占位")
    void returnsIdempotentWhenSent() {
        ClinicalRepository repo = mock(ClinicalRepository.class);
        ChargeReceipt r = receipt("ISSUED");
        UUID receiptId = r.id();
        when(repo.findReceipt(receiptId)).thenReturn(Optional.of(r));
        when(repo.findReceiptPush(receiptId, "FINANCE")).thenReturn(Optional.of(
                new ReceiptPush(UUID.randomUUID(), receiptId, "FINANCE", "SENT", 1,
                        "EXT-001", null, Instant.now())));
        var service = new ReceiptPushService(repo, objectMapper,
                "http://127.0.0.1:9098/receipts", "secret", "127.0.0.1", 1000);

        var view = service.push(receiptId, "admin01");

        assertThat(view.status()).isEqualTo("SENT");
        assertThat(view.externalNo()).isEqualTo("EXT-001");
        verify(repo).findReceiptPush(receiptId, "FINANCE");
    }
}
