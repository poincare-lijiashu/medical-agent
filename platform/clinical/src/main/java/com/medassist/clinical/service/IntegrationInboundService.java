package com.medassist.clinical.service;

import com.medassist.clinical.domain.IntegrationInboundMessage;
import com.medassist.clinical.integration.InboundMessageProcessor;
import com.medassist.clinical.integration.IntegrationMetrics;
import com.medassist.clinical.repo.ClinicalRepository;
import com.medassist.clinical.repo.IntegrationInboundRepository;
import com.medassist.clinical.web.IntegrationDtos.InboundAcceptReq;
import com.medassist.clinical.web.IntegrationDtos.InboundAcceptResult;
import com.medassist.clinical.web.IntegrationDtos.InboundMessageView;
import com.medassist.clinical.web.IntegrationDtos.InboundSummary;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 入站集成消息接收（第 258 批）。
 *
 * <p>核心语义：
 * <ul>
 *   <li>先落库再处理：即使处理器失败，原始报文仍在，可修复后重放；</li>
 *   <li>幂等由唯一索引兜底：同号同文回 AA（对端停止重推），同号不同文回 409；</li>
 *   <li>没有处理器时保持 RECEIVED，不伪造 PROCESSED；</li>
 *   <li>入站与出站共用审计与指标，形成完整证据链。</li>
 * </ul>
 */
@Service
public class IntegrationInboundService {

    private static final int LIST_DEFAULT = 50;
    private static final int LIST_MAX = 200;
    private static final int ERROR_MAX = 1000;

    private final IntegrationInboundRepository repo;
    private final ClinicalRepository clinicalRepo;
    private final IntegrationMetrics metrics;
    private final List<InboundMessageProcessor> processors;
    private final ObjectMapper objectMapper;

    public IntegrationInboundService(IntegrationInboundRepository repo,
                                     ClinicalRepository clinicalRepo,
                                     IntegrationMetrics metrics,
                                     List<InboundMessageProcessor> processors,
                                     ObjectMapper objectMapper) {
        this.repo = repo;
        this.clinicalRepo = clinicalRepo;
        this.metrics = metrics;
        this.processors = processors == null ? List.of() : processors;
        this.objectMapper = objectMapper;
    }

    public InboundAcceptResult accept(InboundAcceptReq req, String actor) {
        String systemCode = normalizeCode(req.systemCode());
        String externalId = requireText(req.externalId(), "消息控制号不能为空", 128);
        String payload = req.payload();
        if (payload == null || payload.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "报文内容不能为空");
        }
        String hash = sha256(payload);
        IntegrationInboundMessage message = new IntegrationInboundMessage(UUID.randomUUID(),
                systemCode, req.messageType().trim(), externalId, trimToNull(req.businessKey()),
                payload, hash, IntegrationInboundMessage.RECEIVED, null, actor, null,
                Instant.now(), 0L);

