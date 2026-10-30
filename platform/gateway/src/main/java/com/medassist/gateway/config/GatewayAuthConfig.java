package com.medassist.gateway.config;

import com.medassist.common.security.SecurityDefaults;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * 网关 JWT 校验组件装配。
 *
 * <p>校验在网关本地完成（不回调 auth）：避免每个请求多一次网络往返与单点故障；
 * 代价是令牌在 TTL 内无法即时撤销，见 README「已知边界」。
 */
@Configuration
@EnableConfigurationProperties(GatewayAuthProperties.class)
public class GatewayAuthConfig {

    private static final Logger log = LoggerFactory.getLogger(GatewayAuthConfig.class);

    /** HS256 密钥最小字节数（256 位）。 */
    private static final int MIN_SECRET_BYTES = 32;

    @Bean
    public JwtDecoder jwtDecoder(GatewayAuthProperties props) {
        String secret = props.secret();
        byte[] secretBytes = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "JWT 密钥未配置或长度不足 " + MIN_SECRET_BYTES + " 字节，网关拒绝启动（请设置环境变量 JWT_SECRET）");
        }
        if (SecurityDefaults.DEV_JWT_SECRET.equals(secret)) {
            log.warn("网关正在使用本地开发默认 JWT 密钥，生产环境必须通过环境变量 JWT_SECRET 覆盖真实密钥");
        }

        SecretKey key = new SecretKeySpec(secretBytes, "HmacSHA256");
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        // 固定 iss + exp 校验：拒绝其他系统签发的令牌与本平台过期令牌
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(props.issuer()));
        return decoder;
    }
}
