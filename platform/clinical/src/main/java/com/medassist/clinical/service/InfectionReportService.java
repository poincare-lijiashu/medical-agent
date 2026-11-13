package com.medassist.clinical.service;

import com.medassist.clinical.config.InfectionMetrics;
import com.medassist.clinical.domain.ClinicalModel.InfectionDetection;
import com.medassist.clinical.domain.ClinicalModel.InfectionDiagnosisCandidate;
import com.medassist.clinical.domain.ClinicalModel.InfectionDisease;
import com.medassist.clinical.domain.ClinicalModel.InfectionReport;
import com.medassist.clinical.domain.ClinicalModel.InfectionReportEvent;
import com.medassist.clinical.domain.ClinicalModel.InfectionSummary;
import com.medassist.clinical.repo.ClinicalRepository;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.time.BusinessTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 传染病上报与漏报监测（§294 / 计划书 W1.9）。
 *
 * <p>法定动作，不能只是"有张表"：
 * <ul>
 *   <li>诊断自动匹配法定目录，生成待报卡（同就诊同病种幂等）；</li>
 *   <li>截止时间 = 诊断时间 + 病种时限（甲类 2 小时，乙/丙类 24 小时），超时即迟报；</li>
 *   <li>提交必须写报告人；疾控受理必须有回执号；退回必须写原因；已提交卡不可作废；</li>
 *   <li>每次流转写追加式事件与审计，漏报/迟报可量化。</li>
 * </ul>
 */
@Service
public class InfectionReportService {

    private static final int MAX_SCAN = 2_000;
    private static final int MAX_REPORT_PAGE = 500;
    private static final int MAX_EVENTS = 100;

    private final ClinicalRepository repo;
    private final ObjectMapper json;
    private final InfectionMetrics metrics;

    public InfectionReportService(ClinicalRepository repo, ObjectMapper json,
                                  InfectionMetrics metrics) {
        this.repo = repo;
        this.json = json;
        this.metrics = metrics;
    }

    // ---------------------------------------------------------------- 查询

    public List<InfectionDisease> diseases(boolean onlyEnabled) {
        return repo.listInfectionDiseases(onlyEnabled);
    }

    public InfectionSummary summary() {
        Map<String, Object> raw = repo.infectionSummaryRaw(BusinessTime.zone().getId());
        InfectionSummary summary = new InfectionSummary(
                num(raw.get("draft")), num(raw.get("overdue")), num(raw.get("submittedToday")),
                num(raw.get("accepted")), num(raw.get("returned")), num(raw.get("totalActive")),
                raw.get("timelyRate") == null ? new java.math.BigDecimal("100.00")
                        : (java.math.BigDecimal) raw.get("timelyRate"));
        metrics.recordPending(summary.draft());
        metrics.recordOverdue(summary.overdue());
        return summary;
    }

    public List<InfectionReport> list(String status, LocalDate from, LocalDate to, String keyword,
                                      boolean overdueOnly, int limit) {
        return repo.listInfectionReports(status, from, to, keyword, overdueOnly,
                Math.max(1, Math.min(limit, MAX_REPORT_PAGE)));
    }

    public ReportDetail detail(UUID id) {
        InfectionReport report = requireReport(id);
        return new ReportDetail(report, repo.listInfectionEvents(id).stream()
                .limit(MAX_EVENTS).toList());
    }

    public record ReportDetail(InfectionReport report, List<InfectionReportEvent> events) {
    }

    // ---------------------------------------------------------------- 自动扫描

