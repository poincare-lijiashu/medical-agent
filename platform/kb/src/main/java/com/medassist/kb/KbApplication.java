package com.medassist.kb;

import com.medassist.common.web.TraceMdcFilter;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 知识库服务：知识空间隔离 → 文档入库 → 切片 → 混合检索与引用溯源。
 *
 * <p>为什么单独一个域而不是塞进病历域：
 * <ul>
 *   <li>知识空间的隔离边界与"患者数据"完全不同（按空间/租户授权，而不是按患者/科室）；</li>
 *   <li>切片与索引的写放大很高，独立库表与独立连接池才不会拖垮临床写入；</li>
 *   <li>检索是读多写少、可缓存的负载，后续要独立扩副本与限流。</li>
 * </ul>
 *
 * <p>本批**不包含**生成式问答：没有配置向量/大模型时只做关键词检索，
 * 不伪造"AI 回答"（去 demo 化的底线：宁可少一个功能，也不产出不可信内容）。
 */
@SpringBootApplication
@ConfigurationPropertiesScan("com.medassist.kb.config")
@EnableScheduling
public class KbApplication {

    public static void main(String[] args) {
        SpringApplication.run(KbApplication.class, args);
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
