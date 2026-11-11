package com.medassist.release.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.release.config.ReleaseProperties;
import com.medassist.release.domain.ReleaseModel.Deployment;
import com.medassist.release.domain.ReleaseModel.Version;
import com.medassist.release.repo.ReleaseRepository;
import com.medassist.release.web.ReleaseDtos;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 部署与回滚记录。
 *
 * <p>生产部署不是"点一下按钮"：必须带完整检查单（备份、回滚方案、冒烟、值班、变更单），
 * 且不能在 FREEZE 窗口内发布；HOTFIX 只有在已发布补丁支持时才允许穿透冻结。
 * 回滚永远允许（它是在恢复稳定性），但必须指向原部署并写原因。
 */
@Service
public class DeploymentService {

    private final ReleaseRepository repo;
    private final ReleaseCatalogService catalog;
    private final ReleaseProperties properties;
    private final Clock clock;

    public DeploymentService(ReleaseRepository repo, ReleaseCatalogService catalog,
                             ReleaseProperties properties, Clock clock) {
        this.repo = repo;
        this.catalog = catalog;
        this.properties = properties;
        this.clock = clock;
    }

    public List<Deployment> list(String component, String environment, String result,
                                 int limit, int offset) {
        int size = Math.min(Math.max(limit, 1), properties.getMaxPageSize());
        return repo.listDeployments(component, environment, result, size, Math.max(offset, 0));
    }

    public long count(String component, String environment, String result) {
        return repo.countDeployments(component, environment, result);
    }

    public Deployment requireDeployment(UUID id) {
        return repo.findDeployment(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.REL_DEPLOYMENT_NOT_FOUND));
    }

    @Transactional
    public Deployment record(ReleaseDtos.DeployReq req, String actor,
                             String clientIp, String traceId) {
        ReleaseCatalogService.requireActor(actor);
        catalog.requireComponent(req.componentCode());
        Version version = catalog.requireVersion(req.versionId());
        if (!version.componentCode().equals(req.componentCode())) {
            throw new BusinessException(ErrorCode.REL_RELEASE_INVALID,
                    "版本 " + version.version() + " 不属于组件 " + req.componentCode());
        }
        if ("ROLLBACK".equals(req.action())) {
            if (req.rollbackOf() == null) {
                throw new BusinessException(ErrorCode.REL_RELEASE_INVALID,
                        "回滚记录必须指向原部署");
            }
            Deployment original = requireDeployment(req.rollbackOf());
            if (!original.componentCode().equals(req.componentCode())) {
                throw new BusinessException(ErrorCode.REL_RELEASE_INVALID,
                        "原部署组件不一致");
            }
        } else if (req.rollbackOf() != null) {
            throw new BusinessException(ErrorCode.REL_RELEASE_INVALID,
                    "只有 ROLLBACK 动作可以关联原部署");
        }

        Instant now = clock.instant();
        if ("PROD".equals(req.environment()) && !"ROLLBACK".equals(req.action())) {
            List<Map<String, Object>> freezes = new ArrayList<>();
            repo.activeFreezes(now, req.componentCode()).forEach(w -> freezes.add(Map.of(
                    "name", w.name(), "reason", w.reason())));
            if (!freezes.isEmpty()) {
                boolean hotfixAllowed = "HOTFIX".equals(req.action())
                        && repo.findReleasedPatch(req.componentCode(), req.versionId()).isPresent();
                if (!hotfixAllowed) {
                    throw new BusinessException(ErrorCode.REL_FREEZE_ACTIVE,
                            "组件处于冻结窗口：" + freezes.get(0).get("name"));
                }
            }
            requireChecklist(req.checklist());
        }

        Deployment deployment = new Deployment(UUID.randomUUID(), req.componentCode(),
                req.versionId(), req.environment(), req.action(), req.result(),
                req.checklist() == null ? Map.of() : req.checklist(), req.rollbackOf(),
                req.reason(), actor, req.startedAt(),
                req.finishedAt() == null ? now : req.finishedAt(), now);
        repo.insertDeployment(deployment);
        repo.audit(actor, "DEPLOY_RECORD",
                req.componentCode() + "@" + version.version(),
                Map.of("environment", req.environment(), "action", req.action(),
                        "result", req.result()), clientIp, traceId);
        return deployment;
    }

    /** 回滚到同一组件/环境下上一次成功部署。 */
    @Transactional
    public Deployment rollback(UUID deploymentId, String reason, String actor,
                               String clientIp, String traceId) {
        ReleaseCatalogService.requireActor(actor);
        if (reason == null || reason.trim().length() < 2) {
            throw new BusinessException(ErrorCode.REL_RELEASE_INVALID, "回滚必须填写原因");
        }
        Deployment current = requireDeployment(deploymentId);
        if (!"SUCCESS".equals(current.result())) {
            throw new BusinessException(ErrorCode.REL_RELEASE_INVALID, "只有成功的部署可以回滚");
        }
        Deployment previous = repo.previousSuccessfulDeployment(current.componentCode(),
                        current.environment(), current.id())
                .orElseThrow(() -> new BusinessException(ErrorCode.REL_ROLLBACK_NO_TARGET));
        Instant now = clock.instant();
        Deployment rollback = new Deployment(UUID.randomUUID(), current.componentCode(),
                previous.versionId(), current.environment(), "ROLLBACK", "SUCCESS",
                Map.of(), current.id(), reason.trim(), actor, now, now, now);
        repo.insertDeployment(rollback);
        repo.audit(actor, "DEPLOY_ROLLBACK", current.componentCode(),
                Map.of("fromDeployment", current.id().toString(),
                        "toDeployment", previous.id().toString(),
                        "reason", reason), clientIp, traceId);
        return rollback;
    }

    private void requireChecklist(Map<String, Object> checklist) {
        List<String> missing = new ArrayList<>();
        for (String key : properties.getProdChecklist()) {
            Object value = checklist == null ? null : checklist.get(key);
            boolean ok = value instanceof Boolean b ? b
                    : value != null && "true".equalsIgnoreCase(String.valueOf(value));
            if (!ok) {
                missing.add(key);
            }
        }
        if (!missing.isEmpty()) {
            throw new BusinessException(ErrorCode.REL_CHECKLIST_INCOMPLETE,
                    "缺少检查项：" + String.join(", ", missing));
        }
    }
}
