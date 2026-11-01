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
                              String status, long version, Instant createdAt,
                              UUID sourceOrderId) {

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

    /**
     * LIS 入站消息（HL7 v2 ORU^R01）留档。
     *
     * <p>messageControlId 唯一：LIS 重投同一消息只处理一次（幂等）。
     * status：PROCESSED 已回写、IGNORED 无需处理（已有签发报告/申请已关闭）、FAILED 处理失败（含原因）。
     */
    public record LisInboundMessage(UUID id, String messageControlId, String messageType,
                                    String sendingApp, String applicationNo, UUID applicationId,
                                    UUID reportId, String status, String errorMessage,
                                    String rawMessage, String messageHash, String actor,
                                    Instant receivedAt) {

        public static final String PROCESSED = "PROCESSED";
        public static final String IGNORED = "IGNORED";
        public static final String FAILED = "FAILED";
    }

    /**
     * PACS 影像检查登记（与检查申请一对一）。
     *
     * <p>StudyInstanceUID 由 PACS 生成并回传，本服务只做唯一性约束与状态推进；
     * AccessionNumber 直接复用申请号，保证 HIS 与 PACS 用同一把"业务钥匙"对账。
     */
    public record PacsStudy(UUID id, UUID applicationId, String studyInstanceUid,
                            String accessionNumber, String modality, String bodyPart,
                            String status, int seriesCount, int imageCount, String viewerUrl,
                            Instant startedAt, Instant completedAt, long version,
                            Instant createdAt) {

        public static final String SCHEDULED = "SCHEDULED";
        public static final String ARRIVED = "ARRIVED";
        public static final String IN_PROGRESS = "IN_PROGRESS";
        public static final String COMPLETED = "COMPLETED";
        public static final String CANCELLED = "CANCELLED";

        public boolean finished() {
            return COMPLETED.equals(status) || CANCELLED.equals(status);
        }
    }

    /**
     * 影像 Worklist 行（读模型，不落库）。
     *
     * <p>刻意不返回临床诊断与患者姓名：worklist 由设备高频轮询，只提供调度所需字段
     * （申请号/患者号/项目/部位/优先级/设备类型），姓名与诊断留在按申请号查询的详情接口。
     */
    public record WorklistItem(UUID applicationId, String applicationNo, String patientNo,
                               String itemCode, String itemName, String bodyPart, String priority,
                               String applicationStatus, Instant scheduledAt, String modality,
                               UUID studyId, String studyInstanceUid, String studyStatus,
                               Integer seriesCount, Integer imageCount, String viewerUrl) {
    }
}
