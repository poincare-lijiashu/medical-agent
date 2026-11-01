package com.medassist.clinical.service;

import com.medassist.clinical.domain.ClinicalModel.DiagnosisDef;
import com.medassist.clinical.domain.ClinicalModel.Visit;
import com.medassist.clinical.domain.ClinicalModel.VisitDiagnosis;
import com.medassist.clinical.repo.ClinicalRepository;
import com.medassist.clinical.web.ClinicalDtos.AddDiagnosisReq;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 诊断域规则：主诊断唯一、同编码不重复、结束就诊不可改、名称快照。
 */
class DiagnosisServiceTest {

    private ClinicalRepository repo;
    private DiagnosisService service;

    private final UUID visitId = UUID.randomUUID();
    private final UUID diagnosisId = UUID.randomUUID();
    private final String code = "J06.900";

    private final DiagnosisDef def = new DiagnosisDef(UUID.randomUUID(), code,
            "急性上呼吸道感染", "呼吸系统", true, 0L);

    @BeforeEach
    void setUp() {
        repo = mock(ClinicalRepository.class);
        service = new DiagnosisService(repo, JsonMapper.builder().build());
    }

    private Visit visit(String status) {
        return new Visit(visitId, "V1", UUID.randomUUID(), "P1", UUID.randomUUID(), "doctor01",
                null, "OUTPATIENT", status, LocalDate.now(), 0L);
    }

    private VisitDiagnosis diagnosis(boolean primary) {
        return new VisitDiagnosis(diagnosisId, visitId, code, "急性上呼吸道感染", primary, 1,
                "doctor01", Instant.now(), 0L);
    }

    @Test
    @DisplayName("下达主诊断：快照名称与医生，返回该就诊全部诊断")
    void addPrimarySnapshotsName() {
        when(repo.lockVisit(visitId)).thenReturn(Optional.of(visit(Visit.IN_TREATMENT)));
        when(repo.findDiagnosisByCode(code)).thenReturn(Optional.of(def));
        when(repo.existsPrimaryDiagnosis(visitId)).thenReturn(false);
        when(repo.nextDiagnosisSortNo(visitId)).thenReturn(1);
        when(repo.listVisitDiagnoses(visitId)).thenReturn(List.of(diagnosis(true)));

        List<VisitDiagnosis> result = service.add(visitId,
                new AddDiagnosisReq(code, true), "doctor01");

        ArgumentCaptor<VisitDiagnosis> captor = ArgumentCaptor.forClass(VisitDiagnosis.class);
        verify(repo).insertVisitDiagnosis(captor.capture());
        assertThat(captor.getValue().name()).isEqualTo("急性上呼吸道感染");
        assertThat(captor.getValue().primaryFlag()).isTrue();
        assertThat(captor.getValue().diagnosedBy()).isEqualTo("doctor01");
        assertThat(result).hasSize(1);
        verify(repo).logChange(eq("doctor01"), eq("DIAGNOSIS_ADD"), eq("VISIT"),
                eq(visitId.toString()), anyString());
    }

