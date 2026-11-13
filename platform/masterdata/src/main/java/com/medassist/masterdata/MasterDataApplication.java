package com.medassist.masterdata;

import com.medassist.common.web.TraceMdcFilter;
import com.medassist.masterdata.config.MasterDataProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 主数据与字典映射治理服务（§269 / 计划书 W4.1）。
 *
 * <p>定位：不复制业务数据，也不取代各域字典表，而是在既有字典之上提供<b>治理层</b>：
 * <ul>
 *   <li>统一主数据域注册与条目索引（一物一码、别名检索、状态与生效区间）；</li>
 *   <li>外部编码映射（HIS/LIS/PACS/医保国家平台/省平台/区域平台），支持一码多源与一源多码；</li>
 *   <li>条目与映射的版本链（追加式、带校验和），任何一次变更可追溯到人、原因与单据；</li>
 *   <li>变更申请与审批（提交人 ≠ 审批人），批量导入必须先预检、再见校验和落库。</li>
 * </ul>
 *
 * <p>为什么单独成一个服务：主数据跨越临床、药事、医技、物资、医保与账号域，
 * 放在任一业务域里都会形成"某个域替别的域做主"的越权；独立服务还可以按只读账号
 * 访问各域字典表，把治理写入限制在 {@code mdm_svc} 内。
 *
 * <p>边界：业务表仍是各自域的真源。本服务不做收费、库存、医嘱等业务写入；
 * 映射解析结果由调用方在业务事务中快照使用，避免"映射改了历史单据跟着变"。
 */
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(MasterDataProperties.class)
public class MasterDataApplication {

    public static void main(String[] args) {
        SpringApplication.run(MasterDataApplication.class, args);
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
