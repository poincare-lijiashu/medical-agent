package com.medassist.tech.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
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

    /**
     * 报告（同号多版本）。
     *
     * <p>G35 起可选结构化内容：{@code templateId} 指向发布的结构化模板，
     * {@code structuredJson} 是该版本的结构化字段快照（JSON 文本），历史版本不受修订影响。
     */
    public record Report(UUID id, String reportNo, UUID applicationId, int revision,
                         boolean current, String findings, String conclusion, String reporter,
                         String reviewer, Instant reviewedAt, String status,
                         boolean criticalFlag, Instant createdAt, UUID templateId,
                         String structuredJson) {

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

        public static final String PROCESSING = "PROCESSING";
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

    /** 影像序列元数据（PACS QIDO-RS 缓存；不含像素）。 */
    public record PacsSeries(UUID id, UUID studyId, String seriesInstanceUid, Integer seriesNumber,
                             String modality, String bodyPart, String description, int instanceCount,
                             Instant syncedAt) {
    }

    /**
     * 阅片任务（领取/释放/完成）。
     *
     * <p>同一检查同时只允许一条 CLAIMED（库级部分唯一索引）；释放与完成后保留历史，
     * 再次领取生成新记录，保证"谁在什么时候看过/退回"可追溯。
     */
    public record ReadingTask(UUID id, UUID studyId, String assignee, String status,
                              Instant claimedAt, Instant releasedAt, Instant completedAt,
                              String note, long version) {

        public static final String CLAIMED = "CLAIMED";
        public static final String RELEASED = "RELEASED";
        public static final String COMPLETED = "COMPLETED";
    }

    /**
     * 阅片队列行（读模型）。
     *
     * <p>reportStatus 用于前端提示"未写报告/已签发"；readingStatus 为空表示尚未领取。
     */
    public record ReadingQueueItem(UUID studyId, String studyInstanceUid, String accessionNumber,
                                   String modality, String bodyPart, int seriesCount, int imageCount,
                                   Instant completedAt, String viewerUrl,
                                   UUID applicationId, String applicationNo, String patientNo,
                                   String itemName, String priority, UUID deptId,
                                   String reportStatus, UUID readingTaskId, String assignee,
                                   String readingStatus, Instant claimedAt, Instant readCompletedAt) {
    }

    /** 影像调阅审计行（追加式）。 */
    public record PacsAccessLog(UUID id, UUID studyId, String studyInstanceUid,
                                String accessionNumber, String actor, String action,
                                String seriesInstanceUid, String sopInstanceUid, String detail,
                                Instant createdAt) {

        public static final String VIEW_STUDY = "VIEW_STUDY";
        public static final String VIEW_SERIES = "VIEW_SERIES";
        public static final String FETCH_IMAGE = "FETCH_IMAGE";
    }

    /**
     * 医技设备档案（G35）。
     *
     * <p>停用（RETIRED）后不允许再登记质控；MAINTENANCE 允许登记维修后的校准记录。
     */
    public record Device(UUID id, String deviceNo, String name, String modality, UUID deptId,
                         String location, String manufacturer, String model, String serialNo,
                         String status, LocalDate installedAt, long version, Instant createdAt) {

        public static final String ACTIVE = "ACTIVE";
        public static final String MAINTENANCE = "MAINTENANCE";
        public static final String RETIRED = "RETIRED";

        public boolean retired() {
            return RETIRED.equals(status);
        }
    }

    /** 设备质控记录（追加式）。 */
    public record DeviceQc(UUID id, UUID deviceId, String deviceNo, String deviceName,
                           String qcType, String result, String itemName, String expectedValue,
                           String actualValue, String unit, String performedBy,
                           Instant performedAt, String note, Instant createdAt) {

        public static final String PASS = "PASS";
        public static final String FAIL = "FAIL";

        public static final String DAILY = "DAILY";
        public static final String WEEKLY = "WEEKLY";
        public static final String MONTHLY = "MONTHLY";
        public static final String CALIBRATION = "CALIBRATION";
    }

    /** 设备状态计数（看板）。 */
    public record DeviceStatusCount(String status, int count) {
    }

    /**
     * 设备维保工单（G82）：报修 → 维修 → 完工 → 验收。
     *
     * <p>{@code orderNo} 由服务生成（WO+业务日期+序号）；{@code deviceNo}/{@code deviceName}
     * 读取时按设备档案 JOIN 带出，不另存快照（设备改名后工单应显示当前名称）。
     */
    public record DeviceWorkOrder(UUID id, String orderNo, UUID deviceId, String deviceNo,
                                  String deviceName, String orderType, String priority,
                                  String title, String description, String reportedBy,
                                  Instant reportedAt, String vendor, String status,
                                  Instant startedAt, Instant finishedAt, String resolution,
                                  BigDecimal costAmount, String acceptedBy, Instant acceptedAt,
                                  String acceptNote, String cancelReason, String createdBy,
                                  long version, Instant createdAt) {

        public static final String OPEN = "OPEN";
        public static final String IN_PROGRESS = "IN_PROGRESS";
        public static final String DONE = "DONE";
        public static final String ACCEPTED = "ACCEPTED";
        public static final String CANCELLED = "CANCELLED";

        /** 计划保养 / 故障维修 / 检测。 */
        public static final String PREVENTIVE = "PREVENTIVE";
        public static final String CORRECTIVE = "CORRECTIVE";
        public static final String INSPECTION = "INSPECTION";

        public boolean corrective() {
            return CORRECTIVE.equals(orderType);
        }

        /** 终态：验收或取消后工单冻结。 */
        public boolean terminal() {
            return ACCEPTED.equals(status) || CANCELLED.equals(status);
        }
    }

    /**
     * 结构化报告模板（G35）。
     *
     * <p>{@code schemaJson} 形如 {@code {"fields":[{"key","label","type","required",...}]}}；
     * 发布后不可改，只能停用后新建版本（与临床路径模板一致）。
     */
    public record ReportTemplate(UUID id, String code, String name, String modality,
                                 String schemaJson, String status, String createdBy,
                                 Instant createdAt, String publishedBy, Instant publishedAt,
                                 long version) {

        public static final String DRAFT = "DRAFT";
        public static final String PUBLISHED = "PUBLISHED";
        public static final String DISABLED = "DISABLED";

        public boolean published() {
            return PUBLISHED.equals(status);
        }
    }
}
