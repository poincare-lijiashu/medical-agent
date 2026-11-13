package com.medassist.release.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.release.config.ReleaseProperties;
import com.medassist.release.domain.ReleaseModel.Deployment;
import com.medassist.release.domain.ReleaseModel.DrillGate;
import com.medassist.release.domain.ReleaseModel.Version;
import com.medassist.release.repo.ReleaseRepository;
import com.medassist.release.web.ReleaseDtos;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
                                 String tenantCode, int limit, int offset) {
        int size = Math.min(Math.max(limit, 1), properties.getMaxPageSize());
        return repo.listDeployments(component, environment, result, blankToNull(tenantCode),
                size, Math.max(offset, 0));
    }

    public long count(String component, String environment, String result, String tenantCode) {
        return repo.countDeployments(component, environment, result, blankToNull(tenantCode));
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
        String drillRef = null;
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
            // §297：生产上线必须有有效期内的、已复核的灾备演练通过记录
            drillRef = requireDrDrill(req.componentCode(), now);
        }

        Deployment deployment = new Deployment(UUID.randomUUID(), req.componentCode(),
                req.versionId(), req.environment(), blankToNull(req.tenantCode()),
                blankToNull(req.batchLabel()), req.action(), req.result(),
                req.checklist() == null ? Map.of() : req.checklist(), req.rollbackOf(),
                req.reason(), actor, req.startedAt(),
                req.finishedAt() == null ? now : req.finishedAt(), now);
        repo.insertDeployment(deployment);
        Map<String, Object> auditDetail = new LinkedHashMap<>();
        auditDetail.put("environment", req.environment());
        auditDetail.put("action", req.action());
        auditDetail.put("result", req.result());
        auditDetail.put("tenant", deployment.tenantCode() == null ? "" : deployment.tenantCode());
        auditDetail.put("batch", deployment.batchLabel() == null ? "" : deployment.batchLabel());
        if (drillRef != null) {
            auditDetail.put("drDrill", drillRef);
        }
        repo.audit(actor, "DEPLOY_RECORD",
                req.componentCode() + "@" + version.version(), auditDetail, clientIp, traceId);
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
                        current.environment(), current.tenantCode(), current.id())
                .orElseThrow(() -> new BusinessException(ErrorCode.REL_ROLLBACK_NO_TARGET));
        Instant now = clock.instant();
        Deployment rollback = new Deployment(UUID.randomUUID(), current.componentCode(),
                previous.versionId(), current.environment(), current.tenantCode(),
                current.batchLabel(), "ROLLBACK", "SUCCESS",
                Map.of(), current.id(), reason.trim(), actor, now, now, now);
        repo.insertDeployment(rollback);
        repo.audit(actor, "DEPLOY_ROLLBACK", current.componentCode(),
                Map.of("fromDeployment", current.id().toString(),
                        "toDeployment", previous.id().toString(),
                        "tenant", current.tenantCode() == null ? "" : current.tenantCode(),
                        "reason", reason), clientIp, traceId);
        return rollback;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
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

    /**
     * 生产部署的灾备演练门禁（§297）。
     *
     * <p>fail-closed：查不到、查失败、过期、未复核都按"没有演练"处理——
     * 门禁的价值在于拒绝，而不是在依赖故障时默默放行。
     *
     * @return 通过时的演练编号，写入部署审计，便于事后追溯"凭哪次演练上线"
     */
    private String requireDrDrill(String componentCode, Instant now) {
        int maxAgeDays = Math.max(properties.getDrillMaxAgeDays(), 1);
        Instant since = now.minus(Duration.ofDays(maxAgeDays));
        Optional<DrillGate> gate;
        try {
            gate = repo.latestPassedDrill(componentCode, "PROD", since);
        } catch (RuntimeException e) {
            throw new BusinessException(ErrorCode.REL_DR_DRILL_REQUIRED,
                    "无法读取灾备演练记录，按未通过处理；请检查运营服务与数据库连通性");
        }
        if (gate.isEmpty()) {
            throw new BusinessException(ErrorCode.REL_DR_DRILL_REQUIRED,
                    "组件 " + componentCode + " 近 " + maxAgeDays
                            + " 天内没有已复核的灾备演练通过记录");
        }
        return gate.get().drillNo();
    }
}
