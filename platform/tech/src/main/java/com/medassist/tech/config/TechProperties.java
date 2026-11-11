package com.medassist.tech.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

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
     * @param dimse              DIMSE（C-ECHO/C-FIND/C-STORE/C-MOVE）网关配置
     */
    public record Pacs(String baseUrl, Duration timeout, String authHeader, Long maxInstanceBytes,
                       Dimse dimse) {

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

        public Dimse dimseOrDefault() {
            return dimse == null
                    ? new Dimse(false, null, null, null, null, null, null, null, null)
                    : dimse;
        }
    }

    /**
     * DIMSE 网关配置。
     *
     * <p>安全边界：远端主机、被叫 AE 与允许的 C-MOVE 目的地**只能来自配置**，
     * 不接受请求参数——否则本接口会变成"任意 DICOM 目标转发器"（SSRF 的 DICOM 版本）。
     *
     * @param enabled          是否启用；关闭时相关接口返回 503（未配置，而不是报错）
     * @param host             PACS/设备 DICOM 主机
     * @param port             DICOM 端口（默认 4242）
     * @param calledAe         被叫 AE Title（默认 MEDAGENT）
     * @param callingAe        本端 AE Title（默认 MEDASSIST_HIS）
     * @param connectTimeout   连接超时（默认 5s）
     * @param requestTimeout   单次请求超时（默认 30s）
     * @param maxFileBytes     C-STORE 单文件大小上限（默认 64MB）
     * @param moveDestinations 允许的 C-MOVE 目的地 AE 白名单（逗号分隔；为空则拒绝所有 MOVE）
     */
    public record Dimse(Boolean enabled, String host, Integer port, String calledAe, String callingAe,
                        Duration connectTimeout, Duration requestTimeout, Long maxFileBytes,
                        List<String> moveDestinations) {

        public boolean enabledOrDefault() {
            return Boolean.TRUE.equals(enabled);
        }

        public String hostOrEmpty() {
            return host == null ? "" : host.trim();
        }

        public int portOrDefault() {
            return port == null || port <= 0 || port > 65535 ? 4242 : port;
        }

        public String calledAeOrDefault() {
            return calledAe == null || calledAe.isBlank() ? "MEDAGENT" : calledAe.trim();
        }

        public String callingAeOrDefault() {
            return callingAe == null || callingAe.isBlank() ? "MEDASSIST_HIS" : callingAe.trim();
        }

        public Duration connectTimeoutOrDefault() {
            return connectTimeout == null || connectTimeout.isNegative() || connectTimeout.isZero()
                    ? Duration.ofSeconds(5) : connectTimeout;
        }

        public Duration requestTimeoutOrDefault() {
            return requestTimeout == null || requestTimeout.isNegative() || requestTimeout.isZero()
                    ? Duration.ofSeconds(30) : requestTimeout;
        }

        public long maxFileBytesOrDefault() {
            return maxFileBytes == null || maxFileBytes <= 0
                    ? 64L * 1024 * 1024 : maxFileBytes;
        }

        /** 允许的 C-MOVE 目的地（大写、去重）；空集表示全部拒绝。 */
        public Set<String> allowedMoveDestinations() {
            if (moveDestinations == null) {
                return Set.of();
            }
            return moveDestinations.stream()
                    .filter(v -> v != null && !v.isBlank())
                    .map(v -> v.trim().toUpperCase(Locale.ROOT))
                    .collect(Collectors.toUnmodifiableSet());
        }
    }

    public int criticalTimeoutMinutesOrDefault() {
        return criticalTimeoutMinutes == null || criticalTimeoutMinutes <= 0
                ? 30 : criticalTimeoutMinutes;
    }

    /** PACS 配置；未配置时返回一个可安全读取的空配置（不在此处抛错，便于服务启动）。 */
    public Pacs pacsOrDefault() {
        return pacs == null ? new Pacs(null, null, null, null, null) : pacs;
    }
}
