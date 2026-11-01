package com.medassist.clinical.service;

import com.medassist.clinical.domain.ClinicalModel.OperatingRoom;
import com.medassist.clinical.domain.ClinicalModel.RoomDayUsage;
import com.medassist.clinical.domain.ClinicalModel.SurgeryCase;
import com.medassist.clinical.domain.ClinicalModel.SurgerySchedule;
import com.medassist.clinical.repo.ClinicalRepository;
import com.medassist.clinical.web.TheatreDtos.CancelScheduleReq;
import com.medassist.clinical.web.TheatreDtos.CreateRoomReq;
import com.medassist.clinical.web.TheatreDtos.RoomUtilization;
import com.medassist.clinical.web.TheatreDtos.ScheduleReq;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 排台规则：只有计划态可排、同房间不重叠、取消留原因、冲突串行化。 */
class TheatreServiceTest {

    private ClinicalRepository repo;
    private TheatreService service;

    private final UUID visitId = UUID.randomUUID();
    private final UUID caseId = UUID.randomUUID();
    private final UUID roomId = UUID.randomUUID();
    private final Instant start = Instant.parse("2026-11-02T01:00:00Z");
    private final Instant end = Instant.parse("2026-11-02T03:00:00Z");

    @BeforeEach
    void setUp() {
        repo = mock(ClinicalRepository.class);
        service = new TheatreService(repo);
    }

    private SurgeryCase surgery(String status) {
        return new SurgeryCase(caseId, "OP20261102000001", visitId, null, UUID.randomUUID(),
                "47.0901", "阑尾切除术", start, null, null, "doctor01", null, "GENERAL", "II",
                "II", status, null, "doctor01", 0L, Instant.now());
    }

    private OperatingRoom room(String status) {
        return new OperatingRoom(roomId, "OR-1", "一号手术间", null, "3F", status, 0L,
                Instant.now());
    }

    // ---------------------------------------------------------------- 手术间

