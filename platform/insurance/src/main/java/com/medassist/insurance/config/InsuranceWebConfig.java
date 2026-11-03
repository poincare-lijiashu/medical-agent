package com.medassist.insurance.config;

import com.medassist.common.security.DataScopeArgumentResolver;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * 注册 {@code DataScope} 参数解析器（P0-S2/S3）：Controller 方法声明 {@code DataScope scope}
 * 即可拿到 phi:read 与科室集合。
 *
 * <p>insurance 表暂无 dept 列，行级科室过滤待 schema 补列后接入；
 * 本注册先支撑 PHI 掩码（patientNo 无 phi:read 时掩码返回）。
 */
@Configuration
public class InsuranceWebConfig implements WebMvcConfigurer {

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new DataScopeArgumentResolver());
    }
}
