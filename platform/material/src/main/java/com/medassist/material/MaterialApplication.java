package com.medassist.material;

import com.medassist.common.web.TraceMdcFilter;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 物资耗材服务（G3）：耗材目录 → 批次效期库存 → 科室/手术出库 → 使用追溯与批次召回。
 *
 * <p>边界与原则：
 * <ul>
 *   <li>耗材与药品分开建域：耗材没有处方/审方流程，但**高值耗材必须能追到患者与手术**，
 *       追溯链条是飞检与召回的第一证据，不能只记"出库到科室"；</li>
 *   <li>库存扣减只走数据库原子更新（{@code quantity >= ?}），不做"先查后减"；</li>
 *   <li>出库按近效期优先（FEFO），无菌/植入类过期批次永不发出；</li>
 *   <li>患者主索引仍在 EMPI，本服务只存 patientId 引用与 patientNo 快照，响应按 phi:read 掩码。</li>
 * </ul>
 */
@SpringBootApplication
@ConfigurationPropertiesScan("com.medassist.material.config")
@EnableScheduling
public class MaterialApplication {

    public static void main(String[] args) {
        SpringApplication.run(MaterialApplication.class, args);
    }

    /** 把网关注入的 X-Trace-Id 写入日志 MDC（与其它业务服务一致）。 */
    @Bean
    public FilterRegistrationBean<TraceMdcFilter> traceMdcFilter() {
        FilterRegistrationBean<TraceMdcFilter> registration =
                new FilterRegistrationBean<>(new TraceMdcFilter());
        registration.setOrder(Integer.MIN_VALUE + 10);
        registration.addUrlPatterns("/*");
        return registration;
    }
}
