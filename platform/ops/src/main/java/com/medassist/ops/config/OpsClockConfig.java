package com.medassist.ops.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 注入时钟而不是到处 {@code Instant.now()}：
 * SLA 的临期/超时/暂停顺延全部依赖"现在"，测试必须能固定时间，否则会变成时间炸弹。
 */
@Configuration
public class OpsClockConfig {

    @Bean
    public Clock opsClock() {
        return Clock.systemUTC();
    }
}
