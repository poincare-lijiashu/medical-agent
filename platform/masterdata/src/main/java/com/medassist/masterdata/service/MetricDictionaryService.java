package com.medassist.masterdata.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.masterdata.domain.MasterDataModel.MetricDefinition;
import com.medassist.masterdata.domain.MasterDataModel.MetricDetail;
import com.medassist.masterdata.domain.MasterDataModel.MetricImpact;
import com.medassist.masterdata.domain.MasterDataModel.MetricLineage;
import com.medassist.masterdata.domain.MasterDataModel.MetricSummary;
import com.medassist.masterdata.domain.MasterDataModel.MetricVersion;
import com.medassist.masterdata.repo.MasterDataRepository;
import com.medassist.masterdata.repo.MasterDataRepository.MetricFilter;
import com.medassist.masterdata.web.MetricDtos.MetricLineageReq;
import com.medassist.masterdata.web.MetricDtos.MetricUpsertReq;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 指标字典与口径治理（§293 / 计划书 W4.5）。
 *
 * <p>为什么不能只是一个"指标说明表"：同一指标常被多个报表复用，
 * 没有版本与发布语义时，任何一次修改都会静默改写历史报表的口径。
 * 这里的规则：
 * <ul>
 *   <li>只有 DRAFT 可编辑；PUBLISHED 必须 revise 出新版本再发布；</li>
 *   <li>发布要求口径完整（定义/公式/单位/时间窗/责任人/源表），并落版本快照与 SHA-256；</li>
 *   <li>版本链与血缘只增不改，数据库触发器兜底；</li>
 *   <li>影响面按上下游血缘统计，修订中的指标显式标记 revisionInProgress。</li>
 * </ul>
 */
@Service
public class MetricDictionaryService {

    private static final Set<String> DOMAINS = Set.of(
            "CLINICAL", "FINANCE", "PHARMACY", "TECH", "INSURANCE", "QUALITY", "AI", "OPS");
    private static final Set<String> AGGREGATIONS = Set.of(
            "SUM", "COUNT", "COUNT_DISTINCT", "AVG", "RATIO", "MAX", "MIN");
    private static final Set<String> TIME_WINDOWS = Set.of(
            "POINT", "DAY", "WEEK", "MONTH", "QUARTER", "YEAR", "ROLLING_30D");
    private static final Set<String> GRANULARITIES = Set.of(
            "HOSPITAL", "CAMPUS", "DEPT", "PATIENT", "VISIT");
    private static final Set<String> REF_TYPES = Set.of(
            "TABLE", "API", "REPORT", "DASHBOARD", "METRIC");
    private static final int MAX_SOURCE_TABLES = 20;
    private static final int MAX_VERSION_PAGE = 100;

    private final MasterDataRepository repo;
    private final ObjectMapper json;

    public MetricDictionaryService(MasterDataRepository repo, ObjectMapper json) {
        this.repo = repo;
        this.json = json;
    }

    // ---------------------------------------------------------------- 查询