    @Test
    @DisplayName("新建手术间：编码重复 → 409")
    void createRoomDuplicate() {
        when(repo.findOperatingRoomByCode("OR-1")).thenReturn(Optional.of(room("ACTIVE")));

        assertThatThrownBy(() -> service.createRoom(
                new CreateRoomReq("OR-1", "一号间", null, null), "admin01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.CONFLICT));
        verify(repo, never()).insertOperatingRoom(any());
    }

    // ---------------------------------------------------------------- 排台

    @Test
    @DisplayName("排台：未计划（已开始/已完成）的手术不能排 → 422")
    void scheduleRejectsStartedCase() {
        when(repo.findSurgeryCase(caseId)).thenReturn(Optional.of(surgery(SurgeryCase.IN_PROGRESS)));

        assertThatThrownBy(() -> service.schedule(visitId, caseId,
                new ScheduleReq(roomId, start, end), "doctor01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        verify(repo, never()).insertSchedule(any());
    }

    @Test
    @DisplayName("排台：手术间维护中不可排 → 422")
    void scheduleRejectsUnbookableRoom() {
        when(repo.findSurgeryCase(caseId)).thenReturn(Optional.of(surgery(SurgeryCase.PLANNED)));
        when(repo.findOperatingRoom(roomId)).thenReturn(Optional.of(room("MAINTENANCE")));

        assertThatThrownBy(() -> service.schedule(visitId, caseId,
                new ScheduleReq(roomId, start, end), "doctor01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @Test
    @DisplayName("排台：结束时间不晚于开始 → 422")
    void scheduleRejectsInvalidWindow() {
        when(repo.findSurgeryCase(caseId)).thenReturn(Optional.of(surgery(SurgeryCase.PLANNED)));
        when(repo.findOperatingRoom(roomId)).thenReturn(Optional.of(room("ACTIVE")));

        assertThatThrownBy(() -> service.schedule(visitId, caseId,
                new ScheduleReq(roomId, end, start), "doctor01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @Test
    @DisplayName("排台：单台超过 24 小时 → 422（防止误填占满手术间）")
    void scheduleRejectsOverlongWindow() {
        when(repo.findSurgeryCase(caseId)).thenReturn(Optional.of(surgery(SurgeryCase.PLANNED)));
        when(repo.findOperatingRoom(roomId)).thenReturn(Optional.of(room("ACTIVE")));

        assertThatThrownBy(() -> service.schedule(visitId, caseId,
                new ScheduleReq(roomId, start, start.plus(Duration.ofHours(30))), "doctor01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @Test
    @DisplayName("排台：同一手术重复排台 → 409")
    void scheduleRejectsDuplicateCase() {
        when(repo.findSurgeryCase(caseId)).thenReturn(Optional.of(surgery(SurgeryCase.PLANNED)));
        when(repo.findOperatingRoom(roomId)).thenReturn(Optional.of(room("ACTIVE")));
        when(repo.findActiveScheduleByCase(caseId)).thenReturn(Optional.of(
                new SurgerySchedule(UUID.randomUUID(), caseId, roomId, start, end, 1,
                        SurgerySchedule.SCHEDULED, null, "doctor01", 0L, Instant.now())));

        assertThatThrownBy(() -> service.schedule(visitId, caseId,
                new ScheduleReq(roomId, start, end), "doctor01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.CONFLICT));
        verify(repo, never()).insertSchedule(any());
    }

    @Test
    @DisplayName("排台：同房间时段重叠 → 409，且必须先取房间锁再判冲突")
    void scheduleRejectsOverlap() {
        when(repo.findSurgeryCase(caseId)).thenReturn(Optional.of(surgery(SurgeryCase.PLANNED)));
        when(repo.findOperatingRoom(roomId)).thenReturn(Optional.of(room("ACTIVE")));
        when(repo.findActiveScheduleByCase(caseId)).thenReturn(Optional.empty());
        when(repo.countOverlappingSchedules(roomId, start, end, caseId)).thenReturn(1L);

        assertThatThrownBy(() -> service.schedule(visitId, caseId,
                new ScheduleReq(roomId, start, end), "doctor01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.CONFLICT));

        verify(repo).lockRoomForScheduling(roomId);
        verify(repo, never()).insertSchedule(any());
    }

    @Test
    @DisplayName("排台：成功时落 SCHEDULED 与当天接台序号，并留审计")
    void scheduleCreates() {
        when(repo.findSurgeryCase(caseId)).thenReturn(Optional.of(surgery(SurgeryCase.PLANNED)));
        when(repo.findOperatingRoom(roomId)).thenReturn(Optional.of(room("ACTIVE")));
        when(repo.findActiveScheduleByCase(caseId)).thenReturn(Optional.empty());
        when(repo.countOverlappingSchedules(roomId, start, end, caseId)).thenReturn(0L);
        when(repo.nextScheduleSequence(any(), any(), any())).thenReturn(3);

        SurgerySchedule created = service.schedule(visitId, caseId,
                new ScheduleReq(roomId, start, end), "doctor01");

        ArgumentCaptor<SurgerySchedule> captor = ArgumentCaptor.forClass(SurgerySchedule.class);
        verify(repo).insertSchedule(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(SurgerySchedule.SCHEDULED);
        assertThat(captor.getValue().sequence()).isEqualTo(3);
        assertThat(created.roomId()).isEqualTo(roomId);
        verify(repo).logChange(eq("doctor01"), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("查询排台：已取消也要返回（前端要展示取消原因，不能退化成\"从未排台\"）")
    void getByCaseReturnsCancelledRecord() {
        when(repo.findSurgeryCase(caseId)).thenReturn(Optional.of(surgery(SurgeryCase.PLANNED)));
        when(repo.findLatestScheduleByCase(caseId)).thenReturn(Optional.of(
                new SurgerySchedule(UUID.randomUUID(), caseId, roomId, start, end, 1,
                        SurgerySchedule.CANCELLED, "患者发热", "doctor01", 2L, Instant.now())));

        SurgerySchedule result = service.getByCase(visitId, caseId);

        assertThat(result.status()).isEqualTo(SurgerySchedule.CANCELLED);
        assertThat(result.cancelReason()).isEqualTo("患者发热");
    }

    @Test
    @DisplayName("取消排台：原因为空 → 422")
    void cancelRequiresReason() {
        when(repo.findSurgeryCase(caseId)).thenReturn(Optional.of(surgery(SurgeryCase.PLANNED)));

        assertThatThrownBy(() -> service.cancel(visitId, caseId,
                new CancelScheduleReq(0L, "  "), "doctor01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        verify(repo, never()).cancelSchedule(any(), anyString(), anyLong());
    }

    @Test
    @DisplayName("取消排台：版本已变化 → 409")
    void cancelStaleVersion() {
        when(repo.findSurgeryCase(caseId)).thenReturn(Optional.of(surgery(SurgeryCase.PLANNED)));
        when(repo.lockScheduleByCase(caseId)).thenReturn(Optional.of(
                new SurgerySchedule(UUID.randomUUID(), caseId, roomId, start, end, 1,
                        SurgerySchedule.SCHEDULED, null, "doctor01", 2L, Instant.now())));
        when(repo.cancelSchedule(any(), anyString(), anyLong())).thenReturn(false);

        assertThatThrownBy(() -> service.cancel(visitId, caseId,
                new CancelScheduleReq(0L, "患者临时取消"), "doctor01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.CONFLICT));
    }

    @Test
    @DisplayName("手术间看板：手术间不存在 → 404")
    void boardRejectsUnknownRoom() {
        when(repo.findOperatingRoom(roomId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.board(roomId, null))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    // ---------------------------------------------------------------- 利用率

    @Test
    @DisplayName("利用率：逐日聚合 + 合计，比率保留 4 位（120/600=0.2000，120/1200=0.1000）")
    void utilizationAggregatesDaysAndTotals() {
        when(repo.findOperatingRoom(roomId)).thenReturn(Optional.of(room("ACTIVE")));
        when(repo.listRoomDayUsage(eq(roomId), any(Instant.class), any(Instant.class), eq(480),
                eq(1080)))
                .thenReturn(List.of(
                        new RoomDayUsage(Instant.parse("2026-10-31T16:00:00Z"), 2, 1, 0, 120,
                                30, 1),
                        new RoomDayUsage(Instant.parse("2026-11-01T16:00:00Z"), 0, 0, 0, 0, 0, 0)));

        RoomUtilization result = service.utilization(roomId, LocalDate.of(2026, 11, 1),
                LocalDate.of(2026, 11, 2), LocalTime.of(8, 0), LocalTime.of(18, 0));

        assertThat(result.days()).hasSize(2);
        assertThat(result.days().get(0).date()).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(result.days().get(0).availableMinutes()).isEqualTo(600);
        assertThat(result.days().get(0).utilizationRate())
                .isEqualByComparingTo("0.2000");
        assertThat(result.totalScheduled()).isEqualTo(2);
        assertThat(result.totalCompleted()).isEqualTo(1);
        assertThat(result.totalOccupiedMinutes()).isEqualTo(120);
        assertThat(result.totalAvailableMinutes()).isEqualTo(1200);
        assertThat(result.totalTurnoverMinutes()).isEqualTo(30);
        assertThat(result.utilizationRate()).isEqualByComparingTo("0.1000");
    }

    @Test
    @DisplayName("利用率：开放时段倒置 → 422；结束日期早于开始 → 422")
    void utilizationRejectsInvalidWindow() {
        when(repo.findOperatingRoom(roomId)).thenReturn(Optional.of(room("ACTIVE")));
        LocalDate day = LocalDate.of(2026, 11, 1);

        assertThatThrownBy(() -> service.utilization(roomId, day, day, LocalTime.of(18, 0),
                LocalTime.of(8, 0)))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));

        assertThatThrownBy(() -> service.utilization(roomId, day.plusDays(1), day,
                LocalTime.of(8, 0), LocalTime.of(18, 0)))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @Test
    @DisplayName("利用率：区间超过 92 天 → 422")
    void utilizationRejectsTooLongRange() {
        when(repo.findOperatingRoom(roomId)).thenReturn(Optional.of(room("ACTIVE")));
        LocalDate from = LocalDate.of(2026, 1, 1);

        assertThatThrownBy(() -> service.utilization(roomId, from, from.plusDays(92),
                null, null))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @Test
    @DisplayName("利用率：手术间不存在 → 404")
    void utilizationRejectsUnknownRoom() {
        when(repo.findOperatingRoom(roomId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.utilization(roomId, null, null, null, null))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    @DisplayName("全院利用率：只统计 ACTIVE 手术间")
    void hospitalUtilizationListsActiveRooms() {
        when(repo.listOperatingRooms(OperatingRoom.ACTIVE, 200))
                .thenReturn(List.of(room("ACTIVE")));
        when(repo.listRoomDayUsage(eq(roomId), any(Instant.class), any(Instant.class), eq(480),
                eq(1080))).thenReturn(List.of());

        List<RoomUtilization> result = service.hospitalUtilization(LocalDate.of(2026, 11, 1),
                LocalDate.of(2026, 11, 1), LocalTime.of(8, 0), LocalTime.of(18, 0));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).totalAvailableMinutes()).isZero();
        assertThat(result.get(0).utilizationRate()).isEqualByComparingTo("0.0000");
    }
}