package com.medassist.release.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.release.domain.ReleaseModel.ChangeWindow;
import com.medassist.release.repo.ReleaseRepository;
import com.medassist.release.web.ReleaseDtos;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 变更窗口：FREEZE（禁止变更）与 MAINTENANCE（计划维护）。
 *
 * <p>scope 为空表示全院；否则按组件匹配。冻结窗口是"上线评审会"与"夜班发布"之间的硬约束，
 * 不是备忘录。
 */
@Service
public class ChangeWindowService {

    private final ReleaseRepository repo;

    public ChangeWindowService(ReleaseRepository repo) {
        this.repo = repo;
    }

    public List<ChangeWindow> list(String kind, String status, int limit) {
        return repo.listWindows(kind, status, Math.min(Math.max(limit, 1), 200));
    }

    @Transactional
    public ChangeWindow create(ReleaseDtos.WindowReq req, String actor,
                               String clientIp, String traceId) {
        ReleaseCatalogService.requireActor(actor);
        if (!req.endAt().isAfter(req.startAt())) {
            throw new BusinessException(ErrorCode.REL_WINDOW_INVALID);
        }
        ChangeWindow window = new ChangeWindow(UUID.randomUUID(), req.name(), req.kind(),
                req.startAt(), req.endAt(), req.scope() == null ? List.of() : req.scope(),
                req.reason(), "ACTIVE", actor, 0);
        repo.insertWindow(window);
        repo.audit(actor, "WINDOW_CREATE", window.name(),
                Map.of("kind", req.kind(), "scope", window.scope()), clientIp, traceId);
        return requireWindow(window.id());
    }

    @Transactional
    public ChangeWindow cancel(UUID id, String actor, String clientIp, String traceId) {
        ReleaseCatalogService.requireActor(actor);
        ChangeWindow window = requireWindow(id);
        if (!"ACTIVE".equals(window.status())) {
            throw new BusinessException(ErrorCode.REL_WINDOW_INVALID, "窗口已取消");
        }
        if (!repo.updateWindowFields(id, window.version(), Map.of("status", "CANCELLED"))) {
            throw new BusinessException(ErrorCode.REL_WINDOW_INVALID,
                    "窗口已被他人修改，请刷新后重试");
        }
        repo.audit(actor, "WINDOW_CANCEL", window.name(), Map.of(), clientIp, traceId);
        return requireWindow(id);
    }

    public ChangeWindow requireWindow(UUID id) {
        return repo.findWindow(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.REL_WINDOW_NOT_FOUND));
    }
}
