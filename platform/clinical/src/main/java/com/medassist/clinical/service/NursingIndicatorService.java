package com.medassist.clinical.service;

import com.medassist.clinical.config.NursingIndicatorProperties;
import com.medassist.clinical.domain.ClinicalModel.Admission;
import com.medassist.clinical.domain.ClinicalModel.Dept;
import com.medassist.clinical.domain.ClinicalModel.NursingAdmissionStay;
import com.medassist.clinical.domain.ClinicalModel.NursingIndicatorDef;
import com.medassist.clinical.domain.ClinicalModel.NursingIndicatorDenominator;
import com.medassist.clinical.domain.ClinicalModel.NursingIndicatorEvent;
import com.medassist.clinical.domain.ClinicalModel.NursingIndicatorEventCount;
import com.medassist.clinical.domain.ClinicalModel.NursingIndicatorReport;
import com.medassist.clinical.domain.ClinicalModel.NursingNurseDayCount;
import com.medassist.clinical.domain.ClinicalModel.NursingShift;
import com.medassist.clinical.domain.ClinicalModel.NursingShiftEventCount;
import com.medassist.clinical.domain.ClinicalModel.NursingShiftNurseCount;
import com.medassist.clinical.domain.ClinicalModel.NursingTimelinessCount;
import com.medassist.clinical.domain.ClinicalModel.Ward;
import com.medassist.clinical.repo.ClinicalRepository;
import com.medassist.clinical.web.NursingIndicatorDtos.DenominatorBatchReq;
import com.medassist.clinical.web.NursingIndicatorDtos.DenominatorBatchResult;
import com.medassist.clinical.web.NursingIndicatorDtos.DenominatorItemReq;
import com.medassist.clinical.web.NursingIndicatorDtos.DenominatorView;
import com.medassist.clinical.web.NursingIndicatorDtos.EventReq;
import com.medassist.clinical.web.NursingIndicatorDtos.EventView;
import com.medassist.clinical.web.NursingIndicatorDtos.IndicatorBoardView;
import com.medassist.clinical.web.NursingIndicatorDtos.IndicatorDefView;
import com.medassist.clinical.web.NursingIndicatorDtos.IndicatorMetricView;
import com.medassist.clinical.web.NursingIndicatorDtos.ReportGenerateReq;
import com.medassist.clinical.web.NursingIndicatorDtos.ReportSummaryView;
import com.medassist.clinical.web.NursingIndicatorDtos.ReportView;
import com.medassist.clinical.web.NursingIndicatorDtos.ReviewReq;
import com.medassist.clinical.web.NursingIndicatorDtos.ShiftDayView;
import com.medassist.clinical.web.NursingIndicatorDtos.ShiftDetailView;
import com.medassist.clinical.web.NursingIndicatorDtos.ShiftNurseView;
import com.medassist.clinical.web.NursingIndicatorDtos.ShiftStatView;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.time.BusinessTime;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 护理敏感质量指标（§266）。
 *
 * <p>三件事必须做对，否则指标没有可信度：
 * <ol>
 *   <li><b>班次归属</b>：按 {@code nursing_shift} 的起止时间解析发生时间；跨午夜班次在午夜后发生的
 *       事件归属到班次开始日，指定班次与发生时间不匹配直接拒绝（不能随手挪班）；</li>
 *   <li><b>分母诚实</b>：住院床日由住院登记与本地日切分推导，设备日由科室按日录入；
 *       分母为 0 时比值为 {@code null} 并标记 dataComplete=false，绝不输出 0；</li>
 *   <li><b>上报闭环</b>：周期快照生成后进入 DRAFT，提交后只能由他人审核；退回必须写原因，
 *       重新生成会清空上一轮的提交/审核痕迹，避免"改完数据还保留通过结论"。</li>
 * </ol>
 *
 * <p>刻意不做：不自动把护理不良事件写回病历、不修改医嘱或收费——指标是质量测量，不是执行动作。
 */
@Service
public class NursingIndicatorService {

    private static final Set<String> SEVERITIES = Set.of(
            "NEAR_MISS", "NO_HARM", "MILD", "MODERATE", "SEVERE", "DEATH");
    private static final Set<String> DEVICE_TYPES = Set.of(
            "VENTILATOR", "URINARY_CATHETER", "CENTRAL_LINE");

    private static final String STATUS_DRAFT = NursingIndicatorReport.DRAFT;
    private static final String STATUS_SUBMITTED = NursingIndicatorReport.SUBMITTED;
    private static final String STATUS_ACCEPTED = NursingIndicatorReport.ACCEPTED;
    private static final String STATUS_RETURNED = NursingIndicatorReport.RETURNED;

    private static final Pattern QUARTER_KEY = Pattern.compile("^(\\d{4})-Q([1-4])$");
    private static final Pattern YEAR_KEY = Pattern.compile("^(\\d{4})$");
    private static final DateTimeFormatter MONTH_KEY = DateTimeFormatter.ofPattern("yyyy-MM");
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    private static final int MAX_REPORT_LOOKBACK_YEARS = 5;
    private static final int EVENT_LIST_DEFAULT = 50;
    private static final int EVENT_LIST_MAX = 200;
    private static final int REPORT_LIST_DEFAULT = 30;
    private static final int REPORT_LIST_MAX = 100;
    private static final int DETAIL_MAX_LENGTH = 4000;

    private final ClinicalRepository repo;
    private final ObjectMapper objectMapper;
    private final NursingIndicatorProperties properties;
    private final ZoneId zone = BusinessTime.zone();

