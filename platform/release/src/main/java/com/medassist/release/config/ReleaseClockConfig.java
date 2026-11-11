package com.medassist.release.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 注入时钟：发布/冻结窗口判断依赖"现在"，测试必须能固定时间。
 */
@Configuration
public class ReleaseClockConfig {

    @Bean
    public Clock releaseClock() {
        return Clock.systemUTC();
    }
}
