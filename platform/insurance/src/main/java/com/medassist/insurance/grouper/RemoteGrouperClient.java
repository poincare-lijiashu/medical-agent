package com.medassist.insurance.grouper;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.insurance.config.GrouperProperties;
import com.medassist.insurance.domain.InsuranceModel.GrouperRuleSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 远端分组器适配（§263）：把本地病案输入发给省局/厂商分组器，取回分组结果。
 *
 * <p>协议边界：请求/响应都是 JSON，字段与 {@link GrouperOutcome} 对齐；
 * 具体厂商字段差异在 {@code insurance.grouper} 适配层处理，不污染本地引擎。
 * 远端不可用默认**不回落本地**，避免两种口径混用。
 */
@Component
public class RemoteGrouperClient {

    private static final Logger log = LoggerFactory.getLogger(RemoteGrouperClient.class);

    private final GrouperProperties properties;
    private final RestClient client;
    private final ObjectMapper objectMapper;

    public RemoteGrouperClient(GrouperProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.timeoutOrDefault());
        factory.setReadTimeout(properties.timeoutOrDefault());
        this.client = RestClient.builder().requestFactory(factory).build();
    }

    public boolean configured() {
        return properties.remoteUrlOrNull() != null;
    }

    public GrouperOutcome group(GrouperRuleSet ruleSet, GrouperInput input) {
        String url = properties.remoteUrlOrNull();
        if (url == null) {
            throw new BusinessException(ErrorCode.INSURANCE_GROUPER_UNAVAILABLE,
                    "未配置远端分组器地址（INSURANCE_GROUPER_REMOTE_URL）");
        }
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("ruleSetCode", ruleSet.code());
        request.put("ruleSetVersion", ruleSet.version());
        request.put("type", ruleSet.type());
        request.put("principalDiagnosis", input.principalDiagnosis());
        request.put("secondaryDiagnoses", input.secondaryDiagnoses());
        request.put("procedures", input.procedures());
        try {
            byte[] body = client.post()
                    .uri(java.net.URI.create(url))
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .headers(headers -> {
                        if (properties.remoteToken() != null
                                && !properties.remoteToken().isBlank()) {
                            headers.setBearerAuth(properties.remoteToken().trim());
                        }
                    })
                    .body(request)
                    .retrieve()
                    .body(byte[].class);
            return parse(ruleSet, body);
        } catch (BusinessException e) {
            throw e;
        } catch (RestClientException e) {
            log.warn("grouper.remote.unavailable ruleSet={} reason={}", ruleSet.code(),
                    e.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.INSURANCE_GROUPER_UNAVAILABLE,
                    "远端分组器不可用：" + e.getClass().getSimpleName());
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.INSURANCE_GROUPER_UNAVAILABLE,
                    "远端分组器响应解析失败：" + e.getMessage());
        }
    }

    private GrouperOutcome parse(GrouperRuleSet ruleSet, byte[] body) {
        JsonNode root = objectMapper.readTree(body);
        String mdcCode = text(root, "mdcCode");
        String adrgCode = text(root, "adrgCode");
        String drgCode = text(root, "drgCode");
        String dipCode = text(root, "dipCode");
        String severity = text(root, "severity");
        BigDecimal weight = decimal(root, "weight");
        BigDecimal score = decimal(root, "score");
        boolean matched = root.path("matched").asBoolean(drgCode != null || dipCode != null);
        String reason = text(root, "reason");
        if (reason == null && !matched) {
            reason = "远端分组器未返回入组结果";
        }
        List<Map<String, Object>> trace = new ArrayList<>();
        JsonNode traceNode = root.path("trace");
        if (traceNode.isArray()) {
            for (JsonNode item : traceNode) {
                @SuppressWarnings("unchecked")
                Map<String, Object> step = objectMapper.convertValue(item, Map.class);
                trace.add(step);
            }
        }
        if (trace.isEmpty()) {
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("step", "REMOTE");
            step.put("matched", matched);
            step.put("drgCode", drgCode);
            step.put("dipCode", dipCode);
            trace.add(step);
        }
        return new GrouperOutcome(ruleSet.type(), mdcCode, adrgCode, drgCode, dipCode, severity,
                weight, score, matched, reason, trace);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() || value.asText().isBlank()
                ? null : value.asText();
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull() || value.asText().isBlank()) {
            return null;
        }
        try {
            return value.isNumber() ? BigDecimal.valueOf(value.asDouble())
                    : new BigDecimal(value.asText());
        } catch (NumberFormatException e) {
            throw new BusinessException(ErrorCode.INSURANCE_GROUPER_UNAVAILABLE,
                    "远端分组器返回的 " + field + " 不是数字：" + value.asText());
        }
    }
}
