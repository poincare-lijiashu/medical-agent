package com.medassist.auth.service;

import com.medassist.auth.repo.AuditRepository;
import com.medassist.auth.web.dto.AuditEventResp;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.api.PageResult;
import com.medassist.common.exception.BusinessException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * 审计查询服务：把各域 {@code change_log} 汇聚成分页视图。
 *
 * <p>约束：
 * <ul>
 *   <li>时间窗必须 from ≤ to，否则 422（避免前端传反导致空结果被误判为"无审计"）；</li>
 *   <li>分页大小强制 ≤ {@link PageResult#MAX_PAGE_SIZE}，防一次拉全表；</li>
 *   <li>本接口只读，不写任何审计（读审计本身不产生新事件，避免自我放大）。</li>
 * </ul>
 */
@Service
public class AuditService {

    private final AuditRepository repo;

    public AuditService(AuditRepository repo) {
        this.repo = repo;
    }

    public PageResult<AuditEventResp> search(String actor, String action, String targetType,
                                             String targetId, Instant from, Instant to,
                                             int page, int size) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "开始时间不能晚于结束时间");
        }
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), PageResult.MAX_PAGE_SIZE);
        long total = repo.count(actor, action, targetType, targetId, from, to);
        if (total == 0) {
            return PageResult.of(safePage, safeSize, 0, List.of());
        }
        int offset = (safePage - 1) * safeSize;
        return PageResult.of(safePage, safeSize, total,
                repo.search(actor, action, targetType, targetId, from, to, safeSize, offset));
    }
}
