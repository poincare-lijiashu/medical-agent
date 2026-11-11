package com.medassist.fhir;

import com.medassist.common.web.TraceMdcFilter;
import com.medassist.fhir.config.FhirProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;

/**
 * FHIR R4 开放层。
 *
 * <p>定位：把院内既有域模型（EMPI / 临床 / 医技 / 药事）投影为标准 FHIR R4 资源，
 * 供 HIS/LIS/PACS/区域平台与第三方应用按国际标准读取；<b>本期只读</b>，
 * 写入仍走各业务域的既有命令接口（审批、库存、计费等规则不能绕过）。
 *
 * <p>为什么单独成一个服务而不是塞进 clinical：
 * <ul>
 *   <li>FHIR 是**对外协议**，版本升级（R4 → R5）与业务域发布节奏不同；</li>
 *   <li>对外暴露面需要单独的网络策略、限流与审计口径；</li>
 *   <li>读模型可以指向只读副本，不占业务库主连接池。</li>
 * </ul>
 */
@SpringBootApplication
@EnableConfigurationProperties(FhirProperties.class)
public class FhirApplication {

    public static void main(String[] args) {
        SpringApplication.run(FhirApplication.class, args);
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
