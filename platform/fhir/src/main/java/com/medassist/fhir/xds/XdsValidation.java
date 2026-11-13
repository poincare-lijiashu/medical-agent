package com.medassist.fhir.xds;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * XDS 入参校验与哈希工具。
 *
 * <p>把规则从 Service 里抽出来，是为了让"同一个唯一标识在注册与检索时判定一致"
 * ——否则注册接受 {@code urn:uuid:...}、检索只认 OID，会出现"刚注册就查不到"。
 */
public final class XdsValidation {

    /** XDS uniqueId：OID 或 urn；只允许常见安全字符，禁止空白与路径字符。 */
    private static final Pattern UNIQUE_ID = Pattern.compile("^[0-9A-Za-z][0-9A-Za-z._:-]{2,191}$");
    /** 文档事件码等短码。 */
    private static final Pattern SHORT_CODE = Pattern.compile("^[0-9A-Za-z][0-9A-Za-z._:-]{0,63}$");
    private static final Pattern SHA256_HEX = Pattern.compile("^[0-9a-fA-F]{64}$");

    private XdsValidation() {
    }

    public static String requireText(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.XDS_METADATA_INVALID, field + "不能为空");
        }
        String trimmed = value.trim();
        if (trimmed.length() > maxLength) {
            throw new BusinessException(ErrorCode.XDS_METADATA_INVALID,
                    field + "长度不能超过 " + maxLength + " 个字符");
        }
        return trimmed;
    }

    public static String optionalText(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() > maxLength) {
            throw new BusinessException(ErrorCode.XDS_METADATA_INVALID,
                    field + "长度不能超过 " + maxLength + " 个字符");
        }
        return trimmed;
    }

    public static String requireUniqueId(String value, String field) {
        String trimmed = requireText(value, field, 192);
        if (!UNIQUE_ID.matcher(trimmed).matches()) {
            throw new BusinessException(ErrorCode.XDS_METADATA_INVALID,
                    field + "格式不合法：应为 OID 或 urn（如 1.2.156.112.1.1 或 urn:uuid:...）");
        }
        return trimmed;
    }

    public static List<String> optionalCodes(List<String> values, String field, int maxCount) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        if (values.size() > maxCount) {
            throw new BusinessException(ErrorCode.XDS_METADATA_INVALID,
                    field + "最多 " + maxCount + " 项");
        }
        List<String> codes = new ArrayList<>(values.size());
        for (String raw : values) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String code = raw.trim();
            if (!SHORT_CODE.matcher(code).matches()) {
                throw new BusinessException(ErrorCode.XDS_METADATA_INVALID,
                        field + "包含非法编码：" + code);
            }
            if (!codes.contains(code)) {
                codes.add(code);
            }
        }
        return List.copyOf(codes);
    }

    public static UUID parseUuid(String value, String field) {
        try {
            return UUID.fromString(requireText(value, field, 64));
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.XDS_METADATA_INVALID, field + "必须是合法 UUID");
        }
    }

    public static UUID optionalUuid(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return parseUuid(value, field);
    }

    /** 只接受 ISO-8601 带时区的时间（避免"把本地时间当 UTC"造成 8 小时偏差）。 */
    public static Instant requireInstant(String value, String field) {
        String trimmed = requireText(value, field, 64);
        try {
            return OffsetDateTime.parse(trimmed).toInstant();
        } catch (DateTimeParseException ignored) {
            try {
                return Instant.parse(trimmed);
            } catch (DateTimeParseException e) {
                throw new BusinessException(ErrorCode.XDS_METADATA_INVALID,
                        field + "格式不合法：应为带时区的 ISO-8601 时间（如 2026-11-13T09:30:00+08:00）");
            }
        }
    }

    public static Instant optionalInstant(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return requireInstant(value, field);
    }

    /** 检索用的起止时间：允许只写日期，按 UTC 当日 0 点解释。 */
    public static Instant parseQueryInstant(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() == 10) {
            try {
                return LocalDate.parse(trimmed).atStartOfDay().toInstant(ZoneOffset.UTC);
            } catch (DateTimeParseException e) {
                throw new BusinessException(ErrorCode.XDS_METADATA_INVALID,
                        field + "格式不合法：应为 YYYY-MM-DD");
            }
        }
        return requireInstant(trimmed, field);
    }

    public static String requireHash(String value, String field) {
        String trimmed = requireText(value, field, 64).toLowerCase(Locale.ROOT);
        if (!SHA256_HEX.matcher(trimmed).matches()) {
            throw new BusinessException(ErrorCode.XDS_METADATA_INVALID,
                    field + "必须是 64 位十六进制 SHA-256");
        }
        return trimmed;
    }

    /** 计算 SHA-256（小写十六进制）。 */
    public static String sha256Hex(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 缺少 SHA-256 实现", e);
        }
    }

    public static boolean hashEquals(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII),
                actual.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII));
    }

    /** 简单 JSON 字符串数组（元素已按短码校验，不需要通用序列化器）。 */
    public static String toJsonArray(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(values.get(i)).append('"');
        }
        return sb.append(']').toString();
    }

    /** 解析 {@link #toJsonArray} 产出的字符串数组；格式异常时返回空集合而不是抛错。 */
    public static List<String> parseJsonArray(String json) {
        if (json == null || json.isBlank() || "[]".equals(json.trim())) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        int i = 0;
        while (i < json.length()) {
            int start = json.indexOf('"', i);
            if (start < 0) {
                break;
            }
            int end = json.indexOf('"', start + 1);
            if (end < 0) {
                break;
            }
            values.add(json.substring(start + 1, end));
            i = end + 1;
        }
        return List.copyOf(values);
    }
}
