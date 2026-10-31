package com.medassist.clinical;

import com.medassist.common.web.TraceMdcFilter;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;

/**
 * 临床核心服务（HIS 业务域第一段）：科室/号源 → 挂号 → 接诊 → 医嘱 → 计费。
 *
 * <p>边界：患者主索引在 EMPI，本服务只持有 patientId 引用与 patientNo 快照；
 * 医保结算、药房发药、住院与病历文书为后续模块（见 docs/CLINICAL-CORE-ROADMAP.md）。
 */
@SpringBootApplication
@ConfigurationPropertiesScan("com.medassist.clinical.config")
public class ClinicalApplication {

    public static void main(String[] args) {
        SpringApplication.run(ClinicalApplication.class, args);
    }

    /** 把网关注入的 X-Trace-Id 写入日志 MDC（与 auth/empi 一致）。 */
    @Bean
    public FilterRegistrationBean<TraceMdcFilter> traceMdcFilter() {
        FilterRegistrationBean<TraceMdcFilter> registration =
                new FilterRegistrationBean<>(new TraceMdcFilter());
        registration.setOrder(Integer.MIN_VALUE + 10);
        registration.addUrlPatterns("/*");
        return registration;
    }
}
