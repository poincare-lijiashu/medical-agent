package com.medassist.tech.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 医技配置。
 *
 * @param empi                    EMPI 内网接口（开单前校验患者）
 * @param criticalTimeoutMinutes  危急值确认时限（分钟），超过则查询接口标记 overdue
 * @param pacs                    DICOMweb（PACS）调阅配置；baseUrl 为空表示未接入
 */
@ConfigurationProperties("tech")
public record TechProperties(Empi empi, Integer criticalTimeoutMinutes, Pacs pacs) {

    public record Empi(String baseUrl, String internalToken, Duration timeout) {
    }

    /**
     * DICOMweb 接入参数。
     *
     * @param baseUrl            PACS DICOMweb 根地址（如 http://pacs.local/dicom-web）；为空则调阅接口返回 503
     * @param timeout            连接与读取超时
     * @param authHeader         可选 Authorization 头原样值（如 Basic xxx / Bearer xxx），为空不发送
     * @param maxInstanceBytes   单张影像允许的最大字节数，防止异常大对象打爆内存
     */
    public record Pacs(String baseUrl, Duration timeout, String authHeader, Long maxInstanceBytes) {

        public boolean configured() {
            return baseUrl != null && !baseUrl.isBlank();
        }

        public Duration timeoutOrDefault() {
            return timeout == null || timeout.isNegative() || timeout.isZero()
                    ? Duration.ofSeconds(10) : timeout;
        }

        public long maxInstanceBytesOrDefault() {
            return maxInstanceBytes == null || maxInstanceBytes <= 0
                    ? 64L * 1024 * 1024 : maxInstanceBytes;
        }

        public String authHeaderOrEmpty() {
            return authHeader == null ? "" : authHeader.trim();
        }
    }

    public int criticalTimeoutMinutesOrDefault() {
        return criticalTimeoutMinutes == null || criticalTimeoutMinutes <= 0
                ? 30 : criticalTimeoutMinutes;
    }

    /** PACS 配置；未配置时返回一个可安全读取的空配置（不在此处抛错，便于服务启动）。 */
    public Pacs pacsOrDefault() {
        return pacs == null ? new Pacs(null, null, null, null) : pacs;
    }
}
