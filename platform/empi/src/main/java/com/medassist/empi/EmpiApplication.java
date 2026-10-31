package com.medassist.empi;

import com.medassist.common.web.TraceMdcFilter;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;

/**
 * 患者主索引（EMPI）服务。
 *
 * <p>P0 阶段交付骨架；后续按计划书 D3/D4 落地建档、合并管理与跨系统标识映射
 * （{@code patient_ids_crosswalk}）。
 */
@SpringBootApplication
public class EmpiApplication {

    public static void main(String[] args) {
        SpringApplication.run(EmpiApplication.class, args);
    }

    /** 把网关注入的 X-Trace-Id 写入日志 MDC，实现 trace ↔ log 关联（M12）。 */
    @Bean
    public FilterRegistrationBean<TraceMdcFilter> traceMdcFilter() {
        FilterRegistrationBean<TraceMdcFilter> registration = new FilterRegistrationBean<>(new TraceMdcFilter());
        registration.setOrder(Integer.MIN_VALUE + 10);
        registration.addUrlPatterns("/*");
        return registration;
    }
}
