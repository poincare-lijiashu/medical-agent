package com.medassist.common.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.Map;

/**
 * 密钥集解析与选择（MF-07）。
 *
 * <p>JSON 结构：
 * <pre>
 * {
 *   "active": "k2",
 *   "keys": {"k1": "&lt;base64&gt;", "k2": "&lt;base64&gt;"},          // 网关通道，支持轮换
 *   "internal": {"clinical": "&lt;base64&gt;", "emr": "&lt;base64&gt;"}   // 调用方通道，按调用方隔离
 * }
 * </pre>
 *
 * <p>网关通道按请求头 {@code X-Internal-Key-Id} 选密钥，轮换期可同时接受新旧两把；
 * 调用方通道按 {@code X-Internal-Caller} 选密钥，一个调用方的密钥泄露不影响其它调用方。
 */
public final class InternalKeySet {

    private final String activeKeyId;
    private final Map<String, String> keys;
    private final Map<String, String> callerKeys;

    private InternalKeySet(String activeKeyId, Map<String, String> keys,
                           Map<String, String> callerKeys) {
        this.activeKeyId = activeKeyId;
        this.keys = keys;
        this.callerKeys = callerKeys;
    }

    public static InternalKeySet empty() {
        return new InternalKeySet(null, Map.of(), Map.of());
    }

    public static InternalKeySet parse(String json, ObjectMapper mapper) {
        if (json == null || json.isBlank()) {
            return empty();
        }
        try {
            JsonNode root = mapper.readTree(json);
            String active = root.path("active").asText(null);
            Map<String, String> keys = new HashMap<>();
            root.path("keys").fields().forEachRemaining(entry -> {
                String secret = entry.getValue().asText("");
                if (!secret.isBlank()) {
                    keys.put(entry.getKey(), secret);
                }
            });
            Map<String, String> callerKeys = new HashMap<>();
            root.path("internal").fields().forEachRemaining(entry -> {
                String secret = entry.getValue().asText("");
                if (!secret.isBlank()) {
                    callerKeys.put(entry.getKey().toLowerCase(), secret);
                }
            });
            return new InternalKeySet(active, Map.copyOf(keys), Map.copyOf(callerKeys));
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

    /** 调用方密钥集是否已配置（服务→服务通道强制校验的前置条件）。 */
    public boolean callerConfigured() {
        return !callerKeys.isEmpty();
    }

    /** 按调用方取密钥；未配置该调用方返回 null（403）。 */
    public String callerSecret(String caller) {
        return caller == null ? null : callerKeys.get(caller.toLowerCase());
    }
}
