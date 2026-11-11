package com.medassist.fhir.map;

import com.medassist.common.security.Masks;
import com.medassist.fhir.repo.FhirRepository;
import com.medassist.fhir.repo.FhirRows.CrosswalkRow;
import com.medassist.fhir.repo.FhirRows.EncounterRow;
import com.medassist.fhir.repo.FhirRows.MedicationRequestRow;
import com.medassist.fhir.repo.FhirRows.ObservationRow;
import com.medassist.fhir.repo.FhirRows.PatientRow;
import org.hl7.fhir.exceptions.FHIRException;
import org.hl7.fhir.r4.model.BooleanType;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.DateTimeType;
import org.hl7.fhir.r4.model.DateType;
import org.hl7.fhir.r4.model.Duration;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.Enumerations;
import org.hl7.fhir.r4.model.HumanName;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.InstantType;
import org.hl7.fhir.r4.model.MedicationRequest;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Period;
import org.hl7.fhir.r4.model.Quantity;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.StringType;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 内部行 → FHIR R4 资源。
 *
 * <p>映射原则：
 * <ul>
 *   <li><b>只映射库里有证据的字段</b>：不为了"看起来完整"编造 code/LOINC/SNOMED；</li>
 *   <li><b>本地编码显式标注</b>：没有标准术语时用 {@code urn:medassist:*} 本地系统，
 *       并保留中文 text，便于对端人工核对；</li>
 *   <li><b>PHI 默认脱敏</b>：无 {@code fhir:read_phi} 时姓名、患者号、出生日期只给
 *       掩码/年份，跨系统标识整体不下发。</li>
 * </ul>
 */
@Component
public class FhirMapper {

    public static final String PATIENT_NO_SYSTEM = FhirRepository.PATIENT_NO_SYSTEM;
    public static final String VISIT_NO_SYSTEM = "urn:medassist:visit-no";
    public static final String ADMISSION_NO_SYSTEM = "urn:medassist:admission-no";
    public static final String PRESCRIPTION_NO_SYSTEM = "urn:medassist:prescription-no";
    public static final String PRESCRIPTION_ITEM_SYSTEM = "urn:medassist:prescription-item";
    public static final String CROSSWALK_SYSTEM_PREFIX = "urn:medassist:crosswalk:";
    public static final String DRUG_CODE_SYSTEM = "urn:medassist:drug-code";
    public static final String VISIT_TYPE_SYSTEM = "urn:medassist:visit-type";

    private static final String ACT_CODE_SYSTEM = "http://terminology.hl7.org/CodeSystem/v3-ActCode";
    private static final String V2_0203 = "http://terminology.hl7.org/CodeSystem/v2-0203";
    private static final String OBSERVATION_CATEGORY =
            "http://terminology.hl7.org/CodeSystem/observation-category";
    private static final String INTERPRETATION_SYSTEM =
            "http://terminology.hl7.org/CodeSystem/v3-ObservationInterpretation";

    /**
     * FHIR dateTime 必须带秒（HAPI 会拒绝 {@code 09:30+08:00} 这类"分钟精度"）。
     * {@code OffsetDateTime.toString()} 在秒为 0 时会省略秒，不能直接用。
     */
    private static final DateTimeFormatter FHIR_DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

    public Patient toPatient(PatientRow row, List<CrosswalkRow> crosswalks, boolean phiReadable) {
        Patient patient = new Patient();
        patient.setId(row.id().toString());
        meta(patient, row.updatedAt(), Long.toString(row.version()));

        Identifier mrn = patient.addIdentifier()
                .setSystem(PATIENT_NO_SYSTEM)
                .setValue(phiReadable ? row.patientNo() : Masks.patientNo(row.patientNo()));
        mrn.getType().addCoding().setSystem(V2_0203).setCode("MR").setDisplay("Medical record number");

        if (phiReadable) {
            for (CrosswalkRow crosswalk : crosswalks) {
                patient.addIdentifier()
                        .setSystem(CROSSWALK_SYSTEM_PREFIX + crosswalk.systemCode())
                        .setValue(crosswalk.externalId());
            }
        }

        String display = phiReadable ? row.name() : Masks.patientName(row.name());
        HumanName name = patient.addName().setUse(HumanName.NameUse.OFFICIAL).setText(display);
        if (phiReadable) {
            applyStructuredName(name, row.name());
        }

        patient.setGender(gender(row.gender()));
        if (row.birthDate() != null) {
            patient.setBirthDateElement(new DateType(
                    phiReadable ? row.birthDate().toString() : String.valueOf(row.birthDate().getYear())));
        }

        patient.setActive("ACTIVE".equals(row.status()));
        if ("DECEASED".equals(row.status())) {
            patient.setDeceased(new BooleanType(true));
        }
        if ("MERGED".equals(row.status()) && row.mergedInto() != null) {
            patient.addLink().setType(Patient.LinkType.REPLACEDBY)
                    .setOther(new Reference("Patient/" + row.mergedInto()));
        }
        return patient;
    }

