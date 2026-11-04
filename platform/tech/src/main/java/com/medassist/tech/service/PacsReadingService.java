package com.medassist.tech.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.time.BusinessTime;
import com.medassist.tech.domain.TechModel.PacsAccessLog;
import com.medassist.tech.domain.TechModel.PacsStudy;
import com.medassist.tech.domain.TechModel.ReadingQueueItem;
import com.medassist.tech.domain.TechModel.ReadingTask;
import com.medassist.tech.domain.TechModel.Report;
import com.medassist.tech.repo.TechRepository;
import com.medassist.tech.web.PacsDtos.ReadingNoteReq;
import com.medassist.tech.web.PacsDtos.ReadingTaskView;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 影像阅片流程：队列 → 领取 → （写/签发报告）→ 完成，或释放回池。
 *
 * <p>并发与防冲突：
 * <ul>
 *   <li>领取先锁检查行，再由库级部分唯一索引兜住"同一检查只有一位医师"；</li>
 *   <li>释放/完成用"期望状态 + 版本号 + 持有人"三重 CAS，别人无法代操作；</li>
 *   <li>完成前要求当前报告已签发（FINAL/AMENDED），避免"点了完成却没报告"。</li>
 * </ul>
 *
 * <p>状态机：CLAIMED → RELEASED / COMPLETED；释放后可再次领取（生成新记录，历史留档）。
 */
@Service
public class PacsReadingService {

    private static final Pattern MODALITY = Pattern.compile("[A-Z0-9]{2,16}");
    private static final Set<String> STATES = Set.of("UNCLAIMED", "CLAIMED", "MINE", "DONE");

    private final TechRepository repo;
    private final ObjectMapper objectMapper;

    public PacsReadingService(TechRepository repo, ObjectMapper objectMapper) {
        this.repo = repo;
        this.objectMapper = objectMapper;
    }

    /**
     * 阅片队列。
     *
     * @param state UNCLAIMED / CLAIMED / MINE / DONE；为空返回全部
     * @param date  按检查完成日过滤；为空表示"全部积压 + 历史"（由 limit 截断）
     */
    public List<ReadingQueueItem> queue(String modality, String state, LocalDate date,
                                        Integer limit, String actor) {
        return queue(modality, state, date, limit, actor, java.util.Set.of());
    }

