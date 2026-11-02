package com.medassist.common.config;

import com.medassist.common.web.JsonBodyLimitFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;

/**
 * JSON 请求体体积上限的自动装配（P1-6）。
 *
 * <p>各业务服务都依赖 {@code platform-common}，因此在这里注册一次即全平台生效，
 * 不需要逐个服务改配置。阈值可用 {@code medassist.http.max-json-body-bytes} 覆盖。
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class HttpBodyLimitAutoConfiguration {

    /** 默认 2MB：业务 JSON 远小于此，批量数据应走文件导入。 */
    private static final long DEFAULT_MAX_JSON_BYTES = 2L * 1024 * 1024;

    @Bean
    public FilterRegistrationBean<JsonBodyLimitFilter> jsonBodyLimitFilter(
            @Value("${medassist.http.max-json-body-bytes:" + DEFAULT_MAX_JSON_BYTES + "}") long maxBytes) {
        FilterRegistrationBean<JsonBodyLimitFilter> registration =
                new FilterRegistrationBean<>(new JsonBodyLimitFilter(maxBytes));
        // 排在 TraceMdcFilter(MIN+10) 与 InternalAuthFilter(MIN+20) 之后：拒绝日志带 traceId，
        // 且签名校验先于体积判定（未通过来源校验的请求不必暴露上限策略）
        registration.setOrder(Integer.MIN_VALUE + 30);
        return registration;
    }
}
