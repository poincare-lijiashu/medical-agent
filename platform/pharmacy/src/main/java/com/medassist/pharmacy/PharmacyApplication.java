package com.medassist.pharmacy;

import com.medassist.common.web.TraceMdcFilter;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 药房服务（HIS 业务域第二段）：药品字典 → 入库批次 → 处方审核 → 发药/退药。
 *
 * <p>边界与原则：
 * <ul>
 *   <li>药品字典是<b>价格与医保编码的唯一真源</b>之一，但收费金额由 clinical 侧按医嘱计价，
 *       本服务只提供"处方金额"用于对账，不直接改收费单；</li>
 *   <li>库存扣减必须走数据库原子更新（{@code quantity >= ?}），不依赖"先查后改"；</li>
 *   <li>发药按近效期优先（FEFO），过期批次永不出库；</li>
 *   <li>患者主索引仍在 EMPI，本服务只存 patientId 引用与 patientNo 快照。</li>
 * </ul>
 */
@SpringBootApplication
@ConfigurationPropertiesScan("com.medassist.pharmacy.config")
@EnableScheduling
public class PharmacyApplication {

    public static void main(String[] args) {
        SpringApplication.run(PharmacyApplication.class, args);
    }

    /** 把网关注入的 X-Trace-Id 写入日志 MDC（与 auth/empi/clinical 一致）。 */
    @Bean
    public FilterRegistrationBean<TraceMdcFilter> traceMdcFilter() {
        FilterRegistrationBean<TraceMdcFilter> registration =
                new FilterRegistrationBean<>(new TraceMdcFilter());
        registration.setOrder(Integer.MIN_VALUE + 10);
        registration.addUrlPatterns("/*");
        return registration;
    }
}
