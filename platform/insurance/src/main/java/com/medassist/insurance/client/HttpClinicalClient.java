package com.medassist.insurance.client;

import com.medassist.common.security.InternalCallerSigner;
import com.medassist.common.security.InternalSignatureClientInterceptor;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.insurance.config.InsuranceProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 通过临床域内网接口拉取收费明细（内网直连 + 共享令牌）。
 *
 * <p>与 EMR 的时限质控不同，这里**不能 fail-open**：拿不到收费明细就无法出结算清单，
 * 必须明确返回 503，绝不能凭客户端传参或缓存数据编造金额。
 */
@Component
public class HttpClinicalClient implements ClinicalClient {

    private static final Logger log = LoggerFactory.getLogger(HttpClinicalClient.class);

    private final RestClient client;
    private final String token;

    public HttpClinicalClient(InsuranceProperties props, InternalCallerSigner signer) {
        InsuranceProperties.Clinical clinical = props.clinical();
        this.token = clinical == null || clinical.internalToken() == null ? "" : clinical.internalToken();
        if (this.token.isBlank()) {
            log.warn("insurance.clinical.internal-token 未配置：医保结算清单将无法生成"
                    + "（设置 INSURANCE_CLINICAL_TOKEN 或 INSURANCE_INTERNAL_TOKEN）");
        }
        String baseUrl = clinical == null || clinical.baseUrl() == null
                ? "http://127.0.0.1:8083" : clinical.baseUrl();
        Duration timeout = clinical == null || clinical.timeout() == null
                ? Duration.ofSeconds(3) : clinical.timeout();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        this.client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory)
                .requestInterceptor(new InternalSignatureClientInterceptor(signer)).build();
    }

    @Override
    @SuppressWarnings("unchecked")
    public ChargeSummary charges(UUID visitId) {
        if (visitId == null) {
            throw new BusinessException(ErrorCode.INSURANCE_NO_CHARGE);
        }
        if (token.isBlank()) {
            throw new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE,
                    "医保服务未配置临床域内部令牌，无法读取收费明细");
        }
        Map<?, ?> body;
        try {
            body = client.get()
                    .uri("/internal/visits/{visitId}/charges", visitId)
                    .header("X-Internal-Token", token)
                    .retrieve()
                    .body(Map.class);
        } catch (HttpClientErrorException.NotFound e) {
            throw new BusinessException(ErrorCode.INSURANCE_NO_CHARGE, "该就诊单不存在或暂无收费");
        } catch (RestClientException e) {
            log.warn("insurance.clinical_unavailable visitId={} err={}",
                    visitId, e.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE,
                    "临床收费服务暂不可用，无法生成结算清单，请稍后重试");
        }
        if (body == null) {
            throw new BusinessException(ErrorCode.INSURANCE_NO_CHARGE);
        }
        UUID patientId = uuid(body.get("patientId"));
        String patientNo = str(body.get("patientNo"));
        LocalDate serviceDate = date(body.get("serviceDate"));
        List<ChargeLine> lines = new ArrayList<>();
        Object rawLines = body.get("lines");
        if (rawLines instanceof List<?> rows) {
            for (Object row : rows) {
                if (!(row instanceof Map<?, ?> m)) {
                    continue;
                }
                lines.add(new ChargeLine(
                        str(m.get("chargeNo")),
                        str(m.get("itemCode")),
                        str(m.get("itemName")),
                        str(m.get("category")),
                        decimal(m.get("amount")),
                        str(m.get("chargeStatus"))));
            }
        }
        return new ChargeSummary(visitId, patientId, patientNo, serviceDate, lines);
    }

    private static LocalDate date(Object o) {
        if (o == null) {
            return null;
        }
        try {
            return LocalDate.parse(String.valueOf(o));
        } catch (DateTimeParseException e) {
            log.warn("insurance.clinical_bad_service_date value={}", o);
            return null;
        }
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

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static BigDecimal decimal(Object o) {
        if (o == null) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(String.valueOf(o));
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }
}