    public Encounter toEncounter(EncounterRow row) {
        Encounter encounter = new Encounter();
        encounter.setId(row.id().toString());
        encounter.addIdentifier().setSystem(VISIT_NO_SYSTEM).setValue(row.visitNo());
        if (row.admissionNo() != null) {
            encounter.addIdentifier().setSystem(ADMISSION_NO_SYSTEM).setValue(row.admissionNo());
        }
        encounter.setStatus(encounterStatus(row.fhirStatus()));
        encounter.setClass_(encounterClass(row.visitType()));
        encounter.setSubject(new Reference("Patient/" + row.patientId()));

        String typeLabel = visitTypeLabel(row.visitType());
        CodeableConcept type = encounter.addType();
        type.setText(typeLabel);
        type.addCoding().setSystem(VISIT_TYPE_SYSTEM).setCode(row.visitType()).setDisplay(typeLabel);

        Period period = new Period();
        OffsetDateTime start = row.admittedAt() != null ? row.admittedAt() : firstNonNull(
                row.startedAt(), row.registeredAt());
        OffsetDateTime end = row.dischargedAt() != null ? row.dischargedAt() : row.finishedAt();
        if (start != null) {
            period.setStartElement(new DateTimeType(fhirDateTime(start)));
        }
        if (end != null) {
            period.setEndElement(new DateTimeType(fhirDateTime(end)));
        }
        encounter.setPeriod(period);

        String location = locationLabel(row);
        if (location != null) {
            Encounter.EncounterLocationComponent component = encounter.addLocation();
            component.setLocation(new Reference().setDisplay(location));
            component.setStatus(Encounter.EncounterLocationStatus.ACTIVE);
        }
        if (row.doctorId() != null && !row.doctorId().isBlank()) {
            // 本服务不提供 Practitioner 资源，只给 display，避免产生悬空引用
            encounter.addParticipant().setIndividual(new Reference().setDisplay(row.doctorId()));
        }
        return encounter;
    }

    public Observation toObservation(ObservationRow row) {
        Observation observation = new Observation();
        observation.setId(row.id().toString());
        meta(observation, row.createdAt(), null);
        observation.setStatus(observationStatus(row.fhirStatus()));
        observation.addCategory().addCoding()
                .setSystem(OBSERVATION_CATEGORY).setCode("laboratory").setDisplay("Laboratory");
        observation.getCode().setText(row.itemName());
        observation.setSubject(new Reference("Patient/" + row.patientId()));
        if (row.visitId() != null) {
            observation.setEncounter(new Reference("Encounter/" + row.visitId()));
        }
        if (row.createdAt() != null) {
            observation.setEffective(new DateTimeType(fhirDateTime(row.createdAt())));
            observation.setIssuedElement(new InstantType(row.createdAt().toInstant().toString()));
        }
        if (row.reporter() != null && !row.reporter().isBlank()) {
            observation.addPerformer().setDisplay(row.reporter());
        }

        NumericValue numeric = NumericValue.parse(row.resultValue());
        if (numeric != null) {
            Quantity quantity = new Quantity().setValue(numeric.value());
            if (numeric.unit() != null && !numeric.unit().isBlank()) {
                quantity.setUnit(numeric.unit());
            }
            observation.setValue(quantity);
        } else if (row.resultValue() != null && !row.resultValue().isBlank()) {
            observation.setValue(new StringType(row.resultValue()));
        }

        if (row.referenceRange() != null && !row.referenceRange().isBlank()) {
            Observation.ObservationReferenceRangeComponent range = observation.addReferenceRange();
            ReferenceRange parsed = ReferenceRange.parse(row.referenceRange());
            if (parsed != null) {
                org.hl7.fhir.r4.model.SimpleQuantity low = new org.hl7.fhir.r4.model.SimpleQuantity();
                low.setValue(parsed.low());
                org.hl7.fhir.r4.model.SimpleQuantity high = new org.hl7.fhir.r4.model.SimpleQuantity();
                high.setValue(parsed.high());
                String unit = numeric == null ? parsed.unit() : numeric.unit();
                if (unit != null && !unit.isBlank()) {
                    low.setUnit(unit);
                    high.setUnit(unit);
                }
                range.setLow(low);
                range.setHigh(high);
                range.setText(row.referenceRange());
            } else {
                range.setText(row.referenceRange());
            }
        }

        CodeableConcept interpretation = interpretation(row.severity());
        if (interpretation != null) {
            observation.addInterpretation(interpretation);
        }
        if (row.note() != null && !row.note().isBlank()) {
            observation.addNote().setText(row.note());
        }
        return observation;
    }

