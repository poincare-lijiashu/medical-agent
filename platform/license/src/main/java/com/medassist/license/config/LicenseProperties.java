package com.medassist.license.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Locale;

/**
 * 授权服务配置（全部可通过环境变量覆盖，便于同一镜像在不同院区部署）。
 *
 * <p>{@code deploymentMode} 是**构建/交付形态**，不是业务开关（§289）：
 * <ul>
 *   <li>{@code vendor}：厂商侧交付包，允许签发授权书；</li>
 *   <li>{@code hospital}：院方侧交付包，必须不能签发——否则等于把"给自己发证"交给院方。</li>
 * </ul>
 * 默认 {@code hospital}（fail-closed）：忘记配置的院内部署不会意外获得签发能力；
 * 厂商侧必须显式设置 {@code LICENSE_DEPLOYMENT_MODE=vendor}。启动校验见
 * {@link LicenseDeploymentGuard}。
 *
 * @param instanceCode    部署实例码：授权书按此绑定，换院必须重新签发
 * @param signingKey      Ed25519 私钥（PKCS#8 Base64）；为空时按 {@code keyDir} 自动生成（仅开发）
 * @param verifyKey       Ed25519 公钥（X.509/SPKI Base64）；为空时由私钥推导
 * @param keyDir          自动生成密钥的落盘目录
 * @param deploymentMode  交付形态：vendor（可签发）/ hospital（禁止签发）
 * @param issuanceEnabled 是否开放签发接口（厂商侧能力，院内必须为 false）
 * @param graceDays       到期宽限期（天），宽限期内只告警
 * @param enforce         是否由网关强制拦截未授权模块（默认关闭）
 */
@ConfigurationProperties(prefix = "medassist.license")
public record LicenseProperties(
        String instanceCode,
        String signingKey,
        String verifyKey,
        String keyDir,
        String deploymentMode,
        boolean issuanceEnabled,
        int graceDays,
        boolean enforce
) {

    /** 允许的交付形态；未知值在启动校验阶段直接拒绝（不静默猜）。 */
    public static final String VENDOR = "vendor";
    public static final String HOSPITAL = "hospital";

    public LicenseProperties {
        instanceCode = blank(instanceCode) ? "MEDASSIST-DEV-0001" : instanceCode.trim();
        keyDir = blank(keyDir) ? "data/license" : keyDir.trim();
        deploymentMode = blank(deploymentMode) ? HOSPITAL : deploymentMode.trim().toLowerCase(Locale.ROOT);
        if (graceDays < 0 || graceDays > 365) {
            graceDays = 30;
        }
    }

    /** 院方交付形态：禁止签发（仍需启动校验兜住"配置写了 true"的误配）。 */
    public boolean hospitalMode() {
        return HOSPITAL.equals(deploymentMode);
    }

    public boolean vendorMode() {
        return VENDOR.equals(deploymentMode);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
