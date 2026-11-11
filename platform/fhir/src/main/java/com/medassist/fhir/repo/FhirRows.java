package com.medassist.fhir.repo;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 读取行的内部投影（不直接暴露给外部）。
 *
 * <p>刻意与 FHIR 资源解耦：SQL 只关心"库里有什么"，{@code FhirMapper} 才决定
 * "怎么翻译成 FHIR"。这样字段增删不会把 SQL 与协议绑死，映射规则也能单测。
 */
public final class FhirRows {

    private FhirRows() {
    }

    /** empi_svc.patient 的一行（敏感字段密文不在投影内）。 */
    public record PatientRow(
            UUID id,
            String patientNo,
            String name,
            String gender,
            LocalDate birthDate,
            String bloodType,
            String allergy,
            String status,
            UUID mergedInto,
            long version,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {
    }

    /** 跨系统标识（HIS/LIS/PACS/医保/区域平台）。 */
    public record CrosswalkRow(UUID patientId, String systemCode, String externalId) {
    }

    /** 就诊 + 住院登记的联合投影（一次就诊最多一条住院登记）。 */
    public record EncounterRow(
            UUID id,
            String visitNo,
            String visitType,
            String fhirStatus,
            LocalDate visitDate,
            OffsetDateTime registeredAt,
            OffsetDateTime startedAt,
            OffsetDateTime finishedAt,
            UUID campusId,
            UUID patientId,
            String deptCode,
            String deptName,
            String doctorId,
            UUID admissionId,
            String admissionNo,
            OffsetDateTime admittedAt,
            OffsetDateTime dischargedAt,
            String wardCode,
            String wardName,
            String bedNo) {
    }

    /** 危急值（结构化指标 + 参考范围）与所属报告/申请。 */
    public record ObservationRow(
            UUID id,
            UUID patientId,
            UUID visitId,
            String itemName,
            String resultValue,
            String referenceRange,
            String severity,
            String fhirStatus,
            String reportNo,
            String reporter,
            String note,
            OffsetDateTime createdAt,
            OffsetDateTime acknowledgedAt) {
    }

    /** 处方明细（一条明细 = 一个 MedicationRequest）。 */
    public record MedicationRequestRow(
            UUID id,
            UUID prescriptionId,
            String prescriptionNo,
            UUID patientId,
            UUID visitId,
            String fhirStatus,
            String prescriber,
            String reviewer,
            OffsetDateTime reviewedAt,
            String rejectReason,
            OffsetDateTime createdAt,
            String drugCode,
            String drugName,
            String spec,
            int quantity,
            String unit,
            String dosage,
            String frequency,
            Integer days) {
    }
}
