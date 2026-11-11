package com.medassist.release.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.release.domain.ReleaseModel.Compatibility;
import com.medassist.release.domain.ReleaseModel.Version;
import com.medassist.release.repo.ReleaseRepository;
import com.medassist.release.web.ReleaseDtos;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 兼容矩阵：显式 REQUIRES / CONFLICTS 边。
 *
 * <p>不做"版本号大就等于兼容"的推断：医院系统里 2.0 可能比 1.9 更依赖新的 auth 协议，
 * 只有发布方显式声明依赖，列车校验才有意义。
 */
@Service
public class CompatibilityService {

    private final ReleaseRepository repo;
    private final ReleaseCatalogService catalog;

    public CompatibilityService(ReleaseRepository repo, ReleaseCatalogService catalog) {
        this.repo = repo;
        this.catalog = catalog;
    }

    public List<Compatibility> list(UUID versionId) {
        return repo.listCompatibility(versionId);
    }

    public Compatibility add(ReleaseDtos.CompatibilityReq req, String actor,
                             String clientIp, String traceId) {
        ReleaseCatalogService.requireActor(actor);
        Version version = catalog.requireVersion(req.versionId());
        Version target = catalog.requireVersion(req.targetVersionId());
        if (version.id().equals(target.id())) {
            throw new BusinessException(ErrorCode.REL_RELEASE_INVALID,
                    "不能声明组件与自身版本的兼容关系");
        }
        Compatibility compatibility = new Compatibility(UUID.randomUUID(), version.id(),
                req.kind(), target.id(), req.note(), actor, null);
        try {
            repo.insertCompatibility(compatibility);
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.REL_RELEASE_INVALID, "该兼容关系已存在");
        }
        repo.audit(actor, "COMPAT_ADD",
                version.componentCode() + "@" + version.version(),
                Map.of("kind", req.kind(),
                        "target", target.componentCode() + "@" + target.version()),
                clientIp, traceId);
        return compatibility;
    }

    public void remove(UUID id, String actor, String clientIp, String traceId) {
        ReleaseCatalogService.requireActor(actor);
        if (!repo.deleteCompatibility(id)) {
            throw new BusinessException(ErrorCode.REL_RELEASE_INVALID, "兼容关系不存在");
        }
        repo.audit(actor, "COMPAT_REMOVE", id.toString(), Map.of(), clientIp, traceId);
    }
}
