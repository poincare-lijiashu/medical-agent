package com.medassist.release.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.security.HookTokens;
import com.medassist.release.domain.ReleaseIntegrationModel.CiEvent;
import com.medassist.release.domain.ReleaseIntegrationModel.CiSource;
import com.medassist.release.domain.ReleaseIntegrationModel.CiSourceCreated;
import com.medassist.release.domain.ReleaseModel.Deployment;
import com.medassist.release.domain.ReleaseModel.Version;
import com.medassist.release.repo.CiIntegrationRepository;
import com.medassist.release.repo.CiIntegrationRepository.StoredSource;
import com.medassist.release.repo.ReleaseRepository;
import com.medassist.release.web.ReleaseDtos;
import com.medassist.release.web.ReleaseIntegrationDtos.CiEventReq;
import com.medassist.release.web.ReleaseIntegrationDtos.CiSourceReq;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * CI/CD 流水线回写（§276）。
 *
 * <p>三条纪律：
 * <ul>
 *   <li><b>幂等</b>：同一 {@code (source, eventId)} 只处理一次；已处理的事件重复投递返回首次结果；</li>
 *   <li><b>不绕过门禁</b>：DEPLOY 仍走 {@link DeploymentService#record}，冻结窗口/生产检查单/
 *       回滚链全部生效，CI 不能"先发后补"；</li>
 *   <li><b>构建不等于发布</b>：BUILD 成功最多自动登记 DRAFT 版本，发布仍需人工审批。</li>
 * </ul>
 */
@Service
public class CiIngestService {

    private static final Set<String> KINDS = Set.of("BUILD", "DEPLOY");
    private static final Set<String> ENVIRONMENTS = Set.of("DEV", "STAGING", "PROD");
    private static final Set<String> ACTIONS = Set.of("DEPLOY", "ROLLBACK", "HOTFIX");
    private static final Set<String> RESULTS = Set.of("SUCCESS", "FAILED");
    private static final int MAX_REASON = 500;

    private final CiIntegrationRepository repo;
    private final ReleaseRepository releaseRepo;
    private final ReleaseCatalogService catalog;
    private final DeploymentService deploymentService;

    public CiIngestService(CiIntegrationRepository repo, ReleaseRepository releaseRepo,
                           ReleaseCatalogService catalog, DeploymentService deploymentService) {
        this.repo = repo;
        this.releaseRepo = releaseRepo;
        this.catalog = catalog;
        this.deploymentService = deploymentService;
    }

    // ------------------------------------------------------------------ 接入源管理

    public List<CiSource> listSources() {
        return repo.listSources();
    }

    public List<CiEvent> listEvents(String sourceCode, String kind, Integer limit) {
        int size = limit == null ? 50 : Math.min(Math.max(limit, 1), 200);
        return repo.listEvents(blankToNull(sourceCode), blankToNull(kind), size);
    }

    public CiSourceCreated createSource(CiSourceReq req, String actor, String clientIp,
                                        String traceId) {
        requireActor(actor);
        if (repo.findSource(req.code()).isPresent()) {
            throw new BusinessException(ErrorCode.REL_CI_PAYLOAD_INVALID,
                    "CI 接入源编码已存在：" + req.code());
        }
        List<String> components = req.allowedComponents() == null
                ? List.of()
                : req.allowedComponents().stream()
                        .filter(v -> v != null && !v.isBlank())
                        .map(String::trim)
                        .distinct()
                        .toList();
        String token = HookTokens.generate("ci");
        CiSource source = new CiSource(UUID.randomUUID(), req.code(), req.name(),
                req.kind() == null ? "GENERIC" : req.kind(), HookTokens.hint(token), components,
                "ACTIVE", null, actor, 0, Instant.now(), Instant.now());
        repo.insertSource(source, HookTokens.hash(token));
        releaseRepo.audit(actor, "CI_SOURCE_CREATE", source.code(),
                Map.of("kind", source.kind(), "allowedComponents", components), clientIp, traceId);
        return new CiSourceCreated(source, token);
    }

    public CiSourceCreated rotate(UUID id, String actor, String clientIp, String traceId) {
        requireActor(actor);
        CiSource source = repo.findSourceMeta(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.REL_CI_SOURCE_NOT_FOUND));
        String token = HookTokens.generate("ci");
        repo.updateToken(id, HookTokens.hash(token), HookTokens.hint(token));
        releaseRepo.audit(actor, "CI_SOURCE_ROTATE", source.code(), Map.of(), clientIp, traceId);
        return new CiSourceCreated(source, token);
    }

    public CiSource disable(UUID id, String actor, String clientIp, String traceId) {
        requireActor(actor);
        CiSource source = repo.findSourceMeta(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.REL_CI_SOURCE_NOT_FOUND));
        repo.disable(id);
        releaseRepo.audit(actor, "CI_SOURCE_DISABLE", source.code(), Map.of(), clientIp, traceId);
        return repo.findSourceMeta(id).orElse(source);
    }

    // ------------------------------------------------------------------ 入站

    public Map<String, Object> ingest(String sourceCode, String authorization, String eventIdHeader,
                                      CiEventReq req, String clientIp, String traceId) {
        if (req == null) {
            throw new BusinessException(ErrorCode.REL_CI_PAYLOAD_INVALID, "缺少事件内容");
        }
        StoredSource stored = repo.findSource(sourceCode)
                .orElseThrow(() -> new BusinessException(ErrorCode.REL_CI_TOKEN_INVALID));
        String token = HookTokens.bearer(authorization);
        if (!"ACTIVE".equals(stored.source().status())
                || !HookTokens.matches(token, stored.tokenHash())) {
            throw new BusinessException(ErrorCode.REL_CI_TOKEN_INVALID);
        }
        CiSource source = stored.source();
        repo.touchUsed(sourceCode);

        String eventId = blankToNull(eventIdHeader) != null ? eventIdHeader.trim()
                : blankToNull(req == null ? null : req.eventId());
        if (eventId == null) {
            throw new BusinessException(ErrorCode.REL_CI_PAYLOAD_INVALID,
                    "缺少事件幂等号（X-CI-Event-Id 或 eventId）");
        }
        if (eventId.length() > 128) {
            throw new BusinessException(ErrorCode.REL_CI_PAYLOAD_INVALID, "事件幂等号不能超过 128 字符");
        }

        CiEvent existing = repo.findEvent(sourceCode, eventId).orElse(null);
        if (existing != null && "PROCESSED".equals(existing.status())) {
            return summary(sourceCode, eventId, existing.kind(), existing.componentCode(),
                    existing.versionNo(), true, existing.deploymentId(), "duplicate");
        }

        String kind = upper(req.kind(), "DEPLOY");
        if (!KINDS.contains(kind)) {
            throw new BusinessException(ErrorCode.REL_CI_PAYLOAD_INVALID,
                    "kind 只能是 BUILD 或 DEPLOY");
        }
        String componentCode = requireComponent(source, req.componentCode());

        CiEvent pending = new CiEvent(0, Instant.now(), sourceCode, eventId, kind, componentCode,
                req.version(), req.environment(), req.action(), req.result(), null, "FAILED",
                "处理中", payload(req));
        if (existing == null && !repo.tryInsertEvent(pending)) {
            // 并发投递：另一请求已先插入，直接返回其结果
            CiEvent raced = repo.findEvent(sourceCode, eventId).orElse(existing);
            return summary(sourceCode, eventId, kind, componentCode, req.version(), true,
                    raced == null ? null : raced.deploymentId(), "duplicate");
        }

        String actor = "ci:" + sourceCode;
        UUID deploymentId = null;
        String message;
        try {
            if ("BUILD".equals(kind)) {
                Version version = resolveOrRegisterBuildVersion(componentCode, req, actor,
                        clientIp, traceId);
                message = version == null ? "构建事件已记录（未登记版本）"
                        : "构建事件已记录，版本 " + version.componentCode() + "@" + version.version();
            } else {
                Version version = resolveVersion(componentCode, req);
                Deployment deployment = deploymentService.record(deployReq(componentCode, version,
                        req, sourceCode), actor, clientIp, traceId);
                deploymentId = deployment.id();
                message = "部署已记录：" + deployment.environment() + " / " + deployment.result();
            }
        } catch (BusinessException e) {
            repo.finishEvent(sourceCode, eventId, "FAILED", null, e.getMessage());
            throw e;
        } catch (RuntimeException e) {
            repo.finishEvent(sourceCode, eventId, "FAILED", null, "处理异常：" + e.getMessage());
            throw new BusinessException(ErrorCode.REL_CI_PAYLOAD_INVALID,
                    "CI 事件处理失败，请查看发布服务日志");
        }
        repo.finishEvent(sourceCode, eventId, "PROCESSED", deploymentId, message);
        releaseRepo.audit(actor, "CI_EVENT", sourceCode + "/" + eventId,
                Map.of("kind", kind, "component", componentCode,
                        "version", req.version() == null ? "" : req.version(),
                        "deploymentId", deploymentId == null ? "" : deploymentId.toString()),
                clientIp, traceId);
        return summary(sourceCode, eventId, kind, componentCode, req.version(), false,
                deploymentId, "processed");
    }

    // ------------------------------------------------------------------ 版本与部署

    private Version resolveOrRegisterBuildVersion(String componentCode, CiEventReq req, String actor,
                                                  String clientIp, String traceId) {
        if (req.versionId() != null) {
            return catalog.requireVersion(req.versionId());
        }
        String versionNo = blankToNull(req.version());
        if (versionNo == null) {
            return null;
        }
        Version existing = releaseRepo.findVersionByKey(componentCode, versionNo).orElse(null);
        if (existing != null) {
            return existing;
        }
        boolean failed = "FAILED".equals(upper(req.result(), "SUCCESS"));
        if (failed) {
            return null;
        }
        String changelog = blankToNull(req.changelog());
        if (changelog == null || changelog.trim().length() < 10) {
            changelog = "CI build " + (req.eventId() == null ? "" : req.eventId()) + " (auto)";
        }
        try {
            return catalog.registerVersion(new ReleaseDtos.VersionReq(
                            componentCode, versionNo, req.gitCommit(), req.imageDigest(),
                            req.schemaVersion(), req.breakingChanges(), truncate(changelog, 4000)),
                    actor, clientIp, traceId);
        } catch (BusinessException e) {
            if (e.errorCode() == ErrorCode.REL_VERSION_DUPLICATE) {
                return releaseRepo.findVersionByKey(componentCode, versionNo).orElseThrow(() -> e);
            }
            throw e;
        }
    }

    private Version resolveVersion(String componentCode, CiEventReq req) {
        if (req.versionId() != null) {
            Version version = catalog.requireVersion(req.versionId());
            if (!version.componentCode().equals(componentCode)) {
                throw new BusinessException(ErrorCode.REL_CI_PAYLOAD_INVALID,
                        "versionId 不属于组件 " + componentCode);
            }
            return version;
        }
        String versionNo = blankToNull(req.version());
        if (versionNo == null) {
            throw new BusinessException(ErrorCode.REL_CI_PAYLOAD_INVALID,
                    "部署事件必须提供 version 或 versionId");
        }
        return releaseRepo.findVersionByKey(componentCode, versionNo)
                .orElseThrow(() -> new BusinessException(ErrorCode.REL_VERSION_NOT_FOUND,
                        componentCode + " 未找到版本 " + versionNo));
    }

    private ReleaseDtos.DeployReq deployReq(String componentCode, Version version, CiEventReq req,
                                            String sourceCode) {
        String environment = upper(req.environment(), "");
        if (!ENVIRONMENTS.contains(environment)) {
            throw new BusinessException(ErrorCode.REL_CI_PAYLOAD_INVALID,
                    "environment 必须是 DEV/STAGING/PROD");
        }
        String action = upper(req.action(), "DEPLOY");
        if (!ACTIONS.contains(action)) {
            throw new BusinessException(ErrorCode.REL_CI_PAYLOAD_INVALID,
                    "action 必须是 DEPLOY/ROLLBACK/HOTFIX");
        }
        String result = upper(req.result(), "SUCCESS");
        if (!RESULTS.contains(result)) {
            throw new BusinessException(ErrorCode.REL_CI_PAYLOAD_INVALID,
                    "result 必须是 SUCCESS 或 FAILED");
        }
        if ("ROLLBACK".equals(action) && req.rollbackOf() == null) {
            throw new BusinessException(ErrorCode.REL_CI_PAYLOAD_INVALID,
                    "回滚事件必须提供 rollbackOf（原部署 ID）");
        }
        Instant startedAt = parseInstant(req.startedAt(), Instant.now());
        Instant finishedAt = parseInstant(req.finishedAt(), null);
        String reason = "ci:" + sourceCode
                + (blankToNull(req.actor()) == null ? "" : " by " + req.actor().trim())
                + (blankToNull(req.reason()) == null ? "" : " — " + req.reason().trim());
        return new ReleaseDtos.DeployReq(componentCode, version.id(), environment,
                blankToNull(req.tenantCode()), blankToNull(req.batchLabel()), action, result,
                req.checklist(), req.rollbackOf(), truncate(reason, MAX_REASON), startedAt,
                finishedAt);
    }

    // ------------------------------------------------------------------ 辅助

    private static String requireComponent(CiSource source, String componentCode) {
        String value = blankToNull(componentCode);
        if (value == null) {
            throw new BusinessException(ErrorCode.REL_CI_PAYLOAD_INVALID, "缺少 componentCode");
        }
        String code = value.trim();
        if (!source.allowedComponents().isEmpty() && !source.allowedComponents().contains(code)) {
            throw new BusinessException(ErrorCode.REL_CI_PAYLOAD_INVALID,
                    "该接入源不允许发布组件 " + code);
        }
        return code;
    }

    private static Map<String, Object> summary(String sourceCode, String eventId, String kind,
                                               String componentCode, String version,
                                               boolean duplicate, UUID deploymentId,
                                               String status) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("source", sourceCode);
        data.put("eventId", eventId);
        data.put("kind", kind);
        data.put("componentCode", componentCode);
        data.put("version", version);
        data.put("duplicate", duplicate);
        data.put("status", status);
        data.put("deploymentId", deploymentId);
        return data;
    }

    private static Map<String, Object> payload(CiEventReq req) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("kind", req.kind());
        data.put("componentCode", req.componentCode());
        data.put("version", req.version());
        data.put("environment", req.environment());
        data.put("action", req.action());
        data.put("result", req.result());
        data.put("checklist", req.checklist());
        data.put("reason", req.reason());
        data.put("startedAt", req.startedAt());
        data.put("finishedAt", req.finishedAt());
        data.put("actor", req.actor());
        return data;
    }

    private static String upper(String value, String fallback) {
        String v = blankToNull(value);
        return v == null ? fallback : v.trim().toUpperCase(Locale.ROOT);
    }

    private static Instant parseInstant(String value, Instant fallback) {
        String v = blankToNull(value);
        if (v == null) {
            return fallback;
        }
        try {
            return Instant.parse(v.trim());
        } catch (RuntimeException e) {
            throw new BusinessException(ErrorCode.REL_CI_PAYLOAD_INVALID,
                    "时间格式必须是 ISO-8601（如 2026-11-12T08:00:00Z）");
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static void requireActor(String actor) {
        if (actor == null || actor.isBlank() || "system".equalsIgnoreCase(actor.trim())) {
            throw new BusinessException(ErrorCode.REL_ACTOR_REQUIRED);
        }
    }
}
