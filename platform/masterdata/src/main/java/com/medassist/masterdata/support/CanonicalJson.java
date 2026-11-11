package com.medassist.masterdata.support;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * 规范化 JSON 与校验和。
 *
 * <p>为什么必须"规范化"而不是直接序列化：
 * <ul>
 *   <li>PostgreSQL 的 {@code jsonb} <b>不保留键顺序</b>（按长度/字节归一化），
 *       写入前与读回后的序列化结果会不同；</li>
 *   <li>数值 {@code 1} / {@code 1.0} / {@code 4800.00} 在库里与 Java 里的标度不一致；</li>
 *   <li>版本链的 checksum 要求"同一内容永远得到同一个摘要"，否则校验必然误报断裂。</li>
 * </ul>
 *
 * <p>规则：对象键按字典序、数组保持顺序、数值去掉无意义尾零、空值写 {@code null}，
 * 全部紧凑输出（无空格）。日期等非 JSON 标量禁止进入快照，统一由调用方转成 ISO 字符串。
 */
public final class CanonicalJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CanonicalJson() {
    }

    /** 规范化 JSON 文本（键排序、数值归一、紧凑输出）。 */
    public static String canonical(Object value) {
        JsonNode node = MAPPER.valueToTree(value);
        StringBuilder sb = new StringBuilder(256);
        append(node, sb);
        return sb.toString();
    }

    /** 计算版本校验和：{@code sha256(prev + "\n" + canonical(value))}。 */
    public static String checksum(String prev, Object value) {
        String seed = (prev == null ? "" : prev) + "\n" + canonical(value);
        return sha256(seed);
    }

    /** 十六进制 SHA-256。 */
    public static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // JVM 必须支持 SHA-256；走到这里说明运行环境被裁剪，不能降级成弱摘要
            throw new IllegalStateException("运行环境缺少 SHA-256 摘要算法", e);
        }
    }

    private static void append(JsonNode node, StringBuilder sb) {
        if (node == null || node.isNull()) {
            sb.append("null");
            return;
        }
        if (node.isObject()) {
            List<String> names = new ArrayList<>(node.propertyNames());
            names.sort(String::compareTo);
            sb.append('{');
            boolean first = true;
            for (String name : names) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                appendString(name, sb);
                sb.append(':');
                append(node.get(name), sb);
            }
            sb.append('}');
            return;
        }
        if (node.isArray()) {
            sb.append('[');
            boolean first = true;
            for (JsonNode child : node) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                append(child, sb);
            }
            sb.append(']');
            return;
        }
        if (node.isNumber()) {
            sb.append(number(node.decimalValue()));
            return;
        }
        if (node.isBoolean()) {
            sb.append(node.booleanValue());
            return;
        }
        if (node.isString()) {
            appendString(node.stringValue(), sb);
            return;
        }
        // 二进制/特殊节点不是快照的合法内容；序列化其 JSON 表示，保证校验和仍可复算
        appendString(MAPPER.writeValueAsString(node), sb);
    }

    private static String number(BigDecimal value) {
        if (value == null) {
            return "0";
        }
        BigDecimal normalized = value.stripTrailingZeros();
        if (normalized.scale() <= 0) {
            return normalized.toBigInteger().toString();
        }
        return normalized.toPlainString();
    }

    private static void appendString(String text, StringBuilder sb) {
        sb.append(MAPPER.writeValueAsString(text));
    }
}
