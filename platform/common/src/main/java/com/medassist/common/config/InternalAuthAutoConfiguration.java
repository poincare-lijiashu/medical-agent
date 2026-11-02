package com.medassist.common.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medassist.common.security.InternalAuthProperties;
import com.medassist.common.security.InternalCallerSigner;
import com.medassist.common.security.InternalKeySet;
import com.medassist.common.security.SecurityDefaults;
import com.medassist.common.web.ActorGuardFilter;
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
    public InternalKeySet internalKeySet(InternalAuthProperties props,
                                         ObjectProvider<ObjectMapper> mapperProvider) {
        return InternalKeySet.parse(props.keysJson(), mapperProvider.getIfAvailable(ObjectMapper::new))
                // 阶段二：Helm 按组件只注入"自己的 + 直接调用方的"密钥（R5-M3）
                .withCallerKeys(props.callerKeysOrDefault());
    }

    /**
     * 出站签名器（T5）：按 spring.application.name 推导调用方身份（platform-clinical → clinical）。
     *
     * <p>未配置调用方密钥时返回 disabled（不附加签名头），这样"开关未打开"的阶段行为不变；
     * 但一旦 {@code internal-paths-signature-required=true}，调用方密钥集必须存在，否则拒启。
     */
    @Bean
    @ConditionalOnMissingBean
    public InternalCallerSigner internalCallerSigner(InternalAuthProperties props, InternalKeySet keySet,
                                                     Environment environment) {
        boolean required = props.internalPathsSignatureRequiredOrDefault();
        if (required && !keySet.callerConfigured()) {
            throw new IllegalStateException(
                    "medassist.internal-auth.internal-paths-signature-required=true 但 keys-json 未配置 "
                            + "internal 调用方密钥，服务拒绝启动");
        }
        String caller = props.callerOrNull();
        if (caller == null) {
            caller = deriveCaller(environment.getProperty("spring.application.name"));
        }
        String secret = keySet.callerSecret(caller);
        if (required && secret == null) {
            // 与其余启动守卫同规格：宁可拒绝启动，也不要"能起来但所有内部调用都 403"（第五轮 R5-S3）
            throw new IllegalStateException(
                    "medassist.internal-auth.internal-paths-signature-required=true 但本服务调用方 "
                            + caller + " 无密钥（keys-json.internal." + caller + "），服务拒绝启动");
        }
        return InternalCallerSigner.of(caller, secret);
    }

    @Bean
    @ConditionalOnMissingBean
    public InternalAuthFilter internalAuthFilter(InternalAuthProperties props, InternalKeySet keySet,
                                                 Environment environment,
                                                 ObjectProvider<MeterRegistry> registryProvider) {
        String mode = props.modeOrDefault();
        if (!InternalAuthProperties.OFF.equals(mode)
                && !InternalAuthProperties.LOG.equals(mode)
                && !InternalAuthProperties.ENFORCE.equals(mode)) {
            throw new IllegalStateException(
                    "medassist.internal-auth.mode 非法（" + mode + "）：只允许 off / log / enforce，服务拒绝启动");
        }

        // 公钥在启动期解析：坏密钥必须拒绝启动，而不是让每个请求 403/500（与 v1 同规格）
        java.security.PublicKey gatewayPublicKey = null;
        if (props.gatewayPublicKey() != null && !props.gatewayPublicKey().isBlank()) {
            gatewayPublicKey = com.medassist.common.security.InternalSignature
                    .parsePublicKey(props.gatewayPublicKey());
        }
        // 校验算法取值（网关会用到）；业务服务侧只按请求头版本分支，不受该值影响
        props.gatewaySigningOrDefault();

        if (InternalAuthProperties.ENFORCE.equals(mode) && !keySet.configured()
                && gatewayPublicKey == null) {
            throw new IllegalStateException(
                    "medassist.internal-auth.mode=enforce 但未配置验签材料："
                            + "请配置 keys-json（v1/HMAC）或 gateway-public-key（v2/Ed25519），服务拒绝启动");
        }
        if (InternalAuthProperties.OFF.equals(mode) && SecurityDefaults.isProduction(environment)) {
            throw new IllegalStateException(
                    "生产/预发布环境不允许关闭服务间签名校验（medassist.internal-auth.mode=off），服务拒绝启动");
        }
        if (InternalAuthProperties.LOG.equals(mode) && !keySet.configured() && gatewayPublicKey == null) {
            log.warn("medassist.internal-auth.mode=log 但未配置 keys-json / gateway-public-key："
                    + "所有请求都无法验签，仅记录日志");
        }
        // nonce 表至少要比时间窗活得久，否则窗内已用过的 nonce 提前过期 → 可重放（第五轮 R5-M2）
        long skew = props.clockSkewSecondsOrDefault();
        long ttl = props.nonceTtlSecondsOrDefault();
        if (ttl < 2 * skew + 1) {
            throw new IllegalStateException(
                    "medassist.internal-auth.nonce-ttl-seconds(" + ttl + ") 必须 ≥ 2×clock-skew-seconds("
                            + skew + ")+1，否则时间窗内可用 nonce 会提前失效并允许重放，服务拒绝启动");
        }

        log.info("internal-auth.mode={} keys={} gatewayPublicKey={} clockSkew={}s "
                        + "internalPathsRequired={} internalKeys={}",
                mode, keySet.configured() ? "configured" : "none",
                gatewayPublicKey != null ? "configured" : "none",
                props.clockSkewSecondsOrDefault(), props.internalPathsSignatureRequiredOrDefault(),
                keySet.callerConfigured() ? "configured" : "none");
        return new InternalAuthFilter(props, keySet, registryProvider.getIfAvailable(), gatewayPublicKey);
    }

    /** platform-clinical → clinical；medassist-gateway → gateway；其它原样小写。 */
    static String deriveCaller(String applicationName) {
        if (applicationName == null || applicationName.isBlank()) {
            return null;
        }
        String name = applicationName.trim().toLowerCase();
        if (name.startsWith("platform-")) {
            name = name.substring("platform-".length());
        } else if (name.startsWith("medassist-")) {
            name = name.substring("medassist-".length());
        }
        return name;
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

    /**
     * 写操作必须有操作者身份（E1）：在验签过滤器之后执行，
     * 避免"先要求 X-Actor 再发现请求本身没通过签名"的错误顺序。
     */
    @Bean
    public FilterRegistrationBean<ActorGuardFilter> actorGuardFilterRegistration() {
        FilterRegistrationBean<ActorGuardFilter> registration =
                new FilterRegistrationBean<>(new ActorGuardFilter());
        registration.setOrder(Integer.MIN_VALUE + 30);
        registration.addUrlPatterns("/*");
        return registration;
    }
}
