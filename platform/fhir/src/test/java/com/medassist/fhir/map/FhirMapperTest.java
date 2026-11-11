package com.medassist.fhir.map;

import com.medassist.fhir.repo.FhirRows.CrosswalkRow;
import com.medassist.fhir.repo.FhirRows.EncounterRow;
import com.medassist.fhir.repo.FhirRows.MedicationRequestRow;
import com.medassist.fhir.repo.FhirRows.ObservationRow;
import com.medassist.fhir.repo.FhirRows.PatientRow;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.MedicationRequest;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Patient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 资源映射单测（无数据库、无 Spring 上下文）。
 *
 * <p>重点覆盖三类最容易出事故的规则：
 * PHI 脱敏口径、状态机到 FHIR 枚举的映射、以及"数值解析失败不能硬塞数字"。
 */
class FhirMapperTest {

    private static final UUID PATIENT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-11-11T09:30:00+08:00");

    private final FhirMapper mapper = new FhirMapper();

    @Test
    @DisplayName("患者：有 PHI 权限时返回明文、跨系统标识与结构化姓名")
    void patientWithPhi() {
        Patient patient = mapper.toPatient(patientRow("ACTIVE", null),
                List.of(new CrosswalkRow(PATIENT_ID, "HIS", "H0001")), true);

        assertThat(patient.getId()).isEqualTo(PATIENT_ID.toString());
        assertThat(patient.getIdentifier()).hasSize(2);
        assertThat(patient.getIdentifierFirstRep().getSystem()).isEqualTo(FhirMapper.PATIENT_NO_SYSTEM);
        assertThat(patient.getIdentifierFirstRep().getValue()).isEqualTo("P20261111001");
        assertThat(patient.getIdentifier().get(1).getSystem())
                .isEqualTo(FhirMapper.CROSSWALK_SYSTEM_PREFIX + "HIS");
        assertThat(patient.getNameFirstRep().getText()).isEqualTo("张三");
        assertThat(patient.getNameFirstRep().getFamily()).isEqualTo("张");
        assertThat(patient.getNameFirstRep().getGivenAsSingleString()).isEqualTo("三");
        assertThat(patient.getBirthDateElement().getValueAsString()).isEqualTo("1980-01-02");
        assertThat(patient.getGender().toCode()).isEqualTo("male");
        assertThat(patient.getActive()).isTrue();
        assertThat(patient.getMeta().getVersionId()).isEqualTo("3");
        // Instant 在序列化时统一归一到 UTC（FHIR instant 的时区规范）
        assertThat(patient.getMeta().getLastUpdated().toInstant()).isEqualTo(NOW.toInstant());
    }

    @Test
    @DisplayName("患者：无 PHI 权限时姓名/患者号掩码、出生日期只到年、跨系统标识不下发")
    void patientWithoutPhi() {
        Patient patient = mapper.toPatient(patientRow("ACTIVE", null),
                List.of(new CrosswalkRow(PATIENT_ID, "HIS", "H0001")), false);

        assertThat(patient.getIdentifier()).hasSize(1);
        assertThat(patient.getIdentifierFirstRep().getValue()).isNotEqualTo("P20261111001");
        assertThat(patient.getNameFirstRep().getText()).isEqualTo("张*");
        assertThat(patient.getNameFirstRep().getFamily()).isNull();
        assertThat(patient.getBirthDateElement().getValueAsString()).isEqualTo("1980");
    }

    @Test
    @DisplayName("患者：已死亡标记 deceased，已合并返回 replaced-by 链接且 active=false")
    void patientDeceasedAndMerged() {
        UUID target = UUID.fromString("22222222-2222-2222-2222-222222222222");

        Patient deceased = mapper.toPatient(patientRow("DECEASED", null), List.of(), true);
        assertThat(deceased.getActive()).isFalse();
        assertThat(deceased.getDeceasedBooleanType().getValue()).isTrue();

        Patient merged = mapper.toPatient(patientRow("MERGED", target), List.of(), true);
        assertThat(merged.getActive()).isFalse();
        assertThat(merged.getLinkFirstRep().getType().toCode()).isEqualTo("replaced-by");
        assertThat(merged.getLinkFirstRep().getOther().getReference())
                .isEqualTo("Patient/" + target);
    }

    @Test
    @DisplayName("就诊：住院完成映射为 IMP/finished，期间取入院与出院时间")
    void encounterInpatient() {
        EncounterRow row = new EncounterRow(UUID.randomUUID(), "A20261111001", "INPATIENT",
                "finished", LocalDate.parse("2026-11-01"),
                NOW.minusDays(10), NOW.minusDays(10), NOW.minusDays(2),
                UUID.randomUUID(), PATIENT_ID, "DEPT-IM", "内科病区", "doctor01",
                UUID.randomUUID(), "A20261111001", NOW.minusDays(10), NOW.minusDays(2),
                "WARD-1", "一病区", "12");

        Encounter encounter = mapper.toEncounter(row);

        assertThat(encounter.getStatus().toCode()).isEqualTo("finished");
        assertThat(encounter.getClass_().getCode()).isEqualTo("IMP");
        assertThat(encounter.getIdentifier()).hasSize(2);
        assertThat(encounter.getTypeFirstRep().getText()).isEqualTo("住院");
        assertThat(encounter.getSubject().getReference()).isEqualTo("Patient/" + PATIENT_ID);
        assertThat(encounter.getPeriod().getStartElement().getValueAsString())
                .isEqualTo("2026-11-01T09:30:00+08:00");
        assertThat(encounter.getLocationFirstRep().getStatus().toCode()).isEqualTo("active");
        assertThat(encounter.getLocationFirstRep().getLocation().getDisplay())
                .isEqualTo("内科病区 / 一病区 / 12床");
    }

