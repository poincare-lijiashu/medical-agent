package com.medassist.gateway.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medassist.common.security.InternalAuthProperties;
import com.medassist.common.security.InternalKeySet;
import com.medassist.common.security.InternalSignature;
import com.medassist.gateway.filter.InternalSignatureGlobalFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 网关签名组件装配（MF-07 阶段一 / 阶段二）。
 *
 * <p>网关是 Reactive 应用，不加载 common 的 Servlet 自动配置，因此这里单独绑定属性并做启动守卫：
 * 只要 {@code mode != off} 就必须有可用签名材料，否则网关拒绝启动——
 * 避免"签名开关打开但密钥没挂载"导致下游全部 403 却难以定位。
 *
 * <p>签名算法由 {@code medassist.internal-auth.gateway-signing} 决定：
 * <ul>
 *   <li>{@code hmac}（默认，v1）：用 keys-json 的 active 密钥；</li>
 *   <li>{@code ed25519}（v2，推荐）：用 {@code gateway-private-key}（PKCS#8 base64），
 *       <b>私钥只存在于网关</b>，业务服务只持公钥。</li>
 * </ul>
 */
@Configuration
@EnableConfigurationProperties(InternalAuthProperties.class)
public class GatewayInternalSignConfig {

    private static final Logger log = LoggerFactory.getLogger(GatewayInternalSignConfig.class);

    @Bean
    public InternalSignatureGlobalFilter internalSignatureGlobalFilter(InternalAuthProperties props) {
        String mode = props.modeOrDefault();
        if (!InternalAuthProperties.OFF.equals(mode)
                && !InternalAuthProperties.LOG.equals(mode)
                && !InternalAuthProperties.ENFORCE.equals(mode)) {
            throw new IllegalStateException(
                    "medassist.internal-auth.mode 非法（" + mode + "）：只允许 off / log / enforce，网关拒绝启动");
        }
        String signing = props.gatewaySigningOrDefault();
        if (InternalAuthProperties.OFF.equals(mode)) {
            return new InternalSignatureGlobalFilter(props, InternalKeySet.empty());
        }
        if (InternalSignature.GATEWAY_SIGNING_ED25519.equals(signing)) {
            // 私钥缺失/格式错误在启动期直接失败：否则网关能起来但每个请求都不签名，enforce 下全站 403
            java.security.PrivateKey privateKey =
                    InternalSignature.parsePrivateKey(props.gatewayPrivateKey());
            log.info("gateway.internal-sign mode={} signing=ed25519 keyId={}", mode,
                    props.gatewayKeyIdOrDefault());
            return new InternalSignatureGlobalFilter(props, InternalKeySet.empty(), privateKey);
        }

        InternalKeySet keySet = InternalKeySet.parse(props.keysJson(), new ObjectMapper());
        if (!keySet.configured()) {
            throw new IllegalStateException(
                    "网关已启用服务间签名（medassist.internal-auth.mode=" + mode
                            + "）但未配置 keys-json（INTERNAL_HMAC_KEYS），网关拒绝启动");
        }
        // active 必须真实存在：拼错时网关能正常启动但每个请求都不签名，
        // enforce 下全站 403（fail-closed 但自我瘫痪，第五轮 R5-S2）
        if (keySet.secret(keySet.activeKeyId()) == null) {
            throw new IllegalStateException(
                    "网关 keys-json 的 active（" + keySet.activeKeyId()
                            + "）不在 keys 中，网关将无法为任何请求签名，拒绝启动");
        }
        log.info("gateway.internal-sign mode={} signing=hmac keyId={} keys={}", mode,
                keySet.activeKeyId(), "configured");
        return new InternalSignatureGlobalFilter(props, keySet);
    }
}
