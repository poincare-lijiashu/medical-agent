package com.medassist.tech.client;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.tech.config.TechProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;

/**
 * DICOMweb（QIDO-RS / WADO-RS）客户端。
 *
 * <p>配置 {@code tech.pacs.base-url} 后才真正建立连接；未配置时服务照常启动，
 * 阅片队列、序列缓存与审计都可用，只有"取影像/查远端序列"返回 503——
 * 医院常先把服务上线再接入 PACS，不能因此拒绝启动。
 *
 * <p>所有远端异常都转为 {@link ErrorCode#TECH_PACS_UPSTREAM_FAILED}（503），
 * 避免把 PACS 的 500/超时原样透出成"业务错误"，也避免前端把 4xx 当成用户输入问题。
 */
@Component
public class HttpPacsDicomClient implements PacsDicomGateway {

    private static final Logger log = LoggerFactory.getLogger(HttpPacsDicomClient.class);

    /** DICOMweb 标准媒体类型；不用 Spring 预置常量，避免版本间命名差异。 */
    static final MediaType DICOM_JSON = MediaType.parseMediaType("application/dicom+json");
    static final MediaType DICOM = MediaType.parseMediaType("application/dicom");

    private final TechProperties.Pacs config;
    private final ObjectMapper objectMapper;
    private final RestClient client;

    public HttpPacsDicomClient(TechProperties props, ObjectMapper objectMapper) {
        this.config = props.pacsOrDefault();
        this.objectMapper = objectMapper;
        this.client = config.configured() ? buildClient(config) : null;
    }

    private static RestClient buildClient(TechProperties.Pacs config) {
        Duration timeout = config.timeoutOrDefault();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        String baseUrl = config.baseUrl().trim();
        while (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    @Override
    public boolean configured() {
        return client != null;
    }

    @Override
    public List<SeriesPayload> listSeries(String studyInstanceUid) {
        String body = exchange(() -> client.get()
                .uri("/studies/{study}/series", studyInstanceUid)
                .accept(DICOM_JSON)
                .headers(this::applyAuth)
                .retrieve()
                .body(String.class), "QIDO 查询序列");
        return PacsDicomJson.parseSeries(body, objectMapper);
    }

    @Override
    public List<InstanceMeta> listInstances(String studyInstanceUid, String seriesInstanceUid,
                                            int limit, int offset) {
        String body = exchange(() -> client.get()
                .uri("/studies/{study}/series/{series}/instances"
                                + "?limit={limit}&offset={offset}&orderby=00200013"
                                + "&includefield=00080018,00200013,00080016",
                        studyInstanceUid, seriesInstanceUid, limit, offset)
                .accept(DICOM_JSON)
                .headers(this::applyAuth)
                .retrieve()
                .body(String.class), "QIDO 查询影像");
        return PacsDicomJson.parseInstances(body, objectMapper);
    }

    @Override
    public byte[] fetchInstance(String studyInstanceUid, String seriesInstanceUid, String sopInstanceUid) {
        byte[] bytes = exchange(() -> client.get()
                .uri("/studies/{study}/series/{series}/instances/{instance}",
                        studyInstanceUid, seriesInstanceUid, sopInstanceUid)
                .accept(DICOM)
                .headers(this::applyAuth)
                .retrieve()
                .body(byte[].class), "WADO 取影像");
        return requireBytes(bytes, "影像服务未返回该影像内容", studyInstanceUid, seriesInstanceUid,
                sopInstanceUid);
    }

    @Override
    public byte[] fetchRendered(String studyInstanceUid, String seriesInstanceUid,
                                String sopInstanceUid) {
        byte[] bytes = exchange(() -> client.get()
                .uri("/studies/{study}/series/{series}/instances/{instance}/rendered",
                        studyInstanceUid, seriesInstanceUid, sopInstanceUid)
                .accept(MediaType.IMAGE_JPEG, MediaType.IMAGE_PNG)
                .headers(this::applyAuth)
                .retrieve()
                .body(byte[].class), "WADO 取预览图");
        return requireBytes(bytes, "影像服务未返回该影像的预览图（该设备可能不支持 rendered）",
                studyInstanceUid, seriesInstanceUid, sopInstanceUid);
    }

    private byte[] requireBytes(byte[] bytes, String emptyMessage, String studyUid,
                                String seriesUid, String sopUid) {
        if (bytes == null || bytes.length == 0) {
            throw new BusinessException(ErrorCode.TECH_PACS_REMOTE_NOT_FOUND, emptyMessage);
        }
        long max = config.maxInstanceBytesOrDefault();
        if (bytes.length > max) {
            log.warn("tech.pacs.instance_too_large study={} series={} sop={} bytes={} limit={}",
                    studyUid, seriesUid, sopUid, bytes.length, max);
            throw new BusinessException(ErrorCode.TECH_PACS_UPSTREAM_FAILED,
                    "影像文件超过服务允许的大小上限（" + max + " 字节），请联系影像科");
        }
        return bytes;
    }

    private void applyAuth(HttpHeaders headers) {
        String auth = config.authHeaderOrEmpty();
        if (!auth.isBlank()) {
            headers.set(HttpHeaders.AUTHORIZATION, auth);
        }
    }

    private <T> T exchange(RemoteCall<T> call, String action) {
        if (client == null) {
            throw new BusinessException(ErrorCode.TECH_PACS_NOT_CONFIGURED);
        }
        try {
            return call.execute();
        } catch (HttpClientErrorException.NotFound e) {
            throw new BusinessException(ErrorCode.TECH_PACS_REMOTE_NOT_FOUND,
                    "影像服务未返回该序列或影像（" + action + "）");
        } catch (RestClientException e) {
            log.warn("tech.pacs.upstream_failed action={} err={}", action, e.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.TECH_PACS_UPSTREAM_FAILED);
        }
    }

    @FunctionalInterface
    private interface RemoteCall<T> {
        T execute();
    }
}
