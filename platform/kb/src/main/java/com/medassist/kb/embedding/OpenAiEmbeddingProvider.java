package com.medassist.kb.embedding;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.kb.config.KbProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容 {@code POST /embeddings} 实现（可对接 OpenAI / 通义 / vLLM / Ollama 的兼容网关）。
 *
 * <p>工程约束：
 * <ul>
 *   <li>未配置（开关关闭 / 无 API Key）时 {@link #available()} 为 false，上层退化为关键词检索；</li>
 *   <li>调用失败不吞异常：抛 {@link ErrorCode#KB_EMBEDDING_UNAVAILABLE}，由调用方决定降级并**显式上报**；</li>
 *   <li>返回向量统一 L2 归一化，检索侧才能用点积当余弦相似度；</li>
 *   <li>维度与配置不一致直接失败，避免同一空间混入不同模型的向量导致相似度失真。</li>
 * </ul>
 */
@Component
public class OpenAiEmbeddingProvider implements EmbeddingProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenAiEmbeddingProvider.class);

    private final KbProperties.Embedding props;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public OpenAiEmbeddingProvider(KbProperties properties, ObjectMapper objectMapper) {
        this.props = properties.embedding();
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(props.timeout())
                .build();
    }

    @Override
    public boolean available() {
        return props.usable();
    }

    @Override
    public int dimension() {
        return props.dimension();
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        if (!available()) {
            throw new BusinessException(ErrorCode.KB_EMBEDDING_UNAVAILABLE);
        }
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", props.model());
        payload.put("input", texts);
        String body;
        try {
            body = objectMapper.writeValueAsString(payload);
        } catch (RuntimeException e) {
            throw new BusinessException(ErrorCode.KB_EMBEDDING_UNAVAILABLE, "向量请求无法序列化");
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(trimTrailingSlash(props.baseUrl())
                        + "/embeddings"))
                .timeout(props.timeout())
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + props.apiKey())
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.KB_EMBEDDING_UNAVAILABLE, "向量模型调用被中断");
        } catch (Exception e) {
            log.warn("kb.embedding.unavailable err={}", e.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.KB_EMBEDDING_UNAVAILABLE, "向量模型暂不可用");
        }
        if (response.statusCode() / 100 != 2) {
            log.warn("kb.embedding.http_error status={}", response.statusCode());
            throw new BusinessException(ErrorCode.KB_EMBEDDING_UNAVAILABLE,
                    "向量模型返回异常状态：" + response.statusCode());
        }
        return parse(response.body(), texts.size());
    }

    private List<float[]> parse(String body, int expected) {
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (RuntimeException e) {
            throw new BusinessException(ErrorCode.KB_EMBEDDING_UNAVAILABLE, "向量模型返回内容无法解析");
        }
        JsonNode data = root.path("data");
        if (!data.isArray() || data.size() != expected) {
            throw new BusinessException(ErrorCode.KB_EMBEDDING_UNAVAILABLE,
                    "向量模型返回条数与请求不一致");
        }
        List<float[]> vectors = new ArrayList<>(expected);
        for (JsonNode item : data) {
            JsonNode values = item.path("embedding");
            if (!values.isArray() || values.size() != props.dimension()) {
                throw new BusinessException(ErrorCode.KB_EMBEDDING_UNAVAILABLE,
                        "向量维度与配置不一致（期望 " + props.dimension() + "）");
            }
            float[] vector = new float[values.size()];
            for (int i = 0; i < values.size(); i++) {
                vector[i] = (float) values.get(i).asDouble();
            }
            vectors.add(normalize(vector));
        }
        return vectors;
    }

    /** L2 归一化：检索侧用点积代替余弦，避免每行重复算模长。 */
    static float[] normalize(float[] vector) {
        double sum = 0;
        for (float v : vector) {
            sum += (double) v * v;
        }
        double norm = Math.sqrt(sum);
        if (norm <= 1e-12) {
            return vector;
        }
        float[] out = new float[vector.length];
        for (int i = 0; i < vector.length; i++) {
            out[i] = (float) (vector[i] / norm);
        }
        return out;
    }

    private static String trimTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
