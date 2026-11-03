package com.medassist.emr.config;

import com.medassist.common.security.DataScopeArgumentResolver;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * 注册 {@code DataScope} 参数解析器：Controller 方法声明 {@code DataScope scope} 即可拿到行级数据范围。
 *
 * <p>病历域的病案首页与病历正文都是 PHI，必须和临床域使用同一套数据范围规则；
 * 这里只做注册，判定逻辑集中在 {@code EmrAccessGuard}。
 */
@Configuration
public class EmrWebConfig implements WebMvcConfigurer {

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new DataScopeArgumentResolver());
    }
}
