package com.medassist.clinical.service;

import com.medassist.clinical.domain.ClinicalModel.OperatingRoom;
import com.medassist.clinical.domain.ClinicalModel.SurgeryCase;
import com.medassist.clinical.domain.ClinicalModel.SurgerySchedule;
import com.medassist.clinical.repo.ClinicalRepository;
import com.medassist.clinical.web.TheatreDtos.CancelScheduleReq;
import com.medassist.clinical.web.TheatreDtos.CreateRoomReq;
import com.medassist.clinical.web.TheatreDtos.RoomStatusReq;
import com.medassist.clinical.web.TheatreDtos.ScheduleReq;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.time.BusinessTime;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 手术间与排台。
 *
 * <p>核心不变量：
 * <ol>
 *   <li>只有手术间状态为 ACTIVE 才能排台；只有 PLANNED 的手术可以排台；</li>
 *   <li>同一手术间的时间段不得重叠（半开区间：首尾相接允许，便于接台）；</li>
 *   <li>一台手术同时只有一个未取消的排台；取消必须写原因，取消后可重排；</li>
 *   <li>排台冲突的判定必须串行化：进入校验前先取该手术间的事务级 advisory lock，
 *       否则两个并发请求会同时通过"查无冲突"再双双插入。</li>
 * </ol>
 */
@Service
public class TheatreService {

    private final ClinicalRepository repo;

    public TheatreService(ClinicalRepository repo) {
        this.repo = repo;
    }

    // ---------------------------------------------------------------- 手术间

    public List<OperatingRoom> rooms(String status, Integer limit) {
        String normalized = status == null || status.isBlank() ? null : status.trim().toUpperCase();
        int size = limit == null || limit <= 0 ? 50 : Math.min(limit, 200);
        return repo.listOperatingRooms(normalized, size);
    }

