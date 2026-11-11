package com.medassist.clinical.service;

import com.medassist.clinical.domain.ClinicalModel.Bed;
import com.medassist.clinical.domain.ClinicalModel.BedReservation;
import com.medassist.clinical.domain.ClinicalModel.Ward;
import com.medassist.clinical.repo.ClinicalRepository;
import com.medassist.clinical.web.ClinicalDtos.BedReservationView;
import com.medassist.clinical.web.ClinicalDtos.BedReserveReq;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
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

/**
 * 床位预留：占床 CAS、到期兜底、解除释放、清理不误伤已消费预留。
 */
class BedReservationServiceTest {

    private ClinicalRepository repo;
    private BedReservationService service;

    private final UUID wardId = UUID.randomUUID();
    private final UUID bedId = UUID.randomUUID();
    private final UUID patientId = UUID.randomUUID();
    private final Ward ward = new Ward(wardId, "W01", "内科一病区", UUID.randomUUID(), true);
    private final Bed bed = new Bed(bedId, wardId, "01", Bed.FREE, 3L);

    @BeforeEach
    void setUp() {
        repo = mock(ClinicalRepository.class);
        service = new BedReservationService(repo, JsonMapper.builder().build());
        when(repo.findWard(wardId)).thenReturn(Optional.of(ward));
        when(repo.lockBed(bedId)).thenReturn(Optional.of(bed));
        when(repo.listBeds(wardId)).thenReturn(List.of(bed));
    }

    @Test
    @DisplayName("预留：空闲床置 RESERVED 并落库留痕；未给到期时间默认 24 小时")
    void createReservesFreeBed() {
        when(repo.reserveBed(bedId, 3L)).thenReturn(true);

        BedReservationView view = service.create(wardId,
                new BedReserveReq(bedId, patientId, "P20260101000001", "择期手术", null),
                "nurse01");

        assertThat(view.bedNo()).isEqualTo("01");
        assertThat(view.patientNo()).isEqualTo("P20260101000001");
        assertThat(view.expired()).isFalse();
        assertThat(view.reservedUntil()).isAfter(Instant.now().plus(Duration.ofHours(23)));
        assertThat(view.reservedUntil()).isBefore(Instant.now().plus(Duration.ofHours(25)));

        verify(repo).reserveBed(bedId, 3L);
        verify(repo).insertBedReservation(any(BedReservation.class));
        verify(repo).logChange(eq("nurse01"), eq("BED_RESERVE"), eq("BED_RESERVATION"),
                anyString(), anyString());
    }