    public NursingIndicatorService(ClinicalRepository repo, ObjectMapper objectMapper,
                                   NursingIndicatorProperties properties) {
        this.repo = repo;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    // ---------------------------------------------------------------- 指标字典

    public List<IndicatorDefView> listDefinitions() {
        return repo.listNursingIndicatorDefs().stream().map(this::toDefView).toList();
    }

    // ---------------------------------------------------------------- 不良事件

    /** 上报不良事件：自动/指定班次归属，写入后不可改（追加式）。 */
    @Transactional
    public EventView reportEvent(EventReq req, String actor) {
        Dept dept = requireDept(req.deptId());
        NursingIndicatorDef def = requireEventIndicator(req.indicatorCode());
        Instant now = Instant.now();
        Instant occurred = req.occurredAt();
        if (occurred.isAfter(now.plus(5, ChronoUnit.MINUTES))) {
            throw new BusinessException(ErrorCode.NURSING_INDICATOR_EVENT_INVALID,
                    "事件发生时间不能晚于当前时间");
        }
        if (occurred.isBefore(now.minus(properties.maxBackDaysOrDefault(), ChronoUnit.DAYS))) {
            throw new BusinessException(ErrorCode.NURSING_INDICATOR_EVENT_INVALID,
                    "事件发生时间超过可补录范围（" + properties.maxBackDaysOrDefault() + " 天）");
        }
        if (req.discoveredAt() != null
                && req.discoveredAt().isBefore(occurred.minus(5, ChronoUnit.MINUTES))) {
            throw new BusinessException(ErrorCode.NURSING_INDICATOR_EVENT_INVALID,
                    "发现时间不能早于发生时间");
        }
        String severity = normalizeUpper(req.severity());
        if (severity == null || !SEVERITIES.contains(severity)) {
            throw new BusinessException(ErrorCode.NURSING_INDICATOR_EVENT_INVALID,
                    "事件等级只能是 " + String.join("/", SEVERITIES));
        }

        Ward ward = null;
        UUID wardId = req.wardId();
        UUID admissionId = req.admissionId();
        String patientNo = trimToNull(req.patientNo());
        if (admissionId != null) {
            Admission admission = repo.findAdmission(admissionId)
                    .orElseThrow(() -> new BusinessException(
                            ErrorCode.NURSING_INDICATOR_EVENT_INVALID, "未找到该住院登记"));
            ward = repo.findWard(admission.wardId())
                    .orElseThrow(() -> new BusinessException(
                            ErrorCode.NURSING_INDICATOR_EVENT_INVALID, "住院登记所属病区不存在"));
            if (!dept.id().equals(ward.deptId())) {
                throw new BusinessException(ErrorCode.NURSING_INDICATOR_EVENT_INVALID,
                        "住院登记不属于所选科室，请核对科室/病区");
            }
            if (wardId != null && !wardId.equals(ward.id())) {
                throw new BusinessException(ErrorCode.NURSING_INDICATOR_EVENT_INVALID,
                        "所选病区与住院登记不一致，请核对");
            }
            wardId = ward.id();
            patientNo = admission.patientNo();
        } else if (wardId != null) {
            ward = repo.findWard(wardId)
                    .orElseThrow(() -> new BusinessException(
                            ErrorCode.NURSING_INDICATOR_EVENT_INVALID, "未找到该病区"));
            if (!dept.id().equals(ward.deptId())) {
                throw new BusinessException(ErrorCode.NURSING_INDICATOR_EVENT_INVALID,
                        "病区不属于所选科室，请核对科室/病区");
            }
        }

        ShiftAttribution attribution = resolveShift(occurred, req.shiftCode());
        NursingIndicatorEvent entity = new NursingIndicatorEvent(UUID.randomUUID(),
                repo.nextNursingIndicatorEventNo(), def.code(), def.name(), dept.id(), dept.name(),
                wardId, ward == null ? null : ward.name(), admissionId, patientNo, occurred,
                req.discoveredAt(), now, attribution.shiftDate(), attribution.shift().code(),
                attribution.shift().name(),
                attribution.specified()
                        ? NursingIndicatorEvent.SHIFT_SPECIFIED
                        : NursingIndicatorEvent.SHIFT_AUTO,
                severity, serializeDetail(req.detail()), trimToNull(req.note()), actor, now);
        repo.insertNursingIndicatorEvent(entity);

        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("indicatorCode", def.code());
        audit.put("deptId", dept.id().toString());
        audit.put("occurredAt", occurred.toString());
        audit.put("shiftDate", attribution.shiftDate().toString());
        audit.put("shiftCode", attribution.shift().code());
        audit.put("severity", severity);
        audit.put("shiftSource", entity.shiftSource());
        audit(actor, "NURSING_INDICATOR_EVENT_REPORT", entity.id().toString(), audit);
        return toEventView(entity);
    }

    /** 事件列表：科室必填，区间默认最近 30 天，最长按配置（默认 92 天）。 */
    public List<EventView> listEvents(UUID deptId, LocalDate from, LocalDate to,
                                      String indicatorCode, String shiftCode, Integer limit) {
        requireDept(deptId);
        LocalDate[] range = resolveRange(from, to, 30);
        int n = limit == null ? EVENT_LIST_DEFAULT : Math.max(1, Math.min(limit, EVENT_LIST_MAX));
        return repo.listNursingIndicatorEvents(deptId, startOf(range[0]),
                        startOf(range[1].plusDays(1)), trimToNull(indicatorCode),
                        trimToNull(shiftCode), n).stream()
                .map(this::toEventView).toList();
    }

    // ---------------------------------------------------------------- 设备日

    /** 设备日录入：按日期+类型追加；同一天重复录入时以最新记录为准。 */
    @Transactional
    public DenominatorBatchResult recordDenominators(DenominatorBatchReq req, String actor) {
        LocalDate today = BusinessTime.today();
        LocalDate earliest = today.minusDays(properties.maxBackDaysOrDefault());
        int created = 0;
        for (DenominatorItemReq item : req.items()) {
            requireDept(item.deptId());
            if (item.statDate().isAfter(today)) {
                throw new BusinessException(ErrorCode.NURSING_INDICATOR_DENOMINATOR_INVALID,
                        "设备日日期不能晚于今天：" + item.statDate());
            }
            if (item.statDate().isBefore(earliest)) {
                throw new BusinessException(ErrorCode.NURSING_INDICATOR_DENOMINATOR_INVALID,
                        "设备日日期超过可补录范围（" + properties.maxBackDaysOrDefault() + " 天）");
            }
            String deviceType = normalizeUpper(item.deviceType());
            if (deviceType == null || !DEVICE_TYPES.contains(deviceType)) {
                throw new BusinessException(ErrorCode.NURSING_INDICATOR_DENOMINATOR_INVALID,
                        "设备类型只能是 " + String.join("/", DEVICE_TYPES));
            }
            if (item.count().compareTo(BigDecimal.valueOf(100000)) > 0) {
                throw new BusinessException(ErrorCode.NURSING_INDICATOR_DENOMINATOR_INVALID,
                        "设备日数量超出合理范围（最大 100000）");
            }
            repo.insertNursingIndicatorDenominator(UUID.randomUUID(), item.deptId(),
                    item.statDate(), deviceType, item.count().setScale(2, RoundingMode.HALF_UP),
                    trimToNull(item.note()), actor);
            created++;
        }
        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("created", created);
        audit(actor, "NURSING_INDICATOR_DENOMINATOR_RECORD", "batch", audit);
        return new DenominatorBatchResult(created);
    }

    public List<DenominatorView> listDenominators(UUID deptId, LocalDate from, LocalDate to) {
        requireDept(deptId);
        LocalDate[] range = resolveRange(from, to, 30);
        return repo.listNursingIndicatorDenominators(deptId, range[0], range[1]).stream()
                .map(d -> new DenominatorView(d.id(), d.deptId(), d.statDate(), d.deviceType(),
                        d.count(), d.note(), d.recordedBy(), d.recordedAt()))
                .toList();
    }

    // ---------------------------------------------------------------- 看板与班次

    /** 指标看板：周期内指标值、分母、班次密度与数据缺口警告。 */
    public IndicatorBoardView board(UUID deptId, LocalDate from, LocalDate to) {
        Dept dept = requireDept(deptId);
        LocalDate[] range = resolveRange(from, to, 0);
        Snapshot snapshot = compute(dept.id(), range[0], range[1]);
        return new IndicatorBoardView(dept.id(), range[0], range[1], snapshot.days(),
                snapshot.patientDays(), snapshot.avgCensus(), snapshot.bedCount(),
                snapshot.nurseDays(), snapshot.avgNurses(), snapshot.bedNurseRatio(),
                snapshot.nursePatientRatio(), snapshot.eventCount(), snapshot.timelyCount(),
                snapshot.timelyRate(), snapshot.metrics(), snapshot.shifts(),
                snapshot.warnings());
    }

    /**
     * 某业务日的班次明细：夜班跨午夜时，00:00–08:00 的事件归属前一天的业务日。
     *
     * <p>这里只读：护士名单来自排班（未取消），事件来自指标事件表，不做任何回写。
     */
    public ShiftDayView shiftSummary(UUID deptId, LocalDate date) {
        Dept dept = requireDept(deptId);
        LocalDate day = date == null ? BusinessTime.today() : date;
        LocalDate today = BusinessTime.today();
        if (day.isAfter(today)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "不能查看未来日期的班次汇总");
        }
        if (day.isBefore(today.minusDays(properties.maxBackDaysOrDefault()))) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "班次汇总超过可追溯范围（" + properties.maxBackDaysOrDefault() + " 天）");
        }
        List<NursingShift> shifts = repo.listNursingShifts();
        List<com.medassist.clinical.domain.ClinicalModel.NursingSchedule> schedules =
                repo.listNursingSchedules(deptId, day, day);
        List<NursingIndicatorEvent> events = repo.listNursingIndicatorEvents(deptId,
                startOf(day), startOf(day.plusDays(1)), null, null, EVENT_LIST_MAX);

        Map<String, List<com.medassist.clinical.domain.ClinicalModel.NursingSchedule>> byShift =
                new HashMap<>();
        for (var schedule : schedules) {
            if ("PUBLISHED".equals(schedule.status())) {
                byShift.computeIfAbsent(schedule.shiftCode(), k -> new ArrayList<>()).add(schedule);
            }
        }
        Map<String, long[]> eventCounts = new HashMap<>();
        long totalEvents = 0;
        for (NursingShiftEventCount row : repo.countNursingShiftEvents(deptId, day, day)) {
            eventCounts.put(row.shiftCode(), new long[]{row.total(), row.countable()});
            totalEvents += row.total();
        }

        List<ShiftDetailView> details = new ArrayList<>();
        int totalNurses = 0;
        for (NursingShift shift : shifts) {
            List<ShiftNurseView> nurses = byShift.getOrDefault(shift.code(), List.of()).stream()
                    .sorted(Comparator.comparing(
                            com.medassist.clinical.domain.ClinicalModel.NursingSchedule::nurseName))
                    .map(s -> new ShiftNurseView(s.nurseName(), s.nurseNo(), s.status()))
                    .toList();
            totalNurses += nurses.size();
            long[] counts = eventCounts.getOrDefault(shift.code(), new long[2]);
            details.add(new ShiftDetailView(day, shift.code(), shift.name(),
                    shift.startTime().format(TIME_FORMAT), shift.endTime().format(TIME_FORMAT),
                    shift.crossMidnight(), nurses, counts[0], counts[1],
                    perNurse(counts[0], nurses.size())));
        }
        return new ShiftDayView(dept.id(), day, totalNurses, totalEvents, details,
                events.stream().map(this::toEventView).toList());
    }

    // ---------------------------------------------------------------- 上报

    /** 生成/重新生成周期上报快照：DRAFT 与 RETURNED 可覆盖，已提交/已通过拒绝。 */
    @Transactional
    public ReportView generateReport(ReportGenerateReq req, String actor) {
        Dept dept = requireDept(req.deptId());
        String periodType = normalizePeriodType(req.periodType());
        PeriodRange range = parsePeriod(periodType, req.periodKey());
        String periodKey = normalizePeriodKey(periodType, req.periodKey());
        Optional<NursingIndicatorReport> existing =
                repo.findNursingIndicatorReportByPeriod(dept.id(), periodType, periodKey);
        if (existing.isPresent()) {
            NursingIndicatorReport old = existing.get();
            if (!STATUS_DRAFT.equals(old.status()) && !STATUS_RETURNED.equals(old.status())) {
                throw new BusinessException(ErrorCode.NURSING_INDICATOR_REPORT_STATUS_INVALID,
                        "该周期已提交或已通过，不能重新生成；如数据有误请先由审核人退回");
            }
        }
        Snapshot snapshot = compute(dept.id(), range.start(), range.end());
        String metricsJson = toJson(snapshot.metrics());
        String warningsJson = toJson(snapshot.warnings());
        Instant now = Instant.now();

        if (existing.isPresent()) {
            NursingIndicatorReport old = existing.get();
            NursingIndicatorReport updated = new NursingIndicatorReport(old.id(), old.reportNo(),
                    dept.id(), dept.name(), periodType, periodKey, range.start(), range.end(),
                    STATUS_DRAFT, snapshot.patientDays(), snapshot.eventCount(),
                    snapshot.timelyCount(), snapshot.nurseDays(), metricsJson, warningsJson,
                    actor, now, null, null, null, null, null, old.version() + 1);
            if (!repo.updateNursingIndicatorReport(updated, old.version())) {
                throw new BusinessException(ErrorCode.CONFLICT, "上报记录已被他人修改，请刷新后重试");
            }
            audit(actor, "NURSING_INDICATOR_REPORT_REGENERATE", old.id().toString(),
                    reportAudit(dept.id(), periodType, periodKey, snapshot));
            return toReportView(updated);
        }

        NursingIndicatorReport created = new NursingIndicatorReport(UUID.randomUUID(),
                repo.nextNursingIndicatorReportNo(), dept.id(), dept.name(), periodType,
                periodKey, range.start(), range.end(), STATUS_DRAFT, snapshot.patientDays(),
                snapshot.eventCount(), snapshot.timelyCount(), snapshot.nurseDays(), metricsJson,
                warningsJson, actor, now, null, null, null, null, null, 0L);
        try {
            repo.insertNursingIndicatorReport(created);
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "该科室该周期已有上报记录，请刷新后重试");
        }
        audit(actor, "NURSING_INDICATOR_REPORT_GENERATE", created.id().toString(),
                reportAudit(dept.id(), periodType, periodKey, snapshot));
        return toReportView(created);
    }

    public List<ReportSummaryView> listReports(UUID deptId, String periodType, String status,
                                               Integer limit) {
        if (deptId != null) {
            requireDept(deptId);
        }
        String type = periodType == null || periodType.isBlank()
                ? null : normalizePeriodType(periodType);
        String normalizedStatus = status == null || status.isBlank()
                ? null : normalizeUpper(status);
        if (normalizedStatus != null
                && !Set.of(STATUS_DRAFT, STATUS_SUBMITTED, STATUS_ACCEPTED, STATUS_RETURNED)
                .contains(normalizedStatus)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "上报状态不合法");
        }
        int n = limit == null ? REPORT_LIST_DEFAULT : Math.max(1, Math.min(limit, REPORT_LIST_MAX));
        return repo.listNursingIndicatorReports(deptId, type, normalizedStatus, n).stream()
                .map(this::toSummaryView).toList();
    }

    public ReportView getReport(UUID id) {
        return toReportView(requireReport(id));
    }

    /** 提交：DRAFT → SUBMITTED，记录提交人/时间。 */
    @Transactional
    public ReportView submit(UUID id, String actor) {
        NursingIndicatorReport report = requireReport(id);
        if (!STATUS_DRAFT.equals(report.status())) {
            throw new BusinessException(ErrorCode.NURSING_INDICATOR_REPORT_STATUS_INVALID,
                    "只有草稿状态可以提交；退回后请先重新生成");
        }
        if (!repo.submitNursingIndicatorReport(id, actor, report.version())) {
            throw new BusinessException(ErrorCode.CONFLICT, "上报记录已被他人修改，请刷新后重试");
        }
        audit(actor, "NURSING_INDICATOR_REPORT_SUBMIT", id.toString(),
                Map.of("deptId", report.deptId().toString(),
                        "periodType", report.periodType(), "periodKey", report.periodKey()));
        return toReportView(requireReport(id));
    }

    /** 审核：SUBMITTED → ACCEPTED / RETURNED；提交人不能审自己的上报。 */
    @Transactional
    public ReportView review(UUID id, ReviewReq req, String actor) {
        NursingIndicatorReport report = requireReport(id);
        if (!STATUS_SUBMITTED.equals(report.status())) {
            throw new BusinessException(ErrorCode.NURSING_INDICATOR_REPORT_STATUS_INVALID,
                    "只有已提交状态可以审核");
        }
        if (report.submittedBy() != null && report.submittedBy().equals(actor)) {
            throw new BusinessException(ErrorCode.NURSING_INDICATOR_SELF_REVIEW,
                    "上报审核不能由提交人本人完成，请由其他人复核");
        }
        String decision = normalizeUpper(req.decision());
        String target;
        String note = trimToNull(req.note());
        if ("ACCEPT".equals(decision)) {
            target = STATUS_ACCEPTED;
        } else if ("RETURN".equals(decision)) {
            target = STATUS_RETURNED;
            if (note == null) {
                throw new BusinessException(
                        ErrorCode.NURSING_INDICATOR_REPORT_RETURN_REASON_REQUIRED,
                        "退回上报必须填写原因");
            }
        } else {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "审核结论只能是 ACCEPT 或 RETURN");
        }
        if (!repo.reviewNursingIndicatorReport(id, target, actor, note, report.version())) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "上报记录已被他人审核或修改，请刷新后重试");
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("deptId", report.deptId().toString());
        detail.put("periodType", report.periodType());
        detail.put("periodKey", report.periodKey());
        detail.put("decision", target);
        detail.put("note", note);
        audit(actor, "NURSING_INDICATOR_REPORT_REVIEW", id.toString(), detail);
        return toReportView(requireReport(id));
    }

    // ---------------------------------------------------------------- 指标计算

    private Snapshot compute(UUID deptId, LocalDate start, LocalDate end) {
        int days = (int) ChronoUnit.DAYS.between(start, end) + 1;
        Instant fromTs = startOf(start);
        Instant toTs = startOf(end.plusDays(1));
        List<NursingIndicatorDef> defs = repo.listNursingIndicatorDefs();
        if (defs.isEmpty()) {
            throw new BusinessException(ErrorCode.NURSING_INDICATOR_NOT_FOUND,
                    "护理敏感指标字典为空，请先完成初始化");
        }

        Map<String, NursingIndicatorEventCount> counts = new HashMap<>();
        for (NursingIndicatorEventCount row : repo.countNursingIndicatorEvents(
                deptId, fromTs, toTs)) {
            counts.put(row.indicatorCode(), row);
        }
        NursingTimelinessCount timeliness = repo.countNursingTimeliness(deptId, fromTs, toTs,
                properties.reportWindowHoursOrDefault());
        long eventCount = timeliness.total();
        long timelyCount = timeliness.timely();

        BigDecimal patientDays = patientDays(deptId, start, end, fromTs, toTs);
        BigDecimal nurseDays = repo.listNursingNurseDays(deptId, start, end).stream()
                .map(row -> BigDecimal.valueOf(row.nurses()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        int bedCount = repo.countDeptBeds(deptId);

        Map<String, BigDecimal> deviceDays = new LinkedHashMap<>();
        for (NursingIndicatorDef def : defs) {
            if (NursingIndicatorDef.DENOM_DEVICE_DAYS.equals(def.denominatorType())
                    && def.deviceType() != null) {
                deviceDays.computeIfAbsent(def.deviceType(), type ->
                        repo.sumNursingDeviceDays(deptId, start, end, type));
            }
        }

        BigDecimal avgCensus = divide(patientDays, days, 2);
        BigDecimal avgNurses = divide(nurseDays, days, 2);
        BigDecimal bedNurseRatio = avgNurses == null || avgNurses.signum() == 0
                ? null : divide(BigDecimal.valueOf(bedCount), avgNurses, 2);
        BigDecimal nursePatientRatio = avgNurses == null || avgNurses.signum() == 0
                ? null : divide(avgCensus, avgNurses, 2);
        BigDecimal timelyRate = eventCount == 0
                ? null : percent(timelyCount, eventCount);

        Set<String> warningSet = new LinkedHashSet<>();
        if (nurseDays.signum() == 0) {
            warningSet.add("本期无排班数据，床护比/护患比无法计算");
        }
        if (patientDays.signum() == 0) {
            warningSet.add("本期无在院床日，床日类事件发生率无法计算");
        }
        if (bedCount == 0) {
            warningSet.add("科室未配置床位，床护比无法计算");
        }
        if (eventCount == 0) {
            warningSet.add("本期无护理不良事件上报，事件类指标与上报及时率无数据");
        }

        List<IndicatorMetricView> metrics = new ArrayList<>();
        for (NursingIndicatorDef def : defs) {
            metrics.add(metric(def, counts, deviceDays, patientDays, avgNurses, avgCensus,
                    bedCount, timelyCount, eventCount, warningSet));
        }

        Map<String, Long> nurseShifts = new HashMap<>();
        for (NursingShiftNurseCount row : repo.countNursingShiftNurses(deptId, start, end)) {
            nurseShifts.put(row.shiftCode(), row.nurseShifts());
        }
        Map<String, NursingShiftEventCount> shiftEvents = new HashMap<>();
        for (NursingShiftEventCount row : repo.countNursingShiftEvents(deptId, start, end)) {
            shiftEvents.put(row.shiftCode(), row);
        }
        List<ShiftStatView> shiftStats = new ArrayList<>();
        for (NursingShift shift : repo.listNursingShifts()) {
            long shifts = nurseShifts.getOrDefault(shift.code(), 0L);
            NursingShiftEventCount count = shiftEvents.get(shift.code());
            long total = count == null ? 0 : count.total();
            long countable = count == null ? 0 : count.countable();
            shiftStats.add(new ShiftStatView(shift.code(), shift.name(), shift.crossMidnight(),
                    shifts, total, countable, perNurse(total, shifts)));
        }

        return new Snapshot(days, money(patientDays), avgCensus, bedCount, money(nurseDays),
                avgNurses, bedNurseRatio, nursePatientRatio, eventCount, timelyCount, timelyRate,
                metrics, shiftStats, new ArrayList<>(warningSet));
    }

    private IndicatorMetricView metric(NursingIndicatorDef def,
                                       Map<String, NursingIndicatorEventCount> counts,
                                       Map<String, BigDecimal> deviceDays,
                                       BigDecimal patientDays, BigDecimal avgNurses,
                                       BigDecimal avgCensus, int bedCount, long timelyCount,
                                       long eventCount, Set<String> warnings) {
        switch (def.calcType()) {
            case NursingIndicatorDef.CALC_EVENT_RATE -> {
                NursingIndicatorEventCount count = counts.get(def.code());
                long countable = count == null ? 0 : count.countable();
                BigDecimal numerator = BigDecimal.valueOf(countable);
                BigDecimal denominator;
                boolean complete;
                if (NursingIndicatorDef.DENOM_DEVICE_DAYS.equals(def.denominatorType())) {
                    denominator = deviceDays.getOrDefault(def.deviceType(), BigDecimal.ZERO);
                    complete = denominator.signum() > 0;
                    if (!complete) {
                        warnings.add("「" + def.name() + "」缺少设备日数据（"
                                + deviceLabel(def.deviceType()) + "），比率不可计算");
                    }
                } else {
                    denominator = patientDays;
                    complete = patientDays.signum() > 0;
                }
                BigDecimal value = complete
                        ? rate(numerator, denominator, multiplier(def.unit())) : null;
                return new IndicatorMetricView(def.code(), def.name(), def.calcType(),
                        numerator, denominator, value, def.unit(), def.direction(),
                        def.targetValue(), meetsTarget(value, def), complete);
            }
            case NursingIndicatorDef.CALC_TIMELINESS -> {
                boolean complete = eventCount > 0;
                BigDecimal numerator = BigDecimal.valueOf(timelyCount);
                BigDecimal denominator = BigDecimal.valueOf(eventCount);
                BigDecimal value = complete ? percent(timelyCount, eventCount) : null;
                return new IndicatorMetricView(def.code(), def.name(), def.calcType(),
                        numerator, denominator, value, def.unit(), def.direction(),
                        def.targetValue(), meetsTarget(value, def), complete);
            }
            default -> {
                boolean bedRatio = "BED_NURSE_RATIO".equals(def.code());
                boolean nurseReady = avgNurses != null && avgNurses.signum() > 0;
                BigDecimal numerator = bedRatio ? BigDecimal.valueOf(bedCount) : avgCensus;
                BigDecimal denominator = avgNurses;
                BigDecimal value;
                boolean complete;
                if (bedRatio) {
                    complete = nurseReady && bedCount > 0;
                    value = complete ? divide(numerator, denominator, 2) : null;
                } else {
                    complete = nurseReady && avgCensus != null && avgCensus.signum() > 0;
                    value = complete ? divide(numerator, denominator, 2) : null;
                }
                return new IndicatorMetricView(def.code(), def.name(), def.calcType(),
                        numerator, denominator, value, def.unit(), def.direction(),
                        def.targetValue(), meetsTarget(value, def), complete);
            }
        }
    }

    /**
     * 住院床日：按本地日历日统计"该日 00:00–24:00 之间在院"。
     *
     * <p>入院当日计 1 天；出院当日若在 00:00 之后离院也计 1 天（用出院时间减 1ns 取日期）；
     * 00:00 整点出院不计当日，避免"当天入院当天 00:00 出院"被算成 1 个床日。
     */
    private BigDecimal patientDays(UUID deptId, LocalDate start, LocalDate end, Instant fromTs,
                                   Instant toTs) {
        long total = 0;
        for (NursingAdmissionStay stay : repo.listNursingAdmissionStays(deptId, fromTs, toTs)) {
            if (stay.admittedAt() == null) {
                continue;
            }
            LocalDate first = localDate(stay.admittedAt());
            if (first.isBefore(start)) {
                first = start;
            }
            LocalDate last;
            if (stay.dischargedAt() == null) {
                last = end;
            } else {
                last = localDate(stay.dischargedAt().minusNanos(1));
            }
            if (last.isAfter(end)) {
                last = end;
            }
            if (!last.isBefore(first)) {
                total += ChronoUnit.DAYS.between(first, last) + 1;
            }
        }
        return BigDecimal.valueOf(total);
    }

    // ---------------------------------------------------------------- 班次归属

    private record ShiftAttribution(LocalDate shiftDate, NursingShift shift, boolean specified) {
    }

    private ShiftAttribution resolveShift(Instant occurredAt, String shiftCode) {
        List<NursingShift> shifts = repo.listNursingShifts();
        if (shifts.isEmpty()) {
            throw new BusinessException(ErrorCode.NURSING_INDICATOR_SHIFT_INVALID,
                    "没有可用的护理班次字典，请先维护班次");
        }
        ZonedDateTime local = occurredAt.atZone(zone);
        LocalTime time = local.toLocalTime();
        String specifiedCode = trimToNull(shiftCode);
        if (specifiedCode != null) {
            NursingShift shift = shifts.stream()
                    .filter(s -> s.code().equals(specifiedCode))
                    .findFirst()
                    .orElseThrow(() -> new BusinessException(
                            ErrorCode.NURSING_INDICATOR_SHIFT_INVALID,
                            "未找到该班次或班次已停用：" + specifiedCode));
            if (!withinShift(shift, time)) {
                throw new BusinessException(ErrorCode.NURSING_INDICATOR_SHIFT_INVALID,
                        "发生时间 " + time.format(TIME_FORMAT) + " 不在" + shift.name()
                                + "（" + shift.startTime().format(TIME_FORMAT) + "-"
                                + shift.endTime().format(TIME_FORMAT) + "）时间窗内");
            }
            return new ShiftAttribution(shiftDateFor(shift, local), shift, true);
        }
        List<NursingShift> matches = shifts.stream()
                .filter(s -> withinShift(s, time))
                .toList();
        if (matches.isEmpty()) {
            throw new BusinessException(ErrorCode.NURSING_INDICATOR_SHIFT_INVALID,
                    "发生时间 " + time.format(TIME_FORMAT)
                            + " 不在任何班次时间窗内，请核对时间或指定班次");
        }
        if (matches.size() > 1) {
            throw new BusinessException(ErrorCode.NURSING_INDICATOR_SHIFT_INVALID,
                    "发生时间落在多个班次重叠区间，请指定班次");
        }
        NursingShift shift = matches.get(0);
        return new ShiftAttribution(shiftDateFor(shift, local), shift, false);
    }

    private static boolean withinShift(NursingShift shift, LocalTime time) {
        LocalTime start = shift.startTime();
        LocalTime end = shift.endTime();
        if (shift.crossMidnight()) {
            return !time.isBefore(start) || time.isBefore(end);
        }
        LocalTime effectiveEnd = end.equals(LocalTime.of(23, 59)) ? LocalTime.MAX : end;
        if (!effectiveEnd.isAfter(start)) {
            return !time.isBefore(start);
        }
        return !time.isBefore(start) && time.isBefore(effectiveEnd);
    }

    /** 跨午夜班次：午夜后（早于结束时间）归属到班次开始日。 */
    private static LocalDate shiftDateFor(NursingShift shift, ZonedDateTime local) {
        if (shift.crossMidnight() && local.toLocalTime().isBefore(shift.endTime())) {
            return local.toLocalDate().minusDays(1);
        }
        return local.toLocalDate();
    }

    // ---------------------------------------------------------------- 周期与区间

    private record PeriodRange(LocalDate start, LocalDate end) {
    }

    private PeriodRange parsePeriod(String periodType, String periodKey) {
        String key = periodKey == null ? "" : periodKey.trim();
        LocalDate start;
        LocalDate end;
        try {
            switch (periodType) {
                case NursingIndicatorReport.PERIOD_MONTH -> {
                    YearMonth month = YearMonth.parse(key, MONTH_KEY);
                    start = month.atDay(1);
                    end = month.atEndOfMonth();
                }
                case NursingIndicatorReport.PERIOD_QUARTER -> {
                    Matcher matcher = QUARTER_KEY.matcher(key.toUpperCase(Locale.ROOT));
                    if (!matcher.matches()) {
                        throw new BusinessException(ErrorCode.NURSING_INDICATOR_PERIOD_INVALID,
                                "季度格式应为 yyyy-Q1~Q4，例如 2026-Q4");
                    }
                    int year = Integer.parseInt(matcher.group(1));
                    int quarter = Integer.parseInt(matcher.group(2));
                    start = LocalDate.of(year, (quarter - 1) * 3 + 1, 1);
                    end = start.plusMonths(3).minusDays(1);
                }
                case NursingIndicatorReport.PERIOD_YEAR -> {
                    if (!YEAR_KEY.matcher(key).matches()) {
                        throw new BusinessException(ErrorCode.NURSING_INDICATOR_PERIOD_INVALID,
                                "年度格式应为 yyyy，例如 2026");
                    }
                    start = LocalDate.of(Integer.parseInt(key), 1, 1);
                    end = LocalDate.of(Integer.parseInt(key), 12, 31);
                }
                default -> throw new BusinessException(ErrorCode.NURSING_INDICATOR_PERIOD_INVALID,
                        "上报周期类型只能是 MONTH/QUARTER/YEAR");
            }
        } catch (DateTimeParseException e) {
            throw new BusinessException(ErrorCode.NURSING_INDICATOR_PERIOD_INVALID,
                    "周期格式不正确，月份示例：2026-11");
        }
        LocalDate today = BusinessTime.today();
        if (start.isAfter(today)) {
            throw new BusinessException(ErrorCode.NURSING_INDICATOR_PERIOD_INVALID,
                    "不能生成未来周期的上报");
        }
        if (end.isBefore(today.minusYears(MAX_REPORT_LOOKBACK_YEARS))) {
            throw new BusinessException(ErrorCode.NURSING_INDICATOR_PERIOD_INVALID,
                    "上报周期最多追溯 " + MAX_REPORT_LOOKBACK_YEARS + " 年");
        }
        return new PeriodRange(start, end);
    }

    private static String normalizePeriodType(String periodType) {
        String type = normalizeUpper(periodType);
        if (type == null || !Set.of(NursingIndicatorReport.PERIOD_MONTH,
                NursingIndicatorReport.PERIOD_QUARTER, NursingIndicatorReport.PERIOD_YEAR)
                .contains(type)) {
            throw new BusinessException(ErrorCode.NURSING_INDICATOR_PERIOD_INVALID,
                    "上报周期类型只能是 MONTH/QUARTER/YEAR");
        }
        return type;
    }

    private static String normalizePeriodKey(String periodType, String periodKey) {
        String key = periodKey.trim();
        if (NursingIndicatorReport.PERIOD_QUARTER.equals(periodType)) {
            return key.toUpperCase(Locale.ROOT);
        }
        return key;
    }

    /**
     * 查询区间：默认最近 {@code defaultDays} 天（看板为当月 1 日至今），
     * 未来日期拒绝，最长按配置（默认 92 天）。
     */
    private LocalDate[] resolveRange(LocalDate from, LocalDate to, int defaultDays) {
        LocalDate today = BusinessTime.today();
        LocalDate end = to == null ? today : to;
        LocalDate start = from;
        if (start == null) {
            start = defaultDays > 0 ? end.minusDays(defaultDays - 1L) : end.withDayOfMonth(1);
        }
        if (end.isAfter(today)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "不能查询未来日期");
        }
        if (start.isAfter(end)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "开始日期不能晚于结束日期");
        }
        if (ChronoUnit.DAYS.between(start, end) + 1 > properties.maxQueryDaysOrDefault()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "单次查询最多 " + properties.maxQueryDaysOrDefault() + " 天，请缩短区间");
        }
        return new LocalDate[]{start, end};
    }

    // ---------------------------------------------------------------- 视图映射

    private IndicatorDefView toDefView(NursingIndicatorDef def) {
        return new IndicatorDefView(def.id(), def.code(), def.name(), def.category(),
                def.calcType(), def.denominatorType(), def.deviceType(), def.unit(),
                def.direction(), def.targetValue(), def.sortNo());
    }

    private EventView toEventView(NursingIndicatorEvent event) {
        boolean timely = !event.reportedAt().isAfter(event.occurredAt()
                .plus(properties.reportWindowHoursOrDefault(), ChronoUnit.HOURS));
        return new EventView(event.id(), event.eventNo(), event.indicatorCode(),
                event.indicatorName(), event.deptId(), event.deptName(), event.wardId(),
                event.wardName(), event.admissionId(), event.patientNo(), event.occurredAt(),
                event.discoveredAt(), event.reportedAt(), event.shiftDate(), event.shiftCode(),
                event.shiftName(), event.shiftSource(), event.severity(), event.detail(),
                event.note(), event.reportedBy(), timely);
    }

    private ReportView toReportView(NursingIndicatorReport report) {
        return new ReportView(report.id(), report.reportNo(), report.deptId(), report.deptName(),
                report.periodType(), report.periodKey(), report.periodStart(), report.periodEnd(),
                report.status(), report.patientDays(), report.eventCount(), report.timelyCount(),
                report.nurseDays(), parseMetrics(report.metrics()), parseWarnings(report.warnings()),
                report.generatedBy(), report.generatedAt(), report.submittedBy(),
                report.submittedAt(), report.reviewedBy(), report.reviewedAt(),
                report.reviewNote(), report.version());
    }

    private ReportSummaryView toSummaryView(NursingIndicatorReport report) {
        return new ReportSummaryView(report.id(), report.reportNo(), report.deptId(),
                report.deptName(), report.periodType(), report.periodKey(), report.periodStart(),
                report.periodEnd(), report.status(), report.eventCount(), report.generatedBy(),
                report.generatedAt(), report.submittedBy(), report.submittedAt(),
                report.reviewedBy(), report.reviewedAt(), report.version());
    }

    private List<IndicatorMetricView> parseMetrics(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<IndicatorMetricView>>() {
            });
        } catch (JacksonException e) {
            return List.of();
        }
    }

    private List<String> parseWarnings(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (JacksonException e) {
            return List.of();
        }
    }

    // ---------------------------------------------------------------- 校验与工具

    private Dept requireDept(UUID deptId) {
        if (deptId == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "必须指定科室");
        }
        return repo.findDept(deptId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORG_NOT_FOUND, "未找到该科室"));
    }

    private NursingIndicatorDef requireEventIndicator(String code) {
        String normalized = trimToNull(code);
        if (normalized == null) {
            throw new BusinessException(ErrorCode.NURSING_INDICATOR_EVENT_INVALID,
                    "必须指定指标编码");
        }
        NursingIndicatorDef def = repo.findNursingIndicatorDef(normalized)
                .filter(NursingIndicatorDef::enabled)
                .orElseThrow(() -> new BusinessException(ErrorCode.NURSING_INDICATOR_NOT_FOUND,
                        "未找到该护理敏感指标，或指标已停用：" + normalized));
        if (!def.eventBased()) {
            throw new BusinessException(ErrorCode.NURSING_INDICATOR_EVENT_INVALID,
                    "「" + def.name() + "」不是不良事件类指标，不能录入事件");
        }
        return def;
    }

    private NursingIndicatorReport requireReport(UUID id) {
        return repo.findNursingIndicatorReport(id)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.NURSING_INDICATOR_REPORT_NOT_FOUND, "未找到该护理指标上报记录"));
    }

    private String serializeDetail(Map<String, Object> detail) {
        if (detail == null || detail.isEmpty()) {
            return "{}";
        }
        try {
            String json = objectMapper.writeValueAsString(detail);
            if (json.length() > DETAIL_MAX_LENGTH) {
                throw new BusinessException(ErrorCode.NURSING_INDICATOR_EVENT_INVALID,
                        "事件详情过大（最多 " + DETAIL_MAX_LENGTH + " 字符）");
            }
            return json;
        } catch (JacksonException e) {
            throw new BusinessException(ErrorCode.NURSING_INDICATOR_EVENT_INVALID,
                    "事件详情无法序列化，请检查字段");
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "指标快照序列化失败");
        }
    }

    private Map<String, Object> reportAudit(UUID deptId, String periodType, String periodKey,
                                            Snapshot snapshot) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("deptId", deptId.toString());
        detail.put("periodType", periodType);
        detail.put("periodKey", periodKey);
        detail.put("patientDays", snapshot.patientDays());
        detail.put("nurseDays", snapshot.nurseDays());
        detail.put("eventCount", snapshot.eventCount());
        detail.put("timelyCount", snapshot.timelyCount());
        detail.put("warnings", snapshot.warnings().size());
        return detail;
    }

    private void audit(String actor, String action, String targetId, Map<String, Object> detail) {
        try {
            repo.logChange(actor, action, "NURSING_INDICATOR", targetId,
                    objectMapper.writeValueAsString(detail));
        } catch (JacksonException e) {
            repo.logChange(actor, action, "NURSING_INDICATOR", targetId,
                    "{\"_detail_serialize_failed\":true}");
        }
    }

    private Instant startOf(LocalDate date) {
        return date.atStartOfDay(zone).toInstant();
    }

    private LocalDate localDate(Instant instant) {
        return instant.atZone(zone).toLocalDate();
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String normalizeUpper(String value) {
        String trimmed = trimToNull(value);
        return trimmed == null ? null : trimmed.toUpperCase(Locale.ROOT);
    }

    private static BigDecimal divide(BigDecimal numerator, long denominator, int scale) {
        if (numerator == null || denominator == 0) {
            return null;
        }
        return numerator.divide(BigDecimal.valueOf(denominator), scale, RoundingMode.HALF_UP);
    }

    private static BigDecimal divide(BigDecimal numerator, BigDecimal denominator, int scale) {
        if (numerator == null || denominator == null || denominator.signum() == 0) {
            return null;
        }
        return numerator.divide(denominator, scale, RoundingMode.HALF_UP);
    }

    private static BigDecimal rate(BigDecimal numerator, BigDecimal denominator, int multiplier) {
        if (numerator == null || denominator == null || denominator.signum() == 0) {
            return null;
        }
        return numerator.divide(denominator, 8, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(multiplier))
                .setScale(4, RoundingMode.HALF_UP);
    }

    private static BigDecimal percent(long numerator, long denominator) {
        if (denominator == 0) {
            return null;
        }
        return BigDecimal.valueOf(numerator)
                .divide(BigDecimal.valueOf(denominator), 8, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(4, RoundingMode.HALF_UP);
    }

    private static BigDecimal perNurse(long events, long nurses) {
        if (nurses <= 0) {
            return null;
        }
        return BigDecimal.valueOf(events)
                .divide(BigDecimal.valueOf(nurses), 4, RoundingMode.HALF_UP);
    }

    private static BigDecimal money(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }

    private static int multiplier(String unit) {
        if ("‰".equals(unit)) {
            return 1000;
        }
        if ("%".equals(unit)) {
            return 100;
        }
        return 1;
    }

    private static Boolean meetsTarget(BigDecimal value, NursingIndicatorDef def) {
        if (value == null || def.targetValue() == null) {
            return null;
        }
        if (NursingIndicatorDef.LOWER_BETTER.equals(def.direction())) {
            return value.compareTo(def.targetValue()) <= 0;
        }
        if (NursingIndicatorDef.HIGHER_BETTER.equals(def.direction())) {
            return value.compareTo(def.targetValue()) >= 0;
        }
        return null;
    }

    private static String deviceLabel(String deviceType) {
        if ("VENTILATOR".equals(deviceType)) {
            return "呼吸机";
        }
        if ("URINARY_CATHETER".equals(deviceType)) {
            return "导尿管";
        }
        if ("CENTRAL_LINE".equals(deviceType)) {
            return "中心静脉导管";
        }
        return deviceType;
    }

    /** 一次计算得到的完整快照；看板与上报共用，保证两处口径完全一致。 */
    private record Snapshot(int days, BigDecimal patientDays, BigDecimal avgCensus,
                            int bedCount, BigDecimal nurseDays, BigDecimal avgNurses,
                            BigDecimal bedNurseRatio, BigDecimal nursePatientRatio,
                            long eventCount, long timelyCount, BigDecimal timelyRate,
                            List<IndicatorMetricView> metrics, List<ShiftStatView> shifts,
                            List<String> warnings) {
    }
}
