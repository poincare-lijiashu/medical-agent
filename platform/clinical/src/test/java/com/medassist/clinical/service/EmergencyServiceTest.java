package com.medassist.clinical.service;

import com.medassist.clinical.client.PatientClient;
import com.medassist.clinical.domain.ClinicalModel.Dept;
import com.medassist.clinical.domain.ClinicalModel.EmergencyBed;
import com.medassist.clinical.domain.ClinicalModel.EmergencyBoardRow;
import com.medassist.clinical.domain.ClinicalModel.EmergencyObservation;
import com.medassist.clinical.domain.ClinicalModel.EmergencyTriage;
import com.medassist.clinical.domain.ClinicalModel.Visit;
import com.medassist.clinical.repo.ClinicalRepository;
import com.medassist.clinical.web.EmergencyDtos.CreateTriageReq;
import com.medassist.clinical.web.EmergencyDtos.ObserveReq;
import com.medassist.clinical.web.EmergencyDtos.OutcomeReq;
import com.medassist.clinical.web.EmergencyDtos.RegisterTriageReq;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.time.BusinessTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
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
 * 急诊分诊与留观：分级/绿色通道、挂号状态机、床位占用、转归与看板。
 */
class EmergencyServiceTest {

    private ClinicalRepository repo;
    private PatientClient patientClient;
    private RegistrationService registrationService;
    private EmergencyService service;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final UUID deptId = UUID.randomUUID();
    private final UUID patientId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repo = mock(ClinicalRepository.class);
        patientClient = mock(PatientClient.class);
        registrationService = mock(RegistrationService.class);
        service = new EmergencyService(repo, patientClient, registrationService, objectMapper);
    }

    private static Dept dept(UUID id) {
        return new Dept(id, "DEPT-EMERGENCY", "急诊科", "BOTH", true);
    }

    private EmergencyTriage triage(String status, int level, UUID visitId, long version) {
        return new EmergencyTriage(UUID.randomUUID(), "ET20261104000001", patientId, "MR0001",
                "张三", deptId, "急诊科", level, "胸痛 2 小时", new BigDecimal("36.8"),
                88, 20, 120, 80, 98, level <= 2, status, visitId,
                visitId == null ? null : "V20261104000001", "nurse01", Instant.now(),
                Instant.now(), null, version, Instant.now(), Instant.now());
    }

    private EmergencyBed bed(String status, long version) {
        return new EmergencyBed(UUID.randomUUID(), "E-03", deptId, "急诊科", "OBSERVE",
                status, null, true, version);
    }

    private EmergencyObservation observation(String status, long version) {
        UUID bedId = UUID.randomUUID();
        return new EmergencyObservation(UUID.randomUUID(), "EO20261104000001", UUID.randomUUID(),
                "ET20261104000001", UUID.randomUUID(), "V20261104000001", patientId, "MR0001",
                "张三", deptId, "急诊科", bedId, "E-03", status, Instant.now(),
                null, null, null, "nurse01", null, version, Instant.now());
    }

    private CreateTriageReq createReq(int level) {
        return new CreateTriageReq(patientId, deptId, level, "胸痛 2 小时",
                new BigDecimal("36.8"), 88, 20, 120, 80, 98, null, null);
    }

    @Test
    @DisplayName("分诊登记：1–2 级自动绿色通道，3 级默认普通")
    void createsTriageWithAutomaticGreenChannel() {
        when(repo.findDept(deptId)).thenReturn(Optional.of(dept(deptId)));
        when(patientClient.requireActive(patientId)).thenReturn(
                new PatientClient.PatientRef(patientId, "MR0001", "张三"));
        when(repo.nextEmergencyTriageNo()).thenReturn("ET20261104000001");

        var critical = service.createTriage(createReq(2), "nurse01");
        assertThat(critical.greenChannel()).isTrue();
        assertThat(critical.status()).isEqualTo(EmergencyTriage.WAITING);

        var normal = service.createTriage(createReq(3), "nurse01");
        assertThat(normal.greenChannel()).isFalse();

        verify(repo, org.mockito.Mockito.times(2)).insertEmergencyTriage(any());
    }

    @Test
    @DisplayName("分诊登记：分级越界与生命体征异常 → 422")
    void rejectsInvalidLevelAndVitals() {
        assertThatThrownBy(() -> service.createTriage(createReq(5), "nurse01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.EMERGENCY_TRIAGE_LEVEL_INVALID);
        verify(patientClient, never()).requireActive(any());

        CreateTriageReq badVitals = new CreateTriageReq(patientId, deptId, 3, "头晕",
                new BigDecimal("36.8"), 88, 20, 80, 90, 98, null, null);
        assertThatThrownBy(() -> service.createTriage(badVitals, "nurse01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.EMERGENCY_TRIAGE_VITALS_INVALID);
    }

    @Test
    @DisplayName("挂号：生成 EMERGENCY 就诊并绑定分诊；已挂号幂等")
    void registersAndBindsVisit() {
        EmergencyTriage waiting = triage(EmergencyTriage.WAITING, 2, null, 0);
        Visit visit = new Visit(UUID.randomUUID(), "V20261104000001", patientId, "MR0001",
                deptId, "doctor01", null, "EMERGENCY", Visit.REGISTERED,
                BusinessTime.today(), 0);
        EmergencyTriage registered = triage(EmergencyTriage.REGISTERED, 2, visit.id(), 1);
        // 第一次取到 WAITING，CAS 成功后第二次取到 REGISTERED（幂等返回）
        when(repo.lockEmergencyTriage(waiting.id()))
                .thenReturn(Optional.of(waiting), Optional.of(registered));
        when(registrationService.register(any(), eq("registrar01"))).thenReturn(visit);
        when(repo.registerEmergencyTriage(waiting.id(), visit.id(), 0)).thenReturn(true);
        when(repo.findEmergencyTriage(waiting.id())).thenReturn(Optional.of(registered));

        var view = service.register(waiting.id(), new RegisterTriageReq("doctor01"),
                "registrar01");
        assertThat(view.status()).isEqualTo(EmergencyTriage.REGISTERED);
        assertThat(view.visitId()).isEqualTo(visit.id());

        var again = service.register(waiting.id(), new RegisterTriageReq("doctor01"),
                "registrar01");
        assertThat(again.status()).isEqualTo(EmergencyTriage.REGISTERED);
        verify(registrationService, org.mockito.Mockito.times(1)).register(any(), anyString());
    }

    @Test
    @DisplayName("离院：必须写原因；状态非法 409；版本冲突 409")
    void leaveStateMachine() {
        EmergencyTriage waiting = triage(EmergencyTriage.WAITING, 3, null, 0);
        when(repo.lockEmergencyTriage(waiting.id())).thenReturn(Optional.of(waiting));

        assertThatThrownBy(() -> service.leave(waiting.id(), " ", "nurse01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.EMERGENCY_LEAVE_REASON_REQUIRED);

        when(repo.leaveEmergencyTriage(waiting.id(), "自行离院", 0)).thenReturn(false);
        assertThatThrownBy(() -> service.leave(waiting.id(), "自行离院", "nurse01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CONFLICT);

        EmergencyTriage registered = triage(EmergencyTriage.REGISTERED, 3, UUID.randomUUID(), 1);
        when(repo.lockEmergencyTriage(registered.id())).thenReturn(Optional.of(registered));
        assertThatThrownBy(() -> service.leave(registered.id(), "自行离院", "nurse01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.EMERGENCY_TRIAGE_STATUS_INVALID);
    }

    @Test
    @DisplayName("留观：未挂号拒绝；自动选床占床并推进分诊")
    void observeRequiresRegistrationAndOccupiesBed() {
        EmergencyTriage waiting = triage(EmergencyTriage.WAITING, 2, null, 0);
        when(repo.lockEmergencyTriage(waiting.id())).thenReturn(Optional.of(waiting));
        assertThatThrownBy(() -> service.observe(waiting.id(), null, "nurse01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.EMERGENCY_TRIAGE_NOT_REGISTERED);

        EmergencyTriage registered = triage(EmergencyTriage.REGISTERED, 2, UUID.randomUUID(), 1);
        when(repo.lockEmergencyTriage(registered.id())).thenReturn(Optional.of(registered));
        EmergencyBed bed = bed(EmergencyBed.FREE, 0);
        when(repo.findFirstFreeEmergencyBed(deptId)).thenReturn(Optional.of(bed));
        when(repo.lockEmergencyBed(bed.id())).thenReturn(Optional.of(bed));
        when(repo.nextEmergencyObservationNo()).thenReturn("EO20261104000001");
        when(repo.occupyEmergencyBed(eq(bed.id()), any(UUID.class), eq(0L))).thenReturn(true);
        when(repo.observeEmergencyTriage(registered.id(), 1)).thenReturn(true);
        EmergencyObservation active = observation(EmergencyObservation.OBSERVING, 0);
        when(repo.findEmergencyObservation(any())).thenReturn(Optional.of(active));

        var view = service.observe(registered.id(), new ObserveReq(null), "nurse01");
        assertThat(view.status()).isEqualTo(EmergencyObservation.OBSERVING);
        verify(repo).observeEmergencyTriage(registered.id(), 1);
    }

    @Test
    @DisplayName("留观：床位被抢 → 409")
    void observeBedConflict() {
        EmergencyTriage registered = triage(EmergencyTriage.REGISTERED, 2, UUID.randomUUID(), 1);
        when(repo.lockEmergencyTriage(registered.id())).thenReturn(Optional.of(registered));
        EmergencyBed bed = bed(EmergencyBed.FREE, 0);
        when(repo.lockEmergencyBed(bed.id())).thenReturn(Optional.of(bed));
        when(repo.nextEmergencyObservationNo()).thenReturn("EO20261104000001");
        when(repo.occupyEmergencyBed(eq(bed.id()), any(UUID.class), eq(0L))).thenReturn(false);

        assertThatThrownBy(() -> service.observe(registered.id(), new ObserveReq(bed.id()),
                "nurse01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.EMERGENCY_BED_UNAVAILABLE);
        verify(repo, never()).observeEmergencyTriage(any(), anyLong());
    }

    @Test
    @DisplayName("转归：非法值/缺说明 422；成功后释放床位并闭合分诊")
    void completesObservationAndReleasesBed() {
        EmergencyObservation active = observation(EmergencyObservation.OBSERVING, 0);
        when(repo.lockEmergencyObservation(active.id())).thenReturn(Optional.of(active));
        EmergencyTriage triage = triage(EmergencyTriage.OBSERVING, 2, active.visitId(), 2);
        when(repo.lockEmergencyTriage(active.triageId())).thenReturn(Optional.of(triage));

        assertThatThrownBy(() -> service.completeObservation(active.id(),
                new OutcomeReq("UNKNOWN", null), "doctor01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.EMERGENCY_OUTCOME_INVALID);

        assertThatThrownBy(() -> service.completeObservation(active.id(),
                new OutcomeReq("LEFT", null), "doctor01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.EMERGENCY_OUTCOME_NOTE_REQUIRED);

        when(repo.completeEmergencyObservation(active.id(), "DISCHARGED", "症状缓解", "doctor01", 0))
                .thenReturn(true);
        when(repo.releaseEmergencyBed(active.bedId(), active.id())).thenReturn(true);
        when(repo.closeEmergencyTriage(triage.id(), 2)).thenReturn(true);
        EmergencyObservation closed = observation(EmergencyObservation.CLOSED, 1);
        when(repo.findEmergencyObservation(active.id())).thenReturn(Optional.of(closed));

        var view = service.completeObservation(active.id(),
                new OutcomeReq("DISCHARGED", "症状缓解"), "doctor01");
        assertThat(view.status()).isEqualTo(EmergencyObservation.CLOSED);
        verify(repo).releaseEmergencyBed(active.bedId(), active.id());
        verify(repo).closeEmergencyTriage(triage.id(), 2);
    }

    @Test
    @DisplayName("看板：区间倒置 422；正常映射计数")
    void boardMapsCounters() {
        LocalDate today = BusinessTime.today();
        assertThatThrownBy(() -> service.board(deptId, today, today.minusDays(1)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);

        when(repo.emergencyBoard(eq(deptId), any(), any())).thenReturn(
                new EmergencyBoardRow(3, 1, 2, 3, 4, 5, 2, 6, 1, 15, 4, 2));
        var view = service.board(deptId, today, today);
        assertThat(view.waiting()).isEqualTo(3);
        assertThat(view.level1()).isEqualTo(1);
        assertThat(view.freeBeds()).isEqualTo(4);
        assertThat(view.occupiedBeds()).isEqualTo(2);
    }

    @Test
    @DisplayName("列表：非法状态 422；查询区间倒置 422")
    void listValidatesStatusAndRange() {
        LocalDate today = BusinessTime.today();
        assertThatThrownBy(() -> service.listTriages(deptId, "UNKNOWN", today, today))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> service.listObservations(deptId, null, today, today.minusDays(1)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);

        when(repo.listEmergencyTriages(eq(deptId), eq("WAITING"), any(), any(), eq(200)))
                .thenReturn(List.of(triage(EmergencyTriage.WAITING, 2, null, 0)));
        assertThat(service.listTriages(deptId, "waiting", today, today)).hasSize(1);
    }
}