    @Test
    @DisplayName("预留：非空闲床拒绝；跨病区床拒绝")
    void createRejectsUnavailableBed() {
        when(repo.lockBed(bedId)).thenReturn(Optional.of(
                new Bed(bedId, wardId, "01", Bed.RESERVED, 3L)));

        assertThatThrownBy(() -> service.create(wardId,
                new BedReserveReq(bedId, null, null, null, null), "nurse01"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不能预留")
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CONFLICT);
        verify(repo, never()).reserveBed(any(), anyLong());

        when(repo.lockBed(bedId)).thenReturn(Optional.of(
                new Bed(bedId, UUID.randomUUID(), "01", Bed.FREE, 3L)));
        assertThatThrownBy(() -> service.create(wardId,
                new BedReserveReq(bedId, null, null, null, null), "nurse01"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不属于该病区");
    }

    @Test
    @DisplayName("预留：到期时间已过 422；占床 CAS 失败转 409")
    void createRejectsPastExpiryAndStaleBed() {
        assertThatThrownBy(() -> service.create(wardId,
                new BedReserveReq(bedId, null, null, null, Instant.now().minusSeconds(60)), "nurse01"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("必须晚于当前时间")
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        verify(repo, never()).reserveBed(any(), anyLong());

        when(repo.reserveBed(bedId, 3L)).thenReturn(false);
        assertThatThrownBy(() -> service.create(wardId,
                new BedReserveReq(bedId, null, null, null, null), "nurse01"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已被他人修改")
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    @Test
    @DisplayName("解除：预留置 RELEASED，仍为 RESERVED 的床放回 FREE，并留痕")
    void releaseFreesReservedBed() {
        UUID reservationId = UUID.randomUUID();
        BedReservation r = reservation(reservationId, BedReservation.ACTIVE, 1L,
                Instant.now().plusSeconds(3600));
        when(repo.findBedReservation(reservationId)).thenReturn(Optional.of(r));
        when(repo.lockBed(bedId)).thenReturn(Optional.of(
                new Bed(bedId, wardId, "01", Bed.RESERVED, 7L)));
        when(repo.lockBedReservation(reservationId)).thenReturn(Optional.of(r));
        when(repo.updateBedReservationStatus(reservationId, BedReservation.ACTIVE,
                BedReservation.RELEASED, 1L)).thenReturn(true);
        when(repo.freeReservedBed(bedId, 7L)).thenReturn(true);

        service.release(wardId, reservationId, "患者改期", "nurse01");

        verify(repo).updateBedReservationStatus(reservationId, BedReservation.ACTIVE,
                BedReservation.RELEASED, 1L);
        verify(repo).freeReservedBed(bedId, 7L);
        verify(repo).logChange(eq("nurse01"), eq("BED_RESERVE_RELEASE"),
                eq("BED_RESERVATION"), eq(reservationId.toString()), anyString());
    }

    @Test
    @DisplayName("解除：已消费/已解除的预留 409；床位已占用时只结束预留不腾床")
    void releaseRejectsInactiveAndSkipsOccupiedBed() {
        UUID consumedId = UUID.randomUUID();
        BedReservation consumed = reservation(consumedId, BedReservation.CONSUMED, 2L,
                Instant.now().plusSeconds(3600));
        when(repo.findBedReservation(consumedId)).thenReturn(Optional.of(consumed));
        when(repo.lockBedReservation(consumedId)).thenReturn(Optional.of(consumed));

        assertThatThrownBy(() -> service.release(wardId, consumedId, null, "nurse01"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已结束")
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CONFLICT);

        UUID activeId = UUID.randomUUID();
        BedReservation active = reservation(activeId, BedReservation.ACTIVE, 1L,
                Instant.now().plusSeconds(3600));
        when(repo.findBedReservation(activeId)).thenReturn(Optional.of(active));
        when(repo.lockBedReservation(activeId)).thenReturn(Optional.of(active));
        when(repo.lockBed(bedId)).thenReturn(Optional.of(
                new Bed(bedId, wardId, "01", Bed.OCCUPIED, 9L)));
        when(repo.updateBedReservationStatus(activeId, BedReservation.ACTIVE,
                BedReservation.RELEASED, 1L)).thenReturn(true);

        service.release(wardId, activeId, null, "nurse01");

        verify(repo, never()).freeReservedBed(any(), anyLong());
    }

    @Test
    @DisplayName("清理：只结束真正到期的有效预留；已消费的跳过；不误放占用床")
    void sweepExpiresOnlyOverdueActive() {
        UUID overdueId = UUID.randomUUID();
        UUID consumedId = UUID.randomUUID();
        UUID notYetId = UUID.randomUUID();
        BedReservation overdue = reservation(overdueId, BedReservation.ACTIVE, 4L,
                Instant.now().minusSeconds(600));
        BedReservation consumed = reservation(consumedId, BedReservation.CONSUMED, 2L,
                Instant.now().minusSeconds(600));
        BedReservation notYet = reservation(notYetId, BedReservation.ACTIVE, 1L,
                Instant.now().plusSeconds(600));

        when(repo.listExpiredBedReservations(any(), eq(50)))
                .thenReturn(List.of(overdue, consumed, notYet));
        when(repo.lockBed(bedId)).thenReturn(Optional.of(
                new Bed(bedId, wardId, "01", Bed.RESERVED, 5L)));
        when(repo.lockBedReservation(overdueId)).thenReturn(Optional.of(overdue));
        when(repo.lockBedReservation(consumedId)).thenReturn(Optional.of(consumed));
        when(repo.lockBedReservation(notYetId)).thenReturn(Optional.of(notYet));
        when(repo.updateBedReservationStatus(overdueId, BedReservation.ACTIVE,
                BedReservation.EXPIRED, 4L)).thenReturn(true);
        when(repo.freeReservedBed(bedId, 5L)).thenReturn(true);

        int expired = service.sweepExpired(50);

        assertThat(expired).isEqualTo(1);
        verify(repo).updateBedReservationStatus(overdueId, BedReservation.ACTIVE,
                BedReservation.EXPIRED, 4L);
        verify(repo).freeReservedBed(bedId, 5L);
        verify(repo, never()).updateBedReservationStatus(eq(consumedId), anyString(),
                anyString(), anyLong());
        verify(repo, never()).updateBedReservationStatus(eq(notYetId), anyString(),
                anyString(), anyLong());
    }

    @Test
    @DisplayName("列表：回带床号；到期但未回收标记 expired")
    void listMarksExpiredButNotSwept() {
        BedReservation expired = reservation(UUID.randomUUID(), BedReservation.ACTIVE, 0L,
                Instant.now().minusSeconds(60));
        BedReservation active = reservation(UUID.randomUUID(), BedReservation.ACTIVE, 0L,
                Instant.now().plusSeconds(3600));
        when(repo.listActiveBedReservations(wardId)).thenReturn(List.of(expired, active));

        List<BedReservationView> views = service.list(wardId);

        assertThat(views).hasSize(2);
        assertThat(views.get(0).expired()).isTrue();
        assertThat(views.get(0).bedNo()).isEqualTo("01");
        assertThat(views.get(1).expired()).isFalse();
    }

    private BedReservation reservation(UUID id, String status, long version, Instant until) {
        return new BedReservation(id, bedId, wardId, patientId, "P1", "择期", "nurse01",
                until, status, Instant.now(), version);
    }
}
