package com.medassist.masterdata.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.masterdata.domain.DataQualityModel.Dashboard;
import com.medassist.masterdata.domain.DataQualityModel.Issue;
import com.medassist.masterdata.domain.DataQualityModel.Rule;
import com.medassist.masterdata.domain.DataQualityModel.Run;
import com.medassist.masterdata.repo.DataQualityRepository;
import com.medassist.masterdata.repo.MasterDataRepository;
import com.medassist.masterdata.web.DataQualityDtos;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 数据质量与指标稽核（§299）。
 *
 * <p>质量规则是声明式 SQL 探针，执行后形成运行记录；失败自动开问题单。
 * 探针只允许只读 SELECT/WITH，且 5 秒超时——质量检查不能成为拖垮数据库的入口。
 */
@Service
public class DataQualityService {

    /** 明确禁止的写操作/DDL/系统目录/延时函数。 */
    private static final Pattern FORBIDDEN = Pattern.compile(
            "\\b(insert|update|delete|drop|alter|create|truncate|grant|revoke|copy|call|do|"
                    + "vacuum|analyze|reindex|refresh|lock|set|reset|comment|security|into|"
                    + "pg_sleep|dblink)\\b|\\b(pg_|information_schema|pg_catalog)");

    private final DataQualityRepository repo;
    private final MasterDataRepository masterRepo;
    private final Clock clock;

    public DataQualityService(DataQualityRepository repo, MasterDataRepository masterRepo,
                              Clock clock) {
        this.repo = repo;
        this.masterRepo = masterRepo;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ 规则

    public List<Rule> listRules(String domain, String status, int limit) {
        return repo.listRules(domain, status, Math.min(Math.max(limit, 1), 200));
    }

    public Rule rule(UUID id) {
        return requireRule(id);
    }

    public Rule upsertRule(DataQualityDtos.RuleReq req, String actor, String clientIp,
                           String traceId) {
        requireActor(actor);
        validateRule(req);
        Optional<Rule> existing = repo.findRuleByCode(req.code());
        Instant now = clock.instant();
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("name", req.name().trim());
        fields.put("domain", req.domain());
        fields.put("dimension", req.dimension());
        fields.put("sql_text", req.sqlText().trim());
        fields.put("comparator", req.comparator());
        fields.put("threshold", req.threshold());
        fields.put("threshold2", "BETWEEN".equals(req.comparator()) ? req.threshold2() : null);
        fields.put("unit", req.unit());
        fields.put("severity", req.severity());
        fields.put("owner", req.owner().trim());
        fields.put("status", req.status() == null ? "ACTIVE" : req.status());
        if (existing.isPresent()) {
            Rule rule = existing.get();
            if (!repo.updateRule(rule.id(), rule.version(), fields)) {
                throw new BusinessException(ErrorCode.CONFLICT, "质量规则已被他人修改，请刷新后重试");
            }
        } else {
            repo.insertRule(new Rule(UUID.randomUUID(), req.code(), (String) fields.get("name"),
                    req.domain(), req.dimension(), (String) fields.get("sql_text"),
                    req.comparator(), req.threshold(), (BigDecimal) fields.get("threshold2"),
                    req.unit(), req.severity(), (String) fields.get("owner"),
                    (String) fields.get("status"), 0, actor, now, now));
        }
        audit(actor, "DQ_RULE_UPSERT", req.code(), Map.of("domain", req.domain(),
                "dimension", req.dimension(), "comparator", req.comparator()), clientIp, traceId);
        return repo.findRuleByCode(req.code()).orElseThrow();
    }

    private static void validateRule(DataQualityDtos.RuleReq req) {
        BigDecimal threshold = req.threshold();
        if (threshold.signum() < 0) {
            throw new BusinessException(ErrorCode.MDM_DQ_RULE_INVALID, "阈值不能为负数");
        }
        if ("PERCENT".equals(req.unit())
                && threshold.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new BusinessException(ErrorCode.MDM_DQ_RULE_INVALID, "百分比阈值不能超过 100");
        }
        if ("BETWEEN".equals(req.comparator())) {
            if (req.threshold2() == null) {
                throw new BusinessException(ErrorCode.MDM_DQ_RULE_INVALID,
                        "BETWEEN 必须提供上下限");
            }
            if (req.threshold2().compareTo(threshold) < 0) {
                throw new BusinessException(ErrorCode.MDM_DQ_RULE_INVALID,
                        "BETWEEN 的上限不能小于下限");
            }
        }
        assertSafeSql(req.sqlText());
    }

    /**
     * SQL 白名单：只允许单条 SELECT/WITH；禁止写入、DDL、系统目录与显式事务。
     *
     * <p>规则维护本身是受信操作（{@code mdm:quality:manage}），但受信不等于无限制：
     * 一条误写的 UPDATE 会改变生产数据，必须在入口拦住。
     */
    public static void assertSafeSql(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new BusinessException(ErrorCode.MDM_DQ_SQL_UNSAFE, "探针 SQL 不能为空");
        }
        String text = sql.trim();
        String lower = text.toLowerCase(Locale.ROOT);
        if (!(lower.startsWith("select") || lower.startsWith("with"))) {
            throw new BusinessException(ErrorCode.MDM_DQ_SQL_UNSAFE);
        }
        if (text.contains(";") || lower.contains("--") || lower.contains("/*")) {
            throw new BusinessException(ErrorCode.MDM_DQ_SQL_UNSAFE,
                    "探针只允许单条语句，禁止分号与注释");
        }
        Matcher matcher = FORBIDDEN.matcher(lower);
        if (matcher.find()) {
            throw new BusinessException(ErrorCode.MDM_DQ_SQL_UNSAFE,
                    "探针包含受限关键字：" + matcher.group());
        }
    }

