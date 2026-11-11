package com.medassist.release.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.release.config.ReleaseProperties;
import com.medassist.release.domain.ReleaseModel.Patch;
import com.medassist.release.domain.ReleaseModel.Version;
import com.medassist.release.repo.ReleaseRepository;
import com.medassist.release.web.ReleaseDtos;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 补丁分级与审批。
 *
 * <p>补丁的生命周期：DRAFT → REVIEW → APPROVED → RELEASED；未发布前可 WITHDRAWN。
 * 提交人不能审批自己的补丁；未 RELEASED 的补丁不能作为生产 HOTFIX 的依据。
 */
@Service
public class PatchService {

    private final ReleaseRepository repo;
    private final ReleaseCatalogService catalog;
    private final ReleaseProperties properties;
    private final Clock clock;

    public PatchService(ReleaseRepository repo, ReleaseCatalogService catalog,
                        ReleaseProperties properties, Clock clock) {
        this.repo = repo;
        this.catalog = catalog;
        this.properties = properties;
        this.clock = clock;
    }

    public List<Patch> list(String component, String status, int limit, int offset) {
        int size = Math.min(Math.max(limit, 1), properties.getMaxPageSize());
        return repo.listPatches(component, status, size, Math.max(offset, 0));
    }

    public long count(String component, String status) {
        return repo.countPatches(component, status);
    }

    public Patch requirePatch(UUID id) {
        return repo.findPatch(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.REL_PATCH_NOT_FOUND));
    }

    @Transactional
    public Patch create(ReleaseDtos.PatchReq req, String actor,
                        String clientIp, String traceId) {
        ReleaseCatalogService.requireActor(actor);
        catalog.requireComponent(req.componentCode());
        Version fixed = catalog.requireVersion(req.fixedVersionId());
        if (!fixed.componentCode().equals(req.componentCode())) {
            throw new BusinessException(ErrorCode.REL_PATCH_INVALID,
                    "修复版本不属于组件 " + req.componentCode());
        }
        if (req.affectedVersions() == null || req.affectedVersions().isEmpty()) {
            throw new BusinessException(ErrorCode.REL_PATCH_INVALID, "影响版本不能为空");
        }
        Patch patch = new Patch(UUID.randomUUID(), repo.nextPatchNo(), req.title(),
                req.severity(), req.componentCode(), req.affectedVersions(), fixed.id(),
                req.advisory(), req.cveId(), "DRAFT", actor, null, null, null, 0, null, null);
        repo.insertPatch(patch);
        repo.audit(actor, "PATCH_CREATE", patch.patchNo(),
                Map.of("severity", req.severity(), "component", req.componentCode()),
                clientIp, traceId);
        return requirePatch(patch.id());
    }

    @Transactional
    public Patch submit(UUID id, String actor, String clientIp, String traceId) {
        ReleaseCatalogService.requireActor(actor);
        Patch patch = requirePatch(id);
        if (!"DRAFT".equals(patch.status())) {
            throw new BusinessException(ErrorCode.REL_PATCH_STATUS_INVALID, "只有草稿可以提交复核");
        }
        update(patch, patch.version(), Map.of("status", "REVIEW"));
        repo.audit(actor, "PATCH_SUBMIT", patch.patchNo(), Map.of(), clientIp, traceId);
        return requirePatch(id);
    }

    @Transactional
    public Patch approve(UUID id, String actor, String clientIp, String traceId) {
        ReleaseCatalogService.requireActor(actor);
        Patch patch = requirePatch(id);
        if (!"REVIEW".equals(patch.status())) {
            throw new BusinessException(ErrorCode.REL_PATCH_STATUS_INVALID, "只有待复核补丁可以审批");
        }
        if (actor.equals(patch.requestedBy())) {
            throw new BusinessException(ErrorCode.REL_PATCH_SELF_REVIEW);
        }
        Instant now = clock.instant();
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("status", "APPROVED");
        fields.put("approved_by", actor);
        fields.put("approved_at", now);
        update(patch, patch.version(), fields);
        repo.audit(actor, "PATCH_APPROVE", patch.patchNo(),
                Map.of("requestedBy", patch.requestedBy()), clientIp, traceId);
        return requirePatch(id);
    }

    @Transactional
    public Patch release(UUID id, String actor, String clientIp, String traceId) {
        ReleaseCatalogService.requireActor(actor);
        Patch patch = requirePatch(id);
        if (!"APPROVED".equals(patch.status())) {
            throw new BusinessException(ErrorCode.REL_PATCH_STATUS_INVALID, "只有已审批补丁可以发布");
        }
        Version fixed = catalog.requireVersion(patch.fixedVersionId());
        if (!"RELEASED".equals(fixed.status())) {
            throw new BusinessException(ErrorCode.REL_RELEASE_INVALID,
                    "修复版本尚未发布，不能先发布补丁");
        }
        Instant now = clock.instant();
        if (!repo.activeFreezes(now, patch.componentCode()).isEmpty()) {
            throw new BusinessException(ErrorCode.REL_FREEZE_ACTIVE,
                    "组件处于变更冻结窗口，不能发布补丁");
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("status", "RELEASED");
        fields.put("released_at", now);
        update(patch, patch.version(), fields);
        repo.audit(actor, "PATCH_RELEASE", patch.patchNo(),
                Map.of("fixedVersion", fixed.version()), clientIp, traceId);
        return requirePatch(id);
    }

    @Transactional
    public Patch withdraw(UUID id, String actor, String clientIp, String traceId) {
        ReleaseCatalogService.requireActor(actor);
        Patch patch = requirePatch(id);
        if ("RELEASED".equals(patch.status()) || "WITHDRAWN".equals(patch.status())) {
            throw new BusinessException(ErrorCode.REL_PATCH_STATUS_INVALID,
                    "已发布或已撤回的补丁不能再次撤回");
        }
        update(patch, patch.version(), Map.of("status", "WITHDRAWN"));
        repo.audit(actor, "PATCH_WITHDRAW", patch.patchNo(), Map.of(), clientIp, traceId);
        return requirePatch(id);
    }

    private void update(Patch patch, long expected, Map<String, Object> fields) {
        if (!repo.updatePatchFields(patch.id(), expected, fields)) {
            throw new BusinessException(ErrorCode.REL_PATCH_STATUS_INVALID,
                    "补丁已被他人修改，请刷新后重试");
        }
    }
}
