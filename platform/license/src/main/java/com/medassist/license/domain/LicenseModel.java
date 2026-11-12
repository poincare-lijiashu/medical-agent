package com.medassist.license.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** 授权域模型（与库表一一对应，不含持久化细节）。 */
public final class LicenseModel {

    private LicenseModel() {
    }

    /** 产品版本矩阵。 */
    public record Edition(
            String code,
            String name,
            String description,
            List<String> modules,
            Integer maxBeds,
            Integer maxUsers,
            Integer maxAiCallsPerDay,
            int sortNo,
            String status
    ) {
    }

    /** 授权书（一条历史记录；instance 维度同时只有一条 ACTIVE）。 */
    public record License(
            UUID id,
            String licenseNo,
            String instanceCode,
            String hospitalName,
            String editionCode,
            List<String> modules,
            Integer maxBeds,
            Integer maxUsers,
            Integer maxAiCallsPerDay,
            Instant notBefore,
            Instant expiresAt,
            int graceDays,
            String status,
            String payload,
            String payloadHash,
            String signature,
            String keyId,
            String importedBy,
            Instant importedAt,
            /** 签发时间：属于签名内容（与 importedAt 不同，导入可能晚于签发） */
            Instant issuedAt,
            String revokedBy,
            Instant revokedAt,
            String revokeReason
    ) {
    }

    /** 配额用量日快照。 */
    public record Usage(
            LocalDate date,
            int activeBeds,
            int activeUsers,
            int aiCalls,
            String source,
            Instant capturedAt
    ) {
    }

    /** 授权阶段：库里只存 ACTIVE/SUPERSEDED/REVOKED，时间维度在这里现算。 */
    public enum Phase {
        /** 尚未导入任何授权（只有开发默认授权时是 DEV） */ NONE,
        /** 未到生效时间 */ PENDING,
        /** 生效中 */ ACTIVE,
        /** 已过期但在宽限期 */ GRACE,
        /** 已过期且超出宽限期 */ EXPIRED,
        /** 被显式吊销 */ REVOKED
    }

    /** 授权书加载后的判定结果。 */
    public record State(Phase phase, Instant graceEndsAt, Long daysLeft) {
        public boolean usable() {
            return phase == Phase.ACTIVE || phase == Phase.GRACE;
        }
    }

    /** 模块权益：本地模块目录 × 授权书模块清单。 */
    public record ModuleEntitlement(String code, String name, boolean entitled, String note) {
    }

    /** 配额对照。 */
    public record Quota(String code, String name, Integer limit, Integer used, Double utilization, boolean exceeded) {
    }

    /** 变更审计。 */
    public record ChangeLog(long id, Instant ts, String actor, String action, String target,
                            String detail, String traceId) {
    }
}
