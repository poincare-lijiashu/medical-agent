package com.medassist.emr;

import com.medassist.common.web.TraceMdcFilter;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;

/**
 * 病历服务（HIS 业务域第四段）：结构化模板 → 书写 → 提交 → 签名 → 归档，修订留痕 + 质控。
 *
 * <p>不可篡改原则：
 * <ul>
 *   <li>签名时对渲染文本计算 SHA-256 并入库，事后可校验内容是否被改；</li>
 *   <li>签名后只能走"修订"（追加版本 + 保留历史），不能直接改正文；</li>
 *   <li>归档是终态，归档后不允许任何修改。</li>
 * </ul>
 */
@SpringBootApplication
@ConfigurationPropertiesScan("com.medassist.emr.config")
public class EmrApplication {

    public static void main(String[] args) {
        SpringApplication.run(EmrApplication.class, args);
    }

    @Bean
    public FilterRegistrationBean<TraceMdcFilter> traceMdcFilter() {
        FilterRegistrationBean<TraceMdcFilter> registration =
                new FilterRegistrationBean<>(new TraceMdcFilter());
        registration.setOrder(Integer.MIN_VALUE + 10);
        registration.addUrlPatterns("/*");
        return registration;
    }
}