    /**
     * 扫描水位之后的诊断，匹配法定目录并生成待报卡。
     *
     * <p>同就诊同病种只生成一张有效卡；并发/重复扫描由部分唯一索引兜底。
     */
    @Transactional
    public InfectionDetection detect(String actor) {
        List<InfectionDisease> catalog = repo.listInfectionDiseases(true);
        if (catalog.isEmpty()) {
            return new InfectionDetection(0, 0, 0);
        }
        Instant since = repo.infectionScanWatermark()
                .orElse(Instant.now().minus(7, ChronoUnit.DAYS));
        List<InfectionDiagnosisCandidate> candidates = repo.listInfectionCandidates(since, MAX_SCAN);
        int created = 0;
        int skipped = 0;
        Instant watermark = null;
        for (InfectionDiagnosisCandidate candidate : candidates) {
            watermark = candidate.diagnosedAt();
            InfectionDisease matched = match(catalog, candidate.code());
            if (matched == null) {
                skipped++;
                continue;
            }
            if (repo.findActiveInfectionReport(candidate.visitId(), matched.code()).isPresent()) {
                skipped++;
                continue;
            }
            InfectionReport report = newCard(candidate, matched, Instant.now(), actor,
                    InfectionReport.SOURCE_AUTO);
            if (insert(report)) {
                created++;
            } else {
                skipped++;
            }
        }
        repo.updateInfectionScanState(watermark, created);
        if (created > 0 || !candidates.isEmpty()) {
            repo.logChange(actor, "INFECTION_AUTO_DETECT", "INFECTION_REPORT", "-",
                    detail(Map.of("scanned", candidates.size(), "created", created,
                            "skipped", skipped)));
        }
        metrics.action("detect", true);
        summary();
        return new InfectionDetection(candidates.size(), created, skipped);
    }

    // ---------------------------------------------------------------- 手工补录

