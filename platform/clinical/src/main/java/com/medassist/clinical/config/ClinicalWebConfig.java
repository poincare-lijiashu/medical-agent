package com.medassist.clinical.config;

import com.medassist.common.security.DataScopeArgumentResolver;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * 注册 {@code DataScope} 参数解析器：Controller 方法声明 {@code DataScope scope} 即可拿到行级数据范围。
 *
 * <p>各业务服务都需要同样的一段注册；后续可以抽到 common 的自动配置里，
 * 现在先显式写在服务内，避免"共享配置悄悄生效"带来的排查困难。
 */
@Configuration
public class ClinicalWebConfig implements WebMvcConfigurer {

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new DataScopeArgumentResolver());
    }
}
