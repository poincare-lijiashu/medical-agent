package com.medassist.clinical.service;

import com.medassist.clinical.domain.ClinicalModel.NursingCoverageRow;
import com.medassist.clinical.domain.ClinicalModel.NursingSchedule;
import com.medassist.clinical.domain.ClinicalModel.NursingShift;
import com.medassist.clinical.domain.ClinicalModel.NursingWorkloadRow;
import com.medassist.clinical.repo.ClinicalRepository;
import com.medassist.clinical.web.NursingScheduleDtos.AssignmentReq;
import com.medassist.clinical.web.NursingScheduleDtos.BatchResult;
import com.medassist.clinical.web.NursingScheduleDtos.BatchScheduleReq;
import com.medassist.clinical.web.NursingScheduleDtos.CoverageView;
import com.medassist.clinical.web.NursingScheduleDtos.NurseLoadView;
import com.medassist.clinical.web.NursingScheduleDtos.PublishResult;
import com.medassist.clinical.web.NursingScheduleDtos.ScheduleView;
import com.medassist.clinical.web.NursingScheduleDtos.ShiftView;
import com.medassist.clinical.web.NursingScheduleDtos.WorkloadView;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.time.BusinessTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 护理排班（G26）。
 *
 * <p>排班以「科室 + 日期 + 班次 + 护士」为最小单元：
 * <ul>
 *   <li>只允许在 {-30, +90} 天窗口内排班，避免误排历史或远期；</li>
 *   <li>同一科室同班次同护士、或同一护士同一天重复排班会被跳过（库级唯一约束兜底）；</li>
 *   <li>草稿可按科室+日期一键发布；取消必须写原因并保留取消人/时间；</li>
 *   <li>工作量按护士统计班次与夜班数，覆盖按日期+班次统计人数，均排除已取消排班。</li>
 * </ul>
 */
@Service
public class NursingScheduleService {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");
    private static final int MAX_RANGE_DAYS = 92;
    private static final int MIN_DAYS_BACK = 30;
    private static final int MAX_DAYS_AHEAD = 90;

    private final ClinicalRepository repo;
    private final ObjectMapper objectMapper;

    public NursingScheduleService(ClinicalRepository repo, ObjectMapper objectMapper) {
        this.repo = repo;
        this.objectMapper = objectMapper;
    }

    public List<ShiftView> listShifts() {
        return repo.listNursingShifts().stream().map(this::toShiftView).toList();
    }

    /** 排班列表：默认前 3 天到后 7 天，区间最长 92 天。 */
    public List<ScheduleView> list(UUID deptId, LocalDate from, LocalDate to) {
        LocalDate[] range = resolveRange(from, to);
        return repo.listNursingSchedules(deptId, range[0], range[1]).stream()
                .map(this::toView).toList();
    }

