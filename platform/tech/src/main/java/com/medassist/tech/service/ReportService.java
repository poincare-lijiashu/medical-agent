package com.medassist.tech.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.tech.config.TechProperties;
import com.medassist.tech.domain.TechModel.Application;
import com.medassist.tech.domain.TechModel.CriticalValue;
import com.medassist.tech.domain.TechModel.Report;
import com.medassist.tech.domain.TechModel.ReportTemplate;
import com.medassist.tech.repo.TechRepository;
import com.medassist.tech.web.TechDtos.AmendReportReq;
import com.medassist.tech.web.TechDtos.CriticalLine;
import com.medassist.tech.web.TechDtos.CriticalView;
import com.medassist.tech.web.TechDtos.ReportView;
import com.medassist.tech.web.TechDtos.WriteReportReq;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 报告与危急值。
 *
 * <p>关键规则：
 * <ul>
 *   <li>报告必须"追加版本"：修订不覆盖历史，旧版本 is_current=false 保留可追溯；</li>
 *   <li>只有 DRAFT 可改（重写），FINAL/AMENDED 只能走修订；</li>
 *   <li>危急值在书写报告时创建（PENDING），**不因报告签发而自动消解**；</li>
 *   <li>危急值必须先确认（ACKNOWLEDGED）再关闭（CLOSED），不允许跳过确认；</li>
 *   <li>申请关闭前必须无未关闭危急值（由 ApplicationService.close 校验）。</li>
 * </ul>
 */
@Service
public class ReportService {

    private final TechRepository repo;
    private final TechProperties props;
    private final ObjectMapper objectMapper;

    public ReportService(TechRepository repo, TechProperties props, ObjectMapper objectMapper) {
        this.repo = repo;
        this.props = props;
        this.objectMapper = objectMapper;
    }

    // ---------------------------------------------------------------- 书写

