package com.medassist.insurance.client;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.security.InternalCallerSigner;
import com.medassist.common.security.InternalSignatureClientInterceptor;

import com.medassist.insurance.config.InsuranceProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 通过病历域内网接口读取病案首页手术操作（内网直连 + 共享令牌）。
 */
@Component
public class HttpEmrClient implements EmrClient {

    private static final Logger log = LoggerFactory.getLogger(HttpEmrClient.class);

    private final RestClient client;
    private final String token;

    public HttpEmrClient(InsuranceProperties props, InternalCallerSigner signer) {
        InsuranceProperties.Emr emr = props.emr();
        this.token = emr == null || emr.internalToken() == null ? "" : emr.internalToken();
        if (this.token.isBlank()) {
            log.warn("insurance.emr.internal-token 未配置：分组模拟将无法自动读取病案首页手术操作"
                    + "（设置 INSURANCE_INTERNAL_TOKEN 或 INTERNAL_API_TOKEN）");
        }
        String baseUrl = emr == null || emr.baseUrl() == null
                ? "http://127.0.0.1:8086" : emr.baseUrl();
        Duration timeout = emr == null || emr.timeout() == null
                ? Duration.ofSeconds(3) : emr.timeout();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        this.client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory)
                .requestInterceptor(new InternalSignatureClientInterceptor(signer)).build();
    }

    @Override
    @SuppressWarnings("unchecked")
    public HomepageProcedures procedures(UUID visitId) {
        if (visitId == null) {
            return new HomepageProcedures(null, false, null, List.of());
        }
        if (token.isBlank()) {
            throw new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE,
                    "医保服务未配置病历域内部令牌，无法读取病案首页手术操作");
        }
        Map<?, ?> body;
        try {
            body = client.get()
                    .uri("/internal/homepages/by-visit/{visitId}/procedures", visitId)
                    .header("X-Internal-Token", token)
                    .retrieve()
                    .body(Map.class);
        } catch (RestClientException e) {
            log.warn("insurance.emr_unavailable visitId={} err={}",
                    visitId, e.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE,
                    "病历服务暂不可用，无法读取病案首页手术操作，请稍后重试");
        }
        if (body == null) {
            return new HomepageProcedures(visitId, false, null, List.of());
        }
        List<String> codes = new ArrayList<>();
        // 病历域只投影编码（不含术者/日期等 PHI）；非归档首页返回空列表
        if (body.get("codes") instanceof List<?> rows) {
            for (Object row : rows) {
                if (row != null && !String.valueOf(row).isBlank()) {
                    codes.add(String.valueOf(row));
                }
            }
        }
        return new HomepageProcedures(visitId, Boolean.TRUE.equals(body.get("found")),
                body.get("status") == null ? null : String.valueOf(body.get("status")), codes);
    }
}
