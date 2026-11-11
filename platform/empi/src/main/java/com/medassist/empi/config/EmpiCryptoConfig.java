package com.medassist.empi.config;

import com.medassist.common.security.FieldCipher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * 患者敏感字段加解密 Bean。
 *
 * <p>实现位于 {@code platform-common}，与历史数据迁移服务共用同一份算法与配置键，
 * 避免"在线建档"和"迁移建档"产生两套互不兼容的密文/查重哈希。
 */
@Configuration
public class EmpiCryptoConfig {

    @Bean
    public FieldCipher fieldCipher(@Value("${empi.field-key:}") String base64Key,
                                   @Value("${empi.hash-salt:}") String hashSalt,
                                   Environment environment) {
        return new FieldCipher(base64Key, hashSalt, environment);
    }
}
