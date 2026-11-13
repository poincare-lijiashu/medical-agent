package com.medassist.common.config;

import com.medassist.common.security.SecurityDefaults;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;

import java.util.ArrayList;
import java.util.List;

/**
 * 开发默认口令守卫（P1-S5）。
 *
 * <p>背景：{@code application.yml} 里的 {@code ${PG_PASSWORD:medagent123}} /
 * {@code ${NACOS_PASSWORD:nacos}} 是为了本地 {@code java -jar} 免配置即可联调；
 * 但一旦被带进预发布/生产，数据库与注册中心就处于"公开口令"状态，
 * 仅靠启动日志告警是无效防线（与 JWT 默认密钥同规格处理）。
 *
 * <p>行为：
 * <ul>
 *   <li>生产/预发布（{@link SecurityDefaults#isProduction}）命中默认值 → <b>拒绝启动</b>；</li>
 *   <li>非生产 → 仅告警，不影响本地开发。</li>
 * </ul>
 */
@AutoConfiguration
public class DevCredentialGuardAutoConfiguration {

    @Bean
    public DevCredentialGuard devCredentialGuard(Environment environment) {
        return new DevCredentialGuard(environment);
    }

    /** 启动期校验器。 */
    public static class DevCredentialGuard implements InitializingBean {

        private static final Logger log = LoggerFactory.getLogger(DevCredentialGuard.class);

        /** 与各服务 application.yml 的默认值保持一致。 */
        static final String DEV_PG_PASSWORD = "medagent123";
        static final String DEV_NACOS_PASSWORD = "nacos";
        /** 评测 D-21：application-dev.yml 里的公开开发令牌，生产出现即为泄露。 */
        static final String DEV_INTERNAL_TOKEN = "medassist-dev-internal-token";

        private final Environment environment;

        DevCredentialGuard(Environment environment) {
            this.environment = environment;
        }

        @Override
        public void afterPropertiesSet() {
            List<String> findings = new ArrayList<>(3);
            if (DEV_PG_PASSWORD.equals(environment.getProperty("spring.datasource.password"))) {
                findings.add("spring.datasource.password");
            }
            boolean nacosEnabled = environment.getProperty(
                    "spring.cloud.nacos.discovery.enabled", Boolean.class, false);
            if (nacosEnabled && DEV_NACOS_PASSWORD.equals(
                    environment.getProperty("spring.cloud.nacos.discovery.password"))) {
                findings.add("spring.cloud.nacos.discovery.password");
            }
            // 评测 D-21：内部接口令牌此前无守卫（JWT/EMPI/PG 都有），
            // 生产一旦沿用仓库里的公开开发令牌，等于"任何人都能调 /internal/**"。
            String leakedKey = findDevInternalTokenKey();
            if (leakedKey != null) {
                findings.add(leakedKey);
            }
            if (findings.isEmpty()) {
                return;
            }
            String detail = "仍在使用开发默认口令：" + String.join("、", findings)
                    + "（请通过 PG_PASSWORD / NACOS_PASSWORD / INTERNAL_API_TOKEN 注入正式凭据）";
            if (SecurityDefaults.isProduction(environment)) {
                throw new IllegalStateException("生产/预发布环境" + detail + "，服务拒绝启动");
            }
            log.warn("dev-credential-guard {}", detail);
        }

        /**
         * 扫描所有配置源，找出"值为开发公开令牌"的内部令牌键。
         *
         * <p>各模块的键名不同（{@code empi.internal-token} / {@code clinical.internal-token} /
         * {@code pharmacy.internal-api-token} …），按后缀匹配比维护一张键名清单更可靠：
         * 新增模块时守卫自动覆盖，不会因为漏登记而失效。
         */
        private String findDevInternalTokenKey() {
            if (!(environment instanceof ConfigurableEnvironment configurable)) {
                return null;
            }
            for (PropertySource<?> source : configurable.getPropertySources()) {
                if (!(source instanceof EnumerablePropertySource<?> enumerable)) {
                    continue;
                }
                for (String name : enumerable.getPropertyNames()) {
                    if (!name.endsWith("internal-token") && !name.endsWith("internal-api-token")) {
                        continue;
                    }
                    Object value = source.getProperty(name);
                    if (value instanceof String text && DEV_INTERNAL_TOKEN.equals(text.trim())) {
                        return name;
                    }
                }
            }
            return null;
        }
    }
}
