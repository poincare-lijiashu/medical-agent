package com.medassist.migration.config;

import com.medassist.common.security.FieldCipher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * 患者敏感字段加解密 Bean。
 *
 * <p>与 platform-empi 使用同一份 {@code platform-common} 实现、同一组配置键
 * （{@code EMPI_FIELD_KEY} / {@code EMPI_HASH_SALT}）。迁移写入的证件号/手机号密文与查重哈希
 * 必须能被在线建档与查重逻辑直接使用；换实现或换密钥都会造成"同一个人两条主索引"。
 */
@Configuration
public class MigrationCryptoConfig {

    @Bean
    public FieldCipher fieldCipher(@Value("${empi.field-key:}") String base64Key,
                                   @Value("${empi.hash-salt:}") String hashSalt,
                                   Environment environment) {
        return new FieldCipher(base64Key, hashSalt, environment);
    }
}
