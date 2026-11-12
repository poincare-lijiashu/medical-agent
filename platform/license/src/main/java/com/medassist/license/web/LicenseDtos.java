package com.medassist.license.web;

import com.medassist.license.domain.LicenseModel.ChangeLog;
import com.medassist.license.domain.LicenseModel.Edition;
import com.medassist.license.domain.LicenseModel.License;
import com.medassist.license.domain.LicenseModel.ModuleEntitlement;
import com.medassist.license.domain.LicenseModel.Quota;
import com.medassist.license.domain.LicenseModel.State;
import com.medassist.license.domain.LicenseModel.Usage;

import java.util.List;

/** 授权 API 出入参（与前端 `api/license.ts` 一一对应）。 */
public final class LicenseDtos {

    private LicenseDtos() {
    }

    /** 授权文件原文（厂商签发物）；后端逐字验签，不做"宽松解析"。 */
    public record ImportReq(String document) {
    }

    /** 签发请求（仅厂商构建可用）：配额留空表示"按版本上限"。 */
    public record IssueReq(
            String licenseNo,
            String instanceCode,
            String hospitalName,
            String editionCode,
            List<String> modules,
            Integer maxBeds,
            Integer maxUsers,
            Integer maxAiCallsPerDay,
            String notBefore,
            String expiresAt,
            Integer graceDays
    ) {
    }

    /** 签发结果：document 交给院方导入，keyId 用于核对公钥。 */
    public record Issued(String document, String payloadHash, String keyId, String licenseNo) {
    }

    public record RevokeReq(String reason) {
    }

    public record UsageReq(String date, Integer activeBeds, Integer activeUsers, Integer aiCalls, String source) {
    }

    /**
     * AI 中台的日调用量上报（§289，内部令牌保护）。
     *
     * <p>只携带 AI 调用数：床位/账号是院方台账事实，不能被 AI 中台的计数顺手覆盖；
     * {@code workerId} / {@code workerCount} 只用于排障（多 worker 时各进程各自累计、
     * 由 AI 中台求和后上报，这里保留来源便于核对）。
     *
     * @param date        统计日（UTC，yyyy-MM-dd）；只接受当日或前一日，防止误回填历史
     * @param aiCalls     当日成功的模型调用次数（计费口径，§290 起在 provider 传输层计量）
     * @param source      上报来源标识，默认 AI_CORE
     * @param workerId    AI 中台进程实例标识（排障用）
     * @param workerCount AI 中台部署的 worker 数（>1 时计数口径需人工复核）
     */
    public record AiUsageReq(
            String date,
            Integer aiCalls,
            String source,
            String workerId,
            Integer workerCount
    ) {
    }

    /** 授权总览：页面一次性拿全，避免多接口拼装导致口径不一致。 */
    public record StatusView(
            String instanceCode,
            String phase,
            State state,
            License license,
            Usage usage,
            List<ModuleEntitlement> entitlements,
            List<Quota> quotas,
            List<String> warnings,
            List<Edition> editions,
            boolean issuanceEnabled,
            boolean enforce,
            String verifyKeyId,
            /** 交付形态（§289）：vendor=厂商构建可签发；hospital=院方构建禁止签发 */
            String deploymentMode
    ) {
    }

    public record LicenseList(List<License> items, int total) {
    }

    /**
     * 实例授权摘要（§284 开通编排 / §288 权益联动用）。
     *
     * <p>只暴露"能不能用 + 可用到什么规模"，不返回授权书正文与签名：
     * 控制面不需要拿到可转发的授权材料。
     *
     * @param present      该实例是否存在授权记录（含已吊销）
     * @param phase        时间事实：ACTIVE / PENDING / GRACE / EXPIRED / REVOKED / NONE
     * @param modules      已授权模块编码（用于开通时核对版本能力）
     * @param maxBeds      床位上限；null 表示不限
     * @param maxUsers     账号上限；null 表示不限
     * @param maxAiCallsPerDay AI 日调用上限；null 表示不限
     * @param daysLeft     距离到期（或宽限结束）的天数；无授权为 null
     */
    public record Summary(
            boolean present,
            String licenseNo,
            String status,
            String editionCode,
            java.time.Instant expiresAt,
            java.util.List<String> modules,
            String phase,
            Integer maxBeds,
            Integer maxUsers,
            Integer maxAiCallsPerDay,
            int graceDays,
            Long daysLeft) {
    }

    public record AuditList(List<ChangeLog> items, int total) {
    }

    public record EditionList(List<Edition> items) {
    }

    public record EntitlementList(boolean usable, List<ModuleEntitlement> items) {
    }
}
