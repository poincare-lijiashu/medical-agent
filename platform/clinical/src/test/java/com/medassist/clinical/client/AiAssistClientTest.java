package com.medassist.clinical.client;

import com.medassist.clinical.config.ClinicalProperties;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AI 中台客户端契约（评测「HIS 深度集成」）：令牌透传、路径、错误码映射。
 *
 * <p>用 JDK 内置 HttpServer（无第三方依赖）起临时端口，真实走一遍 HTTP，
 * 避免 Mock 框架与 RestClient 版本耦合。
 */
class AiAssistClientTest {

    private static HttpServer server;
    private static int port;
    private static final AtomicReference<String> LAST_AUTH = new AtomicReference<>("");
    private static final AtomicReference<String> LAST_PATH = new AtomicReference<>("");

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/medical/inline/", AiAssistClientTest::handle);
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static void handle(HttpExchange exchange) throws IOException {
        LAST_AUTH.set(exchange.getRequestHeaders().getFirst("Authorization"));
        LAST_PATH.set(exchange.getRequestURI().getPath());
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        int status = 200;
        String resp = "{\"answer\":\"ok\",\"confidence\":0.9}";
        if (body.contains("reject")) {
            status = 422;
            resp = "{\"detail\":\"bad payload\"}";
        } else if (body.contains("boom")) {
            status = 500;
            resp = "{\"detail\":\"upstream error\"}";
        }
        byte[] out = resp.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, out.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(out);
        }
    }

    private static ClinicalProperties props(String baseUrl) {
        return new ClinicalProperties(null, null, null,
                new ClinicalProperties.Ai(baseUrl, Duration.ofSeconds(2)));
    }

    private static HttpAiAssistClient client() {
        return new HttpAiAssistClient(props("http://127.0.0.1:" + port));
    }

    @Test
    void qcCheckForwardsTokenAndReturnsBody() {
        Map<String, Object> body = client().qcCheck("demo-jwt", Map.of("text", "病历"));
        assertEquals("ok", body.get("answer"));
        assertEquals("Bearer demo-jwt", LAST_AUTH.get());
        assertEquals("/api/v1/medical/inline/qc-check", LAST_PATH.get());
    }

    @Test
    void rxCheckUsesRxPath() {
        client().rxCheck("demo-jwt", Map.of("text", "医嘱"));
        assertEquals("/api/v1/medical/inline/rx-check", LAST_PATH.get());
    }

    @Test
    void ai4xxMapsToValidationFailed() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> client().qcCheck("demo-jwt", Map.of("text", "reject")));
        assertEquals(ErrorCode.VALIDATION_FAILED, e.errorCode());
    }

    @Test
    void ai5xxMapsToUpstreamUnavailable() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> client().qcCheck("demo-jwt", Map.of("text", "boom")));
        assertEquals(ErrorCode.UPSTREAM_UNAVAILABLE, e.errorCode());
    }

    @Test
    void missingTokenIsUnauthorized() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> client().qcCheck("  ", Map.of("text", "x")));
        assertEquals(ErrorCode.UNAUTHORIZED, e.errorCode());
    }

    @Test
    void unconfiguredAiFailsLoudly() {
        HttpAiAssistClient noAi = new HttpAiAssistClient(props(""));
        BusinessException e = assertThrows(BusinessException.class,
                () -> noAi.qcCheck("demo-jwt", Map.of("text", "x")));
        assertEquals(ErrorCode.UPSTREAM_UNAVAILABLE, e.errorCode());
        assertTrue(e.getMessage().contains("CLINICAL_AI_BASE_URL"));
    }

    @Test
    void unreachableAiMapsToGatewayTimeout() {
        HttpAiAssistClient down = new HttpAiAssistClient(
                props("http://127.0.0.1:1"));
        BusinessException e = assertThrows(BusinessException.class,
                () -> down.qcCheck("demo-jwt", Map.of("text", "x")));
        assertEquals(ErrorCode.GATEWAY_TIMEOUT, e.errorCode());
    }

    @Test
    void controllerBearerParsing() {
        assertEquals("t", com.medassist.clinical.web.AiAssistController.bearer("Bearer t"));
        assertEquals("t", com.medassist.clinical.web.AiAssistController.bearer("bearer  t "));
        assertEquals("", com.medassist.clinical.web.AiAssistController.bearer(null));
        assertEquals("", com.medassist.clinical.web.AiAssistController.bearer("Basic x"));
    }
}
