package com.medassist.clinical.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * 急诊分诊与留观契约（G36）。
 *
 * <p>请求只接受业务意图；状态、分级绿色通道、床位占用与转归时间均由服务端状态机推进。
 */
public final class EmergencyDtos {

    private EmergencyDtos() {
    }

    /** 预检分诊登记。 */
    public record CreateTriageReq(
            @NotNull UUID patientId,
            @NotNull UUID deptId,
            int triageLevel,
            @NotBlank @Size(max = 500) String chiefComplaint,
            BigDecimal temperature,
            Integer pulse,
            Integer respiration,
            Integer systolic,
            Integer diastolic,
            Integer spo2,
            Boolean greenChannel,
            Instant arrivalAt) {
    }

    /** 急诊挂号：指定接诊医师。 */
    public record RegisterTriageReq(@NotBlank @Size(max = 64) String doctorId) {
    }

    /** 进入留观：床位可空，服务端自动选第一张启用空床（优先抢救区）。 */
    public record ObserveReq(UUID bedId) {
    }

    /** 留观转归：DISCHARGED / ADMITTED / TRANSFERRED / LEFT。 */
    public record OutcomeReq(
            @NotBlank @Size(max = 16) String outcome,
            @Size(max = 500) String note) {
    }

    /** 未挂号患者自行离院。 */
    public record LeaveReq(@NotBlank @Size(max = 500) String reason) {
    }

    public record TriageView(UUID id, String triageNo, UUID patientId, String patientNo,
                             String patientName, UUID deptId, String deptName, int triageLevel,
                             String chiefComplaint, BigDecimal temperature, Integer pulse,
                             Integer respiration, Integer systolic, Integer diastolic,
                             Integer spo2, boolean greenChannel, String status, UUID visitId,
                             String visitNo, String triagedBy, Instant arrivalAt, Instant triagedAt,
                             String leaveReason, long version, Instant createdAt) {
    }

    public record BedView(UUID id, String bedNo, UUID deptId, String deptName, String area,
                          String status, UUID currentObservationId, boolean enabled,
                          long version) {
    }

    public record ObservationView(UUID id, String observationNo, UUID triageId, String triageNo,
                                  UUID visitId, String visitNo, UUID patientId, String patientNo,
                                  String patientName, UUID deptId, String deptName, UUID bedId,
                                  String bedNo, String status, Instant startAt, Instant endAt,
                                  String outcome, String outcomeNote, String createdBy,
                                  String closedBy, long version, Instant createdAt) {
    }

    public record BoardView(long waiting, long level1, long level2, long level3, long level4,
                            long registered, long observing, long closedToday, long leftToday,
                            long totalToday, long freeBeds, long occupiedBeds) {
    }
}
