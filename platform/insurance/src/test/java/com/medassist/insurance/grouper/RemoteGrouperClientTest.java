package com.medassist.insurance.grouper;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.insurance.config.GrouperProperties;
import com.medassist.insurance.domain.InsuranceModel.GrouperRuleSet;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 远端分组器适配：真实 HTTP 往返、字段映射、失败不静默。
 */
class RemoteGrouperClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private GrouperRuleSet ruleSet() {
        return new GrouperRuleSet(UUID.randomUUID(), "CHS-DRG2.0", "1.0", "省局规则", "DRG",
                LocalDate.of(2026, 1, 1), "PROVINCIAL", "checksum",
                GrouperRuleSet.STATUS_ACTIVE, "yb01", Instant.now(), "yb01", Instant.now());
    }

    private RemoteGrouperClient client(String url, boolean fallback) {
        return new RemoteGrouperClient(new GrouperProperties("REMOTE", url, "token",
                Duration.ofSeconds(2), fallback), objectMapper);
    }

    private int startServer(int status, String body, AtomicReference<String> captured) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/grouper", exchange -> {
            captured.set(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return server.getAddress().getPort();
    }

    @Test
    @DisplayName("成功：解析 DRG 字段与轨迹，请求带规则包版本与输入")
    void parsesSuccessfulResponse() throws Exception {
        AtomicReference<String> captured = new AtomicReference<>();
        int port = startServer(200, """
                {"mdcCode":"MDCB","adrgCode":"ADRG-B1","drgCode":"DRG-B1C","severity":"MCC",
                 "weight":3.0,"matched":true,"reason":"命中",
                 "trace":[{"step":"REMOTE","drgCode":"DRG-B1C"}]}
                """, captured);

        GrouperOutcome outcome = client("http://127.0.0.1:" + port + "/grouper", false)
                .group(ruleSet(), new GrouperInput("J44.900", List.of("J96.000"),
                        List.of("32.2100")));

        assertThat(outcome.matched()).isTrue();
        assertThat(outcome.drgCode()).isEqualTo("DRG-B1C");
        assertThat(outcome.severity()).isEqualTo("MCC");
        assertThat(outcome.weight()).isEqualByComparingTo("3");
        assertThat(outcome.trace()).hasSize(1);
        assertThat(captured.get()).contains("CHS-DRG2.0").contains("J44.900")
                .contains("32.2100");
    }

    @Test
    @DisplayName("失败：HTTP 500 明确报分组服务不可用，不返回空分组")
    void httpErrorIsExplicit() throws Exception {
        int port = startServer(500, "{}", new AtomicReference<>());

        assertThatThrownBy(() -> client("http://127.0.0.1:" + port + "/grouper", false)
                .group(ruleSet(), new GrouperInput("J44.900", List.of(), List.of())))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.INSURANCE_GROUPER_UNAVAILABLE);
    }

    @Test
    @DisplayName("未配置地址：直接拒绝，不尝试本地兜底")
    void notConfigured() {
        assertThatThrownBy(() -> client("", true)
                .group(ruleSet(), new GrouperInput("J44.900", List.of(), List.of())))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.INSURANCE_GROUPER_UNAVAILABLE);
    }
}
