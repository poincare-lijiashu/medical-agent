package com.medassist.clinical.service;

import com.medassist.clinical.domain.ClinicalModel.OperatingRoom;
import com.medassist.clinical.domain.ClinicalModel.RoomDayUsage;
import com.medassist.clinical.domain.ClinicalModel.RoomDayUsageRow;
import com.medassist.clinical.domain.ClinicalModel.SurgeryBoardRow;
import com.medassist.clinical.domain.ClinicalModel.SurgeryCase;
import com.medassist.clinical.domain.ClinicalModel.SurgerySchedule;
import com.medassist.clinical.repo.ClinicalRepository;
import com.medassist.clinical.web.TheatreDtos.CancelScheduleReq;
import com.medassist.clinical.web.TheatreDtos.CreateRoomReq;
import com.medassist.clinical.web.TheatreDtos.DailyUtilization;
import com.medassist.clinical.web.TheatreDtos.RoomStatusReq;
import com.medassist.clinical.web.TheatreDtos.RoomUtilization;
import com.medassist.clinical.web.TheatreDtos.ScheduleReq;
import com.medassist.clinical.web.TheatreDtos.SurgeryBoardView;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.time.BusinessTime;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
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

    /** 单次看板允许返回的最大排台行数；超出时显式返回 truncated，绝不静默截断。 */
    private static final int BOARD_MAX_ROWS = 500;

    /**
     * 排台看板：某天全部 ACTIVE 手术间的排台，一次请求返回。
     *
     * <p>替换前端「按房间并发 N 次 roomSchedules」的扇出：
     * 一次查询带回术式、术者、患者号与分级审批，看板不再只显示「第 N 台 + 病例号」。
     * 数据量超过 {@link #BOARD_MAX_ROWS} 时返回 {@code truncated}，让调用方知道被裁剪。
     */
    public SurgeryBoardView boardAll(LocalDate date) {
        LocalDate day = date == null ? BusinessTime.today() : date;
        List<OperatingRoom> rooms = repo.listOperatingRooms(OperatingRoom.ACTIVE, 200);
        if (rooms.isEmpty()) {
            return new SurgeryBoardView(day, 0, 0, List.of());
        }
        List<UUID> roomIds = rooms.stream().map(OperatingRoom::id).toList();
        ZoneId zone = BusinessTime.zone();
        List<SurgeryBoardRow> all = repo.listBoardRows(
                day.atStartOfDay(zone).toInstant(),
                day.plusDays(1).atStartOfDay(zone).toInstant(),
                roomIds);
        int truncated = Math.max(0, all.size() - BOARD_MAX_ROWS);
        List<SurgeryBoardRow> rows = truncated == 0 ? all : all.subList(0, BOARD_MAX_ROWS);
        return new SurgeryBoardView(day, rooms.size(), truncated, List.copyOf(rows));
    }

    // ---------------------------------------------------------------- 利用率报表

    /** 默认开放时段：08:00–18:00（可用 {@code openFrom/openTo} 覆盖，报表现算不落表）。 */
    private static final LocalTime DEFAULT_OPEN_FROM = LocalTime.of(8, 0);
    private static final LocalTime DEFAULT_OPEN_TO = LocalTime.of(18, 0);
    /** 单次统计区间上限（防止一次拉一整年把库拖垮）。 */
    private static final int MAX_RANGE_DAYS = 92;

    /**
     * 单个手术间的利用率与接台周转。
     *
     * <p>利用率 = 开放时段内被未取消排台占用的分钟 ÷ 开放时段总分钟。
     * 口径在仓储 SQL 中统一实现，逐日行与合计行同源，避免"日报表与月报表对不上"。
     */
    public RoomUtilization utilization(UUID roomId, LocalDate from, LocalDate to,
                                       LocalTime openFrom, LocalTime openTo) {
        OperatingRoom room = repo.findOperatingRoom(roomId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "未找到该手术间"));
        Window window = resolveWindow(from, to, openFrom, openTo);
        return computeUtilization(room, window);
    }

    /** 全院手术间利用率（只统计 ACTIVE 手术间，按编码排序）。 */
    public List<RoomUtilization> hospitalUtilization(LocalDate from, LocalDate to,
                                                     LocalTime openFrom, LocalTime openTo) {
        Window window = resolveWindow(from, to, openFrom, openTo);
        List<OperatingRoom> rooms = repo.listOperatingRooms(OperatingRoom.ACTIVE, 200);
        if (rooms.isEmpty()) {
            return List.of();
        }
        List<UUID> roomIds = rooms.stream().map(OperatingRoom::id).toList();
        Map<UUID, List<RoomDayUsage>> usageByRoom = new HashMap<>();
        for (RoomDayUsageRow row : repo.listRoomDayUsageBatch(roomIds,
                usageFrom(window), usageTo(window), openMinute(window), closeMinute(window))) {
            usageByRoom.computeIfAbsent(row.roomId(), k -> new ArrayList<>()).add(row.usage());
        }
        List<RoomUtilization> result = new ArrayList<>(rooms.size());
        for (OperatingRoom room : rooms) {
            result.add(buildUtilization(room, window,
                    usageByRoom.getOrDefault(room.id(), List.of())));
        }
        return result;
    }

    private static java.time.Instant usageFrom(Window window) {
        return window.from().atStartOfDay(BusinessTime.zone()).toInstant();
    }

    private static java.time.Instant usageTo(Window window) {
        return window.to().plusDays(1).atStartOfDay(BusinessTime.zone()).toInstant();
    }

    private static int openMinute(Window window) {
        return (int) (window.openFrom().toSecondOfDay() / 60);
    }

    private static int closeMinute(Window window) {
        return (int) (window.openTo().toSecondOfDay() / 60);
    }

    private RoomUtilization computeUtilization(OperatingRoom room, Window window) {
        return buildUtilization(room, window, repo.listRoomDayUsage(room.id(),
                usageFrom(window), usageTo(window), openMinute(window), closeMinute(window)));
    }

    /** 把「某房间某区间的逐日聚合」组装成报表视图（取数与组装分离，便于批量复用）。 */
    private RoomUtilization buildUtilization(OperatingRoom room, Window window,
                                             List<RoomDayUsage> rows) {
        ZoneId zone = BusinessTime.zone();
        long availablePerDay = Duration.between(window.openFrom(), window.openTo()).toMinutes();
        List<DailyUtilization> days = new ArrayList<>(rows.size());
        int scheduled = 0;
        int completed = 0;
        int cancelled = 0;
        int turnoverCount = 0;
        long occupied = 0;
        long turnover = 0;
        for (RoomDayUsage row : rows) {
            LocalDate day = row.dayStart().atZone(zone).toLocalDate();
            days.add(new DailyUtilization(day, row.scheduledCount(), row.completedCount(),
                    row.cancelledCount(), row.occupiedMinutes(), availablePerDay,
                    row.turnoverMinutes(), row.turnoverCount(),
                    rate(row.occupiedMinutes(), availablePerDay)));
            scheduled += row.scheduledCount();
            completed += row.completedCount();
            cancelled += row.cancelledCount();
            occupied += row.occupiedMinutes();
            turnover += row.turnoverMinutes();
            turnoverCount += row.turnoverCount();
        }
        long totalAvailable = availablePerDay * rows.size();
        return new RoomUtilization(room.id(), room.code(), room.name(), window.from(),
                window.to(), window.openFrom().toString(), window.openTo().toString(), days,
                scheduled, completed, cancelled, occupied, totalAvailable, turnover,
                turnoverCount, rate(occupied, totalAvailable));
    }

    /** 利用率比率（0~1，保留 4 位小数；可用时长为 0 时记 0，绝不做除零）。 */
    private static BigDecimal rate(long occupiedMinutes, long availableMinutes) {
        if (availableMinutes <= 0) {
            return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        }
        return BigDecimal.valueOf(occupiedMinutes)
                .divide(BigDecimal.valueOf(availableMinutes), 4, RoundingMode.HALF_UP);
    }

    private static Window resolveWindow(LocalDate from, LocalDate to, LocalTime openFrom,
                                        LocalTime openTo) {
        LocalDate f = from == null ? BusinessTime.today().minusDays(6) : from;
        LocalDate t = to == null ? BusinessTime.today() : to;
        if (t.isBefore(f)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "结束日期不能早于开始日期");
        }
        if (ChronoUnit.DAYS.between(f, t) + 1 > MAX_RANGE_DAYS) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "统计区间不能超过 " + MAX_RANGE_DAYS + " 天");
        }
        LocalTime of = openFrom == null ? DEFAULT_OPEN_FROM : openFrom;
        LocalTime ot = openTo == null ? DEFAULT_OPEN_TO : openTo;
        if (!ot.isAfter(of)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "开放时段的结束时间必须晚于开始时间");
        }
        return new Window(f, t, of, ot);
    }

    /** 已归一化的统计窗口（日期 + 每日开放时段）。 */
    private record Window(LocalDate from, LocalDate to, LocalTime openFrom, LocalTime openTo) {
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
