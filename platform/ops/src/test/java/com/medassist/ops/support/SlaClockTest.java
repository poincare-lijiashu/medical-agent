package com.medassist.ops.support;

import com.medassist.ops.domain.OpsModel.Calendar;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SLA 时钟：跨天、周末、节假日、边界与暂停顺延。
 *
 * <p>这些断言是运营承诺的"合同"：算错一分钟，一线就会觉得系统不可信。
 */
class SlaClockTest {

    /** 工作日 08:00–17:00；2026-11-06 是周五。 */
    private final Calendar workday = new Calendar("CN_8X5", "工作日", "Asia/Shanghai",
            List.of(1, 2, 3, 4, 5), LocalTime.of(8, 0), LocalTime.of(17, 0), List.of(),
            "ACTIVE", 0);

    private static Instant at(String iso) {
        return OffsetDateTime.parse(iso).toInstant();
    }

    @Test
    @DisplayName("7×24：直接加自然分钟")
    void naturalTime() {
        assertThat(SlaClock.dueAt(at("2026-11-06T10:00:00+08:00"), 120, null, false))
                .isEqualTo(at("2026-11-06T12:00:00+08:00"));
    }

    @Test
    @DisplayName("营业时间：周五 16:30 加 120 分钟 → 下周一 09:30（跨周末）")
    void acrossWeekend() {
        assertThat(SlaClock.dayOfWeek(workday, at("2026-11-06T10:00:00+08:00")).toString())
                .isEqualTo("FRIDAY");
        assertThat(SlaClock.dueAt(at("2026-11-06T16:30:00+08:00"), 120, workday, true))
                .isEqualTo(at("2026-11-09T09:30:00+08:00"));
    }

    @Test
    @DisplayName("营业时间：节假日跳过，顺延到下一个工作日")
    void skipHoliday() {
        Calendar withHoliday = new Calendar("CN_8X5", "工作日", "Asia/Shanghai",
                List.of(1, 2, 3, 4, 5), LocalTime.of(8, 0), LocalTime.of(17, 0),
                List.of(LocalDate.of(2026, 11, 9)), "ACTIVE", 0);

        assertThat(SlaClock.dueAt(at("2026-11-06T16:30:00+08:00"), 120, withHoliday, true))
                .isEqualTo(at("2026-11-10T09:30:00+08:00"));
    }

    @Test
    @DisplayName("营业时间：上班前开始，先对齐到上班时间")
    void beforeWork() {
        assertThat(SlaClock.dueAt(at("2026-11-06T07:00:00+08:00"), 60, workday, true))
                .isEqualTo(at("2026-11-06T09:00:00+08:00"));
    }

    @Test
    @DisplayName("营业时间：恰好下班边界不吞时间")
    void atClosingBoundary() {
        assertThat(SlaClock.dueAt(at("2026-11-06T17:00:00+08:00"), 30, workday, true))
                .isEqualTo(at("2026-11-09T08:30:00+08:00"));
    }

    @Test
    @DisplayName("挂起恢复：暂停 30 分钟，截止时间顺延 30 分钟")
    void pauseShift() {
        Instant due = at("2026-11-06T12:00:00+08:00");
        assertThat(SlaClock.shift(due, 30)).isEqualTo(at("2026-11-06T12:30:00+08:00"));
        assertThat(SlaClock.shift(due, 0)).isEqualTo(due);
        assertThat(SlaClock.shift(null, 30)).isNull();
    }

    @Test
    @DisplayName("SLA 状态：临期、超时、挂起、完成")
    void states() {
        Instant due = at("2026-11-06T12:00:00+08:00");
        // 窗口 240 分钟，剩余 120 分钟 → 未到 20% 阈值
        assertThat(SlaClock.state(due, at("2026-11-06T10:00:00+08:00"), false, false, false, 240))
                .isEqualTo("ON_TRACK");
        // 剩余 30 分钟 → 临期
        assertThat(SlaClock.state(due, at("2026-11-06T11:30:00+08:00"), false, false, false, 240))
                .isEqualTo("AT_RISK");
        assertThat(SlaClock.state(due, at("2026-11-06T12:30:00+08:00"), true, false, false, 240))
                .isEqualTo("BREACHED");
        assertThat(SlaClock.state(due, at("2026-11-06T11:00:00+08:00"), false, true, false, 240))
                .isEqualTo("PAUSED");
        assertThat(SlaClock.state(due, at("2026-11-06T11:00:00+08:00"), false, false, true, 240))
                .isEqualTo("MET");
    }
}
