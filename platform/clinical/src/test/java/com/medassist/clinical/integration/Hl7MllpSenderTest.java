package com.medassist.clinical.integration;

import com.medassist.clinical.domain.IntegrationMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * HL7 MLLP 发送器：真实 socket 帧、ACK 校验与错误分支。
 */
class Hl7MllpSenderTest {

    @Test
    @DisplayName("MLLP：写入 0x0B/0x1C0x0D 帧，读取 ACK 并返回 MSA-3 回执号")
    void sendsFrameAndReturnsAckId() throws Exception {
        String ack = "MSH|^~\\&|LIS|HOSP|20261111||ACK|1|P|2.5\rMSA|AA|CTRL-9\r";
        try (ServerSocket server = new ServerSocket(0)) {
            CompletableFuture<byte[]> received = CompletableFuture.supplyAsync(
                    () -> serveOnce(server, ack));
            Hl7MllpSender sender = senderFor(server.getLocalPort());

            String id = sender.send(message());

            assertThat(id).isEqualTo("CTRL-9");
            byte[] frame = received.get(3, TimeUnit.SECONDS);
            assertThat(frame[0]).isEqualTo((byte) 0x0B);
            assertThat(frame[frame.length - 2]).isEqualTo((byte) 0x1C);
            assertThat(frame[frame.length - 1]).isEqualTo((byte) 0x0D);
            assertThat(new String(frame, StandardCharsets.UTF_8)).contains("MSH|^~\\&|HIS");
        }
    }

    @Test
    @DisplayName("MLLP：对端 MSA|AE 视为拒绝并抛出（进入重试）")
    void rejectsNack() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            CompletableFuture.runAsync(() -> serveOnce(server,
                    "MSH|^~\\&|LIS\rMSA|AE|E1|Patient not found\r"));
            Hl7MllpSender sender = senderFor(server.getLocalPort());

            assertThatThrownBy(() -> sender.send(message()))
                    .isInstanceOf(IntegrationSendException.class)
                    .hasMessageContaining("拒绝消息");
        }
    }

    @Test
    @DisplayName("未配置对端地址：明确报错，不伪造成功")
    void unconfiguredSystemFails() {
        Hl7MllpSender sender = new Hl7MllpSender(new IntegrationProperties(
                Map.of(), 8, 30, 1800, 5000));

        assertThatThrownBy(() -> sender.send(message()))
                .isInstanceOf(IntegrationSendException.class)
                .hasMessageContaining("未配置 HIS 的 HL7 接入地址");
    }

    private static Hl7MllpSender senderFor(int port) {
        return new Hl7MllpSender(new IntegrationProperties(
                Map.of("HIS", new IntegrationProperties.Endpoint("127.0.0.1", port, 2000)),
                8, 30, 1800, 2000));
    }

    private static IntegrationMessage message() {
        return new IntegrationMessage(UUID.randomUUID(), "HIS", "ADT_A01", "P001",
                "MSH|^~\\&|HIS|HOSP|20261111||ADT^A01|MSG1|P|2.5\rPID|1||P001\r",
                IntegrationMessage.SENDING, 0, Instant.now(), null, null,
                "his01", null, Instant.now(), 1L);
    }

    private static byte[] serveOnce(ServerSocket server, String ack) {
        try (Socket socket = server.accept()) {
            InputStream in = socket.getInputStream();
            ByteArrayOutputStream frame = new ByteArrayOutputStream();
            boolean sawEnd = false;
            int b;
            while ((b = in.read()) != -1) {
                frame.write(b);
                if (b == 0x1C) {
                    sawEnd = true;
                } else if (sawEnd && b == 0x0D) {
                    break;
                } else if (sawEnd) {
                    sawEnd = false;
                }
            }
            OutputStream out = socket.getOutputStream();
            out.write(0x0B);
            out.write(ack.getBytes(StandardCharsets.UTF_8));
            out.write(0x1C);
            out.write(0x0D);
            out.flush();
            return frame.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
