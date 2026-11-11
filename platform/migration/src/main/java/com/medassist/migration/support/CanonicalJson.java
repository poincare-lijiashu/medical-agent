package com.medassist.migration.support;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 规范化 JSON 与校验和。
 *
 * <p>为什么不用 {@code ObjectMapper.writeValueAsString} 直接算校验和：
 * 同一份数据换一个 Map 实现、多一个空格或 {@code 10.0} vs {@code 10.00}，
 * 序列化结果就不同，校验和随之漂移——预检与应用会被误判为"内容不一致"。
 * 这里对 Map 键排序、数字去尾零、字符串按 UTF-8 原样，保证跨进程稳定。
 */
public final class CanonicalJson {

    private CanonicalJson() {
    }

    /** 规范化 JSON 文本（Map 键升序、数字去尾零）。 */
    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        append(sb, value);
        return sb.toString();
    }

    private static void append(StringBuilder sb, Object value) {
        switch (value) {
            case null -> sb.append("null");
            case String s -> quote(sb, s);
            case Boolean b -> sb.append(b);
            case BigDecimal decimal -> sb.append(decimal.stripTrailingZeros().toPlainString());
            case Number number -> sb.append(number);
            case Map<?, ?> map -> {
                Map<String, Object> sorted = new TreeMap<>();
                map.forEach((k, v) -> sorted.put(String.valueOf(k), v));
                sb.append('{');
                boolean first = true;
                for (Map.Entry<String, Object> entry : sorted.entrySet()) {
                    if (!first) {
                        sb.append(',');
                    }
                    first = false;
                    quote(sb, entry.getKey());
                    sb.append(':');
                    append(sb, entry.getValue());
                }
                sb.append('}');
            }
            case Iterable<?> iterable -> {
                sb.append('[');
                boolean first = true;
                List<Object> copy = new ArrayList<>();
                iterable.forEach(copy::add);
                for (Object item : copy) {
                    if (!first) {
                        sb.append(',');
                    }
                    first = false;
                    append(sb, item);
                }
                sb.append(']');
            }
            default -> quote(sb, String.valueOf(value));
        }
    }

    private static void quote(StringBuilder sb, String value) {
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    /** sha256(prev + canonicalJson(value))，用于批次校验和与载荷指纹。 */
    public static String checksum(String prev, Object value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            if (prev != null) {
                md.update(prev.getBytes(StandardCharsets.UTF_8));
            }
            md.update(write(value).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(md.digest());
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
