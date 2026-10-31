package com.medassist.tech.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * 医技领域模型（不可变记录）。
 *
 * <p>状态用字符串常量 + 数据库 CHECK 双保险；所有迁移必须带版本号 CAS。
 */
public final class TechModel {

    private TechModel() {
    }

    /** 医技项目字典。 */
    public record ExamItem(UUID id, String itemCode, String name, String category,
                           String specimenType, boolean enabled, long version) {
    }

    /** 检查/检验申请。 */
    public record Application(UUID id, String applicationNo, UUID patientId, String patientNo,
                              UUID visitId, String visitNo, UUID deptId, String appliedBy,
                              String category, String itemCode, String itemName, String bodyPart,
                              String priority, String clinicalDiag, String specimenType,
                              String status, long version, Instant createdAt) {

        public static final String APPLIED = "APPLIED";
        public static final String SPECIMEN_COLLECTED = "SPECIMEN_COLLECTED";
        public static final String RECEIVED = "RECEIVED";
        public static final String IN_PROGRESS = "IN_PROGRESS";
        public static final String REPORTED = "REPORTED";
        public static final String CLOSED = "CLOSED";
        public static final String CANCELLED = "CANCELLED";

        public boolean open() {
            return !CLOSED.equals(status) && !CANCELLED.equals(status);
        }
    }

    /** 标本。 */
    public record Specimen(UUID id, UUID applicationId, String barcode, String specimenType,
                           String status, String collectedBy, Instant collectedAt,
                           String receivedBy, Instant receivedAt, String rejectReason, long version) {

        public static final String COLLECTED = "COLLECTED";
        public static final String RECEIVED = "RECEIVED";
        public static final String REJECTED = "REJECTED";
    }

    /** 报告（同号多版本）。 */
    public record Report(UUID id, String reportNo, UUID applicationId, int revision,
                         boolean current, String findings, String conclusion, String reporter,
                         String reviewer, Instant reviewedAt, String status,
                         boolean criticalFlag, Instant createdAt) {

        public static final String DRAFT = "DRAFT";
        public static final String FINAL = "FINAL";
        public static final String AMENDED = "AMENDED";

        public boolean finalized() {
            return FINAL.equals(status) || AMENDED.equals(status);
        }
    }

    /** 危急值。 */
    public record CriticalValue(UUID id, UUID applicationId, UUID reportId, String itemName,
                                String resultValue, String referenceRange, String severity,
                                String status, String notifiedTo, Instant notifiedAt,
                                String acknowledgedBy, Instant acknowledgedAt, String note,
                                Instant createdAt) {

        public static final String PENDING = "PENDING";
        public static final String ACKNOWLEDGED = "ACKNOWLEDGED";
        public static final String CLOSED = "CLOSED";

        /** 未闭环（待确认或已确认未关闭）。 */
        public boolean open() {
            return !CLOSED.equals(status);
        }
    }
}