    @Transactional
    public ReportView write(UUID applicationId, WriteReportReq req, String actor) {
        Application app = repo.lockApplication(applicationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TECH_APPLICATION_NOT_FOUND));
        if (!Application.IN_PROGRESS.equals(app.status())) {
            throw new BusinessException(ErrorCode.TECH_APPLICATION_STATUS_INVALID,
                    "只有执行中（IN_PROGRESS）的申请可以书写报告，当前：" + app.status());
        }
        if (repo.findCurrentReport(applicationId).isPresent()) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "该申请已有报告，请在原报告上重写草稿或走修订流程");
        }
        boolean hasCritical = req.criticalLines() != null && !req.criticalLines().isEmpty();
        Structured structured = structured(req.templateId(), req.structured());
        Report report = new Report(UUID.randomUUID(), repo.nextReportNo(), applicationId,
                repo.nextReportRevision(applicationId), true, req.findings().trim(),
                req.conclusion().trim(), actor, null, null, Report.DRAFT, hasCritical, null,
                structured.templateId(), structured.json());
        repo.insertReport(report);
        List<CriticalValue> criticals = insertCriticals(report, req.criticalLines());

        audit(actor, "REPORT_WRITE", "REPORT", report.id().toString(), Map.of(
                "reportNo", report.reportNo(), "applicationNo", app.applicationNo(),
                "criticalCount", criticals.size()));
        return new ReportView(repo.findReport(report.id()).orElseThrow(), criticals,
                repo.listReports(applicationId));
    }

    /** 重写草稿：只允许 DRAFT，避免"签发后发现改过内容却无版本"。 */
    @Transactional
    public ReportView rewrite(UUID reportId, WriteReportReq req, String actor) {
        Report report = repo.findReport(reportId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TECH_REPORT_NOT_FOUND));
        if (!Report.DRAFT.equals(report.status())) {
            throw new BusinessException(ErrorCode.TECH_REPORT_STATUS_INVALID,
                    "只有草稿可以重写；已签发报告请走修订（amend）");
        }
        boolean hasCritical = req.criticalLines() != null && !req.criticalLines().isEmpty();
        Structured structured = structured(req.templateId(), req.structured());
        if (!repo.updateDraftReport(reportId, req.findings().trim(), req.conclusion().trim(),
                hasCritical, structured.templateId(), structured.json())) {
            throw new BusinessException(ErrorCode.CONFLICT, "报告状态已变化，请刷新后重试");
        }
        List<CriticalValue> criticals = new ArrayList<>();
        if (hasCritical) {
            // 按 report_id 定向全量比对：只要本报告已登记过危急值（含已 CLOSED）就不再新增。
            // 若按"未关闭"过滤，重写会把已闭环的危急值重新插成 PENDING，导致申请永远无法关闭。
            List<CriticalValue> existing = repo.listCriticalValuesByReport(reportId);
            if (existing.isEmpty()) {
                criticals = insertCriticals(report, req.criticalLines());
            } else {
                criticals = existing;
            }
        }
        audit(actor, "REPORT_REWRITE", "REPORT", reportId.toString(),
                Map.of("reportNo", report.reportNo(), "criticalCount", criticals.size()));
        return new ReportView(repo.findReport(reportId).orElseThrow(), criticals,
                repo.listReports(report.applicationId()));
    }

    // ---------------------------------------------------------------- 审核 / 修订

    /**
     * 审核签发。
     *
     * <p>approve=false 时报告保持 DRAFT（不销毁），技师可重写后再次提交；驳回原因入审计。
     */
    @Transactional
    public ReportView review(UUID reportId, boolean approve, String reason, String actor) {
        Report report = repo.findReport(reportId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TECH_REPORT_NOT_FOUND));
        if (!Report.DRAFT.equals(report.status())) {
            throw new BusinessException(ErrorCode.TECH_REPORT_STATUS_INVALID,
                    "只有草稿可以审核，当前：" + report.status());
        }
        if (!approve) {
            audit(actor, "REPORT_REJECT", "REPORT", reportId.toString(), Map.of(
                    "reportNo", report.reportNo(),
                    "reason", reason == null ? "" : reason.trim()));
            return new ReportView(report, listCriticalsOf(reportId), repo.listReports(report.applicationId()));
        }
        if (!repo.finalizeReport(reportId, actor, report.revision())) {
            throw new BusinessException(ErrorCode.CONFLICT, "报告状态已变化，请刷新后重试");
        }
        Application app = repo.lockApplication(report.applicationId())
                .orElseThrow(() -> new BusinessException(ErrorCode.TECH_APPLICATION_NOT_FOUND));
        if (!repo.updateApplicationStatus(app.id(), Application.IN_PROGRESS,
                Application.REPORTED, app.version())) {
            throw new BusinessException(ErrorCode.CONFLICT, "申请状态已变化，请刷新后重试");
        }
        audit(actor, "REPORT_FINAL", "REPORT", reportId.toString(), Map.of(
                "reportNo", report.reportNo(), "applicationNo", app.applicationNo(),
                "criticalFlag", report.criticalFlag()));
        return new ReportView(repo.findReport(reportId).orElseThrow(), listCriticalsOf(reportId),
                repo.listReports(report.applicationId()));
    }

    /** 修订：对已签发报告追加新版本（旧版本保留为历史，is_current 切换）。 */
    @Transactional
    public ReportView amend(UUID reportId, AmendReportReq req, String actor) {
        Report old = repo.findReport(reportId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TECH_REPORT_NOT_FOUND));
        if (!old.finalized()) {
            throw new BusinessException(ErrorCode.TECH_REPORT_STATUS_INVALID,
                    "只有已签发报告可以修订，当前：" + old.status());
        }
        repo.clearCurrentReport(old.applicationId());
        int revision = repo.nextReportRevision(old.applicationId());
        Structured structured = structured(req.templateId(), req.structured());
        Report amended = new Report(UUID.randomUUID(), old.reportNo(), old.applicationId(),
                revision, true, req.findings().trim(), req.conclusion().trim(), actor,
                actor, Instant.now(), Report.AMENDED, old.criticalFlag(), null,
                structured.templateId(), structured.json());
        repo.insertReport(amended);
        audit(actor, "REPORT_AMEND", "REPORT", amended.id().toString(), Map.of(
                "reportNo", old.reportNo(), "fromRevision", old.revision(),
                "toRevision", revision, "reason", req.reason() == null ? "" : req.reason().trim()));
        return new ReportView(repo.findReport(amended.id()).orElseThrow(),
                listCriticalsOf(amended.id()), repo.listReports(old.applicationId()));
    }

    // ---------------------------------------------------------------- 查询

    public ReportView detail(UUID reportId) {
        Report report = repo.findReport(reportId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TECH_REPORT_NOT_FOUND));
        return new ReportView(report, listCriticalsOf(reportId), repo.listReports(report.applicationId()));
    }

    public ReportView currentOf(UUID applicationId) {
        Report report = repo.findCurrentReport(applicationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TECH_REPORT_NOT_FOUND, "该申请还没有报告"));
        return new ReportView(report, listCriticalsOf(report.id()), repo.listReports(applicationId));
    }

    // ---------------------------------------------------------------- 危急值

    /** 危急值列表；overdue = 超过确认时限仍处于 PENDING。 */
    public List<CriticalView> criticals(String status, UUID applicationId, Integer limit) {
        return criticals(status, applicationId, java.util.Set.of(), limit);
    }

    /** 危急值列表（G49）：按申请院区收敛，空集合表示跨院区角色不限院区。 */
    public List<CriticalView> criticals(String status, UUID applicationId,
                                        java.util.Set<UUID> campusIds, Integer limit) {
        String normalized = (status == null || status.isBlank()) ? null : status.trim().toUpperCase();
        int size = limit == null || limit <= 0 ? 50 : Math.min(limit, 200);
        Duration timeout = Duration.ofMinutes(props.criticalTimeoutMinutesOrDefault());
        Instant deadline = Instant.now().minus(timeout);
        return repo.listCriticalValues(normalized, applicationId,
                        ApplicationService.campusCsv(campusIds), size).stream()
                .map(c -> new CriticalView(c,
                        CriticalValue.PENDING.equals(c.status())
                                && c.createdAt().isBefore(deadline)))
                .toList();
    }

    /** 临床确认（护士/医师）。确认后仍为"未关闭"，需再关闭才算闭环。 */
    @Transactional
    public CriticalView acknowledge(UUID criticalId, String note, String actor) {
        CriticalValue critical = repo.findCriticalValue(criticalId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TECH_CRITICAL_NOT_FOUND));
        if (!CriticalValue.PENDING.equals(critical.status())) {
            throw new BusinessException(ErrorCode.TECH_CRITICAL_STATUS_INVALID,
                    "只有待确认的危急值可以确认，当前：" + critical.status());
        }
        if (!repo.acknowledgeCritical(criticalId, actor, note)) {
            throw new BusinessException(ErrorCode.CONFLICT, "危急值状态已变化，请刷新后重试");
        }
        audit(actor, "CRITICAL_ACK", "CRITICAL", criticalId.toString(), Map.of(
                "itemName", critical.itemName(), "resultValue", critical.resultValue()));
        return new CriticalView(repo.findCriticalValue(criticalId).orElseThrow(), false);
    }

    /** 关闭危急值（必须已确认）。 */
    @Transactional
    public CriticalView closeCritical(UUID criticalId, String note, String actor) {
        CriticalValue critical = repo.findCriticalValue(criticalId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TECH_CRITICAL_NOT_FOUND));
        if (!CriticalValue.ACKNOWLEDGED.equals(critical.status())) {
            throw new BusinessException(ErrorCode.TECH_CRITICAL_STATUS_INVALID,
                    "危急值必须先确认再关闭，当前：" + critical.status());
        }
        if (!repo.closeCritical(criticalId, note)) {
            throw new BusinessException(ErrorCode.CONFLICT, "危急值状态已变化，请刷新后重试");
        }
        audit(actor, "CRITICAL_CLOSE", "CRITICAL", criticalId.toString(), Map.of(
                "itemName", critical.itemName()));
        return new CriticalView(repo.findCriticalValue(criticalId).orElseThrow(), false);
    }

    // ---------------------------------------------------------------- 结构化内容

    private record Structured(UUID templateId, String json) {
    }

    /**
     * 校验并序列化结构化内容。
     *
     * <p>未选模板时内容必须为空；选了模板必须是 PUBLISHED，且必填字段齐全、不得出现模板外字段。
     */
    private Structured structured(UUID templateId, Map<String, Object> values) {
        if (templateId == null) {
            if (values != null && !values.isEmpty()) {
                throw new BusinessException(ErrorCode.TECH_REPORT_STRUCTURED_INVALID,
                        "填写了结构化内容但未选择报告模板");
            }
            return new Structured(null, null);
        }
        ReportTemplate template = repo.findReportTemplate(templateId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TECH_REPORT_TEMPLATE_NOT_FOUND));
        if (!template.published()) {
            throw new BusinessException(ErrorCode.TECH_REPORT_STRUCTURED_INVALID,
                    "模板 " + template.code() + " 尚未发布，不能用于报告");
        }
        try {
            JsonNode schema = objectMapper.readTree(template.schemaJson());
            JsonNode fields = schema == null ? null : schema.get("fields");
            Set<String> allowed = new HashSet<>();
            if (fields != null) {
                for (JsonNode field : fields) {
                    String key = field.path("key").asText("");
                    if (!key.isBlank()) {
                        allowed.add(key);
                    }
                    boolean required = field.path("required").asBoolean(false);
                    Object value = values == null ? null : values.get(key);
                    if (required && (value == null || String.valueOf(value).isBlank())) {
                        String label = field.path("label").asText(key);
                        throw new BusinessException(ErrorCode.TECH_REPORT_STRUCTURED_INVALID,
                                "模板必填字段「" + label + "」不能为空");
                    }
                }
            }
            if (values != null) {
                for (String key : values.keySet()) {
                    if (!allowed.contains(key)) {
                        throw new BusinessException(ErrorCode.TECH_REPORT_STRUCTURED_INVALID,
                                "模板中不存在字段：" + key);
                    }
                }
            }
            String json = values == null || values.isEmpty() ? "{}"
                    : objectMapper.writeValueAsString(values);
            return new Structured(templateId, json);
        } catch (JacksonException e) {
            throw new BusinessException(ErrorCode.TECH_REPORT_STRUCTURED_INVALID,
                    "结构化内容无法序列化");
        }
    }

    // ---------------------------------------------------------------- 内部

    private List<CriticalValue> insertCriticals(Report report, List<CriticalLine> lines) {
        List<CriticalValue> created = new ArrayList<>();
        if (lines == null) {
            return created;
        }
        for (CriticalLine line : lines) {
            CriticalValue c = new CriticalValue(UUID.randomUUID(), report.applicationId(),
                    report.id(), line.itemName().trim(), line.resultValue().trim(),
                    blankToNull(line.referenceRange()),
                    line.severity() == null || line.severity().isBlank() ? "HIGH" : line.severity(),
                    CriticalValue.PENDING, blankToNull(line.notifiedTo()), null,
                    null, null, null, null);
            repo.insertCriticalValue(c);
            created.add(repo.findCriticalValue(c.id()).orElse(c));
        }
        return created;
    }

    private List<CriticalValue> listCriticalsOf(UUID reportId) {
        return repo.listCriticalValuesByReport(reportId);
    }

    private String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private void audit(String actor, String action, String targetType, String targetId,
                       Map<String, Object> detail) {
        try {
            repo.logChange(actor, action, targetType, targetId,
                    objectMapper.writeValueAsString(new LinkedHashMap<>(detail)));
        } catch (JacksonException e) {
            repo.logChange(actor, action, targetType, targetId, "{\"_detail_serialize_failed\":true}");
        }
    }
}