    public List<MetricDefinition> list(String domain, String status, String keyword, int limit) {
        String normalizedDomain = domain == null ? "" : domain.trim().toUpperCase();
        if (!normalizedDomain.isEmpty() && !DOMAINS.contains(normalizedDomain)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "指标域不合法：" + domain);
        }
        String normalizedStatus = status == null ? "" : status.trim().toUpperCase();
        if (!normalizedStatus.isEmpty() && !Set.of("DRAFT", "PUBLISHED", "DISABLED")
                .contains(normalizedStatus)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "指标状态不合法：" + status);
        }
        return repo.listMetrics(new MetricFilter(normalizedDomain, normalizedStatus, keyword),
                Math.max(1, Math.min(limit, 500)));
    }

    public MetricSummary summary() {
        return repo.metricSummary();
    }

    public MetricDetail detail(String code) {
        MetricDefinition metric = requireMetric(code);
        return new MetricDetail(metric, repo.listMetricVersions(metric.code(), MAX_VERSION_PAGE),
                repo.listMetricLineage(metric.code()));
    }

    /** 影响分析：上下游规模、版本数与"修订中"标记。 */
    public MetricImpact impact(String code) {
        MetricDefinition metric = requireMetric(code);
        return new MetricImpact(metric.code(), metric.name(), metric.status(), metric.version(),
                metric.publishedVersion(), metric.revisionInProgress(),
                repo.countMetricLineage(metric.code(), MetricLineage.UPSTREAM),
                repo.countMetricLineage(metric.code(), MetricLineage.DOWNSTREAM),
                repo.countMetricVersions(metric.code()), metric.publishedAt());
    }

    // ---------------------------------------------------------------- 写

    @Transactional
    public MetricDetail create(MetricUpsertReq req, String actor) {
        String operator = requireActor(actor);
        String code = normalizeCode(req.code());
        if (repo.findMetric(code).isPresent()) {
            throw new BusinessException(ErrorCode.CONFLICT, "指标编码 " + code + " 已存在");
        }
        MetricDefinition metric = validate(req, code, 1L, operator, true);
        if (!repo.insertMetric(metric)) {
            throw new BusinessException(ErrorCode.CONFLICT, "指标编码已被他人创建，请刷新后重试");
        }
        MetricDefinition saved = requireMetric(code);
        recordVersion(saved, MetricVersion.CREATE, operator);
        audit(operator, "METRIC_CREATE", code, Map.of("name", saved.name(),
                "domain", saved.domain()));
        return detail(code);
    }

    /** 修改草稿：只允许 DRAFT；发布过的口径必须先 revise。 */
    @Transactional
    public MetricDetail update(String code, MetricUpsertReq req, String actor) {
        String operator = requireActor(actor);
        MetricDefinition current = requireMetric(code);
        if (!current.draft()) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "已发布指标不能直接修改，请先修订（revise）出新版本");
        }
        if (req.version() == null || req.version() != current.version()) {
            throw new BusinessException(ErrorCode.CONFLICT, "指标已被他人修改，请刷新后重试");
        }
        MetricDefinition updated = validate(req, current.code(), current.version(), operator, false);
        if (!repo.updateMetric(updated)) {
            throw new BusinessException(ErrorCode.CONFLICT, "指标已被他人修改，请刷新后重试");
        }
        MetricDefinition saved = requireMetric(current.code());
        recordVersion(saved, MetricVersion.UPDATE, operator);
        audit(operator, "METRIC_UPDATE", current.code(), Map.of("version", saved.version()));
        return detail(current.code());
    }

    /** 修订已发布指标：进入下一版本 DRAFT，对外口径仍为 published_version。 */
    @Transactional
    public MetricDetail revise(String code, long version, String actor) {
        String operator = requireActor(actor);
        MetricDefinition current = requireMetric(code);
        if (!current.published()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "只有已发布指标需要修订，当前状态：" + current.status());
        }
        if (!repo.reviseMetric(current.code(), version, operator)) {
            throw new BusinessException(ErrorCode.CONFLICT, "指标已被他人修改，请刷新后重试");
        }
        MetricDefinition saved = requireMetric(code);
        recordVersion(saved, MetricVersion.REVISE, operator);
        audit(operator, "METRIC_REVISE", code, Map.of("version", saved.version(),
                "publishedVersion", String.valueOf(saved.publishedVersion())));
        return detail(code);
    }

    /**
     * 发布：口径必须完整，且至少有一个上游源表/接口。
     *
     * <p>发布时把 sourceTables 自动登记为 UPSTREAM 血缘，保证"发布版本"与血缘一致；
     * 下游（报表/看板）通过 {@link #addLineage} 登记后即可做影响分析。
     */
    @Transactional
    public MetricDetail publish(String code, long version, String actor) {
        String operator = requireActor(actor);
        MetricDefinition current = requireMetric(code);
        if (!current.draft()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "只有草稿指标可以发布，当前状态：" + current.status());
        }
        if (current.version() != version) {
            throw new BusinessException(ErrorCode.CONFLICT, "指标已被他人修改，请刷新后重试");
        }
        assertPublishable(current);
        if (!repo.publishMetric(current.code(), version, operator)) {
            throw new BusinessException(ErrorCode.CONFLICT, "指标已被他人修改，请刷新后重试");
        }
        registerSourceTables(current, operator);
        MetricDefinition saved = requireMetric(code);
        recordVersion(saved, MetricVersion.PUBLISH, operator);
        audit(operator, "METRIC_PUBLISH", code, Map.of("version", version,
                "sources", saved.sourceTables().size()));
        return detail(code);
    }

    /** 停用：草稿/已发布均可停用；停用后不再对外提供口径，但历史版本保留。 */
    @Transactional
    public MetricDetail disable(String code, long version, String actor) {
        String operator = requireActor(actor);
        MetricDefinition current = requireMetric(code);
        if (!repo.disableMetric(current.code(), version, operator)) {
            throw new BusinessException(ErrorCode.CONFLICT, "指标已被他人修改，请刷新后重试");
        }
        MetricDefinition saved = requireMetric(code);
        recordVersion(saved, MetricVersion.DISABLE, operator);
        audit(operator, "METRIC_DISABLE", code, Map.of("version", saved.version()));
        return detail(code);
    }

    /** 登记血缘（同指标同方向同引用幂等）。 */
    @Transactional
    public MetricDetail addLineage(String code, MetricLineageReq req, String actor) {
        String operator = requireActor(actor);
        MetricDefinition metric = requireMetric(code);
        String direction = req.direction() == null ? "" : req.direction().trim().toUpperCase();
        if (!MetricLineage.UPSTREAM.equals(direction)
                && !MetricLineage.DOWNSTREAM.equals(direction)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "血缘方向只能是 UPSTREAM/DOWNSTREAM");
        }
        String refType = req.refType() == null ? "" : req.refType().trim().toUpperCase();
        if (!REF_TYPES.contains(refType)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "引用类型不合法：" + req.refType());
        }
        String refCode = req.refCode() == null ? "" : req.refCode().trim();
        if (refCode.length() < 2) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "引用编码至少 2 个字符");
        }
        MetricLineage lineage = new MetricLineage(UUID.randomUUID(), metric.code(), direction,
                refType, refCode, trimNullable(req.description()), operator, Instant.now());
        boolean created = repo.insertMetricLineage(lineage);
        audit(operator, "METRIC_LINEAGE", code, Map.of("direction", direction,
                "refType", refType, "refCode", refCode, "created", created));
        return detail(code);
    }

    // ---------------------------------------------------------------- 内部

    private MetricDefinition validate(MetricUpsertReq req, String code, long version, String actor,
                                      boolean creating) {
        String domain = upper(req.domain(), "指标域");
        if (!DOMAINS.contains(domain)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "指标域不合法：" + req.domain());
        }
        String aggregation = upper(req.aggregation(), "聚合方式");
        if (!AGGREGATIONS.contains(aggregation)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "聚合方式不合法：" + req.aggregation());
        }
        String timeWindow = upper(req.timeWindow(), "时间窗");
        if (!TIME_WINDOWS.contains(timeWindow)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "时间窗不合法：" + req.timeWindow());
        }
        String granularity = upper(req.granularity(), "统计粒度");
        if (!GRANULARITIES.contains(granularity)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "统计粒度不合法：" + req.granularity());
        }
        String definition = required(req.definition(), "口径定义", 10, 2000);
        String formula = required(req.formula(), "计算公式", 3, 2000);
        String unit = required(req.unit(), "单位", 1, 32);
        String ownerDept = required(req.ownerDept(), "责任科室", 2, 64);
        List<String> sources = req.sourceTables() == null ? List.of()
                : req.sourceTables().stream().map(String::trim).filter(s -> !s.isEmpty())
                        .distinct().toList();
        if (sources.size() > MAX_SOURCE_TABLES) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "源表最多 " + MAX_SOURCE_TABLES + " 个");
        }
        for (String source : sources) {
            if (source.length() > 128) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "源表名过长：" + source);
            }
        }
        MetricDefinition current = creating ? null : requireMetric(code);
        return new MetricDefinition(code, required(req.name(), "指标名称", 2, 128), domain,
                definition, formula, aggregation, unit, timeWindow, granularity,
                trimNullable(req.sourceSchema()), sources, ownerDept,
                trimNullable(req.ownerRole()), creating ? MetricDefinition.DRAFT
                        : current.status(), version, current == null ? null
                        : current.publishedVersion(), current == null ? null : current.publishedAt(),
                current == null ? null : current.publishedBy(),
                creating ? actor : current.createdBy(), actor,
                current == null ? Instant.now() : current.createdAt(), Instant.now());
    }

    private void assertPublishable(MetricDefinition metric) {
        if (metric.sourceTables().isEmpty()
                && repo.countMetricLineage(metric.code(), MetricLineage.UPSTREAM) == 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "发布前必须至少登记一个上游源表或接口（保证口径可追溯）");
        }
    }

    /** 发布时把 sourceTables 登记为 UPSTREAM 血缘（幂等，不删除历史）。 */
    private void registerSourceTables(MetricDefinition metric, String actor) {
        for (String table : metric.sourceTables()) {
            String ref = metric.sourceSchema() == null || metric.sourceSchema().isBlank()
                    ? table : metric.sourceSchema() + "." + table;
            repo.insertMetricLineage(new MetricLineage(UUID.randomUUID(), metric.code(),
                    MetricLineage.UPSTREAM, "TABLE", ref, "发布时登记的源表", actor,
                    Instant.now()));
        }
    }

    private void recordVersion(MetricDefinition metric, String changeType, String actor) {
        Map<String, Object> snapshot = snapshot(metric);
        repo.insertMetricVersion(new MetricVersion(0L, metric.code(), metric.version(),
                changeType, snapshot, sha256(snapshot), actor, Instant.now()));
    }

    /** 版本快照：只放口径相关字段，敏感信息为零；字段顺序固定便于校验和复算。 */
    private Map<String, Object> snapshot(MetricDefinition m) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("code", m.code());
        snapshot.put("name", m.name());
        snapshot.put("domain", m.domain());
        snapshot.put("definition", m.definition());
        snapshot.put("formula", m.formula());
        snapshot.put("aggregation", m.aggregation());
        snapshot.put("unit", m.unit());
        snapshot.put("timeWindow", m.timeWindow());
        snapshot.put("granularity", m.granularity());
        snapshot.put("sourceSchema", m.sourceSchema());
        snapshot.put("sourceTables", m.sourceTables());
        snapshot.put("ownerDept", m.ownerDept());
        snapshot.put("ownerRole", m.ownerRole());
        snapshot.put("status", m.status());
        snapshot.put("version", m.version());
        snapshot.put("publishedVersion", m.publishedVersion());
        return snapshot;
    }

    private String sha256(Map<String, Object> snapshot) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(json.writeValueAsString(snapshot).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (JacksonException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("指标版本快照无法计算校验和", e);
        }
    }

    private MetricDefinition requireMetric(String code) {
        String normalized = normalizeCode(code);
        return repo.findMetric(normalized)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                        "未找到指标：" + code));
    }

    private static String normalizeCode(String raw) {
        String code = raw == null ? "" : raw.trim();
        if (!code.matches("[A-Za-z][A-Za-z0-9_]{2,63}")) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "指标编码只允许字母开头、字母/数字/下划线（3–64 位）");
        }
        return code;
    }

    private static String upper(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, label + "不能为空");
        }
        return value.trim().toUpperCase();
    }

    private static String required(String value, String label, int min, int max) {
        String text = value == null ? "" : value.trim();
        if (text.length() < min) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    label + "至少 " + min + " 个字符");
        }
        if (text.length() > max) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    label + "不能超过 " + max + " 个字符");
        }
        return text;
    }

    private static String trimNullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String requireActor(String actor) {
        if (actor == null || actor.isBlank() || "system".equalsIgnoreCase(actor.trim())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "指标治理操作必须记录真实操作人");
        }
        return actor.trim();
    }

    private void audit(String actor, String action, String code, Map<String, Object> detail) {
        repo.insertAudit(actor, action, "METRIC", code, code, detail, null, null);
    }
}
