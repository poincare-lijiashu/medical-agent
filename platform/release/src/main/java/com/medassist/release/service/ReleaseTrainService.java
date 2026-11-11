package com.medassist.release.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.release.config.ReleaseProperties;
import com.medassist.release.domain.ReleaseModel.Compatibility;
import com.medassist.release.domain.ReleaseModel.Train;
import com.medassist.release.domain.ReleaseModel.TrainItem;
import com.medassist.release.domain.ReleaseModel.Version;
import com.medassist.release.repo.ReleaseRepository;
import com.medassist.release.web.ReleaseDtos;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 发布列车：编排、兼容校验、冻结与发布。
 *
 * <p>为什么冻结和发布要分两步：冻结是"版本清单锁定"，给测试/安全/运维留复核窗口；
 * 发布是"真正可以上生产"，必须在冻结后、且不在变更冻结窗口内。
 */
@Service
public class ReleaseTrainService {

    private final ReleaseRepository repo;
    private final ReleaseCatalogService catalog;
    private final ReleaseProperties properties;
    private final Clock clock;

    public ReleaseTrainService(ReleaseRepository repo, ReleaseCatalogService catalog,
                               ReleaseProperties properties, Clock clock) {
        this.repo = repo;
        this.catalog = catalog;
        this.properties = properties;
        this.clock = clock;
    }

    public List<Train> list(String status, int limit, int offset) {
        int size = Math.min(Math.max(limit, 1), properties.getMaxPageSize());
        return repo.listTrains(status, size, Math.max(offset, 0));
    }

    public long count(String status) {
        return repo.countTrains(status);
    }

