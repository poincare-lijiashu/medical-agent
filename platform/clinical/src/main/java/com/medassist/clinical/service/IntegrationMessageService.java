package com.medassist.clinical.service;

import com.medassist.clinical.domain.IntegrationMessage;
import com.medassist.clinical.integration.IntegrationConfig.IntegrationSender;
import com.medassist.clinical.integration.IntegrationProperties;
import com.medassist.clinical.integration.IntegrationSendException;
import com.medassist.clinical.repo.ClinicalRepository;
import com.medassist.clinical.repo.IntegrationRepository;
import com.medassist.clinical.web.IntegrationDtos.IntegrationEnqueueReq;
import com.medassist.clinical.web.IntegrationDtos.IntegrationMessageView;
import com.medassist.clinical.web.IntegrationDtos.IntegrationSummary;
import com.medassist.clinical.web.IntegrationDtos.SystemCount;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 出站集成消息台账与投递（第 257 批）。
 *
 * <p>解决的实际问题：接口"调不通"时，业务不能把数据丢掉，也不能阻塞用户操作。
 * 因此业务只 enqueue（本地事务落库即成功），投递交给后台 worker：
 * <ul>
 *   <li>失败指数退避重试，超过上限进入 DEAD，保留最后一次错误；</li>
 *   <li>多实例用 {@code FOR UPDATE SKIP LOCKED} 认领，不会重复发送；</li>
 *   <li>SENDING 超时（进程崩溃）会被重新认领，避免消息永久卡住；</li>
 *   <li>人工重投只允许 FAILED/DEAD，ACKED 不能重发（防止重复记账/重复签名）。</li>
 * </ul>
 *
 * <p>刻意不做：不在本类里解析 HL7 业务字段；不替业务判断"是否该发"。
 */
@Service
public class IntegrationMessageService {

    private static final int LIST_DEFAULT = 50;
    private static final int LIST_MAX = 200;
    private static final int ERROR_MAX = 1000;

    private final IntegrationRepository repo;
    private final ClinicalRepository clinicalRepo;
    private final IntegrationSender sender;
    private final IntegrationProperties properties;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;

    public IntegrationMessageService(IntegrationRepository repo, ClinicalRepository clinicalRepo,
                                     IntegrationSender sender, IntegrationProperties properties,
                                     ObjectMapper objectMapper,
                                     PlatformTransactionManager transactionManager) {
        this.repo = repo;
        this.clinicalRepo = clinicalRepo;
        this.sender = sender;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /** 落库一条待发消息：本地事务成功即视为"已接收"，投递由 worker 负责。 */
    public IntegrationMessageView enqueue(IntegrationEnqueueReq req, String actor) {
        String systemCode = normalizeCode(req.systemCode());
        String payload = req.payload() == null ? "" : req.payload().trim();
        if (payload.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "报文内容不能为空");
        }
        String businessKey = trimToNull(req.businessKey());
        IntegrationMessage message = new IntegrationMessage(UUID.randomUUID(), systemCode,
                req.messageType().trim(), businessKey, payload, IntegrationMessage.PENDING,
                0, Instant.now(), null, null, actor, null, Instant.now(), 0L);
        repo.insert(message);
        audit(actor, "INTEGRATION_ENQUEUE", message.id(),
                "system=" + systemCode + ",type=" + message.messageType()
                        + ",businessKey=" + businessKey);
        return view(message);
    }

    public List<IntegrationMessageView> list(String status, String systemCode, Integer limit) {
        int n = limit == null ? LIST_DEFAULT : Math.max(1, Math.min(limit, LIST_MAX));
        String normalizedStatus = status == null || status.isBlank()
                ? null : status.trim().toUpperCase();
        String normalizedSystem = systemCode == null || systemCode.isBlank()
                ? null : normalizeCode(systemCode);
        return repo.list(normalizedStatus, normalizedSystem, n).stream()
                .map(IntegrationMessageService::view)
                .toList();
    }

    public IntegrationSummary summary() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (IntegrationRepository.StatusCount row : repo.countByStatus()) {
            counts.put(row.status(), row.count());
        }
        int pending = counts.getOrDefault(IntegrationMessage.PENDING, 0)
                + counts.getOrDefault(IntegrationMessage.SENDING, 0);
        int failed = counts.getOrDefault(IntegrationMessage.FAILED, 0);
        int dead = counts.getOrDefault(IntegrationMessage.DEAD, 0);
        int acked = counts.getOrDefault(IntegrationMessage.ACKED, 0);

