package com.medassist.material.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.time.BusinessTime;
import com.medassist.material.domain.MaterialModel.Consumable;
import com.medassist.material.domain.MaterialModel.ConsumableBatch;
import com.medassist.material.domain.MaterialModel.ConsumableIssue;
import com.medassist.material.domain.MaterialModel.ConsumableIssueItem;
import com.medassist.material.repo.MaterialRepository;
import com.medassist.material.web.MaterialDtos.CreateIssueReq;
import com.medassist.material.web.MaterialDtos.IssueLine;
import com.medassist.material.web.MaterialDtos.IssueView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 耗材出库（库房 → 科室 / 手术间 / 门诊）。
 *
 * <p>出库是库存唯一的扣减入口：追溯登记不再扣库存，只校验"已出库未追溯数量"，
 * 这样"批次 → 出库 → 患者"三者的数量始终能对上。
 *
 * <p>幂等：同一科室同一 {@code requestId} 只生成一张出库单，且不会重复扣库存。
 */
@Service
public class ConsumableIssueService {

    private final MaterialRepository repo;
    private final ObjectMapper objectMapper;

    public ConsumableIssueService(MaterialRepository repo, ObjectMapper objectMapper) {
        this.repo = repo;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public IssueView issue(CreateIssueReq req, String actor) {
        String requestId = req.requestId().trim();
        Optional<ConsumableIssue> existing = repo.findIssueByRequestId(req.deptId(), requestId);
        if (existing.isPresent()) {
            return view(existing.get());
        }

        Set<UUID> seen = new HashSet<>();
        for (IssueLine line : req.items()) {
            if (!seen.add(line.consumableId())) {
                throw new BusinessException(ErrorCode.CONSUMABLE_ISSUE_INVALID,
                        "出库明细存在重复耗材，请合并为一行后再提交");
            }
        }

        UUID issueId = UUID.randomUUID();
        ConsumableIssue header = new ConsumableIssue(issueId, repo.nextIssueNo(), req.deptId(),
                blankToNull(req.deptName()), req.purpose(), requestId, actor,
                blankToNull(req.remark()), BigDecimal.ZERO, Instant.now());
        if (!repo.insertIssue(header)) {
            // 并发下另一个请求先落库：幂等返回已存在的出库单，不重复扣库存
            ConsumableIssue raced = repo.findIssueByRequestId(req.deptId(), requestId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.CONFLICT,
                            "出库单写入冲突，请刷新后重试"));
            return view(raced);
        }

        LocalDate today = BusinessTime.today();
        List<ConsumableIssueItem> items = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (IssueLine line : req.items()) {
            Consumable consumable = repo.findConsumable(line.consumableId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.CONSUMABLE_NOT_FOUND));
            if (!consumable.enabled()) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "耗材已停用，不能出库：" + consumable.name());
            }
            int remaining = line.quantity();
            for (ConsumableBatch batch : repo.listAvailableBatches(consumable.id(), today)) {
                if (remaining <= 0) {
                    break;
                }
                int take = Math.min(remaining, batch.quantity());
                if (!repo.deductBatch(batch.id(), take)) {
                    throw new BusinessException(ErrorCode.CONSUMABLE_STOCK_INSUFFICIENT,
                            "耗材 " + consumable.name() + " 批号 " + batch.batchNo()
                                    + " 余量不足，请刷新后重试");
                }
                BigDecimal amount = batch.unitPrice().multiply(BigDecimal.valueOf(take))
                        .setScale(2, RoundingMode.HALF_UP);
                items.add(new ConsumableIssueItem(UUID.randomUUID(), issueId, consumable.id(),
                        consumable.code(), consumable.name(), batch.id(), batch.batchNo(),
                        batch.expireDate(), take, 0, batch.unitPrice(), amount));
                total = total.add(amount);
                remaining -= take;
            }
            if (remaining > 0) {
                // 事务回滚：前面已扣的批次会一起恢复，不会出现"扣了一半就失败"
                throw new BusinessException(ErrorCode.CONSUMABLE_STOCK_INSUFFICIENT,
                        "耗材 " + consumable.name() + " 库存不足：需求 " + line.quantity()
                                + "，可用 " + (line.quantity() - remaining));
            }
        }

        items.forEach(repo::insertIssueItem);
        repo.updateIssueTotal(issueId, total);

        audit(actor, "CONSUMABLE_ISSUE", issueId.toString(), detail()
                .put("issueNo", header.issueNo())
                .put("deptId", req.deptId().toString())
                .put("deptName", header.deptName())
                .put("purpose", header.purpose())
                .put("lines", items.size())
                .put("totalAmount", total.toPlainString())
                .build());
        return new IssueView(new ConsumableIssue(issueId, header.issueNo(), header.deptId(),
                header.deptName(), header.purpose(), requestId, actor, header.remark(), total,
                header.createdAt()), items);
    }

    public List<IssueView> list(UUID deptId, Instant from, Instant to, Integer limit) {
        int size = limit == null || limit <= 0 ? 100 : Math.min(limit, 200);
        return repo.listIssues(deptId, from, to, size).stream().map(this::view).toList();
    }

    public IssueView detail(UUID issueId) {
        ConsumableIssue issue = repo.findIssue(issueId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CONSUMABLE_ISSUE_NOT_FOUND));
        return view(issue);
    }

    private IssueView view(ConsumableIssue issue) {
        return new IssueView(issue, repo.listIssueItems(issue.id()));
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
        repo.logChange(actor, action, "CONSUMABLE_ISSUE", targetId, detailJson);
    }
}
