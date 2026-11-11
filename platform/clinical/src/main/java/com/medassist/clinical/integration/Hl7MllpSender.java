package com.medassist.clinical.integration;

import com.medassist.clinical.domain.IntegrationMessage;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

/**
 * HL7 v2 over MLLP 发送器（第 257 批）。
 *
 * <p>为什么先做 MLLP：国内医院院内集成的主流仍是 HL7 v2（ADT/ORM/ORU）经 MLLP 传输，
 * FHIR 更多用于区域/互联网侧。先把"最普遍的那条线"跑通，比只做漂亮的 FHIR Demo 更可用。
 *
 * <p>协议帧：{@code 0x0B} + 报文 + {@code 0x1C 0x0D}；对端 ACK 必须含 {@code MSA|AA}
 * 或 {@code MSA|CA}，否则视为拒绝并进入重试。回执里的消息控制号作为 external_id 落库。
 */
@Component
public class Hl7MllpSender implements IntegrationConfig.IntegrationSender {

    private static final byte START = 0x0B;
    private static final byte END = 0x1C;
    private static final byte CR = 0x0D;
    private static final int MAX_ACK_BYTES = 64 * 1024;

    private final IntegrationProperties properties;

    public Hl7MllpSender(IntegrationProperties properties) {
        this.properties = properties;
    }

    @Override
    public String send(IntegrationMessage message) {
        IntegrationProperties.Endpoint endpoint = endpoint(message.systemCode());
        int timeout = endpoint.timeoutMs() == null
                ? properties.sendTimeoutMs() : endpoint.timeoutMs();
        String ack = exchange(endpoint.host().trim(), endpoint.port(), timeout, message.payload());
        return ackId(message.systemCode(), ack);
    }

    private IntegrationProperties.Endpoint endpoint(String systemCode) {
        String code = systemCode == null ? "" : systemCode.trim().toUpperCase();
        IntegrationProperties.Endpoint endpoint = properties.systems().get(code);
        if (endpoint == null || endpoint.host() == null || endpoint.host().isBlank()
                || endpoint.port() == null || endpoint.port() <= 0) {
            throw new IntegrationSendException(
                    "未配置 " + code + " 的 HL7 接入地址"
                            + "（medassist.integration.systems." + code + ".host/port）");
        }
        return endpoint;
    }

    private String exchange(String host, int port, int timeout, String payload) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeout);
            socket.setSoTimeout(timeout);
            OutputStream out = socket.getOutputStream();
            out.write(START);
            out.write(payload.getBytes(StandardCharsets.UTF_8));
            out.write(END);
            out.write(CR);
            out.flush();

            String ack = readFrame(socket.getInputStream());
            if (ack.isBlank()) {
                throw new IntegrationSendException("对端未返回 ACK（" + host + ":" + port + "）");
            }
            return ack;
        } catch (SocketTimeoutException e) {
            throw new IntegrationSendException(
                    "HL7 发送超时（" + host + ":" + port + "，" + timeout + "ms）", e);
        } catch (IOException e) {
            throw new IntegrationSendException(
                    "HL7 发送失败（" + host + ":" + port + "）：" + e.getMessage(), e);
        }
    }

    /** 读到 MLLP 结束帧为止；对端用 \r 或 \n 结尾都能容忍。 */
    private String readFrame(InputStream in) throws IOException {
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        boolean sawEnd = false;
        int b;
        while ((b = in.read()) != -1) {
            if (b == END) {
                sawEnd = true;
                continue;
            }
            if (sawEnd && b == CR) {
                break;
            }
            if (sawEnd) {
                sawEnd = false;
            }
            if (b != START) {
                frame.write(b);
            }
            if (frame.size() > MAX_ACK_BYTES) {
                throw new IntegrationSendException("对端 ACK 超过 " + MAX_ACK_BYTES + " 字节，疑似非 HL7 对端");
            }
        }
        return frame.toString(StandardCharsets.UTF_8);
    }

    /**
     * 校验 ACK 并取出回执号。
     *
     * @return MSA-3 消息控制号；对端未回填时返回 "ACK"
     */
    private String ackId(String systemCode, String ack) {
        String msa = null;
        for (String segment : ack.split("\\r|\\n")) {
            if (segment.startsWith("MSA|")) {
                msa = segment;
                break;
            }
        }
        if (msa == null) {
            throw new IntegrationSendException(
                    systemCode + " 返回的 ACK 缺少 MSA 段：" + preview(ack));
        }
        String[] fields = msa.split("\\|", -1);
        String code = fields.length > 1 ? fields[1] : "";
        if (!"AA".equals(code) && !"CA".equals(code)) {
            throw new IntegrationSendException(
                    systemCode + " 拒绝消息（MSA|" + code + "）：" + preview(msa));
        }
        return fields.length > 2 && !fields[2].isBlank() ? fields[2].trim() : "ACK";
    }

    private static String preview(String text) {
        String oneLine = text.replace('\r', ' ').replace('\n', ' ').trim();
        return oneLine.length() <= 200 ? oneLine : oneLine.substring(0, 200) + "…";
    }
}
