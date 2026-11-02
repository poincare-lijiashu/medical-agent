package com.medassist.common.security;

import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;
import java.net.URI;
import java.util.Map;

/**
 * 出站请求签名拦截器（MF-07 T5）：只对 {@code /internal/**} 附加调用方签名。
 *
 * <p>之所以在客户端统一拦截而不是逐个接口手写：内部调用分散在 7 个 Http*Client 中，
 * 漏掉任何一处都会在"调用方签名强制"开关打开后变成 403，很难在联调前发现。
 *
 * <p>P0-8：{@link ClientHttpRequestInterceptor#intercept} 的 {@code body} 参数就是
 * **已序列化的请求体字节**，因此这里可以零侵入地把 SHA-256 纳入签名（v1i2），
 * 不需要改任何 Http*Client。
 */
public final class InternalSignatureClientInterceptor implements ClientHttpRequestInterceptor {

    private final InternalCallerSigner signer;

    public InternalSignatureClientInterceptor(InternalCallerSigner signer) {
        this.signer = signer;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body,
                                        ClientHttpRequestExecution execution) throws IOException {
        URI uri = request.getURI();
        String path = uri.getRawPath();
        if (signer.configured() && path != null
                && ("/internal".equals(path) || path.startsWith("/internal/"))) {
            Map<String, String> headers = signer.headers(String.valueOf(request.getMethod()),
                    path, uri.getRawQuery(), body);
            headers.forEach((name, value) -> request.getHeaders().set(name, value));
        }
        return execution.execute(request, body);
    }
}
