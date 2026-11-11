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
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

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
        return Optional.of(toSummary(response));
    }

    /**
     * 上游 JSON → 领域摘要。
     *
     * <p>包装类型不是装饰：Jackson 3 默认 {@code FAIL_ON_NULL_FOR_PRIMITIVES=true}，
     * 上游少发一个布尔字段就整条解析失败。外部报文一律按"缺省=0/false"处理。
     */
    private static ArchiveSummary toSummary(SummaryResponse r) {
        return new ArchiveSummary(nz(r.total()), nz(r.archived()), nz(r.draft()),
                Boolean.TRUE.equals(r.homepageFound()), r.homepageStatus(),
                Boolean.TRUE.equals(r.homepageArchived()));
    }

    private static int nz(Integer value) {
        return value == null ? 0 : value;
    }

    /** 与 emr 的 {@code /internal/records/by-visit/{id}/archive-summary} 对应。 */
    record SummaryResponse(UUID visitId, Integer total, Integer archived, Integer draft,
                           Boolean homepageFound, String homepageStatus, Boolean homepageArchived) {
    }

    @Override
    public Map<UUID, ArchiveSummary> batchArchiveSummary(Collection<UUID> visitIds) {
        if (client == null || visitIds == null || visitIds.isEmpty()) {
            return Map.of();
        }
        String csv = visitIds.stream().map(UUID::toString).collect(Collectors.joining(","));
        BatchResponse response;
        try {
            response = client.get()
                    .uri(builder -> builder.path("/internal/records/archive-summaries")
                            .queryParam("visitIds", csv).build())
                    .header("X-Internal-Token", token)
                    .retrieve()
                    .body(BatchResponse.class);
        } catch (RestClientException e) {
            log.warn("clinical.emr_archive_batch_unavailable visits={} err={} msg={}",
                    visitIds.size(), e.getClass().getSimpleName(), e.getMessage());
            return Map.of();
        }
        if (response == null || response.items() == null) {
            log.warn("clinical.emr_archive_batch_empty visits={}", visitIds.size());
            return Map.of();
        }
        Map<UUID, ArchiveSummary> out = new LinkedHashMap<>();
        for (SummaryResponse item : response.items()) {
            if (item == null || item.visitId() == null) {
                continue;
            }
            out.put(item.visitId(), toSummary(item));
        }
        return out;
    }

    /** 与 emr 的 {@code /internal/records/archive-summaries} 对应。 */
    record BatchResponse(List<SummaryResponse> items) {
    }
}
