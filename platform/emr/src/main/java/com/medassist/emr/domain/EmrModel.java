package com.medassist.emr.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * 病历领域模型（不可变记录）。
 *
 * <p>content 用 JSON 文本保存（结构化字段），contentText 是服务端渲染的确定性文本，
 * 签名哈希只针对 contentText —— 同样内容必然得到同样哈希，便于事后校验。
 */
public final class EmrModel {

    private EmrModel() {
    }

    /** 病历模板。 */
    public record Template(UUID id, String templateCode, String name, String docType,
                           String scope, String schemaJson, boolean enabled, long version) {
    }

    /** 病历文书。 */
    public record MedicalRecord(UUID id, String recordNo, UUID patientId, String patientNo,
                                UUID visitId, String visitNo, UUID deptId, String docType,
                                String templateCode, String title, String content,
                                String contentText, String author, String status, int revision,
                                String signedBy, Instant signedAt, String contentHash,
                                Instant archivedAt, long version, Instant createdAt) {

        public static final String DRAFT = "DRAFT";
        public static final String SUBMITTED = "SUBMITTED";
        public static final String SIGNED = "SIGNED";
        public static final String ARCHIVED = "ARCHIVED";

        public boolean archived() {
            return ARCHIVED.equals(status);
        }
    }

    /**
     * 签名记录。
     *
     * <p>三级签名按 AUTHOR(1) → ATTENDING(2) → CHIEF(3) 顺序签署，
     * 每一级都绑定当前 revision 的内容哈希；归档时按文书类型要求的级别校验。
     */
    public record RecordSignature(UUID id, UUID recordId, int revision, String signer,
                                  String signType, Instant signedAt, String contentHash) {

        public static final String AUTHOR = "AUTHOR";
        public static final String ATTENDING = "ATTENDING";
        public static final String CHIEF = "CHIEF";

        /** 级别数值；非法签名类型返回 0（由调用方拒绝）。 */
        public static int levelOf(String signType) {
            return switch (signType == null ? "" : signType) {
                case AUTHOR -> 1;
                case ATTENDING -> 2;
                case CHIEF -> 3;
                default -> 0;
            };
        }

        /** 级别中文名，用于报错与打印。 */
        public static String levelName(int level) {
            return switch (level) {
                case 1 -> "书写医师";
                case 2 -> "主治医师";
                case 3 -> "主任医师";
                default -> "未签名";
            };
        }
    }

    /** 归档所需的签名级别（按文书类型配置）。 */
    public record SignPolicy(String docType, int requiredLevel) {
    }

    /**
     * 病案修改申请（归档后修改必须经审批）。
     *
     * <p>申请人提交拟修改内容，审批人（不得与申请人同一人）通过后才落库，
     * 落库后 revision+1 并回到 SUBMITTED，必须重新签名与归档。
     */
    public record ChangeRequest(UUID id, UUID recordId, String recordNo, String reason,
                                String proposedContent, String proposedText, String status,
                                String requester, Instant requestedAt, String reviewer,
                                Instant reviewedAt, String reviewComment, Integer appliedRevision) {

        public static final String PENDING = "PENDING";
        public static final String APPROVED = "APPROVED";
        public static final String REJECTED = "REJECTED";
    }

    /** CDR 检索命中项（不带正文，避免检索接口返回大对象）。 */
    public record RecordHit(UUID id, String recordNo, UUID patientId, String patientNo,
                            String docType, String title, String author, String status,
                            int revision, Instant createdAt) {
    }

    /** 修订历史。 */
    public record Amendment(UUID id, UUID recordId, int revision, String content,
                            String contentText, String contentHash, String author,
                            String reason, Instant createdAt) {
    }

    /** 质控规则。 */
    public record QcRule(UUID id, String ruleCode, String name, String docType, String ruleType,
                         String fieldKey, Integer threshold, String severity, String message,
                         boolean enabled) {

        public static final String REQUIRED_FIELD = "REQUIRED_FIELD";
        public static final String MIN_LENGTH = "MIN_LENGTH";
        public static final String SIGNATURE_REQUIRED = "SIGNATURE_REQUIRED";
        public static final String TIMELINESS = "TIMELINESS";

        public static final String FATAL = "FATAL";
        public static final String MAJOR = "MAJOR";
        public static final String MINOR = "MINOR";
    }

    /** 质控结果快照。 */
    public record QcResult(UUID id, UUID recordId, int revision, String ruleCode,
                           String severity, boolean passed, String message) {
    }
}
