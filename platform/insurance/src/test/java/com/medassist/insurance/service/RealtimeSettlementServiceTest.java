package com.medassist.insurance.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.insurance.domain.InsuranceModel.RealtimeSettlement;
import com.medassist.insurance.domain.InsuranceModel.SettlementBill;
import com.medassist.insurance.repo.InsuranceRepository;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 医保实时结算骨架：清单状态门槛、平台配置、域名白名单、幂等、平台业务失败与手动重试。
 */
class RealtimeSettlementServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static SettlementBill bill(String status) {
        return new SettlementBill(UUID.randomUUID(), "SB20261104000001", UUID.randomUUID(),
                UUID.randomUUID(), "P0001", LocalDate.now(), UUID.randomUUID(),
                new BigDecimal("120.00"), new BigDecimal("120.00"), BigDecimal.ZERO,
                new BigDecimal("96.00"), new BigDecimal("24.00"), status, "[]", "admin01",
                SettlementBill.CONFIRMED.equals(status) ? "admin01" : null,
                SettlementBill.CONFIRMED.equals(status) ? Instant.now() : null,
                null, null, null, new BigDecimal("0.80"), new BigDecimal("300000.00"),
                1L, Instant.now(), null, null, 0, "[]");
    }

    /** 用内存状态模拟仓储，验证服务层的状态流转。 */
    private static InsuranceRepository statefulRepo(AtomicReference<RealtimeSettlement> state) {
        InsuranceRepository repo = mock(InsuranceRepository.class);
        when(repo.insertRealtime(any())).thenAnswer(inv -> {
            state.set(inv.getArgument(0));
            return true;
        });
        when(repo.findRealtimeByBillAndRequest(any(), any()))
                .thenAnswer(inv -> Optional.ofNullable(state.get()));
        when(repo.findRealtimeById(any())).thenAnswer(inv -> Optional.ofNullable(state.get()));
        when(repo.markRealtime(any(), any(), anyInt(), any(), any(), any(), anyLong()))
                .thenAnswer(inv -> {
                    RealtimeSettlement current = state.get();
                    String status = inv.getArgument(1);
                    int attempts = inv.getArgument(2);
                    state.set(new RealtimeSettlement(current.id(), current.billId(),
                            current.visitId(), current.requestId(), current.bizType(),
                            current.platform(), status, attempts, inv.getArgument(3),
                            inv.getArgument(4), current.requestPayload(), inv.getArgument(5),
                            current.createdBy(), current.createdAt(),
                            RealtimeSettlement.SETTLED.equals(status) ? Instant.now() : null,
                            current.version() + 1));
                    return true;
                });
        return repo;
    }

    private RealtimeSettlementService service(InsuranceRepository repo, String baseUrl,
                                              String secret, String hosts) {
        return new RealtimeSettlementService(repo, objectMapper, baseUrl, secret, hosts, 1500);
    }

    @Test
    @DisplayName("草稿清单不可提交实时结算 → 422")
    void rejectsDraftBill() {
        InsuranceRepository repo = mock(InsuranceRepository.class);
        when(repo.findBillById(any())).thenReturn(Optional.of(bill(SettlementBill.DRAFT)));

        assertThatThrownBy(() -> service(repo, "http://127.0.0.1:9099/settle",
                "secret", "127.0.0.1").submit(UUID.randomUUID(), "REQ-1", "SETTLE", "admin01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.INSURANCE_BILL_STATUS_INVALID);
    }

    @Test
    @DisplayName("未配置平台地址 → 503")
    void requiresConfiguredPlatform() {
        InsuranceRepository repo = mock(InsuranceRepository.class);
        when(repo.findBillById(any())).thenReturn(Optional.of(bill(SettlementBill.CONFIRMED)));

        assertThatThrownBy(() -> service(repo, "", "secret", "")
                .submit(UUID.randomUUID(), "REQ-1", "SETTLE", "admin01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.UPSTREAM_UNAVAILABLE);
    }

    @Test
    @DisplayName("平台域名不在白名单 → 422")
    void rejectsHostNotAllowed() {
        InsuranceRepository repo = mock(InsuranceRepository.class);
        when(repo.findBillById(any())).thenReturn(Optional.of(bill(SettlementBill.CONFIRMED)));

        assertThatThrownBy(() -> service(repo, "https://evil.example.com/settle",
                "secret", "province.example.com").submit(UUID.randomUUID(), "REQ-1", "SETTLE", "admin01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("未配置签名密钥 → 503")
    void requiresConfiguredSecret() {
        InsuranceRepository repo = mock(InsuranceRepository.class);
        when(repo.findBillById(any())).thenReturn(Optional.of(bill(SettlementBill.CONFIRMED)));

        assertThatThrownBy(() -> service(repo, "http://127.0.0.1:9099/settle", "", "127.0.0.1")
                .submit(UUID.randomUUID(), "REQ-1", "SETTLE", "admin01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.UPSTREAM_UNAVAILABLE);
    }

    @Test
    @DisplayName("已结算交易重复提交幂等返回，不重复出账")
    void submitIsIdempotentWhenSettled() throws Exception {
        AtomicReference<RealtimeSettlement> state = new AtomicReference<>();
        InsuranceRepository repo = statefulRepo(state);
        SettlementBill b = bill(SettlementBill.CONFIRMED);
        when(repo.findBillById(any())).thenReturn(Optional.of(b));
        state.set(new RealtimeSettlement(UUID.randomUUID(), b.id(), b.visitId(), "REQ-1",
                RealtimeSettlement.SETTLE, "PROVINCE", RealtimeSettlement.SETTLED, 1,
                "EXT-001", null, "{}", "{}", "admin01", Instant.now(), Instant.now(), 2L));

        var view = service(repo, "http://127.0.0.1:9099/settle", "secret", "127.0.0.1")
                .submit(b.id(), "REQ-1", "SETTLE", "admin01");

        assertThat(view.status()).isEqualTo(RealtimeSettlement.SETTLED);
        assertThat(view.externalNo()).isEqualTo("EXT-001");
        assertThat(view.attempts()).isEqualTo(1);
    }

    @Test
    @DisplayName("平台业务码失败 → FAILED 且保留原因")
    void marksFailedWhenPlatformRejects() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/settle", exchange -> {
            byte[] body = "{\"code\":1001,\"message\":\"参保状态异常\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            AtomicReference<RealtimeSettlement> state = new AtomicReference<>();
            InsuranceRepository repo = statefulRepo(state);
            SettlementBill b = bill(SettlementBill.CONFIRMED);
            when(repo.findBillById(any())).thenReturn(Optional.of(b));
            var svc = service(repo, "http://127.0.0.1:" + server.getAddress().getPort() + "/settle",
                    "secret", "127.0.0.1");

            var view = svc.submit(b.id(), "REQ-FAIL", "SETTLE", "admin01");

            assertThat(view.status()).isEqualTo(RealtimeSettlement.FAILED);
            assertThat(view.attempts()).isEqualTo(1);
            assertThat(view.lastError()).contains("参保状态异常");
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("成功提交：返回省局交易号且请求带 HMAC 签名")
    void settlesWithSignature() throws Exception {
        AtomicReference<String> signature = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/settle", exchange -> {
            signature.set(exchange.getRequestHeaders().getFirst("X-Signature"));
            byte[] body = "{\"code\":0,\"success\":true,\"data\":{\"externalNo\":\"PROV-2026-0001\"}}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            AtomicReference<RealtimeSettlement> state = new AtomicReference<>();
            InsuranceRepository repo = statefulRepo(state);
            SettlementBill b = bill(SettlementBill.CONFIRMED);
            when(repo.findBillById(any())).thenReturn(Optional.of(b));
            var svc = service(repo, "http://127.0.0.1:" + server.getAddress().getPort() + "/settle",
                    "secret", "127.0.0.1");

            var view = svc.submit(b.id(), "REQ-OK", "SETTLE", "admin01");

            assertThat(view.status()).isEqualTo(RealtimeSettlement.SETTLED);
            assertThat(view.externalNo()).isEqualTo("PROV-2026-0001");
            assertThat(signature.get()).startsWith("sha256=");
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("不存在的交易重试 → 404")
    void retryMissingTransaction() {
        InsuranceRepository repo = mock(InsuranceRepository.class);
        when(repo.findRealtimeById(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service(repo, "http://127.0.0.1:9099/settle",
                "secret", "127.0.0.1").retry(UUID.randomUUID(), "admin01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.INSURANCE_REALTIME_NOT_FOUND);
    }

    @Test
    @DisplayName("已达到尝试上限 → 422，不再打省局接口")
    void rejectsWhenAttemptsExhausted() {
        AtomicReference<RealtimeSettlement> state = new AtomicReference<>();
        InsuranceRepository repo = statefulRepo(state);
        SettlementBill b = bill(SettlementBill.CONFIRMED);
        when(repo.findBillById(any())).thenReturn(Optional.of(b));
        state.set(new RealtimeSettlement(UUID.randomUUID(), b.id(), b.visitId(), "REQ-1",
                RealtimeSettlement.SETTLE, "PROVINCE", RealtimeSettlement.FAILED, 5,
                null, "网络超时", "{}", null, "admin01", Instant.now(), null, 6L));

        assertThatThrownBy(() -> service(repo, "http://127.0.0.1:9099/settle",
                "secret", "127.0.0.1").retry(state.get().id(), "admin01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }
}
