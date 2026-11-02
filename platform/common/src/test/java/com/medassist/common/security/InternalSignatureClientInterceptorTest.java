package com.medassist.common.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 出站签名拦截器（第五轮 R5-M10）：必须只对 {@code /internal/**} 附加签名头。
 *
 * <p>这是"调用方签名开关"打开后最容易被漏掉的一环：7 个 Http*Client 各自建 RestClient，
 * 路径过滤写错就会给业务请求也加上内部签名头，或反之让内部调用没有签名而 403。
 */
class InternalSignatureClientInterceptorTest {

    private static final String SECRET = Base64.getEncoder()
            .encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));

    @Test
    @DisplayName("内部路径：附加 v1i 签名头且可被同一协议验证")
    void signsInternalPaths() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient client = builder
                .baseUrl("http://empi:8082")
                .requestInterceptor(new InternalSignatureClientInterceptor(
                        InternalCallerSigner.of("clinical", SECRET)))
                .build();

        server.expect(requestTo("http://empi:8082/internal/patients/p1"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(request -> {
                    var headers = request.getHeaders();
                    assertThat(headers.getFirst(InternalSignature.HEADER_SIG_VER))
                            .isEqualTo(InternalSignature.SIG_VERSION_CALLER);
                    assertThat(headers.getFirst(InternalSignature.HEADER_CALLER)).isEqualTo("clinical");
                    String canonical = InternalSignature.canonicalCaller("GET",
                            "/internal/patients/p1", null, "clinical",
                            Long.parseLong(headers.getFirst(InternalSignature.HEADER_TS)),
                            headers.getFirst(InternalSignature.HEADER_NONCE),
                            InternalSignature.EMPTY_BODY_HASH);
                    assertThat(InternalSignature.verify(SECRET, canonical,
                            headers.getFirst(InternalSignature.HEADER_SIG))).isTrue();
                })
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        client.get().uri("/internal/patients/p1").retrieve().toBodilessEntity();
        server.verify();
    }

    @Test
    @DisplayName("业务路径：即使已配置密钥也不加任何内部签名头")
    void doesNotSignBusinessPaths() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient client = builder
                .baseUrl("http://clinical:8083")
                .requestInterceptor(new InternalSignatureClientInterceptor(
                        InternalCallerSigner.of("emr", SECRET)))
                .build();

        server.expect(requestTo("http://clinical:8083/clinical/visits"))
                .andExpect(headerDoesNotExist(InternalSignature.HEADER_SIG))
                .andExpect(headerDoesNotExist(InternalSignature.HEADER_CALLER))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        client.get().uri("/clinical/visits").retrieve().toBodilessEntity();
        server.verify();
    }

    @Test
    @DisplayName("未配置调用方密钥：静默不加头（由对端 403，而不是本地报错）")
    void disabledSignerAddsNoHeaders() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient client = builder
                .baseUrl("http://empi:8082")
                .requestInterceptor(new InternalSignatureClientInterceptor(
                        InternalCallerSigner.disabled()))
                .build();

        server.expect(requestTo("http://empi:8082/internal/patients/p1"))
                .andExpect(headerDoesNotExist(InternalSignature.HEADER_SIG))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        client.get().uri("/internal/patients/p1").retrieve().toBodilessEntity();
        server.verify();
    }

    @Test
    @DisplayName("内部 POST：签名覆盖请求体（v1i2），body 摘要参与验签")
    void signsRequestBody() {
        byte[] body = "{\"orderId\":\"o-1\",\"quantity\":2}".getBytes(StandardCharsets.UTF_8);
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient client = builder
                .baseUrl("http://pharmacy:8084")
                .requestInterceptor(new InternalSignatureClientInterceptor(
                        InternalCallerSigner.of("clinical", SECRET, true)))
                .build();

        server.expect(requestTo("http://pharmacy:8084/internal/prescriptions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(request -> {
                    var headers = request.getHeaders();
                    assertThat(headers.getFirst(InternalSignature.HEADER_SIG_VER))
                            .isEqualTo(InternalSignature.SIG_VERSION_CALLER_BODY);
                    String canonical = InternalSignature.canonicalCaller(
                            InternalSignature.SIG_VERSION_CALLER_BODY, "POST",
                            "/internal/prescriptions", null, "clinical",
                            Long.parseLong(headers.getFirst(InternalSignature.HEADER_TS)),
                            headers.getFirst(InternalSignature.HEADER_NONCE),
                            InternalSignature.bodyHash(body));
                    assertThat(InternalSignature.verify(SECRET, canonical,
                            headers.getFirst(InternalSignature.HEADER_SIG))).isTrue();
                })
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        client.post().uri("/internal/prescriptions")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve().toBodilessEntity();
        server.verify();
    }
}
