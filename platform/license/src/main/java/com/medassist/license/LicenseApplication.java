package com.medassist.license;

import com.medassist.common.web.TraceMdcFilter;
import com.medassist.license.config.LicenseProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;

/**
 * 授权与许可服务（§279 / 计划书 W9.2）。
 *
 * <p>把"这套系统卖给了谁、买了什么、用到什么时候、超没超配额"做成可自证的系统能力：
 * <ul>
 *   <li>授权书：厂商用 Ed25519 私钥签发，院内只持公钥验签，内容不可篡改；</li>
 *   <li>实例绑定：授权书绑定部署实例码，防止一张授权书多院复用；</li>
 *   <li>模块权益：哪些业务模块可用，按版本矩阵 + 授权书模块清单求交；</li>
 *   <li>配额：床位/账号/AI 调用上限按日快照对照，超限只告警不阻断（临床连续性优先）；</li>
 *   <li>全离线：不依赖在线激活服务，验签在本地完成。</li>
 * </ul>
 *
 * <p>边界：本服务只做"授权事实"，不做计费与账单；模块门禁的强制执行在网关，
 * 由 {@code medassist.license.enforce} 控制（默认关闭，先观察再收紧）。
 */
@SpringBootApplication
@EnableConfigurationProperties(LicenseProperties.class)
public class LicenseApplication {

    public static void main(String[] args) {
        SpringApplication.run(LicenseApplication.class, args);
    }

    /** 把网关注入的 X-Trace-Id 写入日志 MDC，实现 trace ↔ log 关联（M12）。 */
    @Bean
    public FilterRegistrationBean<TraceMdcFilter> traceMdcFilter() {
        FilterRegistrationBean<TraceMdcFilter> registration = new FilterRegistrationBean<>(new TraceMdcFilter());
        registration.setOrder(Integer.MIN_VALUE + 10);
        registration.addUrlPatterns("/*");
        return registration;
    }

    /** 统一时钟：授权阶段（PENDING/GRACE/EXPIRED）判定必须可注入，测试才能固定时间。 */
    @Bean
    public java.time.Clock clock() {
        return java.time.Clock.systemUTC();
    }
}
