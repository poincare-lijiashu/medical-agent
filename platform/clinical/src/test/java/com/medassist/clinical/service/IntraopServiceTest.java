package com.medassist.clinical.service;

import com.medassist.clinical.domain.ClinicalModel.AnesthesiaRecord;
import com.medassist.clinical.domain.ClinicalModel.IntraopNursing;
import com.medassist.clinical.domain.ClinicalModel.SurgeryCase;
import com.medassist.clinical.repo.ClinicalRepository;
import com.medassist.clinical.web.TheatreDtos.AnesthesiaReq;
import com.medassist.clinical.web.TheatreDtos.NursingReq;
import com.medassist.clinical.web.TheatreDtos.VitalReq;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 术中记录规则：只在术中/术后可写、签署需有内容、签署后冻结。 */
class IntraopServiceTest {

    private ClinicalRepository repo;
    private IntraopService service;

    private final UUID caseId = UUID.randomUUID();
    private final UUID recordId = UUID.randomUUID();
    private final Instant anesthesiaStart = Instant.parse("2026-11-02T01:00:00Z");
    private final Instant anesthesiaEnd = Instant.parse("2026-11-02T03:00:00Z");

    @BeforeEach
    void setUp() {
        repo = mock(ClinicalRepository.class);
        service = new IntraopService(repo);
    }

    private SurgeryCase surgery(String status) {
        return new SurgeryCase(caseId, "OP20261102000001", UUID.randomUUID(), null,
                UUID.randomUUID(), "47.0901", "阑尾切除术", anesthesiaStart, null, null,
                "doctor01", null, "GENERAL", "II", "II", status, null, "doctor01", 0L,
                Instant.now());
    }

    private AnesthesiaRecord anesthesia(String status, Instant startedAt, Instant endedAt,
                                        long version) {
        return new AnesthesiaRecord(recordId, caseId, "GENERAL", "II", "doctor02", "ETT",
                startedAt, endedAt, null, status,
                AnesthesiaRecord.SIGNED.equals(status) ? "doctor02" : null,
                AnesthesiaRecord.SIGNED.equals(status) ? Instant.now() : null,
                "doctor02", version, Instant.now());
    }

    private AnesthesiaReq anesthesiaReq(Long version) {
        return new AnesthesiaReq("general", "ii", "doctor02", "ett", anesthesiaStart,
                anesthesiaEnd, null, version);
    }

    // ---------------------------------------------------------------- 麻醉

