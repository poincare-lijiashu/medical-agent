package com.medassist.clinical.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 临床核心服务配置。
 *
 * @param empi     {@code empi.base-url} 内网地址（如 http://platform-empi:8082），
 *                 {@code empi.internal-token} 服务间令牌（必须与 EMPI 侧一致）
 * @param pharmacy {@code pharmacy.base-url} 药房内网地址（医嘱转处方），令牌与 EMPI 共用
 * @param tech     {@code tech.base-url} 医技内网地址（检查/检验医嘱转申请）
 * @param emr      {@code emr.base-url} 电子病历内网地址（出院体检核查病历归档）；
 *                 令牌留空 = 未接入，体检显示"未核查"而不是失败
 * @param ai       {@code ai.base-url} Python AI 中台地址（HIS 内直接触发质控/审方）；
 *                 留空 = 未对接（调用返回 503 明确提示，不静默降级）
 */
@ConfigurationProperties(prefix = "clinical")
public record ClinicalProperties(Empi empi, Pharmacy pharmacy, Tech tech, Emr emr, Ai ai) {

    public record Empi(String baseUrl, String internalToken, Duration timeout) {
    }

    public record Pharmacy(String baseUrl, String internalToken, Duration timeout) {
    }

    public record Tech(String baseUrl, String internalToken, Duration timeout) {
    }

    public record Emr(String baseUrl, String internalToken, Duration timeout) {
    }

    /**
     * AI 中台（评测「HIS 深度集成」：Java 侧反向调用 AI，形成双向集成）。
     * 令牌不在此配置——由调用方透传医护端 JWT（AI 中台信任同一 HS256 身份源）。
     */
    public record Ai(String baseUrl, Duration timeout) {
    }
}
