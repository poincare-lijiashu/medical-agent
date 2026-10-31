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

    /** 签名记录。 */
    public record RecordSignature(UUID id, UUID recordId, int revision, String signer,
                                  String signType, Instant signedAt, String contentHash) {

        public static final String AUTHOR = "AUTHOR";
        public static final String ATTENDING = "ATTENDING";
        public static final String CHIEF = "CHIEF";
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

        public static final String FATAL = "FATAL";
        public static final String MAJOR = "MAJOR";
        public static final String MINOR = "MINOR";
    }

    /** 质控结果快照。 */
    public record QcResult(UUID id, UUID recordId, int revision, String ruleCode,
                           String severity, boolean passed, String message) {
    }
}