    @Test
    @DisplayName("写麻醉记录：手术还在计划态 → 422（还没上台谈不上麻醉记录）")
    void saveAnesthesiaRejectsPlannedCase() {
        when(repo.findSurgeryCase(caseId)).thenReturn(Optional.of(surgery(SurgeryCase.PLANNED)));

        assertThatThrownBy(() -> service.saveAnesthesia(caseId, anesthesiaReq(null), "doctor02"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        verify(repo, never()).insertAnesthesia(any());
    }

    @Test
    @DisplayName("写麻醉记录：首次保存落 DRAFT，字段归一化")
    void saveAnesthesiaCreatesDraft() {
        when(repo.findSurgeryCase(caseId)).thenReturn(Optional.of(surgery(SurgeryCase.IN_PROGRESS)));
        when(repo.findAnesthesiaByCase(caseId)).thenReturn(Optional.empty());

        service.saveAnesthesia(caseId, anesthesiaReq(null), "doctor02");

        verify(repo).insertAnesthesia(any());
        verify(repo).logChange(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("写麻醉记录：已签署 → 409（不能改）")
    void saveAnesthesiaRejectsSigned() {
        when(repo.findSurgeryCase(caseId)).thenReturn(Optional.of(surgery(SurgeryCase.COMPLETED)));
        when(repo.findAnesthesiaByCase(caseId))
                .thenReturn(Optional.of(anesthesia(AnesthesiaRecord.SIGNED, anesthesiaStart,
                        anesthesiaEnd, 2L)));

        assertThatThrownBy(() -> service.saveAnesthesia(caseId, anesthesiaReq(2L), "doctor02"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.CONFLICT));
        verify(repo, never()).updateAnesthesiaDraft(any(), anyLong());
    }

    @Test
    @DisplayName("写麻醉记录：更新已存在的草稿但不带 version → 422")
    void saveAnesthesiaRequiresVersionOnUpdate() {
        when(repo.findSurgeryCase(caseId)).thenReturn(Optional.of(surgery(SurgeryCase.IN_PROGRESS)));
        when(repo.findAnesthesiaByCase(caseId))
                .thenReturn(Optional.of(anesthesia(AnesthesiaRecord.DRAFT, anesthesiaStart,
                        anesthesiaEnd, 1L)));

        assertThatThrownBy(() -> service.saveAnesthesia(caseId, anesthesiaReq(null), "doctor02"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @Test
    @DisplayName("签署麻醉记录：缺少起止时间 → 422")
    void signAnesthesiaRequiresTimes() {
        when(repo.findSurgeryCase(caseId)).thenReturn(Optional.of(surgery(SurgeryCase.IN_PROGRESS)));
        when(repo.findAnesthesiaByCase(caseId))
                .thenReturn(Optional.of(anesthesia(AnesthesiaRecord.DRAFT, null, null, 1L)));

        assertThatThrownBy(() -> service.signAnesthesia(caseId, 1L, "doctor02"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @Test
    @DisplayName("签署麻醉记录：一条生命体征都没有 → 422")
    void signAnesthesiaRequiresVitals() {
        when(repo.findSurgeryCase(caseId)).thenReturn(Optional.of(surgery(SurgeryCase.IN_PROGRESS)));
        when(repo.findAnesthesiaByCase(caseId))
                .thenReturn(Optional.of(anesthesia(AnesthesiaRecord.DRAFT, anesthesiaStart,
                        anesthesiaEnd, 1L)));
        when(repo.countVitals(recordId)).thenReturn(0L);

        assertThatThrownBy(() -> service.signAnesthesia(caseId, 1L, "doctor02"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        verify(repo, never()).signAnesthesia(any(), anyString(), anyLong());
    }

    @Test
    @DisplayName("签署麻醉记录：内容齐全 → 签名成功")
    void signAnesthesiaOk() {
        when(repo.findSurgeryCase(caseId)).thenReturn(Optional.of(surgery(SurgeryCase.COMPLETED)));
        when(repo.findAnesthesiaByCase(caseId))
                .thenReturn(Optional.of(anesthesia(AnesthesiaRecord.DRAFT, anesthesiaStart,
                                anesthesiaEnd, 1L)),
                        Optional.of(anesthesia(AnesthesiaRecord.SIGNED, anesthesiaStart,
                                anesthesiaEnd, 2L)));
        when(repo.countVitals(recordId)).thenReturn(2L);
        when(repo.signAnesthesia(recordId, "doctor02", 1L)).thenReturn(true);
        when(repo.listVitals(recordId)).thenReturn(List.of());

        var view = service.signAnesthesia(caseId, 1L, "doctor02");

        assertThat(view.record().status()).isEqualTo(AnesthesiaRecord.SIGNED);
        verify(repo).signAnesthesia(recordId, "doctor02", 1L);
    }

    // ---------------------------------------------------------------- 生命体征

    @Test
    @DisplayName("追加生命体征：全字段为空 → 422（空记录没有临床意义）")
    void addVitalRejectsEmpty() {
        when(repo.findSurgeryCase(caseId)).thenReturn(Optional.of(surgery(SurgeryCase.IN_PROGRESS)));
        when(repo.findAnesthesiaByCase(caseId))
                .thenReturn(Optional.of(anesthesia(AnesthesiaRecord.DRAFT, anesthesiaStart,
                        anesthesiaEnd, 0L)));

        assertThatThrownBy(() -> service.addVital(caseId,
                new VitalReq(anesthesiaStart.plusSeconds(600), null, null, null, null, null,
                        null, null), "doctor02"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @Test
    @DisplayName("追加生命体征：时间早于麻醉开始 → 422")
    void addVitalRejectsBeforeStart() {
        when(repo.findSurgeryCase(caseId)).thenReturn(Optional.of(surgery(SurgeryCase.IN_PROGRESS)));
        when(repo.findAnesthesiaByCase(caseId))
                .thenReturn(Optional.of(anesthesia(AnesthesiaRecord.DRAFT, anesthesiaStart,
                        anesthesiaEnd, 0L)));

        assertThatThrownBy(() -> service.addVital(caseId,
                new VitalReq(anesthesiaStart.minusSeconds(600), 80, 120, 70, 98, null, null,
                        null), "doctor02"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @Test
    @DisplayName("追加生命体征：记录已签署 → 409")
    void addVitalRejectsSignedRecord() {
        when(repo.findSurgeryCase(caseId)).thenReturn(Optional.of(surgery(SurgeryCase.COMPLETED)));
        when(repo.findAnesthesiaByCase(caseId))
                .thenReturn(Optional.of(anesthesia(AnesthesiaRecord.SIGNED, anesthesiaStart,
                        anesthesiaEnd, 2L)));

        assertThatThrownBy(() -> service.addVital(caseId,
                new VitalReq(anesthesiaStart.plusSeconds(600), 80, 120, 70, 98, null, null,
                        null), "doctor02"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.CONFLICT));
        verify(repo, never()).insertVital(any(), anyString());
    }

    // ---------------------------------------------------------------- 术中护理

    @Test
    @DisplayName("写术中护理：首次保存落 DRAFT")
    void saveNursingCreatesDraft() {
        when(repo.findSurgeryCase(caseId)).thenReturn(Optional.of(surgery(SurgeryCase.IN_PROGRESS)));
        when(repo.findNursingByCase(caseId)).thenReturn(Optional.empty());

        IntraopNursing created = service.saveNursing(caseId,
                new NursingReq("nurse01", "nurse02", "supine", "碘伏", null, null, 2, null, null),
                "nurse01");

        assertThat(created.status()).isEqualTo(IntraopNursing.DRAFT);
        assertThat(created.patientPosition()).isEqualTo("SUPINE");
        assertThat(created.specimenCount()).isEqualTo(2);
        verify(repo).insertNursing(any());
    }

    @Test
    @DisplayName("签署术中护理：缺少洗手/巡回护士 → 422")
    void signNursingRequiresNurses() {
        when(repo.findSurgeryCase(caseId)).thenReturn(Optional.of(surgery(SurgeryCase.COMPLETED)));
        when(repo.findNursingByCase(caseId)).thenReturn(Optional.of(new IntraopNursing(
                UUID.randomUUID(), caseId, "nurse01", null, "SUPINE", null, null, null, 0,
                null, IntraopNursing.DRAFT, null, null, "nurse01", 0L, Instant.now())));

        assertThatThrownBy(() -> service.signNursing(caseId, 0L, "nurse01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        verify(repo, never()).signNursing(any(), anyString(), anyLong());
    }

    @Test
    @DisplayName("签署术中护理：内容齐全 → 签名成功")
    void signNursingOk() {
        when(repo.findSurgeryCase(caseId)).thenReturn(Optional.of(surgery(SurgeryCase.COMPLETED)));
        when(repo.findNursingByCase(caseId))
                .thenReturn(Optional.of(new IntraopNursing(UUID.randomUUID(), caseId, "nurse01",
                        "nurse02", "SUPINE", null, null, null, 0, null, IntraopNursing.DRAFT,
                        null, null, "nurse01", 1L, Instant.now())))
                .thenReturn(Optional.of(new IntraopNursing(UUID.randomUUID(), caseId, "nurse01",
                        "nurse02", "SUPINE", null, null, null, 0, null, IntraopNursing.SIGNED,
                        "nurse01", Instant.now(), "nurse01", 2L, Instant.now())));
        when(repo.signNursing(any(), anyString(), anyLong())).thenReturn(true);

        IntraopNursing signed = service.signNursing(caseId, 1L, "nurse01");

        assertThat(signed.status()).isEqualTo(IntraopNursing.SIGNED);
    }

    @Test
    @DisplayName("查询麻醉记录：不存在 → 404")
    void getAnesthesiaNotFound() {
        when(repo.findAnesthesiaByCase(caseId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getAnesthesia(caseId))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    @DisplayName("护理记录的标本数默认 0（未传不报错）")
    void nursingSpecimenDefaultsToZero() {
        when(repo.findSurgeryCase(caseId)).thenReturn(Optional.of(surgery(SurgeryCase.IN_PROGRESS)));
        when(repo.findNursingByCase(caseId)).thenReturn(Optional.empty());

        IntraopNursing created = service.saveNursing(caseId,
                new NursingReq("nurse01", "nurse02", null, null, null, null, null, null, null),
                "nurse01");

        assertThat(created.specimenCount()).isZero();
        assertThat(created.createdAt()).isNotNull();
    }
}
