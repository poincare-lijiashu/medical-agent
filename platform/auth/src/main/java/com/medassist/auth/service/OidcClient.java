package com.medassist.auth.service;

import com.medassist.auth.config.SsoProperties;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * OIDC 客户端（G42）：发现文档、授权码换 token、JWKS 验签。
 *
 * <p>安全约束：
 * <ul>
 *   <li>只接受 RS256 的 id_token，拒绝 alg=none / HS256 混淆；</li>
 *   <li>发现文档的 issuer 必须与配置一致（防 IdP mix-up）；</li>
 *   <li>JWKS 与发现文档在进程内缓存 10 分钟，IdP 不可用时快速失败而不是拖垮登录线程；</li>
 *   <li>client_secret 只随表单提交，不写日志、不入库。</li>
 * </ul>
 */
@Component
public class OidcClient {

    private static final Duration DISCOVERY_TTL = Duration.ofMinutes(10);

    private final SsoProperties props;

    private volatile Discovery cachedDiscovery;
    private volatile Instant cachedAt;
    private volatile JwtDecoder cachedDecoder;
    private volatile String cachedJwksUri;

    public OidcClient(SsoProperties props) {
        this.props = props;
    }

    /** 发现文档（带 10 分钟缓存）；失败统一 503。 */
    public Discovery discovery() {
        Discovery cached = cachedDiscovery;
        Instant at = cachedAt;
        if (cached != null && at != null && at.plus(DISCOVERY_TTL).isAfter(Instant.now())) {
            return cached;
        }
        synchronized (this) {
            if (cachedDiscovery != null && cachedAt != null
                    && cachedAt.plus(DISCOVERY_TTL).isAfter(Instant.now())) {
                return cachedDiscovery;
            }
            String url = trimTrailingSlash(props.issuer()) + "/.well-known/openid-configuration";
            DiscoveryResponse response;
            try {
                response = client().get().uri(url).retrieve().body(DiscoveryResponse.class);
            } catch (RestClientException e) {
                throw new BusinessException(ErrorCode.SSO_PROVIDER_UNAVAILABLE,
                        "统一身份发现文档拉取失败：" + e.getClass().getSimpleName());
            }
            if (response == null || isBlank(response.authorizationEndpoint())
                    || isBlank(response.tokenEndpoint()) || isBlank(response.jwksUri())) {
                throw new BusinessException(ErrorCode.SSO_PROVIDER_UNAVAILABLE,
                        "统一身份发现文档缺少必要端点");
            }
            if (!isBlank(response.issuer())
                    && !trimTrailingSlash(response.issuer()).equals(trimTrailingSlash(props.issuer()))) {
                // IdP mix-up：发现文档声明的 issuer 与配置不一致，绝不能继续
                throw new BusinessException(ErrorCode.SSO_PROVIDER_UNAVAILABLE,
                        "统一身份 issuer 与配置不一致");
            }
            Discovery discovery = new Discovery(
                    isBlank(response.issuer()) ? trimTrailingSlash(props.issuer()) : response.issuer(),
                    response.authorizationEndpoint(), response.tokenEndpoint(), response.jwksUri());
            cachedDiscovery = discovery;
            cachedAt = Instant.now();
            return discovery;
        }
    }

    /** 授权码换 token（PKCE）。4xx 视为非法授权码，5xx/网络异常视为 IdP 不可用。 */
    public String exchangeCode(String code, String codeVerifier, String redirectUri) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("code", code);
        form.add("redirect_uri", redirectUri);
        form.add("client_id", props.clientId());
        form.add("client_secret", props.clientSecret());
        form.add("code_verifier", codeVerifier);

        TokenResponse response;
        try {
            response = client().post()
                    .uri(discovery().tokenEndpoint())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(form)
                    .retrieve()
                    .body(TokenResponse.class);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().is4xxClientError()) {
                throw new BusinessException(ErrorCode.SSO_TOKEN_INVALID,
                        "统一身份授权码无效或已过期，请重新登录");
            }
            throw new BusinessException(ErrorCode.SSO_PROVIDER_UNAVAILABLE,
                    "统一身份令牌接口异常：" + e.getStatusCode().value());
        } catch (RestClientException e) {
            throw new BusinessException(ErrorCode.SSO_PROVIDER_UNAVAILABLE,
                    "统一身份令牌接口不可用：" + e.getClass().getSimpleName());
        }
        if (response == null || isBlank(response.idToken())) {
            throw new BusinessException(ErrorCode.SSO_TOKEN_INVALID, "统一身份未返回 id_token");
        }
        return response.idToken();
    }

    /** 校验 id_token 签名（RS256 + JWKS）与 iss/exp，返回声明。 */
    public Jwt decodeIdToken(String idToken) {
        Discovery discovery = discovery();
        JwtDecoder decoder = decoder(discovery);
        try {
            return decoder.decode(idToken);
        } catch (JwtException e) {
            throw new BusinessException(ErrorCode.SSO_TOKEN_INVALID,
                    "统一身份 id_token 校验失败：" + e.getMessage());
        }
    }

    private JwtDecoder decoder(Discovery discovery) {
        JwtDecoder current = cachedDecoder;
        if (current != null && discovery.jwksUri().equals(cachedJwksUri)) {
            return current;
        }
        synchronized (this) {
            if (cachedDecoder != null && discovery.jwksUri().equals(cachedJwksUri)) {
                return cachedDecoder;
            }
            NimbusJwtDecoder nimbus = NimbusJwtDecoder.withJwkSetUri(discovery.jwksUri())
                    .jwsAlgorithm(SignatureAlgorithm.RS256)
                    .build();
            nimbus.setJwtValidator(JwtValidators.createDefaultWithIssuer(discovery.issuer()));
            cachedDecoder = nimbus;
            cachedJwksUri = discovery.jwksUri();
            return nimbus;
        }
    }

    private RestClient client() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(props.timeout());
        factory.setReadTimeout(props.timeout());
        return RestClient.builder().requestFactory(factory).build();
    }

    private static String trimTrailingSlash(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** 发现文档中的必要端点。 */
    public record Discovery(String issuer, String authorizationEndpoint, String tokenEndpoint,
                            String jwksUri) {
    }

    private record DiscoveryResponse(String issuer, String authorization_endpoint,
                                     String token_endpoint, String jwks_uri,
                                     List<String> id_token_signing_alg_values_supported) {
        String authorizationEndpoint() {
            return authorization_endpoint;
        }

        String tokenEndpoint() {
            return token_endpoint;
        }

        String jwksUri() {
            return jwks_uri;
        }
    }

    private record TokenResponse(String access_token, String id_token, String token_type,
                                 Long expires_in) {
        String idToken() {
            return id_token;
        }
    }
}
