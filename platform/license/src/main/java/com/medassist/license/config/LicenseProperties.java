package com.medassist.license.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 授权服务配置（全部可通过环境变量覆盖，便于同一镜像在不同院区部署）。
 *
 * @param instanceCode    部署实例码：授权书按此绑定，换院必须重新签发
 * @param signingKey      Ed25519 私钥（PKCS#8 Base64）；为空时按 {@code keyDir} 自动生成（仅开发）
 * @param verifyKey       Ed25519 公钥（X.509/SPKI Base64）；为空时由私钥推导
 * @param keyDir          自动生成密钥的落盘目录
 * @param issuanceEnabled 是否开放签发接口（厂商侧能力，院内应关闭）
 * @param graceDays       到期宽限期（天），宽限期内只告警
 * @param enforce         是否由网关强制拦截未授权模块（默认关闭）
 */
@ConfigurationProperties(prefix = "medassist.license")
public record LicenseProperties(
        String instanceCode,
        String signingKey,
        String verifyKey,
        String keyDir,
        boolean issuanceEnabled,
        int graceDays,
        boolean enforce
) {

    public LicenseProperties {
        instanceCode = blank(instanceCode) ? "MEDASSIST-DEV-0001" : instanceCode.trim();
        keyDir = blank(keyDir) ? "data/license" : keyDir.trim();
        if (graceDays < 0 || graceDays > 365) {
            graceDays = 30;
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
