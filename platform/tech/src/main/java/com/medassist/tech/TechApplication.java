package com.medassist.tech;

import com.medassist.common.web.TraceMdcFilter;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;

/**
 * 医技服务（HIS 业务域第三段）：检查/检验申请 → 标本采集与接收 → 报告 → 危急值闭环。
 *
 * <p>边界与原则：
 * <ul>
 *   <li>申请可由临床医嘱派生，也可医技站手工录入；本服务只存 patientId 引用与 patientNo 快照；</li>
 *   <li>标本条码唯一，采集/接收/拒收全部留痕；</li>
 *   <li>报告修订采用"追加新版本"而不是覆盖历史（医疗文书不可篡改）；</li>
 *   <li>危急值必须由临床确认后才能关闭，未确认的危急值不会随报告归档而消失。</li>
 * </ul>
 */
@SpringBootApplication
@ConfigurationPropertiesScan("com.medassist.tech.config")
public class TechApplication {

    public static void main(String[] args) {
        SpringApplication.run(TechApplication.class, args);
    }

    /** 把网关注入的 X-Trace-Id 写入日志 MDC（与其它服务一致）。 */
    @Bean
    public FilterRegistrationBean<TraceMdcFilter> traceMdcFilter() {
        FilterRegistrationBean<TraceMdcFilter> registration =
                new FilterRegistrationBean<>(new TraceMdcFilter());
        registration.setOrder(Integer.MIN_VALUE + 10);
        registration.addUrlPatterns("/*");
        return registration;
    }
}
