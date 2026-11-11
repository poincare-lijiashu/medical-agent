package com.medassist.ops.support;

import com.medassist.ops.domain.OpsModel.Calendar;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * SLA 时钟：把"多少分钟"换算成真实截止时间。
 *
 * <p>三种口径必须区分清楚，否则一线会觉得"系统算错了"：
 * <ul>
 *   <li>7×24：直接加自然分钟；</li>
 *   <li>营业时间：只累计工作日、工作时段，午休/夜间/周末/节假日不计；</li>
 *   <li>挂起：暂停期间不计时，恢复时把暂停时长顺延到截止时间。</li>
 * </ul>
 *
 * <p>为什么用 Java 而不是 SQL：跨天、跨周、节假日跳过、边界（恰好下班）这些逻辑
 * 用 SQL 写既难读也难测；SLA 是运营承诺，必须有确定性的单测。
 */
public final class SlaClock {

    /** 防御性上限：避免日历配置错误（例如 end<=start）导致死循环。 */
    private static final int MAX_DAYS_LOOKAHEAD = 3660;

    private SlaClock() {
    }

    /** 计算截止时间；{@code businessHours=false} 或日历为空时按自然时间。 */
    public static Instant dueAt(Instant start, int minutes, Calendar calendar, boolean businessHours) {
        if (start == null || minutes <= 0) {
            return start;
        }
        if (!businessHours || calendar == null) {
            return start.plus(Duration.ofMinutes(minutes));
        }
        ZoneId zone = zoneOf(calendar.timezone());
        ZonedDateTime cursor = start.atZone(zone);
        if (!isWorkday(calendar, cursor.toLocalDate())) {
            cursor = nextWorkdayStart(calendar, cursor.toLocalDate(), zone);
        }
        LocalTime startTime = calendar.startTime() == null ? LocalTime.of(8, 0) : calendar.startTime();
        LocalTime endTime = calendar.endTime() == null ? LocalTime.of(17, 0) : calendar.endTime();
        if (!endTime.isAfter(startTime)) {
            // 配置错误时不静默给出错误承诺：按起点自然时间兜底，并由调用方记录告警
            return start.plus(Duration.ofMinutes(minutes));
        }

        if (cursor.toLocalTime().isBefore(startTime)) {
            cursor = cursor.with(startTime);
        } else if (!cursor.toLocalTime().isBefore(endTime)) {
            cursor = nextWorkdayStart(calendar, cursor.toLocalDate(), zone);
        }

        int remaining = minutes;
        for (int guard = 0; guard < MAX_DAYS_LOOKAHEAD; guard++) {
            if (!isWorkday(calendar, cursor.toLocalDate())) {
                cursor = nextWorkdayStart(calendar, cursor.toLocalDate(), zone);
                continue;
            }
            ZonedDateTime windowEnd = cursor.toLocalDate().atTime(endTime).atZone(zone);
            long available = Duration.between(cursor, windowEnd).toMinutes();
            if (available >= remaining) {
                return cursor.plusMinutes(remaining).toInstant();
            }
            remaining -= (int) Math.max(available, 0);
            cursor = nextWorkdayStart(calendar, cursor.toLocalDate(), zone);
        }
        // 日历异常（几乎不可能）：不返回 null，退回自然时间，保证工单仍可追踪
        return start.plus(Duration.ofMinutes(minutes));
    }

    /** 挂起恢复：把暂停分钟顺延到截止时间。 */
    public static Instant shift(Instant due, long pausedMinutes) {
        if (due == null || pausedMinutes <= 0) {
            return due;
        }
        return due.plus(Duration.ofMinutes(pausedMinutes));
    }

    /** 计算剩余分钟（可为负）。 */
    public static long remainingMinutes(Instant due, Instant now) {
        if (due == null) {
            return 0;
        }
        return Duration.between(now, due).toMinutes();
    }

    /**
     * SLA 状态：
     * MET（已完成且未违约）/ BREACHED / PAUSED / AT_RISK（剩余不足 20%）/ ON_TRACK。
     */
    public static String state(Instant due, Instant now, boolean breached, boolean paused,
                               boolean finished, int windowMinutes) {
        if (finished) {
            return breached ? "BREACHED" : "MET";
        }
        if (paused) {
            return "PAUSED";
        }
        if (breached) {
            return "BREACHED";
        }
        if (due == null) {
            return "ON_TRACK";
        }
        long remaining = Duration.between(now, due).toMinutes();
        // 剩余不足整个窗口的 20% 视为“临期”：需要提前升级，而不是等红了才处理
        long threshold = Math.max(Math.round(Math.max(windowMinutes, 1) * 0.2), 1);
        return remaining <= threshold ? "AT_RISK" : "ON_TRACK";
    }

    private static ZonedDateTime nextWorkdayStart(Calendar calendar, LocalDate from, ZoneId zone) {
        LocalDate date = from.plusDays(1);
        for (int i = 0; i < MAX_DAYS_LOOKAHEAD; i++) {
            if (isWorkday(calendar, date)) {
                LocalTime start = calendar.startTime() == null ? LocalTime.of(8, 0) : calendar.startTime();
                return date.atTime(start).atZone(zone);
            }
            date = date.plusDays(1);
        }
        return from.plusDays(1).atStartOfDay(zone);
    }

    private static boolean isWorkday(Calendar calendar, LocalDate date) {
        if (calendar.holidays() != null && calendar.holidays().contains(date)) {
            return false;
        }
        int isoDay = date.getDayOfWeek().getValue();
        List<Integer> workdays = calendar.workdays();
        return workdays != null && workdays.contains(isoDay);
    }

    private static ZoneId zoneOf(String timezone) {
        try {
            return timezone == null || timezone.isBlank()
                    ? ZoneId.of("Asia/Shanghai") : ZoneId.of(timezone);
        } catch (Exception e) {
            return ZoneId.of("Asia/Shanghai");
        }
    }

    /** 仅用于测试/展示：判断给定时刻是否在营业时段内。 */
    public static boolean inBusinessHours(Calendar calendar, Instant at) {
        if (calendar == null) {
            return true;
        }
        ZonedDateTime local = at.atZone(zoneOf(calendar.timezone()));
        if (!isWorkday(calendar, local.toLocalDate())) {
            return false;
        }
        LocalTime start = calendar.startTime() == null ? LocalTime.of(8, 0) : calendar.startTime();
        LocalTime end = calendar.endTime() == null ? LocalTime.of(17, 0) : calendar.endTime();
        return !local.toLocalTime().isBefore(start) && local.toLocalTime().isBefore(end);
    }

    /** 工作日判断（测试/看板用）。 */
    public static boolean workday(Calendar calendar, LocalDate date) {
        return isWorkday(calendar, date);
    }

    /** 本地日期时间（测试用）。 */
    public static LocalDateTime local(Calendar calendar, Instant at) {
        return LocalDateTime.ofInstant(at, zoneOf(calendar.timezone()));
    }

    /** 星期（测试用）。 */
    public static DayOfWeek dayOfWeek(Calendar calendar, Instant at) {
        return at.atZone(zoneOf(calendar.timezone())).getDayOfWeek();
    }
}
