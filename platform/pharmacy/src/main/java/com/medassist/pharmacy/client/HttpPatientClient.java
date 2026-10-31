package com.medassist.pharmacy.client;

import com.medassist.common.security.InternalCallerSigner;
import com.medassist.common.security.InternalSignatureClientInterceptor;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.pharmacy.config.PharmacyProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.UUID;

/**
 * 通过 EMPI 内网接口校验患者（不跨 schema 直连数据库）。
 *
 * <p>与 clinical 的 HttpPatientClient 同一套边界：内网直连 + 共享令牌；
 * 令牌未配置时构造即失败（配置错误要早暴露，而不是第一次开方才 403）。
 */
@Component
public class HttpPatientClient implements PatientClient {

    private static final Logger log = LoggerFactory.getLogger(HttpPatientClient.class);

    /** EMPI 内部接口返回的最小投影（不含 PHI 明文）。 */
    public record InternalPatient(UUID id, String patientNo, String name, String status) {
    }

    private final RestClient client;
    private final String token;

    public HttpPatientClient(PharmacyProperties props, InternalCallerSigner signer) {
        PharmacyProperties.Empi empi = props.empi();
        this.token = empi == null || empi.internalToken() == null ? "" : empi.internalToken();
        if (this.token.isBlank()) {
            throw new IllegalStateException(
                    "pharmacy.empi.internal-token 未配置：请设置 PHARMACY_INTERNAL_TOKEN（须与 EMPI 的 INTERNAL_API_TOKEN 一致）");
        }
        Duration timeout = empi.timeout() == null ? Duration.ofSeconds(2) : empi.timeout();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        this.client = RestClient.builder()
                .baseUrl(empi.baseUrl())
                .requestFactory(factory)
                .requestInterceptor(new InternalSignatureClientInterceptor(signer))
                .build();
    }

    @Override
    public PatientRef requireActive(UUID patientId) {
        InternalPatient p;
        try {
            p = client.get()
                    .uri("/internal/patients/{id}", patientId)
                    .header("X-Internal-Token", token)
                    .retrieve()
                    .body(InternalPatient.class);
        } catch (HttpClientErrorException.NotFound e) {
            throw new BusinessException(ErrorCode.PATIENT_NOT_FOUND, "未找到该患者，请先在患者主索引建档");
        } catch (RestClientException e) {
            log.warn("pharmacy.empi_unavailable patientId={} err={}", patientId, e.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE, "患者主索引服务暂不可用，请稍后重试");
        }
        if (p == null || p.id() == null) {
            throw new BusinessException(ErrorCode.PATIENT_NOT_FOUND);
        }
        if (!"ACTIVE".equals(p.status())) {
            throw new BusinessException(ErrorCode.PATIENT_MERGE_CONFLICT,
                    "该患者档案状态为 " + p.status() + "，不能开方（请使用主档案）");
        }
        return new PatientRef(p.id(), p.patientNo(), p.name());
    }
}