    @Transactional
    public Train create(ReleaseDtos.TrainReq req, String actor,
                        String clientIp, String traceId) {
        ReleaseCatalogService.requireActor(actor);
        if (req.freezeAt().isAfter(req.plannedDate().plusDays(1).atStartOfDay(
                java.time.ZoneId.of("Asia/Shanghai")).toInstant())) {
            throw new BusinessException(ErrorCode.REL_RELEASE_INVALID,
                    "冻结时间不能晚于计划发布日");
        }
        Train train = new Train(UUID.randomUUID(), req.trainCode().trim(), req.name(),
                req.plannedDate(), req.freezeAt(), "PLANNED", req.notes(), null, actor,
                0, null, null, List.of());
        try {
            repo.insertTrain(train);
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.REL_TRAIN_DUPLICATE);
        }
        repo.audit(actor, "TRAIN_CREATE", train.trainCode(),
                Map.of("plannedDate", req.plannedDate().toString()), clientIp, traceId);
        return detail(train.id());
    }

    public Train detail(UUID id) {
        Train train = requireTrain(id);
        return new Train(train.id(), train.trainCode(), train.name(), train.plannedDate(),
                train.freezeAt(), train.status(), train.notes(), train.releasedAt(),
                train.createdBy(), train.version(), train.createdAt(), train.updatedAt(),
                repo.listTrainItems(id));
    }

    @Transactional
    public Train addItem(UUID trainId, ReleaseDtos.TrainItemReq req, String actor,
                         String clientIp, String traceId) {
        ReleaseCatalogService.requireActor(actor);
        Train train = requireTrain(trainId);
        if (!"PLANNED".equals(train.status())) {
            throw new BusinessException(ErrorCode.REL_TRAIN_STATUS_INVALID,
                    "只有计划中的列车可以增删车厢");
        }
        catalog.requireComponent(req.componentCode());
        Version version = catalog.requireVersion(req.versionId());
        if (!version.componentCode().equals(req.componentCode())) {
            throw new BusinessException(ErrorCode.REL_TRAIN_ITEM_INVALID,
                    "版本 " + version.version() + " 不属于组件 " + req.componentCode());
        }
        if (!"RELEASED".equals(version.status())) {
            throw new BusinessException(ErrorCode.REL_TRAIN_ITEM_INVALID,
                    "只有已发布版本可以进列车");
        }
        repo.findTrainItem(trainId, req.componentCode()).ifPresent(existing ->
                repo.deleteTrainItem(trainId, req.componentCode()));
        repo.insertTrainItem(trainId, req.componentCode(), req.versionId(), actor);
        repo.audit(actor, "TRAIN_ITEM_ADD", train.trainCode(),
                Map.of("component", req.componentCode(), "version", version.version()),
                clientIp, traceId);
        return detail(trainId);
    }

    @Transactional
    public Train removeItem(UUID trainId, String componentCode, String actor,
                            String clientIp, String traceId) {
        ReleaseCatalogService.requireActor(actor);
        Train train = requireTrain(trainId);
        if (!"PLANNED".equals(train.status())) {
            throw new BusinessException(ErrorCode.REL_TRAIN_STATUS_INVALID,
                    "只有计划中的列车可以增删车厢");
        }
        if (!repo.deleteTrainItem(trainId, componentCode)) {
            throw new BusinessException(ErrorCode.REL_TRAIN_ITEM_INVALID,
                    "该组件不在列车中：" + componentCode);
        }
        repo.audit(actor, "TRAIN_ITEM_REMOVE", train.trainCode(),
                Map.of("component", componentCode), clientIp, traceId);
        return detail(trainId);
    }

    @Transactional
    public Train freeze(UUID id, String actor, String clientIp, String traceId) {
        ReleaseCatalogService.requireActor(actor);
        Train train = requireTrain(id);
        if (!"PLANNED".equals(train.status())) {
            throw new BusinessException(ErrorCode.REL_TRAIN_STATUS_INVALID,
                    "只有计划中的列车可以冻结");
        }
        List<Map<String, Object>> conflicts = compatibilityConflicts(id);
        if (!conflicts.isEmpty()) {
            throw new BusinessException(ErrorCode.REL_COMPATIBILITY_CONFLICT,
                    "存在 " + conflicts.size() + " 条兼容性冲突：" + conflicts.get(0).get("message"));
        }
        if (repo.listTrainItems(id).isEmpty()) {
            throw new BusinessException(ErrorCode.REL_RELEASE_INVALID, "列车没有车厢，不能冻结");
        }
        updateTrain(id, train.version(), Map.of("status", "FROZEN"));
        repo.audit(actor, "TRAIN_FREEZE", train.trainCode(),
                Map.of("items", repo.listTrainItems(id).size()), clientIp, traceId);
        return detail(id);
    }

    @Transactional
    public Train release(UUID id, String actor, String clientIp, String traceId) {
        ReleaseCatalogService.requireActor(actor);
        Train train = requireTrain(id);
        if (!"FROZEN".equals(train.status())) {
            throw new BusinessException(ErrorCode.REL_TRAIN_STATUS_INVALID,
                    "只有已冻结的列车可以发布");
        }
        List<Map<String, Object>> conflicts = compatibilityConflicts(id);
        if (!conflicts.isEmpty()) {
            throw new BusinessException(ErrorCode.REL_COMPATIBILITY_CONFLICT,
                    "存在 " + conflicts.size() + " 条兼容性冲突：" + conflicts.get(0).get("message"));
        }
        Instant now = clock.instant();
        for (TrainItem item : repo.listTrainItems(id)) {
            if (!repo.activeFreezes(now, item.componentCode()).isEmpty()) {
                throw new BusinessException(ErrorCode.REL_FREEZE_ACTIVE,
                        "组件 " + item.componentCode() + " 处于变更冻结窗口，不能发布");
            }
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("status", "RELEASED");
        fields.put("released_at", now);
        updateTrain(id, train.version(), fields);
        repo.audit(actor, "TRAIN_RELEASE", train.trainCode(),
                Map.of("releasedAt", now.toString()), clientIp, traceId);
        return detail(id);
    }

    @Transactional
    public Train cancel(UUID id, String actor, String clientIp, String traceId) {
        ReleaseCatalogService.requireActor(actor);
        Train train = requireTrain(id);
        if (!"PLANNED".equals(train.status()) && !"FROZEN".equals(train.status())) {
            throw new BusinessException(ErrorCode.REL_TRAIN_STATUS_INVALID,
                    "只有计划中/已冻结的列车可以取消");
        }
        updateTrain(id, train.version(), Map.of("status", "CANCELLED"));
        repo.audit(actor, "TRAIN_CANCEL", train.trainCode(), Map.of(), clientIp, traceId);
        return detail(id);
    }

    /** 就绪报告：给上线评审会用的"能不能发"清单。 */
    public Map<String, Object> readiness(UUID id) {
        Train train = detail(id);
        List<Map<String, Object>> checks = new ArrayList<>();
        checks.add(check("items", !train.items().isEmpty(),
                train.items().isEmpty() ? "列车没有车厢" : "车厢 " + train.items().size() + " 个"));
        boolean versionsReleased = train.items().stream()
                .allMatch(item -> repo.findVersion(item.versionId())
                        .map(v -> "RELEASED".equals(v.status())).orElse(false));
        checks.add(check("versions_released", versionsReleased, "所有车厢版本均为 RELEASED"));
        List<Map<String, Object>> conflicts = compatibilityConflicts(id);
        checks.add(check("compatibility", conflicts.isEmpty(),
                conflicts.isEmpty() ? "兼容矩阵无冲突" : "兼容冲突 " + conflicts.size() + " 条"));
        Instant now = clock.instant();
        boolean frozen = false;
        for (TrainItem item : train.items()) {
            if (!repo.activeFreezes(now, item.componentCode()).isEmpty()) {
                frozen = true;
            }
        }
        checks.add(check("change_window", !frozen, frozen ? "存在生效中的冻结窗口" : "当前无冻结窗口"));
        boolean ready = checks.stream().allMatch(c -> Boolean.TRUE.equals(c.get("ok")));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("train", train.trainCode());
        result.put("status", train.status());
        result.put("ready", ready);
        result.put("checks", checks);
        result.put("conflicts", conflicts);
        return result;
    }

    public List<Map<String, Object>> compatibilityConflicts(UUID trainId) {
        Train train = requireTrain(trainId);
        List<TrainItem> items = repo.listTrainItems(trainId);
        if (items.isEmpty()) {
            return List.of();
        }
        Map<String, Version> byComponent = items.stream()
                .map(item -> repo.findVersion(item.versionId()).orElse(null))
                .filter(v -> v != null)
                .collect(Collectors.toMap(Version::componentCode, Function.identity(),
                        (a, b) -> a));
        List<UUID> versionIds = items.stream().map(TrainItem::versionId).toList();
        List<Compatibility> edges = repo.listCompatibilityForVersions(versionIds);
        List<Map<String, Object>> conflicts = new ArrayList<>();
        for (Compatibility edge : edges) {
            Version target = repo.findVersion(edge.targetVersionId()).orElse(null);
            if (target == null) {
                continue;
            }
            Version current = byComponent.get(target.componentCode());
            if ("REQUIRES".equals(edge.kind())) {
                if (current == null) {
                    conflicts.add(conflict("REQUIRES", train.trainCode(),
                            "缺少组件 " + target.componentCode() + "（需要 " + target.version() + "）"));
                } else if (!current.id().equals(target.id())) {
                    conflicts.add(conflict("REQUIRES", train.trainCode(),
                            "组件 " + target.componentCode() + " 需要 " + target.version()
                                    + "，当前是 " + current.version()));
                } else if ("YANKED".equals(target.status())) {
                    conflicts.add(conflict("REQUIRES", train.trainCode(),
                            "依赖版本已作废：" + target.componentCode() + "@" + target.version()));
                }
            } else if ("CONFLICTS".equals(edge.kind())) {
                if (current != null && current.id().equals(target.id())) {
                    conflicts.add(conflict("CONFLICTS", train.trainCode(),
                            "版本冲突：" + target.componentCode() + "@" + target.version()));
                }
            }
        }
        return conflicts;
    }

    public Train requireTrain(UUID id) {
        return repo.findTrain(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.REL_TRAIN_NOT_FOUND));
    }

    private void updateTrain(UUID id, long expected, Map<String, Object> fields) {
        if (!repo.updateTrainFields(id, expected, fields)) {
            throw new BusinessException(ErrorCode.REL_TRAIN_STATUS_INVALID,
                    "列车已被他人更新，请刷新后重试");
        }
    }

    private static Map<String, Object> conflict(String kind, String train, String message) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("kind", kind);
        map.put("train", train);
        map.put("message", message);
        return map;
    }

    private static Map<String, Object> check(String code, boolean ok, String message) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("code", code);
        map.put("ok", ok);
        map.put("message", message);
        return map;
    }
}
