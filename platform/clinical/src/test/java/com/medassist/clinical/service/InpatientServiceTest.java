package com.medassist.clinical.service;

import com.medassist.clinical.client.PatientClient;
import com.medassist.clinical.domain.ClinicalModel.Admission;
import com.medassist.clinical.domain.ClinicalModel.Bed;
import com.medassist.clinical.domain.ClinicalModel.BedReservation;
import com.medassist.clinical.domain.ClinicalModel.Charge;
import com.medassist.clinical.domain.ClinicalModel.Dept;
import com.medassist.clinical.domain.ClinicalModel.Visit;
import com.medassist.clinical.domain.ClinicalModel.Ward;
import com.medassist.clinical.repo.ClinicalRepository;
import com.medassist.clinical.web.ClinicalDtos.AdmissionView;
import com.medassist.clinical.web.ClinicalDtos.AdmitReq;
import com.medassist.clinical.web.ClinicalDtos.AssignBedReq;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.time.BusinessTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
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
 * 住院 ADT 规则：重复入院、床位占用与释放、出院前结清。
 */
class InpatientServiceTest {

    private ClinicalRepository repo;
    private PatientClient patientClient;
    private InpatientService service;

    private final UUID deptId = UUID.randomUUID();
    private final UUID wardId = UUID.randomUUID();
    private final UUID patientId = UUID.randomUUID();
    private final Dept dept = new Dept(deptId, "DEPT-IM", "内科门诊", "OUTPATIENT", true);
    private final Ward ward = new Ward(wardId, "W-IM-01", "内科一病区", deptId, true);

    @BeforeEach
    void setUp() {
        repo = mock(ClinicalRepository.class);
        patientClient = mock(PatientClient.class);
        service = new InpatientService(repo, patientClient, JsonMapper.builder().build());
        when(repo.findWard(wardId)).thenReturn(Optional.of(ward));
        when(repo.findDept(deptId)).thenReturn(Optional.of(dept));
        when(patientClient.requireActive(patientId))
                .thenReturn(new PatientClient.PatientRef(patientId, "P20261031000001", "张三"));
    }

    private Visit visit(String status) {
        return new Visit(UUID.randomUUID(), "V1", patientId, "P1", deptId, "doctor01", null,
                "INPATIENT", status, LocalDate.now(), 1L);
    }

    @Test
    @DisplayName("入院（未分床）：建住院就诊 + 0 元收费单 + ADMITTED 登记")
    void admitWithoutBed() {
        when(repo.findActiveAdmissionByPatient(patientId)).thenReturn(Optional.empty());
        when(repo.nextVisitNo()).thenReturn("V20261031000010");
        when(repo.nextChargeNo()).thenReturn("C20261031000010");
        when(repo.nextAdmissionNo()).thenReturn("A20261031000001");
        when(repo.findAdmission(any())).thenAnswer(inv -> {
            UUID id = inv.getArgument(0);
            return Optional.of(new Admission(id, "A20261031000001", UUID.randomUUID(), patientId, "P1",
                    wardId, null, "doctor01", Admission.ADMITTED, Instant.now(), null, 0L));
        });
        when(repo.findVisit(any())).thenReturn(Optional.of(visit(Visit.IN_TREATMENT)));

        AdmissionView v = service.admit(new AdmitReq(patientId, wardId, null, "doctor01"), "clerk01");

        assertThat(v.status()).isEqualTo(Admission.ADMITTED);
        verify(repo).insertVisit(any(Visit.class), any());
        verify(repo).insertCharge(any(Charge.class));
        verify(repo).insertAdmission(any(Admission.class));
        verify(repo, never()).occupyBed(any(), anyLong());
    }

