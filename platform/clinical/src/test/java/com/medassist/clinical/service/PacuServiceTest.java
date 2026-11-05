package com.medassist.clinical.service;

import com.medassist.clinical.domain.ClinicalModel.PacuObservation;
import com.medassist.clinical.domain.ClinicalModel.PacuStay;
import com.medassist.clinical.domain.ClinicalModel.SurgeryCase;
import com.medassist.clinical.repo.ClinicalRepository;
import com.medassist.clinical.web.PacuDtos.AdmitReq;
import com.medassist.clinical.web.PacuDtos.DischargeReq;
import com.medassist.clinical.web.PacuDtos.ObservationReq;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.security.DataScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 复苏闭环规则：仅已结束手术、出室需评分达标、签名四眼且签名后冻结。 */
class PacuServiceTest {

    private ClinicalRepository repo;
    private PacuService service;

    private final UUID caseId = UUID.randomUUID();
    private final UUID stayId = UUID.randomUUID();
    private final UUID deptId = UUID.randomUUID();
    private final UUID campusId = UUID.randomUUID();
    private final Instant surgeryStart = Instant.parse("2026-11-05T01:00:00Z");
    private final Instant surgeryEnd = Instant.parse("2026-11-05T03:00:00Z");
    private final Instant admittedAt = Instant.parse("2026-11-05T03:10:00Z");

    @BeforeEach
    void setUp() {
        repo = mock(ClinicalRepository.class);
        service = new PacuService(repo);
    }

    private SurgeryCase surgery(String status) {
        return new SurgeryCase(caseId, "OP20261105000001", UUID.randomUUID(), null,
                UUID.randomUUID(), "47.0901", "阑尾切除术", surgeryStart, surgeryStart,
                SurgeryCase.COMPLETED.equals(status) ? surgeryEnd : null,
                "doctor01", null, "GENERAL", "II", "II", status, null, "admin01", 2L,
                Instant.now());
    }

    private PacuStay stay(String status, String createdBy, Instant signedAt) {
        return new PacuStay(stayId, caseId, "B01", deptId, campusId, admittedAt,
                PacuStay.OUT.equals(status) ? admittedAt.plusSeconds(1800) : null,
                PacuStay.OUT.equals(status) ? PacuStay.DEST_WARD : null,
                status, signedAt == null ? null : "doctor02", signedAt, createdBy,
                PacuStay.OUT.equals(status) ? 1L : 0L, Instant.now());
    }

    private ObservationReq observation(int activity, int respiration, int circulation,
                                       int consciousness, int oxygenation, Instant at) {
        return new ObservationReq(at, activity, respiration, circulation, consciousness,
                oxygenation, 88, 120, 80, 98, null, null);
    }

    // ---------------------------------------------------------------- 入室

