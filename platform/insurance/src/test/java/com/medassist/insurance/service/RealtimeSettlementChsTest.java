package com.medassist.insurance.service;

import com.medassist.insurance.config.ChsProperties;
import com.medassist.insurance.domain.InsuranceModel.RealtimeSettlement;
import com.medassist.insurance.domain.InsuranceModel.SettlementBill;
import com.medassist.insurance.repo.InsuranceRepository;
import com.medassist.insurance.web.InsuranceDtos.RealtimeSettlementView;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CHS 开启时的端到端：服务层 → 真实 HTTP → 国家 2.0 信封 → 响应解析 → 交易状态。
 *
 * <p>不依赖省局网络：用本机 HttpServer 校验请求确实是 CHS 信封（infno/msgid/input/sign），
 * 并验证成功/失败两种回执下交易状态与对端流水号的落库口径。
 */
class RealtimeSettlementChsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("CHS SETTLE：信封字段正确、签名存在、成功回执写 SETTLED 与 setl_id")
    void settleSuccess() throws Exception {
        AtomicReference<String> reply = new AtomicReference<>(
                "{\"ret_code\":\"0\",\"ret_msg\":\"成功\",\"output\":{\"setl_id\":\"S123\"}}");
        AtomicReference<String> captured = new AtomicReference<>();
        int port = startServer(reply, captured);

        AtomicReference<RealtimeSettlement> state = new AtomicReference<>();
        InsuranceRepository repo = statefulRepo(state);
        SettlementBill bill = bill();
        when(repo.findBillById(bill.id())).thenReturn(Optional.of(bill));

        RealtimeSettlementView view = service(repo, port)
                .submit(bill.id(), "CHS-REQ-1", "SETTLE", "admin01");

        assertThat(view.status()).isEqualTo(RealtimeSettlement.SETTLED);
        assertThat(view.externalNo()).isEqualTo("S123");
        JsonNode sent = MAPPER.readTree(captured.get());
        assertThat(sent.path("infno").asText()).isEqualTo("2201");
        assertThat(sent.path("msgid").asText()).isEqualTo("CHS-REQ-1");
        assertThat(sent.path("fixmedins_code").asText()).isEqualTo("H0001");
        assertThat(sent.path("input").path("psn_no").asText()).isEqualTo("P0001");
        assertThat(sent.path("sign").asText()).hasSize(64);
    }

    @Test
    @DisplayName("CHS SETTLE：ret_code 非成功码 → 交易 FAILED 并保留原因，不抛异常")
    void settleFailureReply() throws Exception {
        AtomicReference<String> reply = new AtomicReference<>(
                "{\"ret_code\":\"-1\",\"ret_msg\":\"参保状态异常\"}");
        int port = startServer(reply, new AtomicReference<>());

        AtomicReference<RealtimeSettlement> state = new AtomicReference<>();
        InsuranceRepository repo = statefulRepo(state);
        SettlementBill bill = bill();
        when(repo.findBillById(bill.id())).thenReturn(Optional.of(bill));

        RealtimeSettlementView view = service(repo, port)
                .submit(bill.id(), "CHS-REQ-2", "SETTLE", "admin01");

        assertThat(view.status()).isEqualTo(RealtimeSettlement.FAILED);
        assertThat(view.lastError()).contains("参保状态异常");
    }

    private int startServer(AtomicReference<String> reply, AtomicReference<String> captured)
            throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chs", exchange -> {
            if (captured != null) {
                captured.set(new String(exchange.getRequestBody().readAllBytes(),
                        StandardCharsets.UTF_8));
            } else {
                exchange.getRequestBody().readAllBytes();
            }
            byte[] bytes = reply.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return server.getAddress().getPort();
    }

    private RealtimeSettlementService service(InsuranceRepository repo, int port) {
        ChsProperties chs = new ChsProperties(true, "http://127.0.0.1:" + port,
                "127.0.0.1", java.time.Duration.ofSeconds(3), "H0001", "测试医院", "HIS",
                "330100", "330100", "V1.0", "HMAC_SHA256", "chs-secret", "op01", "经办人",
                "ret_code", "ret_msg", "output.setl_id", "0",
                Map.of("SETTLE", new ChsProperties.Transaction("2201", "/chs", null)));
        return new RealtimeSettlementService(repo, MAPPER, "", "", "", 1500, chs);
    }

    private static SettlementBill bill() {
        return new SettlementBill(UUID.randomUUID(), "SB20261104000001", UUID.randomUUID(),
                UUID.randomUUID(), "P0001", LocalDate.parse("2026-11-04"), UUID.randomUUID(),
                new BigDecimal("120.00"), new BigDecimal("120.00"), BigDecimal.ZERO,
                new BigDecimal("96.00"), new BigDecimal("24.00"), SettlementBill.CONFIRMED,
                "[]", "admin01", "admin01", Instant.now(), null, null, null,
                new BigDecimal("0.80"), new BigDecimal("300000.00"), 1L, Instant.now(),
                "J18.900", "肺炎", 1, "[]");
    }

    private static InsuranceRepository statefulRepo(AtomicReference<RealtimeSettlement> state) {
        InsuranceRepository repo = mock(InsuranceRepository.class);
        when(repo.insertRealtime(any())).thenAnswer(inv -> {
            if (state.get() != null) {
                return false;
            }
            state.set(inv.getArgument(0));
            return true;
        });
        when(repo.findRealtimeByBillAndRequest(any(), any()))
                .thenAnswer(inv -> Optional.ofNullable(state.get()));
        when(repo.findSettleByBill(any()))
                .thenAnswer(inv -> Optional.ofNullable(state.get()));
        when(repo.findRealtimeById(any())).thenAnswer(inv -> Optional.ofNullable(state.get()));
        when(repo.claimRealtime(any(), anyLong())).thenAnswer(inv -> {
            RealtimeSettlement current = state.get();
            long expected = inv.getArgument(1);
            if (current == null || current.version() != expected) {
                return false;
            }
            state.set(copy(current, RealtimeSettlement.DELIVERING, current.attempts() + 1,
                    current.externalNo(), current.lastError(), current.responsePayload(),
                    current.version() + 1));
            return true;
        });
        when(repo.markRealtime(any(), any(), anyInt(), any(), any(), any(), anyLong()))
                .thenAnswer(inv -> {
                    RealtimeSettlement current = state.get();
                    long expected = inv.getArgument(6);
                    if (current == null || current.version() != expected) {
                        return false;
                    }
                    state.set(copy(current, inv.getArgument(1), inv.getArgument(2),
                            inv.getArgument(3), inv.getArgument(4), inv.getArgument(5),
                            current.version() + 1));
                    return true;
                });
        return repo;
    }

    private static RealtimeSettlement copy(RealtimeSettlement c, String status, int attempts,
                                           String externalNo, String lastError,
                                           String responsePayload, long version) {
        return new RealtimeSettlement(c.id(), c.billId(), c.visitId(), c.requestId(), c.bizType(),
                c.platform(), status, attempts, externalNo, lastError, c.requestPayload(),
                responsePayload, c.createdBy(), c.createdAt(),
                RealtimeSettlement.SETTLED.equals(status) ? Instant.now() : null, version);
    }
}