    // ------------------------------------------------------------------ 执行

    public Run runRule(UUID ruleId, String actor, String clientIp, String traceId) {
        requireActor(actor);
        Rule rule = requireRule(ruleId);
        if (!"ACTIVE".equals(rule.status())) {
            throw new BusinessException(ErrorCode.MDM_DQ_RULE_NOT_FOUND);
        }
        Run run = execute(rule, "MANUAL", actor);
        audit(actor, "DQ_RUN", rule.code(),
                Map.of("runNo", run.runNo(), "status", run.status(),
                        "observed", run.observedValue() == null ? "" : run.observedValue()),
                clientIp, traceId);
        return run;
    }

    /** 调度/批量执行：逐条执行，单条失败不影响其余规则。 */
    public List<Run> runAll(String actor) {
        requireActor(actor);
        List<Run> runs = new ArrayList<>();
        for (Rule rule : repo.activeRules(200)) {
            runs.add(execute(rule, "SCHEDULED", actor));
        }
        return runs;
    }

    private Run execute(Rule rule, String mode, String actor) {
        Instant started = clock.instant();
        String status;
        BigDecimal observed = null;
        String error = null;
        try {
            observed = repo.probe(rule.sqlText());
            if (observed == null) {
                status = "ERROR";
                error = "探针没有返回数值";
            } else {
                status = matches(rule, observed) ? "PASSED" : "FAILED";
            }
        } catch (Exception e) {
            status = "ERROR";
            error = truncate(messageOf(e), 500);
        }
        Instant finished = clock.instant();
        int duration = (int) Math.min(Duration.between(started, finished).toMillis(), Integer.MAX_VALUE);
        Run run = new Run(UUID.randomUUID(), repo.nextRunNo(), rule.id(), rule.code(), status,
                observed, "PASSED".equals(status), error, mode, actor, duration, started, finished);
        repo.insertRun(run);
        if ("FAILED".equals(status)) {
            openOrTouchIssue(rule, run);
        }
        return run;
    }

    private static boolean matches(Rule rule, BigDecimal value) {
        int cmp = value.compareTo(rule.threshold());
        return switch (rule.comparator()) {
            case "LTE" -> cmp <= 0;
            case "GTE" -> cmp >= 0;
            case "EQ" -> cmp == 0;
            case "BETWEEN" -> cmp >= 0 && rule.threshold2() != null
                    && value.compareTo(rule.threshold2()) <= 0;
            default -> false;
        };
    }

    private void openOrTouchIssue(Rule rule, Run run) {
        Optional<Issue> open = repo.findOpenIssue(rule.id());
        if (open.isPresent()) {
            Issue issue = open.get();
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("last_run_id", run.id());
            fields.put("occurrence_count", issue.occurrenceCount() + 1);
            fields.put("observed_value", run.observedValue());
            repo.updateIssue(issue.id(), issue.version(), fields);
            return;
        }
        Issue issue = new Issue(UUID.randomUUID(), repo.nextIssueNo(), rule.id(), rule.code(),
                rule.name(), rule.severity(), "OPEN", rule.owner(), run.id(), run.id(), 1,
                run.observedValue(), null, null, null, null, null, null, null, 0,
                clock.instant(), clock.instant());
        repo.insertIssue(issue);
    }

    public List<Run> listRuns(String ruleCode, String status, int limit, int offset) {
        int size = Math.min(Math.max(limit, 1), 200);
        return repo.listRuns(ruleCode, status, size, Math.max(offset, 0));
    }

    public long countRuns(String ruleCode, String status) {
        return repo.countRuns(ruleCode, status);
    }

