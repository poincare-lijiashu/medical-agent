package com.medassist.common.config;

import com.medassist.common.security.InternalAuthProperties;
import com.medassist.common.web.JdbcNonceStore;
import com.medassist.common.web.NonceStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 共享 nonce 存储装配（P0-S5）：多副本部署时把进程内去重换成数据库表。
 *
 * <p>独立成一个自动配置类，是为了让**没有 JDBC 的服务（如网关）**在类加载期就跳过：
 * 用 {@code @ConditionalOnClass(name = ...)} 走 ASM 元数据判断，方法签名里的
 * {@link JdbcClient} 只在条件命中后才解析，避免无 JDBC 依赖的服务启动即
 * {@code NoClassDefFoundError}。
 *
 * <p>开启：{@code medassist.internal-auth.nonce-store=jdbc}（默认 memory）。
 * 表名固定为 {@code <schema>.internal_nonce}，schema 取 {@code medassist.internal-auth.nonce-schema}，
 * 未配置时取本服务 {@code spring.flyway.default-schema}（各服务的迁移据此建表）。
 */
@AutoConfiguration
@ConditionalOnClass(name = "org.springframework.jdbc.core.simple.JdbcClient")
@ConditionalOnProperty(prefix = "medassist.internal-auth", name = "nonce-store", havingValue = "jdbc")
@EnableConfigurationProperties(InternalAuthProperties.class)
public class JdbcNonceStoreAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(JdbcNonceStoreAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean(NonceStore.class)
    public NonceStore nonceStore(JdbcClient jdbc, InternalAuthProperties props, Environment environment,
                                 @Value("${medassist.internal-auth.nonce-schema:}") String configuredSchema) {
        String schema = configuredSchema;
        if (schema == null || schema.isBlank()) {
            schema = environment.getProperty("spring.flyway.default-schema");
        }
        if (schema == null || schema.isBlank()) {
            // 宁可不启动：用错 schema 会让 nonce 落到 public，多个服务共享一张表且无人迁移
            throw new IllegalStateException(
                    "medassist.internal-auth.nonce-store=jdbc 但无法确定表所属 schema："
                            + "请配置 medassist.internal-auth.nonce-schema 或 spring.flyway.default-schema，服务拒绝启动");
        }
        log.info("internal-auth.nonce-store=jdbc schema={} ttl={}s",
                schema, props.nonceTtlSecondsOrDefault());
        return new JdbcNonceStore(jdbc, schema, props.nonceTtlSecondsOrDefault());
    }
}
