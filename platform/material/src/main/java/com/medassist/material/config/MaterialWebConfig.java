package com.medassist.material.config;

import com.medassist.common.security.DataScopeArgumentResolver;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * 注册 {@code DataScope} 参数解析器：追溯接口据此决定患者号是否明文返回。
 *
 * <p>物资域不做科室行级隔离（物资科需要跨科室召回），数据范围只用于 PHI 掩码。
 */
@Configuration
public class MaterialWebConfig implements WebMvcConfigurer {

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new DataScopeArgumentResolver());
    }
}