    public MedicationRequest toMedicationRequest(MedicationRequestRow row) {
        MedicationRequest request = new MedicationRequest();
        request.setId(row.id().toString());
        request.addIdentifier().setSystem(PRESCRIPTION_NO_SYSTEM).setValue(row.prescriptionNo());
        request.addIdentifier().setSystem(PRESCRIPTION_ITEM_SYSTEM).setValue(row.id().toString());
        request.setStatus(medicationStatus(row.fhirStatus()));
        request.setIntent(MedicationRequest.MedicationRequestIntent.ORDER);

        CodeableConcept medication = request.getMedicationCodeableConcept();
        medication.setText(Stream.of(row.drugName(), row.spec())
                .filter(Objects::nonNull).filter(s -> !s.isBlank())
                .collect(Collectors.joining(" ")));
        medication.addCoding().setSystem(DRUG_CODE_SYSTEM)
                .setCode(row.drugCode()).setDisplay(row.drugName());

        request.setSubject(new Reference("Patient/" + row.patientId()));
        if (row.visitId() != null) {
            request.setEncounter(new Reference("Encounter/" + row.visitId()));
        }
        if (row.createdAt() != null) {
            request.setAuthoredOnElement(new DateTimeType(fhirDateTime(row.createdAt())));
        }
        if (row.prescriber() != null && !row.prescriber().isBlank()) {
            request.setRequester(new Reference().setDisplay(row.prescriber()));
        }
        if (row.reviewer() != null && !row.reviewer().isBlank()) {
            request.setRecorder(new Reference().setDisplay(row.reviewer()));
        }

        String dosageText = Stream.of(row.dosage(), row.frequency())
                .filter(Objects::nonNull).filter(s -> !s.isBlank())
                .collect(Collectors.joining(" "));
        if (!dosageText.isBlank()) {
            request.addDosageInstruction().setText(dosageText);
        }

        Quantity quantity = new Quantity().setValue(row.quantity());
        if (row.unit() != null && !row.unit().isBlank()) {
            quantity.setUnit(row.unit());
        }
        request.getDispenseRequest().setQuantity(quantity);
        if (row.days() != null && row.days() > 0) {
            Duration duration = new Duration();
            duration.setValue(row.days());
            duration.setUnit("天");
            duration.setSystem("http://unitsofmeasure.org");
            duration.setCode("d");
            request.getDispenseRequest().setExpectedSupplyDuration(duration);
        }
        if (row.rejectReason() != null && !row.rejectReason().isBlank()) {
            request.addNote().setText("驳回原因：" + row.rejectReason());
        }
        return request;
    }

    // ---------------------------------------------------------------- 内部

    /**
     * 状态码到 HAPI 枚举。
     *
     * <p>{@code fromCode} 抛受检异常，且遇到库里的未知值会直接中断整个查询——
     * 一处脏数据不能把整页都变成 500，因此统一回落 {@code UNKNOWN}。
     */
    private static Encounter.EncounterStatus encounterStatus(String code) {
        if (code == null || code.isBlank()) {
            return Encounter.EncounterStatus.UNKNOWN;
        }
        try {
            return Encounter.EncounterStatus.fromCode(code);
        } catch (FHIRException e) {
            return Encounter.EncounterStatus.UNKNOWN;
        }
    }

    private static Observation.ObservationStatus observationStatus(String code) {
        if (code == null || code.isBlank()) {
            return Observation.ObservationStatus.UNKNOWN;
        }
        try {
            return Observation.ObservationStatus.fromCode(code);
        } catch (FHIRException e) {
            return Observation.ObservationStatus.UNKNOWN;
        }
    }

    private static MedicationRequest.MedicationRequestStatus medicationStatus(String code) {
        if (code == null || code.isBlank()) {
            return MedicationRequest.MedicationRequestStatus.UNKNOWN;
        }
        try {
            return MedicationRequest.MedicationRequestStatus.fromCode(code);
        } catch (FHIRException e) {
            return MedicationRequest.MedicationRequestStatus.UNKNOWN;
        }
    }

    private static String fhirDateTime(OffsetDateTime value) {
        return FHIR_DATE_TIME.format(value);
    }

    private static void meta(org.hl7.fhir.r4.model.Resource resource,
                             OffsetDateTime updated, String versionId) {
        if (updated != null) {
            resource.getMeta().setLastUpdatedElement(new InstantType(updated.toInstant().toString()));
        }
        if (versionId != null) {
            resource.getMeta().setVersionId(versionId);
        }
    }

