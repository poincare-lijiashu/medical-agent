package com.medassist.ops;

import com.medassist.common.web.TraceMdcFilter;
import com.medassist.ops.config.OpsProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 运营支撑服务（§271 / 计划书 G7）。
 *
 * <p>医院系统上线后，真正决定"能不能长期用"的不是功能数量，而是：
 * 出了问题谁接、多久响应、多久解决、超时谁升级、事后怎么复盘。
 * 本服务把这一层做成可审计的系统能力，而不是散落的微信群和 Excel：
 * <ul>
 *   <li>服务目录：每个平台服务有明确的责任组与优先级；</li>
 *   <li>SLA：按优先级配置响应/解决时限，支持营业时间日历与节假日；</li>
 *   <li>工单：创建 → 派单 → 接单 → 处理 → 挂起/恢复 → 解决 → 关闭/重开，全程留痕；</li>
 *   <li>超时扫描：定时标记首响/解决超时，形成可统计的违约事件；</li>
 *   <li>看板：积压、超时率、首响时长、MTTR，按服务/优先级拆解。</li>
 * </ul>
 *
 * <p>边界：不替代监控告警（Prometheus/SkyWalking）与即时通讯；本服务接收告警转工单，
 * 并成为"告警之后的人的处理过程"的真源。
 */
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(OpsProperties.class)
public class OpsApplication {

    public static void main(String[] args) {
        SpringApplication.run(OpsApplication.class, args);
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
