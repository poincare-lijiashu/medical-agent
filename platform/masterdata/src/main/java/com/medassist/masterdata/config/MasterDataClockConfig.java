package com.medassist.masterdata.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 主数据服务时钟：质量扫描与审计时间统一从这里取，便于测试替换。
 */
@Configuration
public class MasterDataClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
