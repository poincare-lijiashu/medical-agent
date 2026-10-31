package com.medassist.common.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medassist.common.security.InternalAuthProperties;
import com.medassist.common.security.InternalKeySet;
import com.medassist.common.security.SecurityDefaults;
import com.medassist.common.web.InternalAuthFilter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * 服务间签名校验自动装配（MF-07 阶段一）。
 *
 * <p>业务服务的主类都在各自包下（{@code com.medassist.clinical} 等），
 * 不会扫描到 {@code com.medassist.common}，因此用 Spring Boot 自动配置注册，
 * 各服务无需改代码即可获得过滤器（`META-INF/spring/...AutoConfiguration.imports`）。
 *
 * <p>启动守卫（与 EMPI FieldCipher 同规格，不允许"配错就静默放行"）：
 * <ul>
 *   <li>mode 非法 → 拒绝启动；</li>
 *   <li>mode=enforce 但未配置 {@code keys-json} → 拒绝启动；</li>
 *   <li>生产/预发布环境 mode=off → 拒绝启动（Helm 默认下发 log/enforce）。</li>
 * </ul>
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = "org.springframework.web.filter.OncePerRequestFilter")
@EnableConfigurationProperties(InternalAuthProperties.class)
public class InternalAuthAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(InternalAuthAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public InternalAuthFilter internalAuthFilter(InternalAuthProperties props, Environment environment,
                                                 ObjectProvider<ObjectMapper> mapperProvider,
                                                 ObjectProvider<MeterRegistry> registryProvider) {
        String mode = props.modeOrDefault();
        if (!InternalAuthProperties.OFF.equals(mode)
                && !InternalAuthProperties.LOG.equals(mode)
                && !InternalAuthProperties.ENFORCE.equals(mode)) {
            throw new IllegalStateException(
                    "medassist.internal-auth.mode 非法（" + mode + "）：只允许 off / log / enforce，服务拒绝启动");
        }

        ObjectMapper mapper = mapperProvider.getIfAvailable(ObjectMapper::new);
        InternalKeySet keySet = InternalKeySet.parse(props.keysJson(), mapper);

        if (InternalAuthProperties.ENFORCE.equals(mode) && !keySet.configured()) {
            throw new IllegalStateException(
                    "medassist.internal-auth.mode=enforce 但未配置 keys-json（INTERNAL_HMAC_KEYS），服务拒绝启动");
        }
        if (InternalAuthProperties.OFF.equals(mode) && SecurityDefaults.isProduction(environment)) {
            throw new IllegalStateException(
                    "生产/预发布环境不允许关闭服务间签名校验（medassist.internal-auth.mode=off），服务拒绝启动");
        }
        if (InternalAuthProperties.LOG.equals(mode) && !keySet.configured()) {
            log.warn("medassist.internal-auth.mode=log 但未配置 keys-json：所有请求都无法验签，仅记录日志");
        }

        log.info("internal-auth.mode={} keys={} clockSkew={}s internalPathsRequired={}",
                mode, keySet.configured() ? "configured" : "none",
                props.clockSkewSecondsOrDefault(), props.internalPathsSignatureRequiredOrDefault());
        return new InternalAuthFilter(props, keySet, registryProvider.getIfAvailable());
    }

    /**
     * 过滤器顺序：跟在各服务的 TraceMdcFilter（{@code Integer.MIN_VALUE + 10}）之后，
     * 保证拒绝日志也能带上 traceId。
     */
    @Bean
    public FilterRegistrationBean<InternalAuthFilter> internalAuthFilterRegistration(
            InternalAuthFilter filter) {
        FilterRegistrationBean<InternalAuthFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(Integer.MIN_VALUE + 20);
        registration.addUrlPatterns("/*");
        return registration;
    }
}
