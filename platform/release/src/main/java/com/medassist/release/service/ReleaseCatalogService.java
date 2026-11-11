package com.medassist.release.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.release.config.ReleaseProperties;
import com.medassist.release.domain.ReleaseModel.Component;
import com.medassist.release.domain.ReleaseModel.Version;
import com.medassist.release.repo.ReleaseRepository;
import com.medassist.release.web.ReleaseDtos;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 组件与版本目录。
 *
 * <p>版本一旦 RELEASED，内容不可修改：只能新增更高版本，或 DEPRECATED/YANKED。
 * 这不是"流程洁癖"，而是发布事故回溯的前提——你必须能证明"当时生产跑的是哪份制品"。
 */
@Service
public class ReleaseCatalogService {

    private final ReleaseRepository repo;
    private final ReleaseProperties properties;
    private final Clock clock;

    public ReleaseCatalogService(ReleaseRepository repo, ReleaseProperties properties, Clock clock) {
        this.repo = repo;
        this.properties = properties;
        this.clock = clock;
    }

    public List<Component> listComponents(boolean onlyActive) {
        return repo.listComponents(onlyActive);
    }

    public Component upsertComponent(ReleaseDtos.ComponentReq req, String actor,
                                     String clientIp, String traceId) {
        requireActor(actor);
        repo.upsertComponent(new Component(req.code().trim(), req.name(), req.kind(),
                req.ownerGroup(), req.status() == null ? "ACTIVE" : req.status(),
                req.sortNo() == null ? 100 : req.sortNo()));
        repo.audit(actor, "COMPONENT_UPSERT", req.code(),
                Map.of("kind", req.kind(), "group", req.ownerGroup()), clientIp, traceId);
        return requireComponent(req.code());
    }

    public Component requireComponent(String code) {
        return repo.findComponent(code)
                .orElseThrow(() -> new BusinessException(ErrorCode.REL_COMPONENT_NOT_FOUND));
    }

    public List<Version> listVersions(String component, String status, int limit, int offset) {
        int size = Math.min(Math.max(limit, 1), properties.getMaxPageSize());
        return repo.listVersions(component, status, size, Math.max(offset, 0));
    }

    public long countVersions(String component, String status) {
        return repo.countVersions(component, status);
    }

    public Version registerVersion(ReleaseDtos.VersionReq req, String actor,
                                   String clientIp, String traceId) {
        requireActor(actor);
        Component component = requireComponent(req.componentCode());
        if (repo.findVersionByKey(component.code(), req.version().trim()).isPresent()) {
            throw new BusinessException(ErrorCode.REL_VERSION_DUPLICATE,
                    component.code() + " 已存在版本 " + req.version());
        }
        boolean breaking = Boolean.TRUE.equals(req.breakingChanges());
        Version version = new Version(UUID.randomUUID(), component.code(), req.version().trim(),
                req.gitCommit(), req.imageDigest(), req.schemaVersion(), "DRAFT",
                breaking, req.changelog(), null, actor, 0, null, null);
        repo.insertVersion(version);
        repo.audit(actor, "VERSION_REGISTER", component.code() + "@" + req.version(),
                Map.of("breakingChanges", breaking), clientIp, traceId);
        return repo.findVersion(version.id()).orElse(version);
    }

    public Version publishVersion(UUID id, String actor, String clientIp, String traceId) {
        requireActor(actor);
        Version version = requireVersion(id);
        if (!"DRAFT".equals(version.status())) {
            throw new BusinessException(ErrorCode.REL_VERSION_STATUS_INVALID,
                    "只有草稿版本可以发布");
        }
        Component component = requireComponent(version.componentCode());
        if (version.changelog() == null || version.changelog().trim().length() < 10) {
            throw new BusinessException(ErrorCode.REL_RELEASE_INVALID,
                    "发布版本必须填写至少 10 个字符的变更说明");
        }
        if (("SERVICE".equals(component.kind()) || "AI".equals(component.kind())
                || "WEB".equals(component.kind()))
                && (version.imageDigest() == null || version.imageDigest().isBlank())) {
            throw new BusinessException(ErrorCode.REL_RELEASE_INVALID,
                    "可部署组件发布前必须登记镜像摘要（image digest）");
        }
        if ("INFRA".equals(component.kind())
                && (version.schemaVersion() == null || version.schemaVersion().isBlank())) {
            throw new BusinessException(ErrorCode.REL_RELEASE_INVALID,
                    "基础设施组件发布前必须登记 schema 版本");
        }
        Instant now = clock.instant();
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("status", "RELEASED");
        fields.put("released_at", now);
        if (!repo.updateVersionFields(id, version.lockVersion(), fields)) {
            throw new BusinessException(ErrorCode.REL_VERSION_STATUS_INVALID,
                    "版本已被他人修改，请刷新后重试");
        }
        repo.audit(actor, "VERSION_PUBLISH", version.componentCode() + "@" + version.version(),
                Map.of("releasedAt", now.toString()), clientIp, traceId);
        return requireVersion(id);
    }

    public Version deprecateVersion(UUID id, String actor, String clientIp, String traceId) {
        requireActor(actor);
        Version version = requireVersion(id);
        if (!"RELEASED".equals(version.status())) {
            throw new BusinessException(ErrorCode.REL_VERSION_STATUS_INVALID,
                    "只有已发布版本可以标记弃用");
        }
        repo.updateVersionFields(id, version.lockVersion(), Map.of("status", "DEPRECATED"));
        repo.audit(actor, "VERSION_DEPRECATE",
                version.componentCode() + "@" + version.version(), Map.of(), clientIp, traceId);
        return requireVersion(id);
    }

    public Version yankVersion(UUID id, String actor, String clientIp, String traceId) {
        requireActor(actor);
        Version version = requireVersion(id);
        if ("YANKED".equals(version.status())) {
            throw new BusinessException(ErrorCode.REL_VERSION_STATUS_INVALID, "版本已作废");
        }
        repo.updateVersionFields(id, version.lockVersion(), Map.of("status", "YANKED"));
        repo.audit(actor, "VERSION_YANK",
                version.componentCode() + "@" + version.version(), Map.of(), clientIp, traceId);
        return requireVersion(id);
    }

    public Version requireVersion(UUID id) {
        return repo.findVersion(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.REL_VERSION_NOT_FOUND));
    }

    static void requireActor(String actor) {
        if (actor == null || actor.isBlank()) {
            throw new BusinessException(ErrorCode.REL_ACTOR_REQUIRED);
        }
    }
}
