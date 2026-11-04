package com.medassist.clinical.client;

import com.medassist.common.security.InternalCallerSigner;
import com.medassist.common.security.InternalSignatureClientInterceptor;

import com.medassist.clinical.config.ClinicalProperties;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 通过 EMPI 内网接口校验患者（不跨 schema 直连数据库）。
 *
 * <p>为什么不直查 empi_svc.patient：一旦两个服务分库（三甲多院区几乎必然），
 * 跨库 SQL 会立即失效；服务间 HTTP + 令牌是可持续的边界。代价是多一跳网络，
 * 用 2s 超时 + 失败即拒绝挂号来控制影响面。
 */
@Component
public class HttpPatientClient implements PatientClient {

    private static final Logger log = LoggerFactory.getLogger(HttpPatientClient.class);

    /** EMPI 内部接口返回的最小投影（不含 PHI 明文）。 */
    public record InternalPatient(UUID id, String patientNo, String name, String status,
                                  String gender, LocalDate birthDate) {
    }

    private final RestClient client;
    private final String token;

    public HttpPatientClient(ClinicalProperties props, InternalCallerSigner signer) {
        ClinicalProperties.Empi empi = props.empi();
        this.token = empi.internalToken() == null ? "" : empi.internalToken();
        if (this.token.isBlank()) {
            // 没有令牌就连不上 EMPI：与其在第一次挂号时 403，不如启动即失败（配置错误要早暴露）
            throw new IllegalStateException(
                    "clinical.empi.internal-token 未配置：请设置 CLINICAL_INTERNAL_TOKEN（须与 EMPI 的 INTERNAL_API_TOKEN 一致）");
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
        InternalPatient p = requireActiveInternal(patientId);
        return new PatientRef(p.id(), p.patientNo(), p.name());
    }

    @Override
    public PatientProfile requireProfile(UUID patientId) {
        InternalPatient p = requireActiveInternal(patientId);
        return new PatientProfile(p.id(), p.patientNo(), p.name(), p.gender(), p.birthDate());
    }

    private InternalPatient requireActiveInternal(UUID patientId) {
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
            // 患者主索引不可用时必须拒绝挂号：否则会产生"无主就诊"，后续无法对账与追责
            log.warn("clinical.empi_unavailable patientId={} err={}", patientId, e.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE, "患者主索引服务暂不可用，请稍后重试");
        }
        if (p == null || p.id() == null) {
            throw new BusinessException(ErrorCode.PATIENT_NOT_FOUND);
        }
        if (!"ACTIVE".equals(p.status())) {
            throw new BusinessException(ErrorCode.PATIENT_MERGE_CONFLICT,
                    "该患者档案状态为 " + p.status() + "，不能挂号（请使用主档案）");
        }
        return p;
    }
}
