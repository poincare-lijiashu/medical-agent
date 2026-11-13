package com.medassist.common.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medassist.common.security.InternalAuthProperties;
import com.medassist.common.security.InternalCallerSigner;
import com.medassist.common.security.InternalKeySet;
import com.medassist.common.security.SecurityDefaults;
import com.medassist.common.web.ActorGuardFilter;
import com.medassist.common.web.InternalAuthFilter;
import com.medassist.common.web.NonceStore;
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
        // P0-8：体绑定分两条通道各自生效——网关通道（v1b/v2b）与调用方通道（v1i2）。
        // 因此"只开体绑定、暂不强制 /internal 调用方签名"是合法灰度组合：
        // 网关流量已必须带体版本，服务→服务方向尚未强制，不是配置矛盾。
        if (props.bodyHashRequiredOrDefault() && !required) {
            log.info("internal-auth.body-hash-required=true 但 internal-paths-signature-required=false："
                    + "仅网关通道强制带体签名（v1b/v2b），调用方通道仍处于灰度（v1i 可接受）");
        }
        log.info("internal-auth.caller={} signing={} bodyHash={}",
                caller, secret != null ? "configured" : "none",
                props.bodyHashRequiredOrDefault() ? "v1i2" : "v1i");
        return InternalCallerSigner.of(caller, secret, props.bodyHashRequiredOrDefault());
    }

    @Bean
    @ConditionalOnMissingBean
    public InternalAuthFilter internalAuthFilter(InternalAuthProperties props, InternalKeySet keySet,
                                                 Environment environment,
                                                 ObjectProvider<MeterRegistry> registryProvider,
                                                 ObjectProvider<NonceStore> nonceStoreProvider) {
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
        // log 模式校验失败仍放行，等价于"签名形同虚设"：绕过网关直连业务端口即可伪造 X-Actor 读 PHI。
        // 生产/预发布只允许 enforce；灰度请在非生产环境完成。
        if (InternalAuthProperties.LOG.equals(mode) && SecurityDefaults.isProduction(environment)) {
            throw new IllegalStateException(
                    "生产/预发布环境不允许使用 log 模式（medassist.internal-auth.mode=log），"
                            + "请改为 enforce 后再启动");
        }
        // 评测 S-01（Critical）：mode=off/log 时 X-Actor / X-Permissions 完全由请求头决定。
        // 只要端口对非回环地址可达，任何内网主机都能伪造身份读取 PHI，且审计会记入伪造的 actor。
        // 因此非 enforce 形态必须限定为"只有本机能连"：要么监听回环地址，要么显式声明处于隔离网络。
        if (!InternalAuthProperties.ENFORCE.equals(mode)) {
            String bind = environment.getProperty("server.address", "");
            boolean loopback = "127.0.0.1".equals(bind) || "::1".equals(bind)
                    || "localhost".equalsIgnoreCase(bind);
            boolean isolatedNetwork = Boolean.parseBoolean(environment.getProperty(
                    "medassist.internal-auth.unverified-allow-non-loopback", "false"));
            // 只在"固定端口对外监听"时生效：测试上下文（无 server.port）与随机端口（0）
            // 不会固定暴露，不参与该守卫，避免把单测环境打红。
            String port = environment.getProperty("server.port", "").trim();
            boolean listeningOnFixedPort = !port.isEmpty() && !"0".equals(port);
            if (listeningOnFixedPort && !loopback && !isolatedNetwork) {
                throw new IllegalStateException(
                        "medassist.internal-auth.mode=" + mode + " 且未限定监听回环地址"
                                + "（当前 server.address=" + (bind == null || bind.isBlank() ? "<未配置=全网卡>" : bind)
                                + "）：请求头身份可被任意内网主机伪造，服务拒绝启动。三者选一："
                                + "① 设置 server.address=127.0.0.1（单机/演示形态）；"
                                + "② 改用 mode=enforce 并配置 keys-json / gateway-public-key；"
                                + "③ 确认处于隔离网络后设置 "
                                + "medassist.internal-auth.unverified-allow-non-loopback=true");
            }
            if (isolatedNetwork && !loopback) {
                log.warn("internal-auth.mode={} 未验签且监听非回环地址：已由 "
                        + "unverified-allow-non-loopback 放行，请确保网络不可被横向访问", mode);
            }
        }
        // P0-9：/internal/** 只靠单值共享令牌时，一处令牌泄露即可冒充任意调用方读 PHI。
        // 生产必须要求调用方签名（按 caller 分配密钥），与 mode=off 同规格 fail-fast。
        if (SecurityDefaults.isProduction(environment)
                && !props.internalPathsSignatureRequiredOrDefault()) {
            throw new IllegalStateException(
                    "生产/预发布环境必须开启 /internal/** 调用方签名"
                            + "（medassist.internal-auth.internal-paths-signature-required=true），服务拒绝启动");
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

        // P0-S5：多副本部署注入共享 nonce 存储（nonce-store=jdbc）；未配置时为 null → 进程内去重
        NonceStore nonceStore = nonceStoreProvider.getIfAvailable();
        log.info("internal-auth.mode={} keys={} gatewayPublicKey={} clockSkew={}s "
                        + "internalPathsRequired={} internalKeys={} nonceStore={}",
                mode, keySet.configured() ? "configured" : "none",
                gatewayPublicKey != null ? "configured" : "none",
                props.clockSkewSecondsOrDefault(), props.internalPathsSignatureRequiredOrDefault(),
                keySet.callerConfigured() ? "configured" : "none",
                nonceStore != null ? "shared" : "in-memory");
        return new InternalAuthFilter(props, keySet, registryProvider.getIfAvailable(),
                gatewayPublicKey, nonceStore);
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
