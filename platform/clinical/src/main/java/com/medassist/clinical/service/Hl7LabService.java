package com.medassist.clinical.service;

import com.medassist.clinical.domain.Hl7LabModel.Case;
import com.medassist.clinical.domain.Hl7LabModel.Dashboard;
import com.medassist.clinical.domain.Hl7LabModel.Endpoint;
import com.medassist.clinical.domain.Hl7LabModel.Run;
import com.medassist.clinical.repo.Hl7LabRepository;
import com.medassist.clinical.web.Hl7LabDtos;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * HL7 v2 联调测试台（§301）。
 *
 * <p>执行链路：本地结构校验 → MLLP 发送 → 解析 MSA → 与期望 ACK 比对 → 落执行记录。
 * 校验不通过不发报文；发送失败记 ERROR；ACK 码不符记 FAILED。
 *
 * <p>安全边界：测试用例应使用合成患者；报文摘要只保留前 2000 字符，日志不打印完整报文。
 */
@Service
public class Hl7LabService {

    private static final byte START = 0x0B;
    private static final byte END = 0x1C;
    private static final byte CR = 0x0D;
    private static final int MAX_ACK_BYTES = 64 * 1024;
    private static final int EXCERPT_LIMIT = 2000;

    private final Hl7LabRepository repo;

    public Hl7LabService(Hl7LabRepository repo) {
        this.repo = repo;
    }

    // ------------------------------------------------------------------ 端点

    public List<Endpoint> listEndpoints(boolean onlyActive) {
        return repo.listEndpoints(onlyActive);
    }

