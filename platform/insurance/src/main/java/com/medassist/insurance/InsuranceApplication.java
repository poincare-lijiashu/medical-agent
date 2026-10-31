package com.medassist.insurance;

import com.medassist.common.web.TraceMdcFilter;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;

/**
 * 医保服务（HIS 业务域第四段）：医保目录 → 院内映射 → 参保登记 → 结算清单测算。
 *
 * <p>边界与原则：
 * <ul>
 *   <li>收费明细唯一真源是临床域，本服务只拉取、不复制、不落收费表；</li>
 *   <li>金额全部服务端计算（目录支付类别 + 参保待遇），请求体不接受任何金额；</li>
 *   <li>与省级医保平台的真实结算需专网/前置机/证书，本服务只做本地可验收部分，接口边界清晰；</li>
 *   <li>清单确认后不可再改；重算只允许 DRAFT，且带版本号 CAS。</li>
 * </ul>
 */
@SpringBootApplication
@ConfigurationPropertiesScan("com.medassist.insurance.config")
public class InsuranceApplication {

    public static void main(String[] args) {
        SpringApplication.run(InsuranceApplication.class, args);
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