    @Test
    @DisplayName("观察：数值+单位解析为 Quantity，参考范围解析为 low/high，危急程度映射 interpretation")
    void observationNumeric() {
        ObservationRow row = new ObservationRow(UUID.randomUUID(), PATIENT_ID, UUID.randomUUID(),
                "血清钾", "6.2 mmol/L", "3.5-5.5 mmol/L", "CRITICAL", "final",
                "RP20261111001", "tech01", "已电话通知", NOW, NOW.plusMinutes(5));

        Observation observation = mapper.toObservation(row);

        assertThat(observation.getStatus().toCode()).isEqualTo("final");
        assertThat(observation.getCategoryFirstRep().getCodingFirstRep().getCode())
                .isEqualTo("laboratory");
        assertThat(observation.getCode().getText()).isEqualTo("血清钾");
        assertThat(observation.getValueQuantity().getValue().doubleValue()).isEqualTo(6.2);
        assertThat(observation.getValueQuantity().getUnit()).isEqualTo("mmol/L");
        assertThat(observation.getReferenceRangeFirstRep().getLow().getValue().doubleValue())
                .isEqualTo(3.5);
        assertThat(observation.getReferenceRangeFirstRep().getHigh().getValue().doubleValue())
                .isEqualTo(5.5);
        assertThat(observation.getInterpretationFirstRep().getCodingFirstRep().getCode())
                .isEqualTo("HH");
    }

    @Test
    @DisplayName("观察：无法解析为数值时用 valueString，绝不硬塞错误数字")
    void observationNonNumeric() {
        ObservationRow row = new ObservationRow(UUID.randomUUID(), PATIENT_ID, null,
                "乙肝表面抗原", "阳性", "阴性", "HIGH", "preliminary",
                "RP1", "tech01", null, NOW, null);

        Observation observation = mapper.toObservation(row);

        assertThat(observation.getValueStringType().getValue()).isEqualTo("阳性");
        assertThat(observation.getReferenceRangeFirstRep().getText()).isEqualTo("阴性");
        assertThat(observation.hasEncounter()).isFalse();
        assertThat(observation.getInterpretationFirstRep().getCodingFirstRep().getCode())
                .isEqualTo("H");
    }

    @Test
    @DisplayName("处方：明细映射为 MedicationRequest，状态由处方状态机翻译")
    void medicationRequest() {
        MedicationRequestRow row = new MedicationRequestRow(UUID.randomUUID(), UUID.randomUUID(),
                "RX20261111001", PATIENT_ID, UUID.randomUUID(), "completed", "doctor01",
                "pharmacist01", NOW, null, NOW, "DRUG-AMOX", "阿莫西林胶囊", "0.25g*24粒",
                2, "盒", "0.5g", "每日三次", 5);

        MedicationRequest request = mapper.toMedicationRequest(row);

        assertThat(request.getStatus().toCode()).isEqualTo("completed");
        assertThat(request.getIntent().toCode()).isEqualTo("order");
        assertThat(request.getMedicationCodeableConcept().getCodingFirstRep().getCode())
                .isEqualTo("DRUG-AMOX");
        assertThat(request.getMedicationCodeableConcept().getText()).isEqualTo("阿莫西林胶囊 0.25g*24粒");
        assertThat(request.getDosageInstructionFirstRep().getText()).isEqualTo("0.5g 每日三次");
        assertThat(request.getDispenseRequest().getQuantity().getValue().intValue()).isEqualTo(2);
        assertThat(request.getDispenseRequest().getExpectedSupplyDuration().getValue().intValue())
                .isEqualTo(5);
        assertThat(request.getRequester().getDisplay()).isEqualTo("doctor01");
    }

    @Test
    @DisplayName("处方：驳回原因写入 note，未知状态回落 unknown 而不是抛异常")
    void medicationRejected() {
        MedicationRequestRow row = new MedicationRequestRow(UUID.randomUUID(), UUID.randomUUID(),
                "RX2", PATIENT_ID, null, "cancelled", "doctor01", null, null,
                "诊断与适应证不符", NOW, "DRUG-X", "某药", null, 1, "盒", null, null, null);

        MedicationRequest request = mapper.toMedicationRequest(row);

        assertThat(request.getStatus().toCode()).isEqualTo("cancelled");
        assertThat(request.getNoteFirstRep().getText()).contains("诊断与适应证不符");
    }

    private PatientRow patientRow(String status, UUID mergedInto) {
        return new PatientRow(PATIENT_ID, "P20261111001", "张三", "MALE",
                LocalDate.parse("1980-01-02"), "A", "青霉素", status, mergedInto, 3,
                NOW.minusDays(30), NOW);
    }
}
