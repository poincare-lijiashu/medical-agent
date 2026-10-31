package com.medassist.emr.client;

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
 * 通过临床域内网接口读取入院时间（内网直连 + 共享令牌）。
 *
 * <p>刻意 fail-open：令牌缺失/服务不可用/该就诊无住院登记，都返回空并由时限规则跳过。
 * 时限质控是质量指标，不能反过来阻断医生写病历。
 */
@Component
public class HttpClinicalClient implements ClinicalClient {

    private static final Logger log = LoggerFactory.getLogger(HttpClinicalClient.class);

    private final RestClient client;
    private final String token;

    public HttpClinicalClient(EmrProperties props) {
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
        this.client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    @Override
    public Optional<Instant> admittedAt(UUID visitId) {
        if (visitId == null || token.isBlank()) {
            return Optional.empty();
        }
        Map<?, ?> body;
        try {
            body = client.get()
                    .uri("/internal/admissions/by-visit/{visitId}", visitId)
                    .header("X-Internal-Token", token)
                    .retrieve()
                    .body(Map.class);
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        } catch (RestClientException e) {
            log.warn("emr.clinical_unavailable visitId={} err={}", visitId, e.getClass().getSimpleName());
            return Optional.empty();
        }
        if (body == null || body.get("admittedAt") == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(Instant.parse(String.valueOf(body.get("admittedAt"))));
        } catch (RuntimeException e) {
            log.warn("emr.clinical_bad_payload visitId={} value={}", visitId, body.get("admittedAt"));
            return Optional.empty();
        }
    }
}
