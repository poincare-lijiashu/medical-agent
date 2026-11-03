package com.medassist.material.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.security.Masks;
import com.medassist.common.time.BusinessTime;
import com.medassist.material.domain.MaterialModel.ConsumableIssueItem;
import com.medassist.material.domain.MaterialModel.ConsumableTrace;
import com.medassist.material.repo.MaterialRepository;
import com.medassist.material.web.MaterialDtos.CreateTraceReq;
import com.medassist.material.web.MaterialDtos.RecallView;
import com.medassist.material.web.MaterialDtos.TraceView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 高值/植入耗材使用追溯与批次召回（G3 的合规核心）。
 *
 * <p>追溯链条：出库明细（批次、数量）→ 使用登记（患者、手术、数量）→ 批次召回（影响面）。
 *
 * <p>为什么追溯不扣库存：库存已在出库时扣减；如果追溯再扣一次，同一条耗材会被扣两次，
 * 召回时"出库量 / 使用量 / 库存量"三者永远对不平。这里只做数量上限校验，
 * 保证 {@code sum(trace.quantity) <= issue_item.quantity}。
 */
@Service
public class ConsumableTraceService {

    private static final int RECALL_TRACE_LIMIT = 500;

    private final MaterialRepository repo;
    private final ObjectMapper objectMapper;

    public ConsumableTraceService(MaterialRepository repo, ObjectMapper objectMapper) {
        this.repo = repo;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public TraceView register(CreateTraceReq req, String actor) {
        ConsumableIssueItem item = repo.findIssueItem(req.issueItemId())
                .orElseThrow(() -> new BusinessException(ErrorCode.CONSUMABLE_ISSUE_ITEM_NOT_FOUND));
        LocalDate today = BusinessTime.today();
        if (item.expireDate() != null && item.expireDate().isBefore(today)) {
            throw new BusinessException(ErrorCode.CONSUMABLE_BATCH_EXPIRED,
                    "批号 " + item.batchNo() + " 已过期（效期 " + item.expireDate()
                            + "），禁止用于患者");
        }
        if (req.quantity() > item.untraced()) {
            throw new BusinessException(ErrorCode.CONSUMABLE_TRACE_EXCEEDS,
                    "耗材 " + item.consumableName() + " 未追溯数量仅 " + item.untraced()
                            + "，本次登记 " + req.quantity());
        }
        // 数据库级守卫：并发登记时只有一方能通过 traced_quantity + qty <= quantity
        if (!repo.addTracedQuantity(item.id(), req.quantity())) {
            throw new BusinessException(ErrorCode.CONSUMABLE_TRACE_EXCEEDS,
                    "耗材 " + item.consumableName() + " 的未追溯数量已被其他登记占用，请刷新后重试");
        }

        ConsumableTrace trace = new ConsumableTrace(UUID.randomUUID(), item.id(),
                item.consumableId(), item.consumableCode(), item.consumableName(),
                item.batchNo(), item.expireDate(), req.quantity(), req.patientId(),
                req.patientNo().trim(), req.visitId(), req.surgeryId(), actor,
                req.usedAt() == null ? Instant.now() : req.usedAt(),
                blankToNull(req.remark()), Instant.now());
        repo.insertTrace(trace);

        // 审计只留掩码患者号：审计表长期留存，不需要再复制一份 PHI
        audit(actor, "CONSUMABLE_TRACE", trace.id().toString(), detail()
                .put("consumableCode", trace.consumableCode())
                .put("batchNo", trace.batchNo())
                .put("quantity", trace.quantity())
                .put("patientNo", Masks.patientNo(trace.patientNo()))
                .put("surgeryId", trace.surgeryId() == null ? null : trace.surgeryId().toString())
                .build());
        return new TraceView(trace);
    }

    public List<ConsumableTrace> list(String batchNo, String patientNo, UUID consumableId,
                                       Instant from, Instant to, Integer limit) {
        int size = limit == null || limit <= 0 ? 100 : Math.min(limit, 500);
        return repo.listTraces(batchNo, patientNo, consumableId, from, to, size);
    }

    /** 批次召回：统计影响面并给出明细（最多 500 条，更多请按时间窗分段）。 */
    public RecallView recall(String batchNo) {
        String normalized = batchNo == null ? "" : batchNo.trim();
        if (normalized.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "召回必须指定批号");
        }
        MaterialRepository.RecallStats stats = repo.recallStats(normalized);
        List<ConsumableTrace> traces = repo.listTraces(normalized, null, null, null, null,
                RECALL_TRACE_LIMIT);
        return new RecallView(normalized, stats.traceCount(), stats.totalQuantity(),
                stats.patientCount(), stats.surgeryCount(), traces);
    }

    private String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private Detail detail() {
        return new Detail();
    }

    private final class Detail {
        private final Map<String, Object> map = new LinkedHashMap<>();

        Detail put(String key, Object value) {
            map.put(key, value);
            return this;
        }

        String build() {
            try {
                return objectMapper.writeValueAsString(map);
            } catch (JacksonException e) {
                return "{\"_detail_serialize_failed\":true}";
            }
        }
    }

    private void audit(String actor, String action, String targetId, String detailJson) {
        repo.logChange(actor, action, "CONSUMABLE_TRACE", targetId, detailJson);
    }
}