        try {
            repo.insert(message);
        } catch (DataIntegrityViolationException e) {
            IntegrationInboundMessage existing = repo.findByExternalId(systemCode, externalId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.CONFLICT,
                            "消息控制号冲突，且无法读取已有记录，请稍后重试"));
            if (existing.payloadHash().equals(hash)) {
                // 同号同文：幂等重复，回 AA 让对端停止重推
                metrics.inbound("duplicate", systemCode);
                audit(actor, "INTEGRATION_INBOUND_DUPLICATE", existing.id(),
                        "system=" + systemCode + ",externalId=" + externalId);
                return new InboundAcceptResult(existing.id(), existing.status(), true, "AA",
                        externalId);
            }
            metrics.inbound("conflict", systemCode);
            throw new BusinessException(ErrorCode.CONFLICT,
                    "同一消息控制号但报文内容不同，拒绝覆盖（externalId=" + externalId + "）");
        }

        metrics.inbound("accepted", systemCode);
        IntegrationInboundMessage processed = process(message);
        audit(actor, "INTEGRATION_INBOUND", processed.id(),
                "system=" + systemCode + ",type=" + processed.messageType()
                        + ",status=" + processed.status());
        String ack = IntegrationInboundMessage.FAILED.equals(processed.status()) ? "AE" : "AA";
        return new InboundAcceptResult(processed.id(), processed.status(), false, ack, externalId);
    }

    public List<InboundMessageView> list(String status, String systemCode, Integer limit) {
        int n = limit == null ? LIST_DEFAULT : Math.max(1, Math.min(limit, LIST_MAX));
        String normalizedStatus = status == null || status.isBlank()
                ? null : status.trim().toUpperCase();
        String normalizedSystem = systemCode == null || systemCode.isBlank()
                ? null : normalizeCode(systemCode);
        return repo.list(normalizedStatus, normalizedSystem, n).stream()
                .map(IntegrationInboundService::view)
                .toList();
    }

    public InboundSummary summary() {
        int received = 0;
        int processed = 0;
        int failed = 0;
        for (IntegrationInboundRepository.StatusCount row : repo.countByStatus()) {
            switch (row.status()) {
                case IntegrationInboundMessage.RECEIVED -> received = row.count();
                case IntegrationInboundMessage.PROCESSED -> processed = row.count();
                case IntegrationInboundMessage.FAILED -> failed = row.count();
                default -> {
                    // 未知状态不计入
                }
            }
        }
        return new InboundSummary(received, processed, failed, received + processed + failed);
    }

    // ---------------------------------------------------------------- 内部

    /** 有处理器就同步处理；没有处理器保持 RECEIVED，等待人工/后续批处理。 */
    private IntegrationInboundMessage process(IntegrationInboundMessage message) {
        for (InboundMessageProcessor processor : processors) {
            if (!processor.supports(message.systemCode(), message.messageType())) {
                continue;
            }
            try {
                processor.process(message);
                repo.markProcessed(message.id(), IntegrationInboundMessage.PROCESSED, null,
                        message.version());
                return repo.findById(message.id()).orElse(message);
            } catch (RuntimeException e) {
                String error = truncate(e.getMessage() == null
                        ? e.getClass().getSimpleName() : e.getMessage());
                repo.markProcessed(message.id(), IntegrationInboundMessage.FAILED, error,
                        message.version());
                metrics.inbound("failed", message.systemCode());
                return repo.findById(message.id()).orElse(message);
            }
        }
        return message;
    }

    private static String normalizeCode(String value) {
        String code = value == null ? "" : value.trim().toUpperCase();
        if (code.isEmpty() || code.length() > 32) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "对端系统编码不合法");
        }
        return code;
    }

    private static String requireText(String value, String message, int max) {
        String text = value == null ? "" : value.trim();
        if (text.isEmpty() || text.length() > max) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, message);
        }
        return text;
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String truncate(String value) {
        return value.length() <= ERROR_MAX ? value : value.substring(0, ERROR_MAX);
    }

    private static String sha256(String payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static InboundMessageView view(IntegrationInboundMessage m) {
        String payload = m.payload() == null ? "" : m.payload();
        String preview = payload.length() <= 200 ? payload : payload.substring(0, 200) + "…";
        return new InboundMessageView(m.id(), m.systemCode(), m.messageType(), m.externalId(),
                m.businessKey(), m.status(), m.error(), m.receivedBy(), m.processedAt(),
                m.createdAt(), preview);
    }

    private void audit(String actor, String action, UUID id, String detail) {
        try {
            clinicalRepo.logChange(actor, action, "INTEGRATION_INBOUND", id.toString(),
                    objectMapper.writeValueAsString(Map.of("detail", detail)));
        } catch (JacksonException e) {
            clinicalRepo.logChange(actor, action, "INTEGRATION_INBOUND", id.toString(),
                    "{\"_detail_serialize_failed\":true}");
        }
    }
}
