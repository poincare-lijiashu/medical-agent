package com.medassist.clinical.service;

import com.medassist.clinical.domain.Hl7LabModel.Case;
import com.medassist.clinical.domain.Hl7LabModel.Endpoint;
import com.medassist.clinical.domain.Hl7LabModel.Run;
import com.medassist.clinical.repo.Hl7LabRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * HL7 联调：本地结构校验、MLLP 发送与 ACK 比对（§301）。
 */
class Hl7LabServiceTest {

    private static final UUID CASE_ID = UUID.randomUUID();
    private static final UUID ENDPOINT_ID = UUID.randomUUID();
    private static final String MESSAGE = String.join("\r",
            "MSH|^~\\&|HIS|DEMO|MEDASSIST|DEMO|20261113090000||ADT^A01|MSG001|P|2.5",
            "EVN|A01|20261113090000",
            "PID|1||P000001||张三||19800101|M",
            "PV1|1|I|WARD01^01^01");

    private Hl7LabRepository repo;
    private Hl7LabService service;
    private ServerSocket server;
    private Thread serverThread;

    @BeforeEach
    void setUp() {
        repo = mock(Hl7LabRepository.class);
        service = new Hl7LabService(repo);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (server != null) {
            server.close();
        }
        if (serverThread != null) {
            serverThread.interrupt();
        }
    }

    @Test
    @DisplayName("校验：消息类型与用例不一致时拒绝")
    void validateRejectsWrongType() {
        Case hl7Case = hl7Case(MESSAGE, "ORM", "O01");

        List<String> errors = service.validate(hl7Case);

        assertThat(errors).anyMatch(e -> e.contains("MSH-9"));
    }

    @Test
    @DisplayName("校验：缺少必填段时拒绝")
    void validateRejectsMissingSegment() {
        Case hl7Case = hl7Case(MESSAGE, "ADT", "A01", List.of("MSH", "PID", "OBX"));

        List<String> errors = service.validate(hl7Case);

        assertThat(errors).anyMatch(e -> e.contains("OBX"));
    }

    @Test
    @DisplayName("执行：ACK|AA 与期望一致判 PASSED")
    void runPassed() throws Exception {
        stubCaseAndEndpoint();
        startServer("MSH|^~\\&|LIS|DEMO|MEDASSIST|DEMO|20261113090000||ACK^A01|ACK001|P|2.5\rMSA|AA|MSG001");

        Run run = service.run(CASE_ID, ENDPOINT_ID, "integration01");

        assertThat(run.status()).isEqualTo("PASSED");
        assertThat(run.ackCode()).isEqualTo("AA");
        assertThat(run.ackControlId()).isEqualTo("MSG001");
        ArgumentCaptor<Run> captor = ArgumentCaptor.forClass(Run.class);
        verify(repo).insertRun(captor.capture());
        assertThat(captor.getValue().requestExcerpt()).contains("ADT^A01");
    }

    @Test
    @DisplayName("执行：ACK|AE 与期望 AA 不一致判 FAILED")
    void runFailedOnAckMismatch() throws Exception {
        stubCaseAndEndpoint();
        startServer("MSA|AE|MSG001");

        Run run = service.run(CASE_ID, ENDPOINT_ID, "integration01");

        assertThat(run.status()).isEqualTo("FAILED");
        assertThat(run.ackCode()).isEqualTo("AE");
    }

    @Test
    @DisplayName("执行：端点不可达记 ERROR")
    void runErrorOnUnreachable() throws Exception {
        stubCaseAndEndpoint();
        int port;
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        when(repo.findEndpoint(ENDPOINT_ID)).thenReturn(Optional.of(endpoint(port)));

        Run run = service.run(CASE_ID, ENDPOINT_ID, "integration01");

        assertThat(run.status()).isEqualTo("ERROR");
        assertThat(run.errorMessage()).contains("发送失败");
    }

    // ------------------------------------------------------------------ 构造

    private void stubCaseAndEndpoint() throws Exception {
        when(repo.findCase(CASE_ID)).thenReturn(Optional.of(hl7Case(MESSAGE, "ADT", "A01")));
        when(repo.findEndpoint(ENDPOINT_ID)).thenReturn(Optional.of(endpoint(0)));
        when(repo.nextRunNo()).thenReturn("HL72026111300001");
    }

    private void startServer(String ack) throws Exception {
        server = new ServerSocket(0);
        int port = server.getLocalPort();
        when(repo.findEndpoint(ENDPOINT_ID)).thenReturn(Optional.of(endpoint(port)));
        AtomicReference<Exception> failure = new AtomicReference<>();
        serverThread = new Thread(() -> {
            try (Socket socket = server.accept()) {
                InputStream in = socket.getInputStream();
                // 读到结束帧
                boolean end = false;
                while (true) {
                    int b = in.read();
                    if (b == -1) break;
                    if (b == 0x1C) {
                        end = true;
                    } else if (end && b == 0x0D) {
                        break;
                    } else if (end) {
                        end = false;
                    }
                }
                OutputStream out = socket.getOutputStream();
                out.write(0x0B);
                out.write(ack.getBytes(StandardCharsets.UTF_8));
                out.write(0x1C);
                out.write(0x0D);
                out.flush();
            } catch (Exception e) {
                failure.set(e);
            }
        });
        serverThread.setDaemon(true);
        serverThread.start();
    }

    private static Case hl7Case(String message, String type, String trigger) {
        return hl7Case(message, type, trigger, List.of("MSH", "EVN", "PID", "PV1"));
    }

    private static Case hl7Case(String message, String type, String trigger, List<String> segments) {
        return new Case(CASE_ID, "ADT-A01", "入院", type, trigger, message, "AA", segments,
                "ACTIVE", 0, "system", Instant.now(), Instant.now());
    }

    private static Endpoint endpoint(int port) {
        return new Endpoint(ENDPOINT_ID, "TEST", "测试端点", "127.0.0.1", port, 1500, null,
                "ACTIVE", 0, "system", Instant.now(), Instant.now());
    }
}