    @Transactional
    public ReportDetail createManual(UUID visitId, String diseaseCode, LocalDate onsetDate,
                                     String icdCode, String icdName, String reporterDept,
                                     String actor) {
        requireActor(actor);
        if (visitId == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "必须指定就诊");
        }
        var visit = repo.findVisit(visitId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "就诊不存在"));
        String code = diseaseCode == null ? "" : diseaseCode.trim();
        InfectionDisease disease = repo.findInfectionDisease(code)
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "传染病目录不存在：" + diseaseCode));
        if (!disease.enabled()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "该病种已停用，不能上报：" + disease.name());
        }
        if (repo.findActiveInfectionReport(visitId, code).isPresent()) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "该就诊已存在 " + disease.name() + " 的有效报告卡");
        }
        Instant now = Instant.now();
        InfectionReport report = new InfectionReport(UUID.randomUUID(),
                repo.nextInfectionReportNo(), visitId, visit.patientId(), visit.patientNo(),
                disease.code(), disease.name(), disease.diseaseClass(),
                trim(icdCode), trim(icdName) == null ? disease.name() : trim(icdName),
                onsetDate, now, deadline(now, disease.reportHours()), InfectionReport.DRAFT,
                InfectionReport.SOURCE_MANUAL, null, trim(reporterDept), null, null,
                null, null, null, null, actor, actor, now, now, 0);
        if (!insert(report)) {
            throw new BusinessException(ErrorCode.CONFLICT, "报告卡已被他人创建，请刷新后重试");
        }
        metrics.action("manual", true);
        summary();
        return detail(report.id());
    }

    // ---------------------------------------------------------------- 状态流转

    @Transactional
    public ReportDetail submit(UUID id, long version, String reporter, String reporterDept,
                               String actor) {
        requireActor(actor);
        String name = required(reporter, "报告人", 2, 64);
        InfectionReport current = requireReport(id);
        if (!InfectionReport.DRAFT.equals(current.status())
                && !InfectionReport.RETURNED.equals(current.status())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "只有草稿或退回的报告卡可以提交，当前：" + current.status());
        }
        if (!repo.submitInfectionReport(id, version, name, trim(reporterDept), actor)) {
            throw new BusinessException(ErrorCode.CONFLICT, "报告卡已被他人修改，请刷新后重试");
        }
        String action = InfectionReport.RETURNED.equals(current.status()) ? "RESUBMIT" : "SUBMIT";
        repo.insertInfectionEvent(id, action, current.status(), InfectionReport.SUBMITTED,
                actor, "报告人：" + name);
        audit(actor, "INFECTION_SUBMIT", id, Map.of("reportNo", current.reportNo(),
                "disease", current.diseaseName(), "timely",
                String.valueOf(!Instant.now().isAfter(current.reportDeadline()))));
        metrics.action("submit", true);
        summary();
        return detail(id);
    }

    @Transactional
    public ReportDetail accept(UUID id, long version, String receiptNo, String actor) {
        requireActor(actor);
        String receipt = required(receiptNo, "疾控回执号", 2, 64);
        InfectionReport current = requireReport(id);
        if (!InfectionReport.SUBMITTED.equals(current.status())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "只有已提交的报告卡可以登记受理，当前：" + current.status());
        }
        if (!repo.acceptInfectionReport(id, version, receipt, actor)) {
            throw new BusinessException(ErrorCode.CONFLICT, "报告卡已被他人修改，请刷新后重试");
        }
        repo.insertInfectionEvent(id, "ACCEPT", current.status(), InfectionReport.ACCEPTED,
                actor, "回执号：" + receipt);
        audit(actor, "INFECTION_ACCEPT", id, Map.of("reportNo", current.reportNo(),
                "receiptNo", receipt));
        metrics.action("accept", true);
        return detail(id);
    }

    @Transactional
    public ReportDetail returnCard(UUID id, long version, String reason, String actor) {
        requireActor(actor);
        String note = required(reason, "退回原因", 2, 256);
        InfectionReport current = requireReport(id);
        if (!InfectionReport.SUBMITTED.equals(current.status())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "只有已提交的报告卡可以退回，当前：" + current.status());
        }
        if (!repo.returnInfectionReport(id, version, note, actor)) {
            throw new BusinessException(ErrorCode.CONFLICT, "报告卡已被他人修改，请刷新后重试");
        }
        repo.insertInfectionEvent(id, "RETURN", current.status(), InfectionReport.RETURNED,
                actor, note);
        audit(actor, "INFECTION_RETURN", id, Map.of("reportNo", current.reportNo(),
                "reason", note));
        metrics.action("return", true);
        summary();
        return detail(id);
    }

    @Transactional
    public ReportDetail voidCard(UUID id, long version, String reason, String actor) {
        requireActor(actor);
        String note = required(reason, "作废原因", 2, 256);
        InfectionReport current = requireReport(id);
        if (!InfectionReport.DRAFT.equals(current.status())
                && !InfectionReport.RETURNED.equals(current.status())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "已提交/已受理的报告卡不能作废；如确需更正请联系疾控");
        }
        if (!repo.voidInfectionReport(id, version, note, actor)) {
            throw new BusinessException(ErrorCode.CONFLICT, "报告卡已被他人修改，请刷新后重试");
        }
        repo.insertInfectionEvent(id, "VOID", current.status(), InfectionReport.VOID, actor, note);
        audit(actor, "INFECTION_VOID", id, Map.of("reportNo", current.reportNo(),
                "reason", note));
        metrics.action("void", true);
        summary();
        return detail(id);
    }

    // ---------------------------------------------------------------- 内部

    private boolean insert(InfectionReport report) {
        if (!repo.insertInfectionReport(report)) {
            return false;
        }
        repo.insertInfectionEvent(report.id(), "CREATE", null, InfectionReport.DRAFT,
                report.createdBy(), "来源：" + report.source());
        return true;
    }

    private InfectionReport newCard(InfectionDiagnosisCandidate candidate,
                                    InfectionDisease disease, Instant now, String actor,
                                    String source) {
        return new InfectionReport(UUID.randomUUID(), repo.nextInfectionReportNo(),
                candidate.visitId(), candidate.patientId(), candidate.patientNo(),
                disease.code(), disease.name(), disease.diseaseClass(), candidate.code(),
                candidate.name(), null, candidate.diagnosedAt(),
                deadline(candidate.diagnosedAt(), disease.reportHours()),
                InfectionReport.DRAFT, source, null, null, null, null, null, null, null,
                null, actor, actor, now, now, 0);
    }

    private static Instant deadline(Instant diagnosedAt, int hours) {
        return diagnosedAt.plus(hours, ChronoUnit.HOURS);
    }

    private static InfectionDisease match(List<InfectionDisease> catalog, String icdCode) {
        if (icdCode == null || icdCode.isBlank()) {
            return null;
        }
        for (InfectionDisease disease : catalog) {
            for (String prefix : disease.icdPrefixes()) {
                if (!prefix.isBlank() && icdCode.startsWith(prefix)) {
                    return disease;
                }
            }
        }
        return null;
    }

    private InfectionReport requireReport(UUID id) {
        return repo.findInfectionReport(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "未找到报告卡"));
    }

    private static long num(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
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

    private static String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String requireActor(String actor) {
        if (actor == null || actor.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "缺少操作人");
        }
        return actor.trim();
    }

    private void audit(String actor, String action, UUID id, Map<String, Object> detail) {
        repo.logChange(actor, action, "INFECTION_REPORT", id.toString(), detail(detail));
    }

    private String detail(Map<String, ?> value) {
        try {
            return json.writeValueAsString(new LinkedHashMap<>(value));
        } catch (JacksonException e) {
            return "{}";
        }
    }
}
