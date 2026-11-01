package com.medassist.clinical.service;

import com.medassist.clinical.domain.ClinicalModel.PreopVisit;
import com.medassist.clinical.domain.ClinicalModel.SurgeryCase;
import com.medassist.clinical.domain.ClinicalModel.SurgeryLevel;
import com.medassist.clinical.repo.ClinicalRepository;
import com.medassist.clinical.web.PreopVisitDtos.PreopVisitReq;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
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
 * 术前访视规则：只能术前写、签署需齐全、签署后冻结、三/四级强制、UNFIT 一票否决。
 */
class PreopVisitServiceTest {

    private ClinicalRepository repo;
    private PreopVisitService service;

    private final UUID caseId = UUID.randomUUID();
    private final UUID visitId = UUID.randomUUID();
    private final UUID visitRecordId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repo = mock(ClinicalRepository.class);
        service = new PreopVisitService(repo);
    }

    private SurgeryCase surgery(String status) {
        return new SurgeryCase(caseId, "OP20261101000001", visitId, null, UUID.randomUUID(),
                "47.0901", "阑尾切除术", Instant.now(), null, null, "doctor01", null,
                "GENERAL", "II", "II", status, null, "doctor01", 0L, Instant.now());
    }

    private SurgeryLevel level(int value, String status) {
        return new SurgeryLevel(UUID.randomUUID(), caseId, value, status, "doctor01",
                Instant.now(), null, null, null, "doctor01", 0L, Instant.now());
    }

    private PreopVisit record(String status, String conclusion, long version) {
        return new PreopVisit(visitRecordId, caseId, Instant.now(), "doctor01", "GENERAL",
                "II", "II", null, null, null, null, 8, 2, conclusion, null, status,
                PreopVisit.SIGNED.equals(status) ? "doctor01" : null,
                PreopVisit.SIGNED.equals(status) ? Instant.now() : null, "doctor01", version,
                Instant.now());
    }

    private PreopVisitReq req(String visitedBy, String asa, String mallampati,
                              Integer solid, Integer clear, String conclusion, Long version) {
        return new PreopVisitReq(Instant.now(), visitedBy, "GENERAL", asa, mallampati, null,
                null, null, null, solid, clear, conclusion, null, version);
    }

    // ---------------------------------------------------------------- 草稿

    @Test
    @DisplayName("保存访视：首次落草稿，留审计")
    void saveCreatesDraft() {
        when(repo.findSurgeryCase(caseId)).thenReturn(Optional.of(surgery(SurgeryCase.PLANNED)));
        when(repo.findPreopVisit(caseId)).thenReturn(Optional.empty());

        PreopVisit saved = service.save(caseId,
                req("doctor02", "II", "I", 8, 2, "FIT", null), "doctor02");

        ArgumentCaptor<PreopVisit> captor = ArgumentCaptor.forClass(PreopVisit.class);
        verify(repo).insertPreopVisit(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(PreopVisit.DRAFT);
        assertThat(captor.getValue().visitedBy()).isEqualTo("doctor02");
        assertThat(saved.asaGrade()).isEqualTo("II");
        verify(repo).logChange(eq("doctor02"), eq("PREOP_VISIT_SAVE"), eq("PREOP_VISIT"),
                anyString(), anyString());
    }

    @Test
    @DisplayName("保存访视：手术已开始 → 422")
    void saveRejectsStartedSurgery() {
        when(repo.findSurgeryCase(caseId))
                .thenReturn(Optional.of(surgery(SurgeryCase.IN_PROGRESS)));

        assertThatThrownBy(() -> service.save(caseId,
                req("doctor02", "II", "I", 8, 2, "FIT", null), "doctor02"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        verify(repo, never()).insertPreopVisit(any());
    }

    @Test
    @DisplayName("保存访视：已签署 → 409（冻结）")
    void saveRejectsSignedRecord() {
        when(repo.findSurgeryCase(caseId)).thenReturn(Optional.of(surgery(SurgeryCase.PLANNED)));
        when(repo.findPreopVisit(caseId)).thenReturn(Optional.of(
                record(PreopVisit.SIGNED, "FIT", 2L)));

        assertThatThrownBy(() -> service.save(caseId,
                req("doctor02", "II", "I", 8, 2, "FIT", 2L), "doctor02"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.CONFLICT));
    }

    @Test
    @DisplayName("更新访视：缺 version → 422；CAS 失败 → 409")
    void saveRequiresVersionThenCas() {
        when(repo.findSurgeryCase(caseId)).thenReturn(Optional.of(surgery(SurgeryCase.PLANNED)));
        when(repo.findPreopVisit(caseId)).thenReturn(Optional.of(
                record(PreopVisit.DRAFT, "FIT", 1L)));

        assertThatThrownBy(() -> service.save(caseId,
                req("doctor02", "II", "I", 8, 2, "FIT", null), "doctor02"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));

        when(repo.updatePreopVisitDraft(any(), eq(1L))).thenReturn(false);
        assertThatThrownBy(() -> service.save(caseId,
                req("doctor02", "II", "I", 8, 2, "FIT", 1L), "doctor02"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.CONFLICT));
    }

    // ---------------------------------------------------------------- 签署

    @Test
    @DisplayName("签署：记录不存在 → 404")
    void signRequiresRecord() {
        when(repo.findSurgeryCase(caseId)).thenReturn(Optional.of(surgery(SurgeryCase.PLANNED)));
        when(repo.findPreopVisit(caseId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.sign(caseId, 0L, "doctor02"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    @DisplayName("签署：ASA/Mallampati/禁食/结论缺失 → 422 并列出缺项")
    void signRejectsIncomplete() {
        when(repo.findSurgeryCase(caseId)).thenReturn(Optional.of(surgery(SurgeryCase.PLANNED)));
        when(repo.findPreopVisit(caseId)).thenReturn(Optional.of(new PreopVisit(visitRecordId,
                caseId, null, "doctor02", "GENERAL", null, null, null, null, null, null, null,
                null, null, null, PreopVisit.DRAFT, null, null, "doctor02", 0L, Instant.now())));

        assertThatThrownBy(() -> service.sign(caseId, 0L, "doctor02"))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(e.getMessage()).contains("ASA 分级");
                    assertThat(e.getMessage()).contains("Mallampati 分级");
                });
        verify(repo, never()).signPreopVisit(any(), anyString(), anyLong());
    }

    @Test
    @DisplayName("签署：已签署 → 409")
    void signAlreadySigned() {
        when(repo.findSurgeryCase(caseId)).thenReturn(Optional.of(surgery(SurgeryCase.PLANNED)));
        when(repo.findPreopVisit(caseId)).thenReturn(Optional.of(
                record(PreopVisit.SIGNED, "FIT", 2L)));

        assertThatThrownBy(() -> service.sign(caseId, 2L, "doctor02"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.CONFLICT));
    }

    @Test
    @DisplayName("签署：CAS 失败 → 409")
    void signCasConflict() {
        when(repo.findSurgeryCase(caseId)).thenReturn(Optional.of(surgery(SurgeryCase.PLANNED)));
        when(repo.findPreopVisit(caseId)).thenReturn(Optional.of(
                record(PreopVisit.DRAFT, "FIT", 1L)));
        when(repo.signPreopVisit(visitRecordId, "doctor02", 1L)).thenReturn(false);

        assertThatThrownBy(() -> service.sign(caseId, 1L, "doctor02"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.CONFLICT));
    }

    @Test
    @DisplayName("签署：齐全 → SIGNED 并留审计")
    void signOk() {
        when(repo.findSurgeryCase(caseId)).thenReturn(Optional.of(surgery(SurgeryCase.PLANNED)));
        when(repo.findPreopVisit(caseId))
                .thenReturn(Optional.of(record(PreopVisit.DRAFT, "FIT", 1L)))
                .thenReturn(Optional.of(record(PreopVisit.SIGNED, "FIT", 2L)));
        when(repo.signPreopVisit(visitRecordId, "doctor02", 1L)).thenReturn(true);

        PreopVisit saved = service.sign(caseId, 1L, "doctor02");

        assertThat(saved.status()).isEqualTo(PreopVisit.SIGNED);
        verify(repo).logChange(eq("doctor02"), eq("PREOP_VISIT_SIGN"), eq("PREOP_VISIT"),
                anyString(), anyString());
    }

    // ---------------------------------------------------------------- 开台门槛

    @Test
    @DisplayName("开台门槛：一级手术无访视单 → 放行")
    void gateAllowsLowLevelWithoutRecord() {
        when(repo.findSurgeryLevel(caseId)).thenReturn(Optional.of(
                level(1, SurgeryLevel.NOT_REQUIRED)));
        when(repo.findPreopVisit(caseId)).thenReturn(Optional.empty());

        assertThatCode(() -> service.requireStartCleared(caseId)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("开台门槛：三级手术无访视单 → 422")
    void gateRejectsHighLevelWithoutRecord() {
        when(repo.findSurgeryLevel(caseId)).thenReturn(Optional.of(
                level(3, SurgeryLevel.APPROVED)));
        when(repo.findPreopVisit(caseId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.requireStartCleared(caseId))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(e.getMessage()).contains("术前访视");
                });
    }

    @Test
    @DisplayName("开台门槛：三级手术访视未签署 → 422；已签署 FIT → 放行")
    void gateRequiresSignedForHighLevel() {
        when(repo.findSurgeryLevel(caseId)).thenReturn(Optional.of(
                level(4, SurgeryLevel.APPROVED)));
        when(repo.findPreopVisit(caseId))
                .thenReturn(Optional.of(record(PreopVisit.DRAFT, "FIT", 1L)))
                .thenReturn(Optional.of(record(PreopVisit.SIGNED, "FIT", 2L)));

        assertThatThrownBy(() -> service.requireStartCleared(caseId))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getMessage()).contains("尚未签署"));
        assertThatCode(() -> service.requireStartCleared(caseId)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("开台门槛：任何级别签署为 UNFIT → 422（一票否决）")
    void gateRejectsUnfit() {
        when(repo.findSurgeryLevel(caseId)).thenReturn(Optional.of(
                level(1, SurgeryLevel.NOT_REQUIRED)));
        when(repo.findPreopVisit(caseId)).thenReturn(Optional.of(
                record(PreopVisit.SIGNED, "UNFIT", 2L)));

        assertThatThrownBy(() -> service.requireStartCleared(caseId))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(e.getMessage()).contains("不宜手术");
                });
    }

    @Test
    @DisplayName("查询访视：未填写返回 null；手术不存在 → 404")
    void getReturnsNullWhenAbsent() {
        when(repo.findSurgeryCase(caseId)).thenReturn(Optional.of(surgery(SurgeryCase.PLANNED)));
        when(repo.findPreopVisit(caseId)).thenReturn(Optional.empty());

        assertThat(service.get(caseId)).isNull();

        when(repo.findSurgeryCase(caseId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.get(caseId))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }
}