    @Transactional
    public OperatingRoom createRoom(CreateRoomReq req, String actor) {
        String code = req.code().trim();
        if (repo.findOperatingRoomByCode(code).isPresent()) {
            throw new BusinessException(ErrorCode.CONFLICT, "手术间编码已存在：" + code);
        }
        OperatingRoom room = new OperatingRoom(UUID.randomUUID(), code, req.name().trim(),
                req.deptId(), blankToNull(req.location()), OperatingRoom.ACTIVE, 0L, Instant.now());
        try {
            repo.insertOperatingRoom(room);
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.CONFLICT, "手术间编码已存在：" + code);
        }
        audit(actor, "SURGERY_ROOM_CREATE", "OPERATING_ROOM", room.id().toString(), room.code());
        return room;
    }

    @Transactional
    public OperatingRoom setRoomStatus(UUID roomId, RoomStatusReq req, String actor) {
        OperatingRoom room = repo.findOperatingRoom(roomId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "未找到该手术间"));
        if (!repo.updateOperatingRoomStatus(roomId, req.status(), req.version())) {
            throw new BusinessException(ErrorCode.CONFLICT, "手术间状态已变化，请刷新后重试");
        }
        audit(actor, "SURGERY_ROOM_STATUS", "OPERATING_ROOM", roomId.toString(),
                room.status() + "->" + req.status());
        return repo.findOperatingRoom(roomId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "未找到该手术间"));
    }

    // ---------------------------------------------------------------- 排台

    @Transactional
    public SurgerySchedule schedule(UUID visitId, UUID caseId, ScheduleReq req, String actor) {
        SurgeryCase surgery = requireCase(visitId, caseId);
        if (!SurgeryCase.PLANNED.equals(surgery.status())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "只有已计划（PLANNED）的手术可以排台，当前：" + surgery.status());
        }
        OperatingRoom room = repo.findOperatingRoom(req.roomId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "未找到该手术间"));
        if (!room.bookable()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "手术间当前不可用（" + room.status() + "），请更换手术间");
        }
        validateWindow(req.plannedStart(), req.plannedEnd());
        if (repo.findActiveScheduleByCase(caseId).isPresent()) {
            throw new BusinessException(ErrorCode.CONFLICT, "该手术已排台，请先取消原排台再重排");
        }
        // 串行化同一手术间的排台：并发请求必须排队做"冲突检查 + 插入"
        repo.lockRoomForScheduling(room.id());
        if (repo.countOverlappingSchedules(room.id(), req.plannedStart(), req.plannedEnd(), caseId) > 0) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "该手术间在此时段已有手术，请调整时间或更换手术间");
        }
        int sequence = repo.nextScheduleSequence(room.id(),
                dayStart(req.plannedStart()), dayEnd(req.plannedStart()));
        SurgerySchedule schedule = new SurgerySchedule(UUID.randomUUID(), caseId, room.id(),
                req.plannedStart(), req.plannedEnd(), sequence, SurgerySchedule.SCHEDULED,
                null, actor, 0L, Instant.now());
        try {
            repo.insertSchedule(schedule);
        } catch (DataIntegrityViolationException e) {
            // 两个请求的 planned_start 完全相同时由唯一索引兜底
            throw new BusinessException(ErrorCode.CONFLICT,
                    "该手术间在该开始时间已有排台，请刷新后重试");
        }
        audit(actor, "SURGERY_SCHEDULE_CREATE", "SURGERY_SCHEDULE", schedule.id().toString(),
                "case=" + surgery.caseNo() + " room=" + room.code() + " seq=" + sequence);
        return schedule;
    }

    /**
     * 查询该手术的排台。
     *
     * <p>返回**最近一条（含已取消）**：取消后前端仍要展示"已取消 + 原因"，
     * 若只回未取消记录，界面会退化成"从未排台"，掩盖取消事实。
     */
    public SurgerySchedule getByCase(UUID visitId, UUID caseId) {
        requireCase(visitId, caseId);
        return repo.findLatestScheduleByCase(caseId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "该手术尚未排台"));
    }

    @Transactional
    public SurgerySchedule cancel(UUID visitId, UUID caseId, CancelScheduleReq req, String actor) {
        requireCase(visitId, caseId);
        String reason = req.reason() == null ? "" : req.reason().trim();
        if (reason.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "取消排台必须填写原因");
        }
        SurgerySchedule schedule = repo.lockScheduleByCase(caseId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "该手术尚未排台"));
        if (!repo.cancelSchedule(schedule.id(), reason, req.version())) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "只有待手术（SCHEDULED）的排台可以取消，或状态已被他人更新");
        }
        audit(actor, "SURGERY_SCHEDULE_CANCEL", "SURGERY_SCHEDULE", schedule.id().toString(),
                reason);
        return repo.findLatestScheduleByCase(caseId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "该手术尚未排台"));
    }

    /** 手术间看板：某天该房间的全部排台（按开始时间）。 */
    public List<SurgerySchedule> board(UUID roomId, LocalDate date) {
        repo.findOperatingRoom(roomId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "未找到该手术间"));
        LocalDate day = date == null ? BusinessTime.today() : date;
        return repo.listSchedulesByRoom(roomId,
                day.atStartOfDay(BusinessTime.zone()).toInstant(),
                day.plusDays(1).atStartOfDay(BusinessTime.zone()).toInstant());
    }

    // ---------------------------------------------------------------- 内部工具

    private SurgeryCase requireCase(UUID visitId, UUID caseId) {
        SurgeryCase surgery = repo.findSurgeryCase(caseId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "未找到该手术记录"));
        if (!surgery.visitId().equals(visitId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "未找到该手术记录");
        }
        return surgery;
    }

    /** 排台时间窗校验：必须正序，且单台不超过 24 小时（防止误填把整个手术间占满）。 */
    private static void validateWindow(Instant start, Instant end) {
        if (start == null || end == null || !end.isAfter(start)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "排台结束时间必须晚于开始时间");
        }
        if (Duration.between(start, end).toHours() > 24) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "单台手术排程不能超过 24 小时");
        }
    }

    private static Instant dayStart(Instant instant) {
        ZoneId zone = BusinessTime.zone();
        return instant.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant();
    }

    private static Instant dayEnd(Instant instant) {
        return dayStart(instant).plus(Duration.ofDays(1));
    }

    private void audit(String actor, String action, String targetType, String targetId,
                       String detail) {
        repo.logChange(actor, action, targetType, targetId,
                "{\"detail\":\"" + escape(detail) + "\"}");
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
