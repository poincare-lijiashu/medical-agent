package com.medassist.clinical.client;

import com.medassist.clinical.config.ClinicalProperties;
import com.medassist.common.security.InternalCallerSigner;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * EMR 归档核查客户端：只读降级语义（查不到 ≠ 通过）。
 */
class HttpEmrArchiveClientTest {

    private static HttpServer server;
    private static int port;
    private static final AtomicReference<Integer> STATUS = new AtomicReference<>(200);
    private static final AtomicReference<String> BODY = new AtomicReference<>("");
    private static final AtomicReference<String> LAST_TOKEN = new AtomicReference<>("");
    private static final AtomicReference<String> LAST_PATH = new AtomicReference<>("");
    private static final AtomicInteger CALLS = new AtomicInteger();

    private final UUID visitId = UUID.randomUUID();

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/records/", HttpEmrArchiveClientTest::handle);
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @BeforeEach
    void reset() {
        STATUS.set(200);
        BODY.set("{\"visitId\":\"" + visitId + "\",\"total\":5,\"archived\":3,\"draft\":2,"
                + "\"homepageFound\":true,\"homepageStatus\":\"SUBMITTED\",\"homepageArchived\":false}");
        LAST_TOKEN.set("");
        LAST_PATH.set("");
        CALLS.set(0);
    }

    private static void handle(HttpExchange exchange) throws IOException {
        CALLS.incrementAndGet();
        LAST_TOKEN.set(exchange.getRequestHeaders().getFirst("X-Internal-Token"));
        LAST_PATH.set(exchange.getRequestURI().getPath());
        byte[] out = BODY.get().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(STATUS.get(), out.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(out);
        }
    }

    private HttpEmrArchiveClient client(String token) {
        ClinicalProperties props = new ClinicalProperties(null, null, null,
                new ClinicalProperties.Emr("http://127.0.0.1:" + port, token, Duration.ofSeconds(2)),
                null);
        return new HttpEmrArchiveClient(props, InternalCallerSigner.disabled());
    }

    @Test
    @DisplayName("正常返回：解析计数与首页状态，并带内网令牌")
    void parsesSummary() {
        Optional<EmrArchiveClient.ArchiveSummary> found = client("tok").archiveSummary(visitId);

        assertTrue(found.isPresent());
        assertEquals(5, found.get().total());
        assertEquals(3, found.get().archived());
        assertEquals(2, found.get().draft());
        assertEquals(2, found.get().unarchived());
        assertEquals("SUBMITTED", found.get().homepageStatus());
        assertEquals("tok", LAST_TOKEN.get());
        assertEquals("/internal/records/by-visit/" + visitId + "/archive-summary", LAST_PATH.get());
    }

    @Test
    @DisplayName("上游 5xx：降级为空，不抛异常打断出院体检")
    void upstreamFailureDegradesToEmpty() {
        STATUS.set(503);
        BODY.set("{\"message\":\"down\"}");

        assertEquals(Optional.empty(), client("tok").archiveSummary(visitId));
    }

    @Test
    @DisplayName("令牌未配置：视为未接入，连请求都不发")
    void blankTokenDoesNotCallUpstream() {
        assertEquals(Optional.empty(), client("").archiveSummary(visitId));
        assertEquals(0, CALLS.get());
    }

    @Test
    @DisplayName("批量：解析每个就诊；上游缺省字段按 0/false（Jackson 3 不对 null 装箱宽松）")
    void parsesBatchSummary() {
        UUID second = UUID.randomUUID();
        BODY.set("{\"items\":[{\"visitId\":\"" + visitId + "\",\"total\":5,\"archived\":3,"
                + "\"draft\":2,\"homepageFound\":true,\"homepageStatus\":\"SUBMITTED\","
                + "\"homepageArchived\":false},{\"visitId\":\"" + second + "\",\"total\":0,"
                + "\"archived\":0,\"draft\":0,\"homepageFound\":false}]}");

        Map<UUID, EmrArchiveClient.ArchiveSummary> out =
                client("tok").batchArchiveSummary(List.of(visitId, second));

        assertEquals(2, out.size());
        assertEquals(2, out.get(visitId).unarchived());
        assertEquals("SUBMITTED", out.get(visitId).homepageStatus());
        assertEquals(0, out.get(second).total());
        assertEquals("/internal/records/archive-summaries", LAST_PATH.get());
        assertEquals("tok", LAST_TOKEN.get());
    }

    @Test
    @DisplayName("批量上游 5xx：返回空 Map（调用方据此显示“未核查”）")
    void batchFailureReturnsEmptyMap() {
        STATUS.set(503);
        BODY.set("{\"message\":\"down\"}");

        assertTrue(client("tok").batchArchiveSummary(List.of(visitId)).isEmpty());
    }
}
