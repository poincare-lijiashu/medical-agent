package com.medassist.tech.config;

import com.medassist.common.security.DataScopeArgumentResolver;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * 注册 {@code DataScope} 参数解析器（P0-S3）：Controller 方法声明 {@code DataScope scope}
 * 即可拿到行级数据范围（科室集合 + phi:read）。
 *
 * <p>此前 tech 未注册，申请可跨科室读单——tech 表已有 dept_id 却不过滤，科室隔离形同虚设。
 */
@Configuration
public class TechWebConfig implements WebMvcConfigurer {

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new DataScopeArgumentResolver());
    }
}