        Map<String, int[]> bySystem = new LinkedHashMap<>();
        for (IntegrationRepository.SystemStatusCount row : repo.countBySystem()) {
            int[] bucket = bySystem.computeIfAbsent(row.systemCode(), k -> new int[3]);
            switch (row.status()) {
                case IntegrationMessage.PENDING, IntegrationMessage.SENDING -> bucket[0] += row.count();
                case IntegrationMessage.FAILED -> bucket[1] += row.count();
                case IntegrationMessage.DEAD -> bucket[2] += row.count();
                default -> {
                    // ACKED 不计入待处理
                }
            }
        }
        List<SystemCount> rows = new ArrayList<>(bySystem.size());
        bySystem.forEach((system, bucket) ->
                rows.add(new SystemCount(system, bucket[0], bucket[1], bucket[2])));
        return new IntegrationSummary(pending, failed, dead, acked, List.copyOf(rows));
    }

    /**
     * 人工重投。
     *
     * <p>ACKED 不允许重投：同一条 HL7 重复发送可能造成重复记账或重复签名，
     * 需要重发时应该由业务生成新的消息（新的 business_key/type）。
     */
    public IntegrationMessageView retry(UUID id, String actor) {
        IntegrationMessage message = repo.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "集成消息不存在"));
        if (!message.retryable()) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "只有失败或已终止的消息可以重投，当前状态：" + message.status());
        }
        if (!repo.resetForRetry(id, message.version())) {
            throw new BusinessException(ErrorCode.CONFLICT, "消息状态已被他人修改，请刷新后重试");
        }
        audit(actor, "INTEGRATION_RETRY", id,
                "system=" + message.systemCode() + ",previous=" + message.status());
        return repo.findById(id).map(IntegrationMessageService::view)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "集成消息不存在"));
    }

    /**
     * 投递一批到期消息。
     *
     * @return 成功确认条数
     */
    public int dispatchDue(int batch) {
        List<IntegrationMessage> claimed = claim(batch);
        int acked = 0;
        for (IntegrationMessage message : claimed) {
            try {
                String externalId = sender.send(message);
                if (repo.markAcked(message.id(), externalId, message.version())) {
                    acked++;
                }
            } catch (RuntimeException e) {
                handleFailure(message, e);
            }
        }
        return acked;
    }

    // ---------------------------------------------------------------- 内部

    /** 认领与发送分离：事务只包住加锁与状态更新，网络调用不在事务里。 */
    private List<IntegrationMessage> claim(int batch) {
        List<IntegrationMessage> claimed = tx.execute(status -> {
            List<UUID> ids = repo.lockDueIds(batch);
            if (ids.isEmpty()) {
                return List.<IntegrationMessage>of();
            }
            repo.markSending(ids);
            return repo.findByIds(ids);
        });
        return claimed == null ? List.of() : claimed;
    }

    private void handleFailure(IntegrationMessage message, RuntimeException error) {
        int attempts = message.attempts() + 1;
        boolean dead = attempts >= properties.maxAttempts();
        Instant nextRetryAt = dead ? Instant.now() : Instant.now().plusSeconds(backoffSeconds(attempts));
        String reason = error instanceof IntegrationSendException
                ? error.getMessage()
                : error.getClass().getSimpleName() + ": " + error.getMessage();
        String truncated = reason == null ? "unknown" : (reason.length() > ERROR_MAX
                ? reason.substring(0, ERROR_MAX) : reason);
        repo.markFailed(message.id(), attempts, nextRetryAt, truncated, dead, message.version());
        if (dead) {
            audit(message.createdBy(), "INTEGRATION_DEAD", message.id(),
                    "system=" + message.systemCode() + ",attempts=" + attempts
                            + ",error=" + truncated);
        }
    }

    /** 指数退避：30s、60s、120s……上限 30 分钟。 */
    private long backoffSeconds(int attempts) {
        long base = properties.baseBackoffSeconds();
        long max = properties.maxBackoffSeconds();
        int shift = Math.min(Math.max(attempts - 1, 0), 20);
        long delay = base << shift;
        return delay <= 0 || delay > max ? max : delay;
    }

    private static String normalizeCode(String value) {
        String code = value == null ? "" : value.trim().toUpperCase();
        if (code.isEmpty() || code.length() > 32) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "对端系统编码不合法");
        }
        return code;
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static IntegrationMessageView view(IntegrationMessage m) {
        String payload = Objects.requireNonNullElse(m.payload(), "");
        String preview = payload.length() <= 200 ? payload : payload.substring(0, 200) + "…";
        return new IntegrationMessageView(m.id(), m.systemCode(), m.messageType(), m.businessKey(),
                m.status(), m.attempts(), m.nextRetryAt(), m.lastError(), m.externalId(),
                m.createdBy(), m.createdAt(), m.retryable(), preview);
    }

    private void audit(String actor, String action, UUID id, String detail) {
        try {
            String json = objectMapper.writeValueAsString(Map.of("detail", detail));
            clinicalRepo.logChange(actor, action, "INTEGRATION_MESSAGE", id.toString(), json);
        } catch (JacksonException e) {
            clinicalRepo.logChange(actor, action, "INTEGRATION_MESSAGE", id.toString(),
                    "{\"_detail_serialize_failed\":true}");
        }
    }
}
