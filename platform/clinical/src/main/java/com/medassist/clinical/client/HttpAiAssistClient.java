package com.medassist.clinical.client;

import com.medassist.clinical.config.ClinicalProperties;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.Map;

/**
 * AI 中台 HTTP 客户端（评测「HIS 深度集成」第二步：HIS → AI 反向调用）。
 *
 * <p>语义约定（与 HIS 其它上游客户端一致）：
 * <ul>
 *   <li>未配置 {@code clinical.ai.base-url} → 503 UPSTREAM_UNAVAILABLE（明确"未对接"，
 *       绝不返回空结果伪装成功）；</li>
 *   <li>缺少医护端令牌 → 401 UNAUTHORIZED（令牌透传，不新增服务账号）；</li>
 *   <li>AI 侧 4xx（如内联质控参数不合法）→ 422 VALIDATION_FAILED，不透出英文堆栈；</li>
 *   <li>AI 侧 5xx → 503；连不上/超时 → 504 GATEWAY_TIMEOUT；</li>
 *   <li>本客户端**不记录**病历正文/令牌，只记状态码与路径（PHI 最小化）。</li>
 * </ul>
 */
@Component
public class HttpAiAssistClient implements AiAssistClient {

    private static final Logger log = LoggerFactory.getLogger(HttpAiAssistClient.class);

    static final String QC_PATH = "/api/v1/medical/inline/qc-check";
    static final String RX_PATH = "/api/v1/medical/inline/rx-check";

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_OBJECT =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient client; // null = 未配置（调用时 503）

    @org.springframework.beans.factory.annotation.Autowired
    public HttpAiAssistClient(ClinicalProperties props) {
        this(props, null);
    }

    /** 测试可注入自建 RestClient（生产走上面单参构造，由 Spring 选择）。 */
    HttpAiAssistClient(ClinicalProperties props, RestClient override) {
        if (override != null) {
            this.client = override;
            return;
        }
        ClinicalProperties.Ai ai = props == null ? null : props.ai();
        String baseUrl = ai == null || ai.baseUrl() == null ? "" : ai.baseUrl().trim();
        if (baseUrl.isBlank()) {
            // 不在这里抛异常：未对接 AI 是合法形态（与 HIS_ADAPTER=none 一致），
            // 但一旦被调用必须明确 503，由调用方决定是否把按钮置灰。
            this.client = null;
            return;
        }
        Duration timeout = ai.timeout() == null ? Duration.ofSeconds(3) : ai.timeout();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        this.client = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
    }

    @Override
    public Map<String, Object> qcCheck(String bearerToken, Map<String, Object> payload) {
        return call(QC_PATH, bearerToken, payload);
    }

    @Override
    public Map<String, Object> rxCheck(String bearerToken, Map<String, Object> payload) {
        return call(RX_PATH, bearerToken, payload);
    }

    private Map<String, Object> call(String path, String bearerToken, Map<String, Object> payload) {
        if (client == null) {
            throw new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE,
                    "AI 中台未配置：请设置 CLINICAL_AI_BASE_URL 后再使用内置 AI 质控/审方");
        }
        String token = bearerToken == null ? "" : bearerToken.trim();
        if (token.isBlank()) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED,
                    "缺少会话令牌：请在 HIS 登录后使用 AI 质控/审方");
        }
        try {
            Map<String, Object> body = client.post()
                    .uri(path)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .header("X-Request-Source", "platform-clinical")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload == null ? Map.<String, Object>of() : payload)
                    .retrieve()
                    .body(JSON_OBJECT);
            return body == null ? Map.of() : body;
        } catch (HttpClientErrorException e) {
            log.warn("clinical.ai_rejected path={} status={}", path, e.getStatusCode().value());
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "AI 中台拒绝本次请求（HTTP " + e.getStatusCode().value() + "），请检查提交内容");
        } catch (HttpServerErrorException e) {
            log.warn("clinical.ai_failed path={} status={}", path, e.getStatusCode().value());
            throw new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE,
                    "AI 中台处理失败，请稍后重试");
        } catch (ResourceAccessException e) {
            log.warn("clinical.ai_unreachable path={} err={}", path, e.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.GATEWAY_TIMEOUT,
                    "AI 中台不可达或响应超时，请稍后重试");
        } catch (RestClientException e) {
            log.warn("clinical.ai_error path={} err={}", path, e.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE,
                    "AI 中台暂不可用，请稍后重试");
        }
    }
}