    @Test
    @DisplayName("入室：手术未完成 → 422，不落库")
    void admitRejectsUnfinishedCase() {
        when(repo.findSurgeryCase(caseId)).thenReturn(Optional.of(surgery(SurgeryCase.IN_PROGRESS)));

        assertThatThrownBy(() -> service.admit(caseId, new AdmitReq("B01", null), "nurse01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.PACU_CASE_NOT_ELIGIBLE));
        verify(repo, never()).insertPacuStay(any());
    }

    @Test
    @DisplayName("入室：成功落库并把科室/院区从就诊固化")
    void admitPersistsDepartmentAndCampus() {
        when(repo.findSurgeryCase(caseId)).thenReturn(Optional.of(surgery(SurgeryCase.COMPLETED)));
        when(repo.findVisitDepartmentByCaseId(caseId)).thenReturn(Optional.of(deptId));
        when(repo.findVisitCampusByCaseId(caseId)).thenReturn(Optional.of(campusId));

        service.admit(caseId, new AdmitReq(" B01 ", admittedAt), "nurse01");

        ArgumentCaptor<PacuStay> captor = ArgumentCaptor.forClass(PacuStay.class);
        verify(repo).insertPacuStay(captor.capture());
        assertThat(captor.getValue().deptId()).isEqualTo(deptId);
        assertThat(captor.getValue().campusId()).isEqualTo(campusId);
        assertThat(captor.getValue().bedNo()).isEqualTo("B01");
        assertThat(captor.getValue().status()).isEqualTo(PacuStay.IN_PACU);
        assertThat(captor.getValue().createdBy()).isEqualTo("nurse01");
        verify(repo).logChange(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("入室：同一台手术重复入室 → 409")
    void admitRejectsDuplicate() {
        when(repo.findSurgeryCase(caseId)).thenReturn(Optional.of(surgery(SurgeryCase.COMPLETED)));
        when(repo.findVisitDepartmentByCaseId(caseId)).thenReturn(Optional.of(deptId));
        when(repo.findVisitCampusByCaseId(caseId)).thenReturn(Optional.of(campusId));
        doThrow(new DataIntegrityViolationException("ux_pacu_stay_case"))
                .when(repo).insertPacuStay(any());

        assertThatThrownBy(() -> service.admit(caseId, new AdmitReq(null, admittedAt), "nurse01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.PACU_STAY_EXISTS));
    }

    @Test
    @DisplayName("入室：早于手术结束时间 → 422")
    void admitRejectsBeforeSurgeryEnd() {
        when(repo.findSurgeryCase(caseId)).thenReturn(Optional.of(surgery(SurgeryCase.COMPLETED)));
        when(repo.findVisitDepartmentByCaseId(caseId)).thenReturn(Optional.of(deptId));
        when(repo.findVisitCampusByCaseId(caseId)).thenReturn(Optional.of(campusId));

        assertThatThrownBy(() -> service.admit(caseId,
                new AdmitReq(null, surgeryEnd.minusSeconds(60)), "nurse01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode())
                                .isEqualTo(ErrorCode.PACU_OBSERVATION_TIME_INVALID));
        verify(repo, never()).insertPacuStay(any());
    }

    // ---------------------------------------------------------------- 观察

    @Test
    @DisplayName("观察：已出室 → 409，不追加")
    void observationRejectsOutStay() {
        when(repo.findPacuStayByCase(caseId)).thenReturn(Optional.of(stay(PacuStay.OUT, "nurse01",
                null)));

        assertThatThrownBy(() -> service.addObservation(caseId,
                observation(2, 2, 2, 2, 2, admittedAt.plusSeconds(600)), "nurse01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.PACU_STAY_STATUS_INVALID));
        verify(repo, never()).insertPacuObservation(any(), anyString());
    }

    @Test
    @DisplayName("观察：早于入室时间 → 422")
    void observationRejectsBeforeAdmission() {
        when(repo.findPacuStayByCase(caseId))
                .thenReturn(Optional.of(stay(PacuStay.IN_PACU, "nurse01", null)));

        assertThatThrownBy(() -> service.addObservation(caseId,
                observation(2, 2, 2, 2, 2, admittedAt.minusSeconds(1)), "nurse01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode())
                                .isEqualTo(ErrorCode.PACU_OBSERVATION_TIME_INVALID));
    }

    @Test
    @DisplayName("观察：总分由服务端求和后落库")
    void observationComputesAldreteTotal() {
        when(repo.findPacuStayByCase(caseId))
                .thenReturn(Optional.of(stay(PacuStay.IN_PACU, "nurse01", null)));

        PacuObservation created = service.addObservation(caseId,
                observation(2, 2, 1, 2, 1, admittedAt.plusSeconds(600)), "nurse01");

        assertThat(created.total()).isEqualTo(8);
        assertThat(created.meetsDischargeCriteria()).isFalse();
        ArgumentCaptor<PacuObservation> captor = ArgumentCaptor.forClass(PacuObservation.class);
        verify(repo).insertPacuObservation(captor.capture(), eq("nurse01"));
        assertThat(captor.getValue().total()).isEqualTo(8);
    }

    @Test
    @DisplayName("观察：同一时间点重复 → 409")
    void observationRejectsDuplicateTimestamp() {
        when(repo.findPacuStayByCase(caseId))
                .thenReturn(Optional.of(stay(PacuStay.IN_PACU, "nurse01", null)));
        doThrow(new DataIntegrityViolationException("ux_pacu_observation_time"))
                .when(repo).insertPacuObservation(any(), anyString());

        assertThatThrownBy(() -> service.addObservation(caseId,
                observation(2, 2, 2, 2, 2, admittedAt.plusSeconds(600)), "nurse01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode())
                                .isEqualTo(ErrorCode.PACU_OBSERVATION_DUPLICATED));
    }

    // ---------------------------------------------------------------- 出室

    @Test
    @DisplayName("出室：没有任何评分 → 422")
    void dischargeRequiresObservation() {
        when(repo.findPacuStayByCase(caseId))
                .thenReturn(Optional.of(stay(PacuStay.IN_PACU, "nurse01", null)));
        when(repo.latestPacuObservation(stayId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.discharge(caseId,
                new DischargeReq(0L, null, "WARD"), "nurse01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.PACU_DISCHARGE_NOT_READY));
        verify(repo, never()).dischargePacu(any(), any(), anyString(), anyLong());
    }

    @Test
    @DisplayName("出室：最近一次 Aldrete < 9 → 422（不能用更早的高分蒙混）")
    void dischargeRequiresLatestScore() {
        when(repo.findPacuStayByCase(caseId))
                .thenReturn(Optional.of(stay(PacuStay.IN_PACU, "nurse01", null)));
        when(repo.latestPacuObservation(stayId)).thenReturn(Optional.of(
                new PacuObservation(UUID.randomUUID(), stayId, admittedAt.plusSeconds(900), 2, 2,
                        1, 1, 1, 7, null, null, null, null, null, null, Instant.now())));

        assertThatThrownBy(() -> service.discharge(caseId,
                new DischargeReq(0L, null, "WARD"), "nurse01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.PACU_DISCHARGE_NOT_READY));
    }

    @Test
    @DisplayName("出室：评分达标 → CAS 更新并返回最新记录")
    void dischargeSucceedsWithPassingScore() {
        when(repo.findPacuStayByCase(caseId))
                .thenReturn(Optional.of(stay(PacuStay.IN_PACU, "nurse01", null)))
                .thenReturn(Optional.of(stay(PacuStay.OUT, "nurse01", null)));
        when(repo.latestPacuObservation(stayId)).thenReturn(Optional.of(
                new PacuObservation(UUID.randomUUID(), stayId, admittedAt.plusSeconds(900), 2, 2,
                        2, 2, 1, 9, 80, 120, 70, 98, null, null, Instant.now())));
        when(repo.dischargePacu(eq(stayId), any(), eq("WARD"), eq(0L))).thenReturn(true);
        when(repo.listPacuObservations(stayId)).thenReturn(List.of());

        var view = service.discharge(caseId,
                new DischargeReq(0L, admittedAt.plusSeconds(1800), "ward"), "nurse01");

        assertThat(view.stay().status()).isEqualTo(PacuStay.OUT);
        verify(repo).dischargePacu(stayId, admittedAt.plusSeconds(1800), "WARD", 0L);
    }

    @Test
    @DisplayName("出室：版本号过期 → 409")
    void dischargeRejectsStaleVersion() {
        when(repo.findPacuStayByCase(caseId))
                .thenReturn(Optional.of(stay(PacuStay.IN_PACU, "nurse01", null)));
        when(repo.latestPacuObservation(stayId)).thenReturn(Optional.of(
                new PacuObservation(UUID.randomUUID(), stayId, admittedAt.plusSeconds(900), 2, 2,
                        2, 2, 2, 10, null, null, null, null, null, null, Instant.now())));
        when(repo.dischargePacu(eq(stayId), any(), eq("WARD"), eq(9L))).thenReturn(false);

        assertThatThrownBy(() -> service.discharge(caseId,
                new DischargeReq(9L, null, "WARD"), "nurse01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.CONFLICT));
    }

    @Test
    @DisplayName("出室：去向缺失 → 422（不做默认猜测）")
    void dischargeRequiresDestination() {
        when(repo.findPacuStayByCase(caseId))
                .thenReturn(Optional.of(stay(PacuStay.IN_PACU, "nurse01", null)));

        assertThatThrownBy(() -> service.discharge(caseId,
                new DischargeReq(0L, null, null), "nurse01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.PACU_DESTINATION_INVALID));
    }

    // ---------------------------------------------------------------- 签名

    @Test
    @DisplayName("签名：还在室内 → 409（先出室再签）")
    void signRejectsStayStillInPacu() {
        when(repo.findPacuStayByCase(caseId))
                .thenReturn(Optional.of(stay(PacuStay.IN_PACU, "nurse01", null)));

        assertThatThrownBy(() -> service.sign(caseId, 0L, "doctor02"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.PACU_STAY_STATUS_INVALID));
        verify(repo, never()).signPacu(any(), anyString(), anyLong());
    }

    @Test
    @DisplayName("签名：记录人自己签 → 409（四眼复核）")
    void signRejectsSelfSignature() {
        when(repo.findPacuStayByCase(caseId))
                .thenReturn(Optional.of(stay(PacuStay.OUT, "nurse01", null)));

        assertThatThrownBy(() -> service.sign(caseId, 1L, "nurse01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.PACU_SELF_SIGN));
        verify(repo, never()).signPacu(any(), anyString(), anyLong());
    }

    @Test
    @DisplayName("签名：他人复核 → 冻结成功")
    void signSucceedsByAnotherUser() {
        when(repo.findPacuStayByCase(caseId))
                .thenReturn(Optional.of(stay(PacuStay.OUT, "nurse01", null)))
                .thenReturn(Optional.of(stay(PacuStay.OUT, "nurse01", Instant.now())));
        when(repo.signPacu(stayId, "doctor02", 1L)).thenReturn(true);
        when(repo.listPacuObservations(stayId)).thenReturn(List.of());

        var view = service.sign(caseId, 1L, "doctor02");

        assertThat(view.stay().signed()).isTrue();
        verify(repo).signPacu(stayId, "doctor02", 1L);
    }

    @Test
    @DisplayName("签名：重复签署 → 409")
    void signRejectsAlreadySigned() {
        when(repo.findPacuStayByCase(caseId))
                .thenReturn(Optional.of(stay(PacuStay.OUT, "nurse01", Instant.now())));

        assertThatThrownBy(() -> service.sign(caseId, 2L, "doctor02"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.PACU_STAY_STATUS_INVALID));
    }

    // ---------------------------------------------------------------- 看板

    @Test
    @DisplayName("看板：状态非法 → 422")
    void boardRejectsUnknownStatus() {
        DataScope scope = DataScope.all("admin01", true);

        assertThatThrownBy(() -> service.board("UNKNOWN", null, 10, scope))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @Test
    @DisplayName("看板：科室与院区下推 SQL，跨科室角色不加科室条件")
    void boardPushesScopeIntoQuery() {
        DataScope scoped = DataScope.of("doctor01", Set.of(deptId), false,
                Set.of(campusId), false, true);
        when(repo.listPacuBoard(any(), any(), any(), anyString(), anyString(), anyInt()))
                .thenReturn(List.of());

        service.board("in_pacu", null, 500, scoped);

        verify(repo).listPacuBoard(eq("IN_PACU"), isNull(), isNull(), eq(deptId.toString()),
                eq(campusId.toString()), eq(100));

        service.board(null, null, 0, DataScope.all("admin01", true));
        verify(repo).listPacuBoard(isNull(), isNull(), isNull(), eq(""), eq(""), eq(100));
    }
}