    /** 批量录入：校验失败整批拒绝；重复排班跳过并返回数量。 */
    @Transactional
    public BatchResult create(BatchScheduleReq req, String actor) {
        LocalDate today = BusinessTime.today();
        int created = 0;
        int skipped = 0;
        for (AssignmentReq item : req.items()) {
            repo.findDept(item.deptId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.ORG_NOT_FOUND,
                            "未找到该科室：" + item.deptId()));
            NursingShift shift = repo.findNursingShift(item.shiftCode().trim())
                    .filter(NursingShift::enabled)
                    .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED,
                            "未找到该班次或班次已停用：" + item.shiftCode()));
            LocalDate day = item.workDate();
            if (day.isBefore(today.minusDays(MIN_DAYS_BACK))
                    || day.isAfter(today.plusDays(MAX_DAYS_AHEAD))) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "排班日期必须在近 " + MIN_DAYS_BACK + " 天到未来 "
                                + MAX_DAYS_AHEAD + " 天之间：" + day);
            }
            String nurseName = item.nurseName() == null ? "" : item.nurseName().trim();
            if (nurseName.isEmpty()) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "护士姓名不能为空");
            }
            String nurseNo = item.nurseNo() == null || item.nurseNo().isBlank()
                    ? null : item.nurseNo().trim();
            boolean inserted = repo.insertNursingSchedule(UUID.randomUUID(), item.deptId(),
                    shift.code(), day, nurseName, nurseNo,
                    item.note() == null || item.note().isBlank() ? null : item.note().trim(),
                    actor);
            if (inserted) {
                created++;
            } else {
                skipped++;
            }
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("created", created);
        detail.put("skipped", skipped);
        audit(actor, "NURSING_SCHEDULE_CREATE", "NURSING_SCHEDULE", "batch", detail);
        return new BatchResult(created, skipped);
    }

    /** 发布某科室某日全部草稿；没有草稿时 422。 */
    @Transactional
    public PublishResult publish(UUID deptId, LocalDate day, String actor) {
        if (deptId == null || day == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "发布必须指定科室与日期");
        }
        repo.findDept(deptId).orElseThrow(() -> new BusinessException(
                ErrorCode.ORG_NOT_FOUND, "未找到该科室"));
        int published = repo.publishNursingSchedules(deptId, day);
        if (published == 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "该科室该日期没有待发布的草稿排班");
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("deptId", deptId.toString());
        detail.put("date", day.toString());
        detail.put("published", published);
        audit(actor, "NURSING_SCHEDULE_PUBLISH", "NURSING_SCHEDULE", deptId.toString(), detail);
        return new PublishResult(published);
    }

    /** 取消排班：已取消幂等返回；必须写原因并做版本 CAS。 */
    @Transactional
    public ScheduleView cancel(UUID id, String reason, String actor) {
        NursingSchedule schedule = repo.findNursingSchedule(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "未找到该排班"));
        if (NursingSchedule.CANCELLED.equals(schedule.status())) {
            return toView(schedule);
        }
        String normalized = reason == null ? "" : reason.trim();
        if (normalized.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "取消排班必须填写原因");
        }
        if (!repo.cancelNursingSchedule(id, normalized, actor, schedule.version())) {
            throw new BusinessException(ErrorCode.CONFLICT, "排班已被他人修改，请刷新后重试");
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("deptId", schedule.deptId().toString());
        detail.put("date", schedule.workDate().toString());
        detail.put("shift", schedule.shiftCode());
        detail.put("nurse", schedule.nurseName());
        detail.put("reason", normalized);
        audit(actor, "NURSING_SCHEDULE_CANCEL", "NURSING_SCHEDULE", id.toString(), detail);
        return repo.findNursingSchedule(id).map(this::toView)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "排班取消后读取失败"));
    }

    /** 工作量与每日覆盖：排除已取消排班。 */
    public WorkloadView workload(UUID deptId, LocalDate from, LocalDate to) {
        LocalDate[] range = resolveRange(from, to);
        List<NurseLoadView> nurses = repo.nursingWorkload(deptId, range[0], range[1]).stream()
                .map(this::toLoadView).toList();
        List<CoverageView> coverage = repo.nursingCoverage(deptId, range[0], range[1]).stream()
                .map(this::toCoverageView).toList();
        return new WorkloadView(nurses, coverage);
    }

    // ---------------------------------------------------------------- 内部

    private static LocalDate[] resolveRange(LocalDate from, LocalDate to) {
        LocalDate today = BusinessTime.today();
        LocalDate start = from == null ? today.minusDays(3) : from;
        LocalDate end = to == null ? today.plusDays(7) : to;
        if (start.isAfter(end)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "起始日期不能晚于截止日期");
        }
        if (start.plusDays(MAX_RANGE_DAYS).isBefore(end)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "查询区间不得超过 " + MAX_RANGE_DAYS + " 天");
        }
        return new LocalDate[]{start, end};
    }

    private ShiftView toShiftView(NursingShift s) {
        return new ShiftView(s.id(), s.code(), s.name(), s.startTime().format(TIME_FORMAT),
                s.endTime().format(TIME_FORMAT), s.crossMidnight(), s.enabled());
    }

    private ScheduleView toView(NursingSchedule s) {
        return new ScheduleView(s.id(), s.deptId(), s.deptName(), s.shiftCode(), s.shiftName(),
                s.workDate(), s.nurseName(), s.nurseNo(), s.status(), s.note(), s.createdBy(),
                s.createdAt(), s.cancelReason(), s.cancelledAt(), s.version());
    }

    private NurseLoadView toLoadView(NursingWorkloadRow row) {
        return new NurseLoadView(row.nurseName(), row.nurseNo(), row.shifts(), row.nightShifts());
    }

    private CoverageView toCoverageView(NursingCoverageRow row) {
        return new CoverageView(row.workDate(), row.shiftCode(), row.count());
    }

    private void audit(String actor, String action, String targetType, String targetId,
                       Map<String, Object> detail) {
        try {
            repo.logChange(actor, action, targetType, targetId,
                    objectMapper.writeValueAsString(detail));
        } catch (JacksonException e) {
            repo.logChange(actor, action, targetType, targetId,
                    "{\"_detail_serialize_failed\":true}");
        }
    }
}