    public Run run(UUID id) {
        return repo.findRun(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.MDM_DQ_RUN_NOT_FOUND));
    }

    // ------------------------------------------------------------------ 问题闭环

    public List<Issue> listIssues(String status, String severity, int limit, int offset) {
        int size = Math.min(Math.max(limit, 1), 200);
        return repo.listIssues(status, severity, size, Math.max(offset, 0));
    }

    public long countIssues(String status) {
        return repo.countIssues(status);
    }

    public Issue issue(UUID id) {
        return requireIssue(id);
    }

    public Issue acknowledge(UUID id, DataQualityDtos.IssueAckReq req, String actor,
                             String clientIp, String traceId) {
        requireActor(actor);
        Issue issue = requireIssue(id);
        if (!"OPEN".equals(issue.status())) {
            throw new BusinessException(ErrorCode.MDM_DQ_ISSUE_STATUS_INVALID,
                    "只有待处理的问题可以确认");
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("status", "ACKNOWLEDGED");
        fields.put("acknowledged_by", actor);
        fields.put("acknowledged_at", clock.instant());
        updateIssue(issue, fields);
        audit(actor, "DQ_ISSUE_ACK", issue.issueNo(),
                req == null || req.note() == null ? Map.of() : Map.of("note", req.note()),
                clientIp, traceId);
        return requireIssue(id);
    }

    public Issue resolve(UUID id, DataQualityDtos.IssueResolveReq req, String actor,
                         String clientIp, String traceId) {
        requireActor(actor);
        Issue issue = requireIssue(id);
        if (!"OPEN".equals(issue.status()) && !"ACKNOWLEDGED".equals(issue.status())) {
            throw new BusinessException(ErrorCode.MDM_DQ_ISSUE_STATUS_INVALID,
                    "只有未闭环的问题可以解决");
        }
        if (req.resolution() == null || req.resolution().trim().length() < 2) {
            throw new BusinessException(ErrorCode.MDM_DQ_ISSUE_RESOLUTION_REQUIRED);
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("status", "RESOLVED");
        fields.put("resolution", req.resolution().trim());
        fields.put("resolved_at", clock.instant());
        updateIssue(issue, fields);
        audit(actor, "DQ_ISSUE_RESOLVE", issue.issueNo(),
                Map.of("resolution", req.resolution().trim()), clientIp, traceId);
        return requireIssue(id);
    }

    public Issue waive(UUID id, DataQualityDtos.IssueWaiveReq req, String actor,
                       String clientIp, String traceId) {
        requireActor(actor);
        Issue issue = requireIssue(id);
        if (!"OPEN".equals(issue.status()) && !"ACKNOWLEDGED".equals(issue.status())) {
            throw new BusinessException(ErrorCode.MDM_DQ_ISSUE_STATUS_INVALID,
                    "只有未闭环的问题可以豁免");
        }
        if (req.reason() == null || req.reason().trim().length() < 2) {
            throw new BusinessException(ErrorCode.MDM_DQ_ISSUE_WAIVER_REASON_REQUIRED);
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("status", "WAIVED");
        fields.put("waiver_reason", req.reason().trim());
        fields.put("waiver_by", actor);
        fields.put("waiver_at", clock.instant());
        updateIssue(issue, fields);
        audit(actor, "DQ_ISSUE_WAIVE", issue.issueNo(),
                Map.of("reason", req.reason().trim()), clientIp, traceId);
        return requireIssue(id);
    }

    private void updateIssue(Issue issue, Map<String, Object> fields) {
        if (!repo.updateIssue(issue.id(), issue.version(), fields)) {
            throw new BusinessException(ErrorCode.MDM_DQ_ISSUE_STATUS_INVALID,
                    "质量问题已被他人更新，请刷新后重试");
        }
    }

    private Issue requireIssue(UUID id) {
        return repo.findIssue(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.MDM_DQ_ISSUE_NOT_FOUND));
    }

    // ------------------------------------------------------------------ 看板

    public Dashboard dashboard() {
        Instant since = clock.instant().minus(Duration.ofHours(24));
        long runs = repo.countRunsSince(since, null);
        long failed = repo.countRunsSince(since, "FAILED");
        long errors = repo.countRunsSince(since, "ERROR");
        long passed = repo.countRunsSince(since, "PASSED");
        double passRate = runs == 0 ? 0 : round2(passed * 100.0 / runs);
        return new Dashboard(clock.instant(), repo.countActiveRules(), runs, failed, errors,
                passRate, repo.countOpenIssues(), repo.countOpenBlockers(),
                repo.topFailures(since, 5));
    }

    // ------------------------------------------------------------------ 内部

    private Rule requireRule(UUID id) {
        return repo.findRule(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.MDM_DQ_RULE_NOT_FOUND));
    }

    private void audit(String actor, String action, String refId, Map<String, Object> detail,
                       String clientIp, String traceId) {
        masterRepo.insertAudit(actor, action, null, null, refId, detail, clientIp, traceId);
    }

    private static void requireActor(String actor) {
        if (actor == null || actor.isBlank()) {
            throw new BusinessException(ErrorCode.MDM_DQ_ACTOR_REQUIRED);
        }
    }

    private static String messageOf(Exception e) {
        if (e instanceof DataAccessException dae && dae.getMostSpecificCause() != null) {
            return dae.getMostSpecificCause().getMessage();
        }
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        String value = text.replaceAll("\\s+", " ").trim();
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