    /** 阅片队列（G49）：按申请院区收敛，空集合表示跨院区角色不限院区。 */
    public List<ReadingQueueItem> queue(String modality, String state, LocalDate date,
                                        Integer limit, String actor,
                                        java.util.Set<UUID> campusIds) {
        String normalizedModality = normalizeModality(modality);
        String normalizedState = normalizeState(state);
        if ("MINE".equals(normalizedState) && isBlank(actor)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "按“我的阅片”查询时必须提供操作人");
        }
        int size = limit == null || limit <= 0 ? 100 : Math.min(limit, 200);
        OffsetDateTime[] window = dayWindow(date);
        return repo.listReadingQueue(normalizedModality, normalizedState,
                isBlank(actor) ? null : actor.trim(), window[0], window[1],
                ApplicationService.campusCsv(campusIds), size);
    }

    /** 领取阅片：同一医师重复领取幂等返回，他人已领取则 409。 */
    @Transactional
    public ReadingTaskView claim(String studyInstanceUid, String actor) {
        String operator = requireActor(actor);
        PacsStudy study = lockStudy(studyInstanceUid);
        if (!PacsStudy.COMPLETED.equals(study.status())) {
            throw new BusinessException(ErrorCode.TECH_READING_STUDY_NOT_READY,
                    "影像检查当前状态为 " + study.status() + "，尚未完成，不能领取阅片");
        }
        ReadingTask active = repo.findActiveReadingTask(study.id()).orElse(null);
        if (active != null) {
            if (active.assignee().equals(operator)) {
                return view(active, study);
            }
            throw new BusinessException(ErrorCode.CONFLICT,
                    "该检查已被 " + active.assignee() + " 领取，请刷新队列");
        }
        ReadingTask task = new ReadingTask(UUID.randomUUID(), study.id(), operator,
                ReadingTask.CLAIMED, null, null, null, null, 0L);
        try {
            repo.insertReadingTask(task);
        } catch (DataIntegrityViolationException e) {
            // 并发领取撞部分唯一索引：事务已中止，不能再查库
            throw new BusinessException(ErrorCode.CONFLICT,
                    "该检查刚被其他医师领取，请刷新队列后重试");
        }
        audit(operator, "PACS_READING_CLAIM", task.id().toString(), Map.of(
                "studyInstanceUid", study.studyInstanceUid(),
                "accessionNumber", study.accessionNumber()));
        return view(repo.findReadingTask(task.id()).orElse(task), study);
    }

    /** 释放阅片（退回队列）。 */
    @Transactional
    public ReadingTaskView release(String studyInstanceUid, ReadingNoteReq req, String actor) {
        String operator = requireActor(actor);
        PacsStudy study = lockStudy(studyInstanceUid);
        ReadingTask task = requireOwnedActive(study, operator);
        String note = req == null ? null : blankToNull(req.note());
        boolean updated = repo.closeReadingTask(task.id(), ReadingTask.CLAIMED,
                ReadingTask.RELEASED, operator, note, task.version());
        if (!updated) {
            throw new BusinessException(ErrorCode.CONFLICT, "阅片任务已变化，请刷新后重试");
        }
        audit(operator, "PACS_READING_RELEASE", task.id().toString(), Map.of(
                "studyInstanceUid", study.studyInstanceUid(),
                "accessionNumber", study.accessionNumber(),
                "note", note == null ? "" : note));
        return view(repo.findReadingTask(task.id()).orElse(task), study);
    }

    /** 完成阅片：要求当前报告已签发。 */
    @Transactional
    public ReadingTaskView complete(String studyInstanceUid, ReadingNoteReq req, String actor) {
        String operator = requireActor(actor);
        PacsStudy study = lockStudy(studyInstanceUid);
        ReadingTask task = requireOwnedActive(study, operator);
        Report report = repo.findCurrentReport(study.applicationId()).orElse(null);
        if (report == null || !report.finalized()) {
            throw new BusinessException(ErrorCode.TECH_READING_REPORT_NOT_FINAL,
                    "该检查的报告尚未签发，请先完成报告书写与签发");
        }
        String note = req == null ? null : blankToNull(req.note());
        boolean updated = repo.closeReadingTask(task.id(), ReadingTask.CLAIMED,
                ReadingTask.COMPLETED, operator, note, task.version());
        if (!updated) {
            throw new BusinessException(ErrorCode.CONFLICT, "阅片任务已变化，请刷新后重试");
        }
        audit(operator, "PACS_READING_COMPLETE", task.id().toString(), Map.of(
                "studyInstanceUid", study.studyInstanceUid(),
                "accessionNumber", study.accessionNumber(),
                "reportId", report.id().toString(),
                "note", note == null ? "" : note));
        return view(repo.findReadingTask(task.id()).orElse(task), study);
    }

    /** 检查的调阅审计（按时间倒序）。 */
    public List<PacsAccessLog> accessLog(String studyInstanceUid, Integer limit) {
        PacsStudy study = requireStudy(studyInstanceUid);
        int size = limit == null || limit <= 0 ? 50 : Math.min(limit, 200);
        return repo.listPacsAccess(study.id(), size);
    }

    // ---------------------------------------------------------------- 内部

    private ReadingTask requireOwnedActive(PacsStudy study, String operator) {
        ReadingTask task = repo.findActiveReadingTask(study.id())
                .orElseThrow(() -> new BusinessException(ErrorCode.TECH_READING_TASK_NOT_FOUND,
                        "该检查当前没有进行中的阅片任务"));
        if (!task.assignee().equals(operator)) {
            throw new BusinessException(ErrorCode.TECH_READING_NOT_ASSIGNEE,
                    "该检查的阅片任务由 " + task.assignee() + " 持有，不能代为释放或完成");
        }
        return task;
    }

    private PacsStudy lockStudy(String studyInstanceUid) {
        return repo.lockPacsStudyByUid(trimUid(studyInstanceUid))
                .orElseThrow(() -> new BusinessException(ErrorCode.TECH_PACS_STUDY_NOT_FOUND));
    }

    private PacsStudy requireStudy(String studyInstanceUid) {
        return repo.findPacsStudyByUid(trimUid(studyInstanceUid))
                .orElseThrow(() -> new BusinessException(ErrorCode.TECH_PACS_STUDY_NOT_FOUND));
    }

    private static String trimUid(String uid) {
        return uid == null ? "" : uid.trim();
    }

    private static String requireActor(String actor) {
        if (isBlank(actor)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "缺少操作人，不能执行阅片操作");
        }
        return actor.trim();
    }

    private static String normalizeState(String state) {
        if (isBlank(state)) {
            return null;
        }
        String value = state.trim().toUpperCase();
        if (!STATES.contains(value)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "阅片状态只能是 UNCLAIMED / CLAIMED / MINE / DONE");
        }
        return value;
    }

    private static String normalizeModality(String modality) {
        if (isBlank(modality)) {
            return null;
        }
        String value = modality.trim().toUpperCase();
        if (!MODALITY.matcher(value).matches()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "设备类型（Modality）只能是 2~16 位大写字母或数字：" + modality);
        }
        return value;
    }

    /** date 为空：不设窗口（含全部积压）；否则取该自然日 [from, to)。 */
    private static OffsetDateTime[] dayWindow(LocalDate date) {
        if (date == null) {
            return new OffsetDateTime[]{null, null};
        }
        ZoneId zone = BusinessTime.zone();
        return new OffsetDateTime[]{
                date.atStartOfDay(zone).toOffsetDateTime(),
                date.plusDays(1).atStartOfDay(zone).toOffsetDateTime()};
    }

    private static ReadingTaskView view(ReadingTask t, PacsStudy study) {
        return new ReadingTaskView(t.id(), t.studyId(), study.studyInstanceUid(),
                study.accessionNumber(), t.assignee(), t.status(), t.claimedAt(), t.releasedAt(),
                t.completedAt(), t.note(), t.version());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private void audit(String actor, String action, String targetId, Map<String, Object> detail) {
        try {
            repo.logChange(actor, action, "PACS_READING", targetId,
                    objectMapper.writeValueAsString(new LinkedHashMap<>(detail)));
        } catch (JacksonException e) {
            repo.logChange(actor, action, "PACS_READING", targetId,
                    "{\"_detail_serialize_failed\":true}");
        }
    }
}
