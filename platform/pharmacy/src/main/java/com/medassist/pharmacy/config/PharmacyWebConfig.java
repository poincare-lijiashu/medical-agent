package com.medassist.pharmacy.config;

import com.medassist.common.security.DataScopeArgumentResolver;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * 注册 {@code DataScope} 参数解析器：药房接口据此决定是否返回完整患者号。
 *
 * <p>药房不按科室隔离（药师必须能看到全院处方），数据范围在这里只用于 PHI 掩码判断。
 */
@Configuration
public class PharmacyWebConfig implements WebMvcConfigurer {

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new DataScopeArgumentResolver());
    }
}
