package com.medassist.emr.client;

import com.medassist.common.security.InternalCallerSigner;
import com.medassist.common.security.InternalSignatureClientInterceptor;

import com.medassist.emr.config.EmrProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 通过临床域内网接口读取就诊画像（归属患者 + 入院时间）。
 *
 * <p>就诊不存在/上游不可用时返回空，由调用方决定语义：
 * <ul>
 *   <li>归属校验：取不到就诊不做强校验（fail-open，避免临床域抖动阻断书写），
 *       但取到且患者不一致时一律拒绝；</li>
 *   <li>时限质控：取不到入院时间就跳过（质量指标不能反阻断书写）。</li>
 * </ul>
 */
@Component
public class HttpClinicalClient implements ClinicalClient {

    private static final Logger log = LoggerFactory.getLogger(HttpClinicalClient.class);

    private final RestClient client;
    private final String token;

    public HttpClinicalClient(EmrProperties props, InternalCallerSigner signer) {
        EmrProperties.Clinical clinical = props.clinical();
        this.token = clinical == null || clinical.internalToken() == null ? "" : clinical.internalToken();
        if (this.token.isBlank()) {
            log.warn("emr.clinical.internal-token 未配置：书写时限质控将被跳过"
                    + "（设置 EMR_CLINICAL_TOKEN 或 EMR_INTERNAL_TOKEN）");
        }
        String baseUrl = clinical == null || clinical.baseUrl() == null
                ? "http://127.0.0.1:8083" : clinical.baseUrl();
        Duration timeout = clinical == null || clinical.timeout() == null
                ? Duration.ofSeconds(2) : clinical.timeout();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        this.client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory)
                .requestInterceptor(new InternalSignatureClientInterceptor(signer)).build();
    }

    @Override
    public Optional<VisitRef> visit(UUID visitId) {
        if (visitId == null || token.isBlank()) {
            return Optional.empty();
        }
        Map<?, ?> body;
        try {
            body = client.get()
                    .uri("/internal/visits/{visitId}", visitId)
                    .header("X-Internal-Token", token)
                    .retrieve()
                    .body(Map.class);
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        } catch (RestClientException e) {
            log.warn("emr.clinical_unavailable visitId={} err={}", visitId, e.getClass().getSimpleName());
            return Optional.empty();
        }
        if (body == null) {
            return Optional.empty();
        }
        return Optional.of(new VisitRef(uuid(body.get("patientId")), instant(body.get("admittedAt"))));
    }

    private static UUID uuid(Object o) {
        if (o == null) {
            return null;
        }
        try {
            return UUID.fromString(String.valueOf(o));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Instant instant(Object o) {
        if (o == null) {
            return null;
        }
        try {
            return Instant.parse(String.valueOf(o));
        } catch (RuntimeException e) {
            log.warn("emr.clinical_bad_payload admittedAt={}", o);
            return null;
        }
    }
}
