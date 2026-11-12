package com.medassist.license.support;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 授权书规范化文本（验签对象）。
 *
 * <p>签名必须落在**逐字节稳定**的内容上：JSON 对象的键序、空白、数字格式都可能被
 * 不同实现改写成不同字节，导致同一个对象验签失败或（更糟）同一份授权产生不同哈希。
 * 这里不用通用序列化器，而是按固定键序手写规范化 JSON，规则写死在本类：
 * <ul>
 *   <li>键按字母序；</li>
 *   <li>时间统一 {@link Instant#toString()}（UTC，秒/毫秒精度由值决定）；</li>
 *   <li>模块码去空白、去重、按字母序；</li>
 *   <li>空值与 0 也显式写出（null 表示"不限"，0 表示"不允许"，语义不同）。</li>
 * </ul>
 */
public final class LicensePayloads {

    public static final String SCHEMA = "medassist-license/v1";
    public static final String PRODUCT = "MedAssist";

    private LicensePayloads() {
    }

    /** 生成规范化 JSON（验签与哈希都基于此文本）。 */
    public static String canonical(String licenseNo,
                                   String instanceCode,
                                   String hospitalName,
                                   String editionCode,
                                   List<String> modules,
                                   Integer maxBeds,
                                   Integer maxUsers,
                                   Integer maxAiCallsPerDay,
                                   Instant notBefore,
                                   Instant expiresAt,
                                   int graceDays,
                                   Instant issuedAt) {
        List<String> codes = normalizeModules(modules);
        StringBuilder sb = new StringBuilder(320);
        sb.append('{');
        sb.append("\"editionCode\":").append(q(editionCode)).append(',');
        sb.append("\"expiresAt\":").append(q(text(expiresAt))).append(',');
        sb.append("\"graceDays\":").append(graceDays).append(',');
        sb.append("\"hospitalName\":").append(q(hospitalName)).append(',');
        sb.append("\"instanceCode\":").append(q(instanceCode)).append(',');
        sb.append("\"issuedAt\":").append(q(text(issuedAt))).append(',');
        sb.append("\"licenseNo\":").append(q(licenseNo)).append(',');
        sb.append("\"maxAiCallsPerDay\":").append(number(maxAiCallsPerDay)).append(',');
        sb.append("\"maxBeds\":").append(number(maxBeds)).append(',');
        sb.append("\"maxUsers\":").append(number(maxUsers)).append(',');
        sb.append("\"modules\":[");
        for (int i = 0; i < codes.size(); i += 1) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(q(codes.get(i)));
        }
        sb.append("],");
        sb.append("\"notBefore\":").append(q(text(notBefore))).append(',');
        sb.append("\"product\":").append(q(PRODUCT)).append(',');
        sb.append("\"schema\":").append(q(SCHEMA));
        sb.append('}');
        return sb.toString();
    }

    /** 模块码规范化：去空白、转小写、去重、按字母序（同一份授权的哈希因此稳定）。 */
    public static List<String> normalizeModules(List<String> modules) {
        if (modules == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String raw : modules) {
            if (raw == null) {
                continue;
            }
            String code = raw.trim().toLowerCase();
            if (!code.isEmpty() && !out.contains(code)) {
                out.add(code);
            }
        }
        out.sort(String::compareTo);
        return out;
    }

    private static String text(Instant instant) {
        return instant == null ? "" : instant.toString();
    }

    private static String number(Integer value) {
        return value == null ? "null" : value.toString();
    }

    private static String q(String value) {
        String v = value == null ? "" : value;
        StringBuilder sb = new StringBuilder(v.length() + 2);
        sb.append('"');
        for (int i = 0; i < v.length(); i += 1) {
            char c = v.charAt(i);
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
        return sb.toString();
    }

    /** 规范文本的 UTF-8 字节（签名输入）。 */
    public static byte[] bytes(String canonical) {
        return canonical.getBytes(StandardCharsets.UTF_8);
    }
}
