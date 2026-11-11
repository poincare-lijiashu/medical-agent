package com.medassist.release;

import com.medassist.common.web.TraceMdcFilter;
import com.medassist.release.config.ReleaseProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;

/**
 * 版本与补丁机制（§273 / 计划书 G7）。
 *
 * <p>医院系统的发布不是"把 jar 换掉"：一个版本要能回答——
 * 它包含哪些组件、和哪些版本兼容、有没有破坏性变更、补丁分级是什么、谁批的、
 * 哪个窗口允许发布、发布时检查了什么、出问题回滚到哪一版。
 * 本服务把这些事实做成可查询、可审计的系统记录，而不是发布当晚的聊天记录。
 */
@SpringBootApplication
@EnableConfigurationProperties(ReleaseProperties.class)
public class ReleaseApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReleaseApplication.class, args);
    }

    @Bean
    public FilterRegistrationBean<TraceMdcFilter> traceMdcFilter() {
        FilterRegistrationBean<TraceMdcFilter> registration = new FilterRegistrationBean<>(new TraceMdcFilter());
        registration.setOrder(Integer.MIN_VALUE + 10);
        registration.addUrlPatterns("/*");
        return registration;
    }
}
