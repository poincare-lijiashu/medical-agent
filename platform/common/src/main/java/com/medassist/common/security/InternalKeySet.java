package com.medassist.common.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.Map;

/**
 * 密钥集解析与选择（MF-07）。
 *
 * <p>JSON 结构：{@code {"active":"k2","keys":{"k1":"<base64>","k2":"<base64>"}}}。
 * 校验时按请求头 {@code X-Internal-Key-Id} 选密钥，因此轮换期可同时接受新旧两把密钥。
 */
public final class InternalKeySet {

    private final String activeKeyId;
    private final Map<String, String> keys;

    private InternalKeySet(String activeKeyId, Map<String, String> keys) {
        this.activeKeyId = activeKeyId;
        this.keys = keys;
    }

    public static InternalKeySet empty() {
        return new InternalKeySet(null, Map.of());
    }

    public static InternalKeySet parse(String json, ObjectMapper mapper) {
        if (json == null || json.isBlank()) {
            return empty();
        }
        try {
            JsonNode root = mapper.readTree(json);
            String active = root.path("active").asText(null);
            Map<String, String> keys = new HashMap<>();
            JsonNode keysNode = root.path("keys");
            keysNode.fields().forEachRemaining(entry -> {
                String secret = entry.getValue().asText("");
                if (!secret.isBlank()) {
                    keys.put(entry.getKey(), secret);
                }
            });
            return new InternalKeySet(active, Map.copyOf(keys));
        } catch (Exception e) {
            throw new IllegalStateException("medassist.internal-auth.keys-json 解析失败：" + e.getMessage(), e);
        }
    }

    public boolean configured() {
        return !keys.isEmpty();
    }

    public String activeKeyId() {
        return activeKeyId;
    }

    /** 按 keyId 取密钥；未知 keyId 返回 null（由调用方判定为 403）。 */
    public String secret(String keyId) {
        return keyId == null ? null : keys.get(keyId);
    }
}
