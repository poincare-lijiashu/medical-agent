package com.medassist.clinical.client;

import com.medassist.clinical.config.ClinicalProperties;
import com.medassist.common.security.InternalCallerSigner;
import com.medassist.common.security.InternalSignatureClientInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * 电子病历归档核查客户端（内网直连，不经网关）。
 *
 * <p>与药房/医技客户端的区别：这条链路是**可选**的只读核查——
 * <ul>
 *   <li>令牌未配置 = 未接入，直接返回 empty，不阻断服务启动；</li>
 *   <li>超时/5xx/4xx 一律返回 empty 并记一条 warn，由体检显示"未核查"；</li>
 *   <li>不把异常抛给出院流程：查不到归档不等于不能出院，服务端门禁另有三条。</li>
 * </ul>
 */
@Component
public class HttpEmrArchiveClient implements EmrArchiveClient {

    private static final Logger log = LoggerFactory.getLogger(HttpEmrArchiveClient.class);

    /** 未接入时为 null（不建连接池，也不产生任何出站请求）。 */
    private final RestClient client;
    private final String token;

    public HttpEmrArchiveClient(ClinicalProperties props, InternalCallerSigner signer) {
        ClinicalProperties.Emr emr = props.emr();
        String configured = emr == null || emr.internalToken() == null ? "" : emr.internalToken();
        this.token = configured;
        if (configured.isBlank()) {
            this.client = null;
            return;
        }
        String baseUrl = emr.baseUrl() == null || emr.baseUrl().isBlank()
                ? "http://127.0.0.1:8086" : emr.baseUrl();
        Duration timeout = emr.timeout() == null ? Duration.ofSeconds(2) : emr.timeout();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        this.client = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .requestInterceptor(new InternalSignatureClientInterceptor(signer))
                .build();
    }

    @Override
    public Optional<ArchiveSummary> archiveSummary(UUID visitId) {
        if (client == null) {
            return Optional.empty();
        }
        SummaryResponse response;
        try {
            response = client.get()
                    .uri("/internal/records/by-visit/{visitId}/archive-summary", visitId)
                    .header("X-Internal-Token", token)
                    .retrieve()
                    .body(SummaryResponse.class);
        } catch (RestClientException e) {
            log.warn("clinical.emr_archive_unavailable visitId={} err={}", visitId,
                    e.getClass().getSimpleName());
            return Optional.empty();
        }
        if (response == null) {
            log.warn("clinical.emr_archive_empty visitId={}", visitId);
            return Optional.empty();
        }
        return Optional.of(new ArchiveSummary(response.total(), response.archived(),
                response.draft(), response.homepageFound(), response.homepageStatus(),
                response.homepageArchived()));
    }

    /** 与 emr 的 {@code /internal/records/by-visit/{id}/archive-summary} 对应。 */
    record SummaryResponse(UUID visitId, int total, int archived, int draft,
                           boolean homepageFound, String homepageStatus, boolean homepageArchived) {
    }
}