    private static Enumerations.AdministrativeGender gender(String value) {
        return switch (value == null ? "" : value) {
            case "MALE" -> Enumerations.AdministrativeGender.MALE;
            case "FEMALE" -> Enumerations.AdministrativeGender.FEMALE;
            default -> Enumerations.AdministrativeGender.UNKNOWN;
        };
    }

    private static Coding encounterClass(String visitType) {
        return switch (visitType == null ? "" : visitType) {
            case "EMERGENCY" -> new Coding(ACT_CODE_SYSTEM, "EMER", "emergency");
            case "INPATIENT" -> new Coding(ACT_CODE_SYSTEM, "IMP", "inpatient encounter");
            default -> new Coding(ACT_CODE_SYSTEM, "AMB", "ambulatory");
        };
    }

    private static String visitTypeLabel(String visitType) {
        return switch (visitType == null ? "" : visitType) {
            case "EMERGENCY" -> "急诊";
            case "INPATIENT" -> "住院";
            default -> "门诊";
        };
    }

    private static String locationLabel(EncounterRow row) {
        StringBuilder label = new StringBuilder();
        if (row.deptName() != null && !row.deptName().isBlank()) {
            label.append(row.deptName());
        }
        if (row.wardName() != null && !row.wardName().isBlank()) {
            if (label.length() > 0) {
                label.append(" / ");
            }
            label.append(row.wardName());
        }
        if (row.bedNo() != null && !row.bedNo().isBlank()) {
            if (label.length() > 0) {
                label.append(" / ");
            }
            label.append(row.bedNo()).append("床");
        }
        return label.length() == 0 ? null : label.toString();
    }

    private static CodeableConcept interpretation(String severity) {
        if (severity == null || severity.isBlank()) {
            return null;
        }
        Coding coding = switch (severity) {
            case "LOW" -> new Coding(INTERPRETATION_SYSTEM, "L", "Low");
            case "HIGH" -> new Coding(INTERPRETATION_SYSTEM, "H", "High");
            case "CRITICAL" -> new Coding(INTERPRETATION_SYSTEM, "HH", "Critical high");
            default -> null;
        };
        if (coding == null) {
            return null;
        }
        CodeableConcept concept = new CodeableConcept();
        concept.addCoding(coding);
        concept.setText(coding.getDisplay());
        return concept;
    }

    /** 中文姓名：姓取首字，名取其余；拉丁姓名按最后一个空格切分。 */
    private static void applyStructuredName(HumanName name, String fullName) {
        String value = fullName == null ? "" : fullName.trim();
        if (value.isEmpty()) {
            return;
        }
        if (value.contains(" ")) {
            int index = value.lastIndexOf(' ');
            name.setFamily(value.substring(index + 1));
            String given = value.substring(0, index).trim();
            if (!given.isEmpty()) {
                name.addGiven(given);
            }
            return;
        }
        if (isCjk(value) && value.length() > 1) {
            name.setFamily(value.substring(0, 1));
            name.addGiven(value.substring(1));
        } else {
            name.addGiven(value);
        }
    }

    private static boolean isCjk(String value) {
        return value.codePoints().anyMatch(cp ->
                (cp >= 0x4E00 && cp <= 0x9FFF) || (cp >= 0x3400 && cp <= 0x4DBF));
    }

    private static OffsetDateTime firstNonNull(OffsetDateTime first, OffsetDateTime second) {
        return first != null ? first : second;
    }

    /** 数值 + 单位（"5.9 mmol/L"）。解析不出来时保持 valueString，不硬塞数字。 */
    public record NumericValue(double value, String unit) {

        private static final java.util.regex.Pattern PATTERN = java.util.regex.Pattern.compile(
                "^\\s*(-?\\d+(?:\\.\\d+)?)\\s*([^\\d\\s^:,].*)?$");

        public static NumericValue parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return null;
            }
            var matcher = PATTERN.matcher(raw.trim());
            if (!matcher.matches()) {
                return null;
            }
            try {
                return new NumericValue(Double.parseDouble(matcher.group(1)), matcher.group(2));
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    /** 参考范围（"3.5-5.5 mmol/L"）；不是区间时保持 text。 */
    public record ReferenceRange(double low, double high, String unit) {

        private static final java.util.regex.Pattern PATTERN = java.util.regex.Pattern.compile(
                "^\\s*(-?\\d+(?:\\.\\d+)?)\\s*[-~–—至]\\s*(-?\\d+(?:\\.\\d+)?)\\s*([^\\d\\s].*)?$");

        public static ReferenceRange parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return null;
            }
            var matcher = PATTERN.matcher(raw.trim());
            if (!matcher.matches()) {
                return null;
            }
            try {
                return new ReferenceRange(Double.parseDouble(matcher.group(1)),
                        Double.parseDouble(matcher.group(2)), matcher.group(3));
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }
}
