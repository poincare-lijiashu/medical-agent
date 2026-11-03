package com.medassist.common.security;

import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * 把 {@link DataScope} 注入为 Controller 方法参数。
 *
 * <p>用法：Controller 方法直接声明 {@code DataScope scope}，由本解析器从身份头构造。
 * 服务侧只需注册一次：
 * <pre>{@code
 * public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
 *     resolvers.add(new DataScopeArgumentResolver());
 * }
 * }</pre>
 *
 * <p>为什么不做成 ThreadLocal / 全局拦截器：那样"这个查询受不受权限约束"在代码里看不出来。
 * 显式参数让每个受控查询都必须主动声明，漏写会在 review 时被看见。
 */
public class DataScopeArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return DataScope.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                  NativeWebRequest request, WebDataBinderFactory binderFactory) {
        return DataScopeResolver.resolve(
                request.getHeader(AuthHeaders.ACTOR),
                request.getHeader(AuthHeaders.ROLES),
                request.getHeader(AuthHeaders.PERMISSIONS),
                request.getHeader(AuthHeaders.ORG));
    }
}
