package com.medassist.fhir.xds;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 生成共享文档所需的只读投影（来自 clinical / tech / empi）。
 *
 * <p>刻意不直接暴露业务实体：CDA 只需要"写进文档的那几个字段"，
 * 用完整实体会让文档生成与业务表结构强耦合，改一个字段就断。
 */
public final class XdsSources {

    private XdsSources() {
    }

    public record PatientRef(UUID id, String patientNo, String name, String gender,
                             LocalDate birthDate) {
    }

    public record DiagnosisRow(String code, String name, boolean primaryFlag,
                               String diagnosedBy, Instant diagnosedAt) {
    }

    public record MedicationRow(String drugName, String spec, String dosage, String frequency,
                                Integer days, int quantity, String unit) {
    }

    public record DischargeSource(UUID visitId, String visitNo, LocalDate visitDate,
                                  String doctorId, UUID campusId, PatientRef patient,
                                  UUID admissionId, String admissionNo, String attendingDoctor,
                                  Instant admittedAt, Instant dischargedAt,
                                  String deptCode, String deptName,
                                  String wardName, String bedNo) {
    }

    public record LabSource(UUID applicationId, String applicationNo, UUID patientId,
                            String patientNo, String patientName, String gender,
                            LocalDate birthDate, UUID visitId, String visitNo,
                            String appliedBy, String itemCode, String itemName,
                            String specimenType, String clinicalDiag, UUID campusId,
                            UUID reportId, String reportNo, int revision,
                            String findings, String conclusion, String reporter,
                            String reviewer, Instant reviewedAt, String reportStatus,
                            Instant createdAt) {
    }
}
