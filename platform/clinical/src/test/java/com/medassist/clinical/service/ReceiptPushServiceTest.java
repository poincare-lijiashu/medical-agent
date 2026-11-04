package com.medassist.clinical.service;

import com.medassist.clinical.domain.ClinicalModel.ChargeReceipt;
import com.medassist.clinical.domain.ClinicalModel.ReceiptPush;
import com.medassist.clinical.repo.ClinicalRepository;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.time.BusinessTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 电子票据厂商报文（G21 / G24）：有效票校验、域名白名单、幂等、红冲前置条件与报文模板。
 */
class ReceiptPushServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ClinicalRepository repo = mock(ClinicalRepository.class);

    private static ChargeReceipt receipt(String status) {
        return new ChargeReceipt(UUID.randomUUID(), "RP20261104000001", UUID.randomUUID(),
                UUID.randomUUID(), new BigDecimal("120.00"), "CASH", status, "admin01",
                Instant.now(), null, null, null, 0, 0L, Instant.now());
    }

    private ReceiptPushService service(String baseUrl) {
        return new ReceiptPushService(repo, objectMapper, baseUrl, "secret", "127.0.0.1", 1000,
                "FINANCE", "1.0", "H001");
    }

    private static ReceiptPush push(UUID receiptId, String messageType, String status,
                                    String externalNo) {
        return new ReceiptPush(UUID.randomUUID(), receiptId, "FINANCE", "FINANCE", messageType,
                "1.0", null, status, 1, externalNo, null, Instant.now());
    }

    @Test
    @DisplayName("作废票据不可开具推送 → 422")
    void rejectsVoidReceipt() {
        when(repo.findReceipt(any())).thenReturn(Optional.of(receipt("VOID")));

        assertThatThrownBy(() -> service("http://127.0.0.1:9098/receipts")
                .push(UUID.randomUUID(), "admin01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("未配置平台地址 → 503")
    void requiresConfiguredPlatform() {
        when(repo.findReceipt(any())).thenReturn(Optional.of(receipt("ISSUED")));

        assertThatThrownBy(() -> service("").push(UUID.randomUUID(), "admin01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.UPSTREAM_UNAVAILABLE);
    }

    @Test
    @DisplayName("平台域名不在白名单 → 422")
    void rejectsHostNotAllowed() {
        when(repo.findReceipt(any())).thenReturn(Optional.of(receipt("ISSUED")));

        assertThatThrownBy(() -> service("https://evil.example.com/receipts")
                .push(UUID.randomUUID(), "admin01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("已成功开具幂等返回，不重复占位")
    void returnsIdempotentWhenSent() {
        ChargeReceipt r = receipt("ISSUED");
        when(repo.findReceipt(r.id())).thenReturn(Optional.of(r));
        when(repo.findReceiptPush(r.id(), "FINANCE", ReceiptPush.ISSUE))
                .thenReturn(Optional.of(push(r.id(), ReceiptPush.ISSUE, "SENT", "EXT-001")));

        var view = service("http://127.0.0.1:9098/receipts").push(r.id(), "admin01");

        assertThat(view.status()).isEqualTo("SENT");
        assertThat(view.externalNo()).isEqualTo("EXT-001");
        assertThat(view.messageType()).isEqualTo(ReceiptPush.ISSUE);
    }

    @Test
    @DisplayName("未作废票据不可红冲 → 422")
    void redFlushRejectsIssuedReceipt() {
        when(repo.findReceipt(any())).thenReturn(Optional.of(receipt("ISSUED")));

        assertThatThrownBy(() -> service("http://127.0.0.1:9098/receipts")
                .redFlush(UUID.randomUUID(), "打印错误", "admin01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("没有成功的原开具报文时红冲 → 422")
    void redFlushRequiresSentIssuePush() {
        ChargeReceipt r = receipt("VOID");
        when(repo.findReceipt(r.id())).thenReturn(Optional.of(r));
        when(repo.findReceiptPush(r.id(), "FINANCE", ReceiptPush.ISSUE))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service("http://127.0.0.1:9098/receipts")
                .redFlush(r.id(), "退费", "admin01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("已成功红冲幂等返回")
    void redFlushIsIdempotentWhenSent() {
        ChargeReceipt r = receipt("VOID");
        when(repo.findReceipt(r.id())).thenReturn(Optional.of(r));
        when(repo.findReceiptPush(r.id(), "FINANCE", ReceiptPush.ISSUE))
                .thenReturn(Optional.of(push(r.id(), ReceiptPush.ISSUE, "SENT", "EXT-001")));
        ReceiptPush red = push(r.id(), ReceiptPush.RED_FLUSH, "SENT", "RED-001");
        when(repo.findReceiptPush(r.id(), "FINANCE", ReceiptPush.RED_FLUSH))
                .thenReturn(Optional.of(red));

        var view = service("http://127.0.0.1:9098/receipts")
                .redFlush(r.id(), "退费", "admin01");

        assertThat(view.status()).isEqualTo("SENT");
        assertThat(view.messageType()).isEqualTo(ReceiptPush.RED_FLUSH);
        assertThat(view.externalNo()).isEqualTo("RED-001");
    }

    @Test
    @DisplayName("开具报文包含厂商、模板版本、医疗机构代码与票面快照")
    void issuePayloadUsesVendorTemplate() {
        ChargeReceipt r = receipt("ISSUED");

        JsonNode body = readTree(service("").buildIssuePayload(r));

        assertThat(body.path("vendor").asText()).isEqualTo("FINANCE");
        assertThat(body.path("templateVersion").asText()).isEqualTo("1.0");
        assertThat(body.path("messageType").asText()).isEqualTo("ISSUE");
        assertThat(body.path("hospitalCode").asText()).isEqualTo("H001");
        assertThat(body.path("receipt").path("receiptNo").asText()).isEqualTo("RP20261104000001");
        assertThat(body.path("receipt").path("amount").decimalValue())
                .isEqualByComparingTo(new BigDecimal("120.00"));
    }

    @Test
    @DisplayName("红冲报文携带原平台票据号与原因")
    void redFlushPayloadCarriesOriginalReference() {
        ChargeReceipt r = receipt("VOID");
        ReceiptPush original = push(r.id(), ReceiptPush.ISSUE, "SENT", "EXT-001");

        JsonNode body = readTree(service("").buildRedFlushPayload(r, original, "患者退费"));

        assertThat(body.path("messageType").asText()).isEqualTo("RED_FLUSH");
        assertThat(body.path("originalExternalNo").asText()).isEqualTo("EXT-001");
        assertThat(body.path("redFlushReason").asText()).isEqualTo("患者退费");
        assertThat(body.path("amount").decimalValue())
                .isEqualByComparingTo(new BigDecimal("120.00"));
    }

    @Test
    @DisplayName("对账区间：未来/倒置/超 92 天 → 422")
    void validatesLedgerRange() {
        LocalDate today = BusinessTime.today();

        assertThatThrownBy(() -> service("").listLedger(today.plusDays(1), today.plusDays(2),
                null, null, null, 10))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);

        assertThatThrownBy(() -> service("").listLedger(today, today.minusDays(1),
                null, null, null, 10))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);

        assertThatThrownBy(() -> service("").listLedger(today.minusDays(93), today,
                null, null, null, 10))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    private JsonNode readTree(String json) {
        return objectMapper.readTree(json);
    }
}
