package com.medassist.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 平台统一网关。
 *
 * <p>职责（计划书 D2.2 接入层 / P0）：
 * <ul>
 *   <li>统一鉴权前置：校验 JWT 与角色/权限，下游服务只信任网关注入的身份头；</li>
 *   <li>路由与版本管理：v1（现行 Python 服务）与 v2（Java 微服务）双轨并行；</li>
 *   <li>限流与熔断：Sentinel 按路由 QPS；</li>
 *   <li>审计埋点：注入 traceId / channel / tool，供下游审计落库；</li>
 *   <li>令牌撤销名单增量同步（P1，定时任务）。</li>
 * </ul>
 */
@SpringBootApplication
@EnableScheduling
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