    @Test
    @DisplayName("重复入院：同一患者已有在院登记时拒绝")
    void admitRejectsDuplicate() {
        when(repo.findActiveAdmissionByPatient(patientId)).thenReturn(Optional.of(
                new Admission(UUID.randomUUID(), "A1", UUID.randomUUID(), patientId, "P1", wardId,
                        null, "doctor01", Admission.IN_HOSPITAL, Instant.now(), null, 0L)));

        assertThatThrownBy(() -> service.admit(new AdmitReq(patientId, wardId, null, "doctor01"), "clerk01"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("A1");
        verify(repo, never()).insertAdmission(any());
    }

    @Test
    @DisplayName("转床：占新床、释放旧床、留转床轨迹")
    void transferOccupiesTargetAndFreesSource() {
        UUID admissionId = UUID.randomUUID();
        UUID oldBed = UUID.randomUUID();
        UUID newBed = UUID.randomUUID();
        Admission a = new Admission(admissionId, "A1", UUID.randomUUID(), patientId, "P1", wardId,
                oldBed, "doctor01", Admission.IN_HOSPITAL, Instant.now(), null, 2L);
        when(repo.lockAdmission(admissionId)).thenReturn(Optional.of(a));
        when(repo.lockBed(newBed)).thenReturn(Optional.of(new Bed(newBed, wardId, "02", Bed.FREE, 0L)));
        when(repo.lockBed(oldBed)).thenReturn(Optional.of(new Bed(oldBed, wardId, "01", Bed.OCCUPIED, 3L)));
        when(repo.occupyBed(newBed, 0L)).thenReturn(true);
        when(repo.updateAdmissionBed(admissionId, newBed, 2L)).thenReturn(true);
        when(repo.freeBed(oldBed, 3L)).thenReturn(true);
        when(repo.findAdmission(admissionId)).thenReturn(Optional.of(new Admission(
                admissionId, "A1", a.visitId(), patientId, "P1", wardId, newBed, "doctor01",
                Admission.IN_HOSPITAL, Instant.now(), null, 3L)));
        when(repo.findBed(newBed)).thenReturn(Optional.of(new Bed(newBed, wardId, "02", Bed.OCCUPIED, 1L)));
        when(repo.findVisit(any())).thenReturn(Optional.of(visit(Visit.IN_TREATMENT)));

        AdmissionView v = service.assignBed(admissionId, new AssignBedReq(newBed, "病情需要"), "nurse01");

        assertThat(v.bedNo()).isEqualTo("02");
        verify(repo).occupyBed(newBed, 0L);
        verify(repo).freeBed(oldBed, 3L);
        verify(repo).logBedTransfer(admissionId, oldBed, newBed, "病情需要", "nurse01");
    }

    @Test
    @DisplayName("未结清不得出院")
    void dischargeRejectsUnpaid() {
        UUID admissionId = UUID.randomUUID();
        Admission a = new Admission(admissionId, "A1", UUID.randomUUID(), patientId, "P1", wardId,
                null, "doctor01", Admission.IN_HOSPITAL, Instant.now(), null, 1L);
        when(repo.findAdmission(admissionId)).thenReturn(Optional.of(a));
        when(repo.lockVisit(a.visitId())).thenReturn(Optional.of(visit(Visit.IN_TREATMENT)));
        when(repo.lockAdmission(admissionId)).thenReturn(Optional.of(a));
        when(repo.hasUnsettledCharge(a.visitId())).thenReturn(true);

        assertThatThrownBy(() -> service.discharge(admissionId, "doctor01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CHARGE_STATUS_INVALID);
        verify(repo, never()).updateAdmissionStatus(any(), any(), any(), anyLong());
    }

    @Test
    @DisplayName("还有未计费的临时医嘱不得出院")
    void dischargeRejectsUnbilledOrders() {
        UUID admissionId = UUID.randomUUID();
        UUID visitId = UUID.randomUUID();
        Admission a = new Admission(admissionId, "A1", visitId, patientId, "P1", wardId,
                null, "doctor01", Admission.IN_HOSPITAL, Instant.now(), null, 1L);
        when(repo.findAdmission(admissionId)).thenReturn(Optional.of(a));
        when(repo.lockVisit(visitId)).thenReturn(Optional.of(visit(Visit.IN_TREATMENT)));
        when(repo.lockAdmission(admissionId)).thenReturn(Optional.of(a));
        when(repo.hasUnsettledCharge(visitId)).thenReturn(false);
        when(repo.countUnbilledTempOrders(visitId)).thenReturn(2);

        assertThatThrownBy(() -> service.discharge(admissionId, "doctor01"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("临时医嘱");
        verify(repo, never()).updateAdmissionStatus(any(), any(), any(), anyLong());
    }

    @Test
    @DisplayName("当日有效长嘱未计费不得出院（应收尽收）")
    void dischargeRejectsUnbilledDailyOrders() {
        UUID admissionId = UUID.randomUUID();
        UUID visitId = UUID.randomUUID();
        Admission a = new Admission(admissionId, "A1", visitId, patientId, "P1", wardId,
                null, "doctor01", Admission.IN_HOSPITAL, Instant.now(), null, 1L);
        when(repo.findAdmission(admissionId)).thenReturn(Optional.of(a));
        when(repo.lockVisit(visitId)).thenReturn(Optional.of(visit(Visit.IN_TREATMENT)));
        when(repo.lockAdmission(admissionId)).thenReturn(Optional.of(a));
        when(repo.hasUnsettledCharge(visitId)).thenReturn(false);
        when(repo.countUnbilledTempOrders(visitId)).thenReturn(0);
        when(repo.countUnbilledDailyOrders(eq(visitId), any(LocalDate.class), any(LocalDate.class))).thenReturn(1);

        assertThatThrownBy(() -> service.discharge(admissionId, "doctor01"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("长期医嘱");
        verify(repo, never()).updateAdmissionStatus(any(), any(), any(), anyLong());
    }

    @Test
    @DisplayName("预交金：存入后余额增加，流水记为 DEPOSIT")
    void depositIncreasesBalance() {
        UUID admissionId = UUID.randomUUID();
        Admission a = new Admission(admissionId, "A1", UUID.randomUUID(), patientId, "P1", wardId,
                null, "doctor01", Admission.IN_HOSPITAL, Instant.now(), null, 1L);
        when(repo.findAdmission(admissionId)).thenReturn(Optional.of(a));
        when(repo.lockVisit(a.visitId())).thenReturn(Optional.of(visit(Visit.IN_TREATMENT)));
        when(repo.lockAdmission(admissionId)).thenReturn(Optional.of(a));
        when(repo.depositBalance(admissionId)).thenReturn(new BigDecimal("100.00"));
        when(repo.listDeposits(admissionId)).thenReturn(java.util.List.of());

        var view = service.deposit(admissionId, new BigDecimal("100"), "CASH", "首次预交", "clerk01");

        assertThat(view.balance()).isEqualByComparingTo("100.00");
        verify(repo).insertDeposit(any(), eq(admissionId), eq(new BigDecimal("100.00")),
                eq("DEPOSIT"), eq("CASH"), eq("clerk01"), eq("首次预交"));
    }

    @Test
    @DisplayName("退预交金不得超过余额")
    void refundRejectedOverBalance() {
        UUID admissionId = UUID.randomUUID();
        Admission a = new Admission(admissionId, "A1", UUID.randomUUID(), patientId, "P1", wardId,
                null, "doctor01", Admission.IN_HOSPITAL, Instant.now(), null, 1L);
        when(repo.findAdmission(admissionId)).thenReturn(Optional.of(a));
        when(repo.lockVisit(a.visitId())).thenReturn(Optional.of(visit(Visit.IN_TREATMENT)));
        when(repo.lockAdmission(admissionId)).thenReturn(Optional.of(a));
        when(repo.depositBalance(admissionId)).thenReturn(new BigDecimal("50.00"));

        assertThatThrownBy(() -> service.refund(admissionId, new BigDecimal("60"), "CASH", null, "clerk01"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("50.00");
        verify(repo, never()).insertDeposit(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("G51：负数退款拒绝——不得变成正向入账")
    void refundRejectsNegativeAmount() {
        UUID admissionId = UUID.randomUUID();
        Admission a = new Admission(admissionId, "A1", UUID.randomUUID(), patientId, "P1", wardId,
                null, "doctor01", Admission.IN_HOSPITAL, Instant.now(), null, 1L);
        when(repo.findAdmission(admissionId)).thenReturn(Optional.of(a));
        when(repo.lockVisit(a.visitId())).thenReturn(Optional.of(visit(Visit.IN_TREATMENT)));
        when(repo.lockAdmission(admissionId)).thenReturn(Optional.of(a));

        assertThatThrownBy(() -> service.refund(admissionId, new BigDecimal("-100"), "CASH", null,
                "clerk01"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("必须大于 0");
        verify(repo, never()).insertDeposit(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("G51：0 或负数预交金拒绝（服务层兜底，不依赖 HTTP 校验）")
    void depositRejectsNonPositiveAmount() {
        UUID admissionId = UUID.randomUUID();
        Admission a = new Admission(admissionId, "A1", UUID.randomUUID(), patientId, "P1", wardId,
                null, "doctor01", Admission.IN_HOSPITAL, Instant.now(), null, 1L);
        when(repo.findAdmission(admissionId)).thenReturn(Optional.of(a));
        when(repo.lockVisit(a.visitId())).thenReturn(Optional.of(visit(Visit.IN_TREATMENT)));
        when(repo.lockAdmission(admissionId)).thenReturn(Optional.of(a));

        assertThatThrownBy(() -> service.deposit(admissionId, BigDecimal.ZERO, "CASH", null, "clerk01"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("必须大于 0");
        verify(repo, never()).insertDeposit(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("出院：置 DISCHARGED、腾床、结束就诊")
    void dischargeFreesBedAndFinishesVisit() {
        UUID admissionId = UUID.randomUUID();
        UUID bedId = UUID.randomUUID();
        UUID visitId = UUID.randomUUID();
        Admission a = new Admission(admissionId, "A1", visitId, patientId, "P1", wardId,
                bedId, "doctor01", Admission.IN_HOSPITAL, Instant.now(), null, 4L);
        when(repo.findAdmission(admissionId)).thenReturn(Optional.of(a));
        when(repo.lockVisit(visitId)).thenReturn(Optional.of(
                new Visit(visitId, "V1", patientId, "P1", deptId, "doctor01", null,
                        "INPATIENT", Visit.IN_TREATMENT, LocalDate.now(), 1L)));
        when(repo.lockAdmission(admissionId)).thenReturn(Optional.of(a));
        when(repo.hasUnsettledCharge(visitId)).thenReturn(false);
        when(repo.countUnbilledTempOrders(visitId)).thenReturn(0);
        when(repo.countUnbilledDailyOrders(eq(visitId), any(LocalDate.class), any(LocalDate.class))).thenReturn(0);
        when(repo.findLatestCharge(visitId)).thenReturn(Optional.of(
                new Charge(UUID.randomUUID(), "C1", visitId, new BigDecimal("88.00"),
                        Charge.PAID, "CASH", Instant.now(), 2L)));
        when(repo.updateAdmissionStatus(admissionId, Admission.IN_HOSPITAL, Admission.DISCHARGED, 4L))
                .thenReturn(true);
        when(repo.lockBed(bedId)).thenReturn(Optional.of(new Bed(bedId, wardId, "01", Bed.OCCUPIED, 7L)));
        when(repo.freeBed(bedId, 7L)).thenReturn(true);
        when(repo.findVisit(visitId)).thenReturn(Optional.of(
                new Visit(visitId, "V1", patientId, "P1", deptId, "doctor01", null,
                        "INPATIENT", Visit.IN_TREATMENT, LocalDate.now(), 1L)));
        when(repo.updateVisitStatus(visitId, Visit.IN_TREATMENT, Visit.FINISHED, 1L)).thenReturn(true);
        when(repo.findAdmission(admissionId)).thenReturn(Optional.of(new Admission(
                admissionId, "A1", visitId, patientId, "P1", wardId, bedId, "doctor01",
                Admission.DISCHARGED, Instant.now(), Instant.now(), 5L)));
        when(repo.findBed(bedId)).thenReturn(Optional.of(new Bed(bedId, wardId, "01", Bed.FREE, 8L)));

        AdmissionView v = service.discharge(admissionId, "doctor01");

        assertThat(v.status()).isEqualTo(Admission.DISCHARGED);
        verify(repo).freeBed(bedId, 7L);
        verify(repo).updateVisitStatus(visitId, Visit.IN_TREATMENT, Visit.FINISHED, 1L);
        // 出院自动停嘱：长嘱不会随就诊结束失效，必须显式停
        verify(repo).stopUnstoppedLongOrders(eq(visitId), any(LocalDate.class), eq("doctor01"),
                eq("出院自动停嘱"));
    }

    @Test
    @DisplayName("出院门禁失败时不停嘱：否则当日未计费长嘱会从缺口消失，导致漏收费")
    void dischargeDoesNotStopOrdersWhenGateFails() {
        UUID admissionId = UUID.randomUUID();
        UUID visitId = UUID.randomUUID();
        Admission a = new Admission(admissionId, "A1", visitId, patientId, "P1", wardId,
                null, "doctor01", Admission.IN_HOSPITAL, Instant.now(), null, 4L);
        when(repo.findAdmission(admissionId)).thenReturn(Optional.of(a));
        when(repo.lockVisit(visitId)).thenReturn(Optional.of(
                new Visit(visitId, "V1", patientId, "P1", deptId, "doctor01", null,
                        "INPATIENT", Visit.IN_TREATMENT, LocalDate.now(), 1L)));
        when(repo.lockAdmission(admissionId)).thenReturn(Optional.of(a));
        when(repo.hasUnsettledCharge(visitId)).thenReturn(true);

        assertThatThrownBy(() -> service.discharge(admissionId, "doctor01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CHARGE_STATUS_INVALID);

        verify(repo, never()).stopUnstoppedLongOrders(any(), any(), anyString(), anyString());
    }

    // ---------------------------------------------------------------- 预计出院日

    @Test
    @DisplayName("预计出院日：写入成功并留痕（旧值→新值），视图回带计划日")
    void planDischargeUpdatesAndAudits() {
        UUID admissionId = UUID.randomUUID();
        UUID visitId = UUID.randomUUID();
        LocalDate admittedDay = BusinessTime.today().minusDays(2);
        Admission a = new Admission(admissionId, "A1", visitId, patientId, "P1", wardId,
                null, "doctor01", Admission.IN_HOSPITAL,
                admittedDay.atStartOfDay(BusinessTime.zone()).toInstant(), null, 4L);
        LocalDate target = BusinessTime.today().plusDays(2);
        when(repo.lockAdmission(admissionId)).thenReturn(Optional.of(a));
        when(repo.updatePlannedDischargeDate(admissionId, target, 4L)).thenReturn(true);
        when(repo.findAdmission(admissionId)).thenReturn(Optional.of(new Admission(
                admissionId, "A1", visitId, patientId, "P1", wardId, null, "doctor01",
                Admission.IN_HOSPITAL, a.admittedAt(), null, target, 5L)));
        when(repo.findVisit(visitId)).thenReturn(Optional.of(visit(Visit.IN_TREATMENT)));

        AdmissionView v = service.planDischarge(admissionId, target, "doctor01");

        assertThat(v.plannedDischargeDate()).isEqualTo(target);
        verify(repo).updatePlannedDischargeDate(admissionId, target, 4L);
        verify(repo).logChange(eq("doctor01"), eq("PLAN_DISCHARGE"), eq("ADMISSION"),
                eq(admissionId.toString()), anyString());
    }

    @Test
    @DisplayName("预计出院日：早于入院日 422，且不落库")
    void planDischargeRejectsBeforeAdmission() {
        UUID admissionId = UUID.randomUUID();
        LocalDate admittedDay = BusinessTime.today().minusDays(1);
        Admission a = new Admission(admissionId, "A1", UUID.randomUUID(), patientId, "P1", wardId,
                null, "doctor01", Admission.IN_HOSPITAL,
                admittedDay.atStartOfDay(BusinessTime.zone()).toInstant(), null, 1L);
        when(repo.lockAdmission(admissionId)).thenReturn(Optional.of(a));

        assertThatThrownBy(() -> service.planDischarge(admissionId, admittedDay.minusDays(1), "doctor01"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不能早于入院日")
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        verify(repo, never()).updatePlannedDischargeDate(any(), any(), anyLong());
    }

    // ---------------------------------------------------------------- 床位预留消费

    @Test
    @DisplayName("入院用预留床：同一患者的预留被消费，床位 RESERVED → OCCUPIED")
    void admitConsumesOwnReservation() {
        UUID bedId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        UUID visitId = UUID.randomUUID();
        BedReservation reservation = new BedReservation(reservationId, bedId, wardId, patientId,
                "P1", "择期手术", "nurse01", Instant.now().plusSeconds(3600),
                BedReservation.ACTIVE, Instant.now(), 0L);

        when(repo.nextVisitNo()).thenReturn("V20261031000011");
        when(repo.nextChargeNo()).thenReturn("C20261031000011");
        when(repo.nextAdmissionNo()).thenReturn("A20261031000002");
        when(repo.lockBed(bedId)).thenReturn(Optional.of(
                new Bed(bedId, wardId, "01", Bed.RESERVED, 2L)));
        when(repo.lockActiveBedReservation(bedId)).thenReturn(Optional.of(reservation));
        when(repo.updateBedReservationStatus(reservationId, BedReservation.ACTIVE,
                BedReservation.CONSUMED, 0L)).thenReturn(true);
        when(repo.occupyReservedBed(bedId, 2L)).thenReturn(true);
        when(repo.findAdmission(any())).thenAnswer(inv -> Optional.of(new Admission(
                inv.getArgument(0), "A20261031000002", visitId, patientId, "P1", wardId, bedId,
                "doctor01", Admission.IN_HOSPITAL, Instant.now(), null, 0L)));
        when(repo.findVisit(any())).thenReturn(Optional.of(visit(Visit.IN_TREATMENT)));
        when(repo.findBed(bedId)).thenReturn(Optional.of(
                new Bed(bedId, wardId, "01", Bed.OCCUPIED, 3L)));

        AdmissionView v = service.admit(new AdmitReq(patientId, wardId, bedId, "doctor01"), "clerk01");

        assertThat(v.status()).isEqualTo(Admission.IN_HOSPITAL);
        verify(repo).updateBedReservationStatus(reservationId, BedReservation.ACTIVE,
                BedReservation.CONSUMED, 0L);
        verify(repo).occupyReservedBed(bedId, 2L);
        verify(repo, never()).occupyBed(any(), anyLong());
    }

    @Test
    @DisplayName("入院用别人预留的床：409，且不消费预留、不落住院登记")
    void admitRejectsReservationForOtherPatient() {
        UUID bedId = UUID.randomUUID();
        BedReservation reservation = new BedReservation(UUID.randomUUID(), bedId, wardId,
                UUID.randomUUID(), "P9", "择期", "nurse01", Instant.now().plusSeconds(3600),
                BedReservation.ACTIVE, Instant.now(), 0L);
        when(repo.lockBed(bedId)).thenReturn(Optional.of(
                new Bed(bedId, wardId, "01", Bed.RESERVED, 2L)));
        when(repo.lockActiveBedReservation(bedId)).thenReturn(Optional.of(reservation));

        assertThatThrownBy(() -> service.admit(new AdmitReq(patientId, wardId, bedId, "doctor01"),
                "clerk01"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已预留给其他患者")
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CONFLICT);
        verify(repo, never()).updateBedReservationStatus(any(), anyString(), anyString(), anyLong());
        verify(repo, never()).insertAdmission(any());
    }

    @Test
    @DisplayName("床位空闲却仍有生效预留：状态不一致，拒绝入住而不是挤掉预留患者")
    void admitRejectsInconsistentFreeBedWithReservation() {
        UUID bedId = UUID.randomUUID();
        when(repo.lockBed(bedId)).thenReturn(Optional.of(
                new Bed(bedId, wardId, "01", Bed.FREE, 2L)));
        when(repo.findActiveBedReservation(bedId)).thenReturn(Optional.of(
                new BedReservation(UUID.randomUUID(), bedId, wardId, UUID.randomUUID(), "P9",
                        "择期", "nurse01", Instant.now().plusSeconds(3600),
                        BedReservation.ACTIVE, Instant.now(), 0L)));

        assertThatThrownBy(() -> service.admit(new AdmitReq(patientId, wardId, bedId, "doctor01"),
                "clerk01"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("预留状态异常")
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CONFLICT);
        verify(repo, never()).occupyBed(any(), anyLong());
    }
}
