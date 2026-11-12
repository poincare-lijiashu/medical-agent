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
            String verifyKeyId
    ) {
    }

    public record LicenseList(List<License> items, int total) {
    }

    /**
     * 实例授权摘要（§284 开通编排用）。
     *
     * <p>只暴露"是否已授权 + 状态/版本/到期时间/模块数"，不返回授权书正文与签名：
     * 控制面只需要判断"能不能开通"，不需要拿到可转发的授权材料。
     *
     * @param present 该实例是否存在任何授权记录（含已吊销）
     */
    public record Summary(
            boolean present,
            String licenseNo,
            String status,
            String editionCode,
            java.time.Instant expiresAt,
            int modules) {
    }

    public record AuditList(List<ChangeLog> items, int total) {
    }

    public record EditionList(List<Edition> items) {
    }

    public record EntitlementList(boolean usable, List<ModuleEntitlement> items) {
    }
}