    @Test
    @DisplayName("主诊断唯一：已有主诊断时再下达主诊断 → 409")
    void addSecondPrimaryRejected() {
        when(repo.lockVisit(visitId)).thenReturn(Optional.of(visit(Visit.IN_TREATMENT)));
        when(repo.findDiagnosisByCode(code)).thenReturn(Optional.of(def));
        when(repo.existsPrimaryDiagnosis(visitId)).thenReturn(true);

        assertThatThrownBy(() -> service.add(visitId, new AddDiagnosisReq(code, true), "doctor01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CONFLICT);
        verify(repo, never()).insertVisitDiagnosis(any());
    }

    @Test
    @DisplayName("下达诊断：编码不存在或已停用 → 404")
    void addUnknownCode() {
        when(repo.lockVisit(visitId)).thenReturn(Optional.of(visit(Visit.IN_TREATMENT)));
        when(repo.findDiagnosisByCode("X99")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.add(visitId, new AddDiagnosisReq("X99", false), "doctor01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("下达诊断：同编码重复 → 唯一约束兜底为 409（不静默忽略）")
    void addDuplicateCode() {
        when(repo.lockVisit(visitId)).thenReturn(Optional.of(visit(Visit.IN_TREATMENT)));
        when(repo.findDiagnosisByCode(code)).thenReturn(Optional.of(def));
        when(repo.existsPrimaryDiagnosis(visitId)).thenReturn(false);
        when(repo.nextDiagnosisSortNo(visitId)).thenReturn(2);
        org.mockito.Mockito.doThrow(new DataIntegrityViolationException("ux_visit_diagnosis_code"))
                .when(repo).insertVisitDiagnosis(any());

        assertThatThrownBy(() -> service.add(visitId, new AddDiagnosisReq(code, false), "doctor01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    @Test
    @DisplayName("换主诊断：先清旧主诊断再设新主诊断")
    void setPrimarySwaps() {
        when(repo.lockVisit(visitId)).thenReturn(Optional.of(visit(Visit.IN_TREATMENT)));
        when(repo.findVisitDiagnosis(visitId, diagnosisId)).thenReturn(Optional.of(diagnosis(false)));
        when(repo.setPrimaryDiagnosis(visitId, diagnosisId)).thenReturn(true);
        when(repo.listVisitDiagnoses(visitId)).thenReturn(List.of(diagnosis(true)));

        service.setPrimary(visitId, diagnosisId, "doctor01");

        verify(repo).clearPrimaryDiagnosis(visitId);
        verify(repo).setPrimaryDiagnosis(visitId, diagnosisId);
        verify(repo).logChange(eq("doctor01"), eq("DIAGNOSIS_PRIMARY"), eq("VISIT"),
                eq(visitId.toString()), anyString());
    }

    @Test
    @DisplayName("换主诊断：已经是主诊断时幂等返回，不重复写库")
    void setPrimaryIdempotent() {
        when(repo.lockVisit(visitId)).thenReturn(Optional.of(visit(Visit.IN_TREATMENT)));
        when(repo.findVisitDiagnosis(visitId, diagnosisId)).thenReturn(Optional.of(diagnosis(true)));
        when(repo.listVisitDiagnoses(visitId)).thenReturn(List.of(diagnosis(true)));

        service.setPrimary(visitId, diagnosisId, "doctor01");

        verify(repo, never()).clearPrimaryDiagnosis(any());
        verify(repo, never()).setPrimaryDiagnosis(any(), any());
    }

    @Test
    @DisplayName("就诊已结束/已取消：不能改诊断")
    void rejectWhenVisitClosed() {
        when(repo.lockVisit(visitId)).thenReturn(Optional.of(visit(Visit.FINISHED)));

        assertThatThrownBy(() -> service.add(visitId, new AddDiagnosisReq(code, true), "doctor01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VISIT_STATUS_INVALID);
        verify(repo, never()).insertVisitDiagnosis(any());
    }

    @Test
    @DisplayName("删除诊断：记录动作（主诊断被删后需重新指定）")
    void removeDiagnosis() {
        when(repo.lockVisit(visitId)).thenReturn(Optional.of(visit(Visit.IN_TREATMENT)));
        when(repo.findVisitDiagnosis(visitId, diagnosisId)).thenReturn(Optional.of(diagnosis(true)));
        when(repo.deleteVisitDiagnosis(visitId, diagnosisId)).thenReturn(true);
        when(repo.listVisitDiagnoses(visitId)).thenReturn(List.of());

        service.remove(visitId, diagnosisId, "doctor01");

        verify(repo).deleteVisitDiagnosis(visitId, diagnosisId);
        verify(repo).logChange(eq("doctor01"), eq("DIAGNOSIS_REMOVE"), eq("VISIT"),
                eq(visitId.toString()), anyString());
        verify(repo, never()).clearPrimaryDiagnosis(any());
    }

    @Test
    @DisplayName("查询诊断字典：不传 limit 时默认 50 条")
    void catalogDefaultLimit() {
        when(repo.listDiagnosisDefs("肺", 50)).thenReturn(List.of(def));

        assertThat(service.catalog("肺", null)).hasSize(1);
        verify(repo).listDiagnosisDefs("肺", 50);
    }
}
