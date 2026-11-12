package com.medassist.auth.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * 通过 license 服务的内部接口读取实例授权摘要（§284）。
 *
 * <p>失败语义必须分清：
 * <ul>
 *   <li>未配置地址 → {@link State#DISABLED}（离线交付，不阻塞）；</li>
 *   <li>服务可达但没有记录 → {@link State#NOT_FOUND}（是否阻塞由开通参数决定）；</li>
 *   <li>超时/5xx → {@link State#UNREACHABLE}（不能当成"没授权"，否则一次抖动就会误判）。</li>
 * </ul>
 */
@Component
public class HttpLicenseGateway implements LicenseGateway {

    private static final Logger log = LoggerFactory.getLogger(HttpLicenseGateway.class);

    private final String baseUrl;
    private final String internalToken;
    private final ObjectMapper json;
    private final HttpClient client;

    public HttpLicenseGateway(
            @Value("${medassist.provisioning.license-url:${LICENSE_INTERNAL_URI:}}") String baseUrl,
            @Value("${INTERNAL_API_TOKEN:}") String internalToken,
            ObjectMapper json) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        this.internalToken = internalToken == null ? "" : internalToken;
        this.json = json;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
    }

    @Override
    public Result check(String instanceCode) {
        if (baseUrl.isEmpty()) {
            return new Result(State.DISABLED, null, null, null, null, 0, null);
        }
        String url = baseUrl.replaceAll("/+$", "")
                + "/internal/license/summary?instanceCode="
                + java.net.URLEncoder.encode(instanceCode == null ? "" : instanceCode,
                java.nio.charset.StandardCharsets.UTF_8);
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(4))
                    .header("Accept", "application/json")
                    .header("X-Internal-Token", internalToken)
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request,
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return unreachable("HTTP " + response.statusCode());
            }
            Map<String, Object> body = read(response.body());
            Object data = body.get("data");
            if (!(data instanceof Map<?, ?> map)) {
                return new Result(State.NOT_FOUND, null, null, null, null, 0, null);
            }
            boolean present = Boolean.TRUE.equals(map.get("present"));
            if (!present) {
                return new Result(State.NOT_FOUND, null, null, null, null, 0, null);
            }
            return new Result(State.FOUND,
                    str(map.get("licenseNo")), str(map.get("status")), str(map.get("editionCode")),
                    instant(str(map.get("expiresAt"))),
                    map.get("modules") instanceof Number n ? n.intValue() : 0,
                    null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return unreachable("请求被中断");
        } catch (Exception e) {
            log.warn("license.summary.check_failed error={}", e.getClass().getSimpleName());
            return unreachable(e.getClass().getSimpleName());
        }
    }

    private static Result unreachable(String reason) {
        return new Result(State.UNREACHABLE, null, null, null, null, 0, reason);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> read(String raw) {
        try {
            Map<String, Object> value = json.readValue(raw == null ? "{}" : raw, Map.class);
            return value == null ? Map.of() : value;
        } catch (JacksonException e) {
            return Map.of();
        }
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Instant instant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