    public Endpoint endpoint(UUID id) {
        return repo.findEndpoint(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.HL7_ENDPOINT_NOT_FOUND));
    }

    public Endpoint upsertEndpoint(Hl7LabDtos.EndpointReq req, String actor) {
        requireActor(actor);
        if (req.port() < 1 || req.port() > 65535) {
            throw new BusinessException(ErrorCode.HL7_ENDPOINT_INVALID, "端口必须在 1–65535 之间");
        }
        Instant now = Instant.now();
        Endpoint endpoint = new Endpoint(UUID.randomUUID(), req.code().trim(), req.name().trim(),
                req.host().trim(), req.port(), req.timeoutMs() == null ? 5000 : req.timeoutMs(),
                blankToNull(req.description()), req.status() == null ? "ACTIVE" : req.status(),
                0, actor, now, now);
        repo.upsertEndpoint(endpoint);
        Endpoint saved = repo.findEndpointByCode(req.code()).orElseThrow();
        repo.audit(actor, "HL7_ENDPOINT_UPSERT", "hl7_endpoint", saved.code(),
                Map.of("host", saved.host(), "port", saved.port(), "status", saved.status()));
        return saved;
    }

    public Endpoint changeEndpointStatus(UUID id, String status, String actor) {
        requireActor(actor);
        Endpoint endpoint = endpoint(id);
        if (!status.equals(endpoint.status()) && !repo.updateEndpointStatus(id, status)) {
            throw new BusinessException(ErrorCode.HL7_ENDPOINT_NOT_FOUND);
        }
        repo.audit(actor, "HL7_ENDPOINT_STATUS", "hl7_endpoint", endpoint.code(),
                Map.of("status", status));
        return endpoint(id);
    }

    // ------------------------------------------------------------------ 用例

    public List<Case> listCases(boolean onlyActive) {
        return repo.listCases(onlyActive);
    }

    public Case hl7Case(UUID id) {
        return repo.findCase(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.HL7_CASE_NOT_FOUND));
    }

    public Case upsertCase(Hl7LabDtos.CaseReq req, String actor) {
        requireActor(actor);
        List<String> segments = req.requiredSegments() == null || req.requiredSegments().isEmpty()
                ? List.of("MSH") : req.requiredSegments().stream()
                .filter(s -> s != null && !s.isBlank())
                .map(s -> s.trim().toUpperCase(Locale.ROOT)).distinct().toList();
        if (!segments.contains("MSH")) {
            throw new BusinessException(ErrorCode.HL7_CASE_INVALID, "必填段必须包含 MSH");
        }
        Instant now = Instant.now();
        Case hl7Case = new Case(UUID.randomUUID(), req.code().trim(), req.name().trim(),
                req.messageType().trim().toUpperCase(Locale.ROOT),
                req.triggerEvent().trim().toUpperCase(Locale.ROOT), req.sampleMessage(),
                req.expectedAckCode(), segments,
                req.status() == null ? "ACTIVE" : req.status(), 0, actor, now, now);
        repo.upsertCase(hl7Case);
        Case saved = repo.findCaseByCode(req.code()).orElseThrow();
        repo.audit(actor, "HL7_CASE_UPSERT", "hl7_case", saved.code(),
                Map.of("messageType", saved.messageType(), "trigger", saved.triggerEvent(),
                        "expectedAck", saved.expectedAckCode()));
        return saved;
    }

    public Case changeCaseStatus(UUID id, String status, String actor) {
        requireActor(actor);
        Case hl7Case = hl7Case(id);
        if (!status.equals(hl7Case.status()) && !repo.updateCaseStatus(id, status)) {
            throw new BusinessException(ErrorCode.HL7_CASE_NOT_FOUND);
        }
        repo.audit(actor, "HL7_CASE_STATUS", "hl7_case", hl7Case.code(),
                Map.of("status", status));
        return hl7Case(id);
    }

    // ------------------------------------------------------------------ 执行

    public Run run(UUID caseId, UUID endpointId, String actor) {
        requireActor(actor);
        Case hl7Case = hl7Case(caseId);
        Endpoint endpoint = endpoint(endpointId);
        if (!"ACTIVE".equals(hl7Case.status())) {
            throw new BusinessException(ErrorCode.HL7_CASE_NOT_FOUND);
        }
        if (!"ACTIVE".equals(endpoint.status())) {
            throw new BusinessException(ErrorCode.HL7_ENDPOINT_NOT_FOUND);
        }
        Instant started = Instant.now();
        List<String> validation = validate(hl7Case);
        String status;
        String ackCode = null;
        String ackControl = null;
        String ackMessage = null;
        String response = null;
        String error = null;
        if (!validation.isEmpty()) {
            status = "ERROR";
            error = "报文校验未通过";
        } else {
            try {
                response = exchange(endpoint, hl7Case.sampleMessage());
                Ack ack = parseAck(response);
                ackCode = ack.code();
                ackControl = ack.controlId();
                ackMessage = ack.message();
                status = ack.code().equals(hl7Case.expectedAckCode()) ? "PASSED" : "FAILED";
            } catch (Exception e) {
                status = "ERROR";
                error = truncate(messageOf(e), 500);
            }
        }
        Instant finished = Instant.now();
        int duration = (int) Math.min(Duration.between(started, finished).toMillis(),
                Integer.MAX_VALUE);
        Run run = new Run(UUID.randomUUID(), repo.nextRunNo(), hl7Case.id(), hl7Case.code(),
                endpoint.id(), endpoint.code(), status, ackCode, ackControl, ackMessage,
                excerpt(hl7Case.sampleMessage()), excerpt(response),
                error, validation.isEmpty() ? null : String.join("；", validation), duration,
                actor, started, finished);
        repo.insertRun(run);
        repo.audit(actor, "HL7_RUN", "hl7_case", hl7Case.code(),
                Map.of("endpoint", endpoint.code(), "runNo", run.runNo(), "status", status,
                        "ackCode", ackCode == null ? "" : ackCode));
        return run;
    }

    /**
     * 本地结构校验：只查协议必要字段，不替代对方系统的业务校验。
     */
    public List<String> validate(Case hl7Case) {
        List<String> errors = new ArrayList<>();
        String[] lines = splitSegments(hl7Case.sampleMessage());
        if (lines.length == 0 || !lines[0].startsWith("MSH")) {
            errors.add("报文必须以 MSH 段开始");
            return errors;
        }
        String[] msh = lines[0].split("\\|", -1);
        if (msh.length < 10) {
            errors.add("MSH 字段不足（至少需要 MSH-9 消息类型与 MSH-10 控制号）");
            return errors;
        }
        String encoding = msh[1];
        if (encoding.length() < 3) {
            errors.add("MSH-1/MSH-2 编码字符不足（应形如 ^~\\&）");
        }
        String type = msh[8];
        String expectedType = hl7Case.messageType() + "^" + hl7Case.triggerEvent();
        if (!type.equalsIgnoreCase(expectedType)) {
            errors.add("MSH-9 消息类型为 " + type + "，与用例 " + expectedType + " 不一致");
        }
        if (msh[9].isBlank()) {
            errors.add("MSH-10 消息控制号不能为空");
        }
        for (String required : hl7Case.requiredSegments()) {
            boolean found = false;
            for (String line : lines) {
                String segment = line.contains("|") ? line.substring(0, line.indexOf('|')) : line;
                if (segment.equalsIgnoreCase(required)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                errors.add("缺少必填段 " + required);
            }
        }
        return errors;
    }

    // ------------------------------------------------------------------ ACK/MLLP

    private record Ack(String code, String controlId, String message) {
    }

    private Ack parseAck(String ack) {
        for (String segment : splitSegments(ack)) {
            if (!segment.startsWith("MSA")) {
                continue;
            }
            String[] fields = segment.split("\\|", -1);
            if (fields.length < 2 || fields[1].isBlank()) {
                throw new BusinessException(ErrorCode.HL7_ACK_INVALID, "ACK 的 MSA-1 为空");
            }
            String code = normalizeAck(fields[1].trim());
            if (!List.of("AA", "AE", "AR").contains(code)) {
                throw new BusinessException(ErrorCode.HL7_ACK_INVALID,
                        "ACK 码不合法：" + fields[1]);
            }
            String control = fields.length > 2 ? fields[2].trim() : "";
            return new Ack(code, control, segment);
        }
        throw new BusinessException(ErrorCode.HL7_ACK_INVALID, "ACK 缺少 MSA 段");
    }

    private static String normalizeAck(String code) {
        return switch (code.toUpperCase(Locale.ROOT)) {
            case "CA" -> "AA";
            case "CE" -> "AE";
            case "CR" -> "AR";
            default -> code.toUpperCase(Locale.ROOT);
        };
    }

    private String exchange(Endpoint endpoint, String message) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(endpoint.host(), endpoint.port()),
                    endpoint.timeoutMs());
            socket.setSoTimeout(endpoint.timeoutMs());
            OutputStream out = socket.getOutputStream();
            out.write(START);
            out.write(message.getBytes(StandardCharsets.UTF_8));
            out.write(END);
            out.write(CR);
            out.flush();
            String ack = readFrame(socket.getInputStream());
            if (ack.isBlank()) {
                throw new BusinessException(ErrorCode.HL7_ACK_INVALID,
                        "对端未返回 ACK（" + endpoint.host() + ":" + endpoint.port() + "）");
            }
            return ack;
        } catch (SocketTimeoutException e) {
            throw new BusinessException(ErrorCode.HL7_SEND_FAILED,
                    "HL7 发送超时（" + endpoint.host() + ":" + endpoint.port() + "，"
                            + endpoint.timeoutMs() + "ms）");
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.HL7_SEND_FAILED,
                    "HL7 发送失败（" + endpoint.host() + ":" + endpoint.port() + "）："
                            + e.getMessage());
        }
    }

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
                throw new BusinessException(ErrorCode.HL7_ACK_INVALID,
                        "对端 ACK 超过 " + MAX_ACK_BYTES + " 字节，疑似非 HL7 对端");
            }
        }
        return frame.toString(StandardCharsets.UTF_8);
    }

    private static String[] splitSegments(String message) {
        if (message == null || message.isBlank()) {
            return new String[0];
        }
        return message.replace("\r\n", "\r").replace('\n', '\r').split("\r");
    }

    // ------------------------------------------------------------------ 查询/看板

    public List<Run> listRuns(String caseCode, String status, int limit, int offset) {
        int size = Math.min(Math.max(limit, 1), 200);
        return repo.listRuns(caseCode, status, size, Math.max(offset, 0));
    }

    public long countRuns(String caseCode, String status) {
        return repo.countRuns(caseCode, status);
    }

    public Run run(UUID id) {
        return repo.findRun(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.HL7_RUN_NOT_FOUND));
    }

    public Dashboard dashboard() {
        Instant since = Instant.now().minus(Duration.ofHours(24));
        long runs = repo.countRunsSince(since, null);
        long passed = repo.countRunsSince(since, "PASSED");
        long failed = repo.countRunsSince(since, "FAILED");
        long errors = repo.countRunsSince(since, "ERROR");
        double rate = runs == 0 ? 0 : Math.round(passed * 10000.0 / runs) / 100.0;
        return new Dashboard(Instant.now(), repo.countEndpoints(true), repo.countCases(true),
                runs, passed, failed, errors, rate);
    }

    // ------------------------------------------------------------------ 工具

    private static void requireActor(String actor) {
        if (actor == null || actor.isBlank()) {
            throw new BusinessException(ErrorCode.HL7_ACTOR_REQUIRED);
        }
    }

    private static String messageOf(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String excerpt(String text) {
        if (text == null) {
            return null;
        }
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n').trim();
        return truncate(normalized, EXCERPT_LIMIT);
    }

    private static String truncate(String text, int limit) {
        if (text == null) {
            return null;
        }
        return text.length() <= limit ? text : text.substring(0, limit) + "…";
    }
}
