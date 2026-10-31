package com.medassist.gateway.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medassist.common.security.InternalAuthProperties;
import com.medassist.common.security.InternalKeySet;
import com.medassist.gateway.filter.InternalSignatureGlobalFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 网关签名组件装配（MF-07 阶段一）。
 *
 * <p>网关是 Reactive 应用，不加载 common 的 Servlet 自动配置，因此这里单独绑定属性并做启动守卫：
 * 只要 {@code mode != off} 就必须有可用密钥，否则网关拒绝启动——
 * 避免"签名开关打开但密钥没挂载"导致下游全部 403 却难以定位。
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
        InternalKeySet keySet = InternalKeySet.parse(props.keysJson(), new ObjectMapper());
        if (!InternalAuthProperties.OFF.equals(mode) && !keySet.configured()) {
            throw new IllegalStateException(
                    "网关已启用服务间签名（medassist.internal-auth.mode=" + mode
                            + "）但未配置 keys-json（INTERNAL_HMAC_KEYS），网关拒绝启动");
        }
        // active 必须真实存在：拼错时网关能正常启动但每个请求都不签名，
        // enforce 下全站 403（fail-closed 但自我瘫痪，第五轮 R5-S2）
        if (!InternalAuthProperties.OFF.equals(mode) && keySet.secret(keySet.activeKeyId()) == null) {
            throw new IllegalStateException(
                    "网关 keys-json 的 active（" + keySet.activeKeyId()
                            + "）不在 keys 中，网关将无法为任何请求签名，拒绝启动");
        }
        log.info("gateway.internal-sign mode={} keyId={} keys={}", mode,
                keySet.activeKeyId(), keySet.configured() ? "configured" : "none");
        return new InternalSignatureGlobalFilter(props, keySet);
    }
}
