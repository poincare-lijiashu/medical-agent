package com.medassist.migration.support;

import com.medassist.common.time.BusinessTime;
import com.medassist.migration.domain.MigrationModel.FieldDef;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 迁移值归一化与展示。
 *
 * <p>归一化结果统一为<b>规范字符串</b>：数字不带尾零、日期 ISO、时间带偏移量、布尔 true/false。
 * 这样做的好处是预检、校验和、加密载荷、人工核对看到的是同一个值，
 * 不会出现"预览显示 10.0、入库变成 10.00、回滚比较判定为不等"。
 */
public final class Values {

    /** 患者敏感字段：预览与原文投影中必须掩码，完整值只进加密载荷。 */
    private static final Set<String> SENSITIVE_FIELDS = Set.of(
            "id_no", "phone", "address", "证件号", "手机号", "住址", "身份证号", "联系电话");

    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("yyyy/M/d"),
            DateTimeFormatter.ofPattern("yyyy.M.d"),
            DateTimeFormatter.BASIC_ISO_DATE);

    private static final List<DateTimeFormatter> DATE_TIME_FORMATS = List.of(
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
            DateTimeFormatter.ofPattern("yyyy/M/d HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy/M/d HH:mm"),
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));

    private static final DateTimeFormatter CANONICAL_INSTANT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

    private Values() {
    }

    public static boolean sensitive(String fieldOrHeader) {
        if (fieldOrHeader == null) {
            return false;
        }
        String key = fieldOrHeader.trim();
        if (SENSITIVE_FIELDS.contains(key)) {
            return true;
        }
        // 旧系统列名不统一（"证件号码"/"身份证号"/"患者手机"/"家庭住址"），按语义包含判断，
        // 但排除"证件类型""手机归属地"这类非敏感列。
        return (key.contains("证件号") || key.contains("身份证") || key.contains("手机号")
                || key.contains("联系电话") || key.contains("住址") || key.contains("家庭地址"))
                && !key.contains("类型");
    }

    /** 归一化单字段；空值返回 null。值不合法时抛 {@link IllegalArgumentException}（中文原因）。 */
    public static String normalize(FieldDef field, String raw, Map<String, String> enumAliases) {
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty()) {
            return null;
        }
        if (field.maxLength() != null && text.length() > field.maxLength()) {
            throw new IllegalArgumentException(
                    field.label() + "长度超过 " + field.maxLength() + " 个字符");
        }
        return switch (field.dataType()) {
            case "TEXT" -> text;
            case "INT" -> normalizeInt(field, text);
            case "DECIMAL" -> normalizeDecimal(field, text);
            case "DATE" -> normalizeDate(field, text);
            case "TIMESTAMPTZ" -> normalizeInstant(field, text);
            case "BOOL" -> normalizeBool(field, text);
            case "ENUM" -> normalizeEnum(field, text, enumAliases);
            default -> text;
        };
    }

    private static String normalizeInt(FieldDef field, String text) {
        try {
            BigDecimal decimal = new BigDecimal(text.replace(",", ""));
            if (decimal.stripTrailingZeros().scale() > 0) {
                throw new NumberFormatException("not integer");
            }
            return String.valueOf(decimal.longValueExact());
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(field.label() + "必须是整数：" + text);
        }
    }

    private static String normalizeDecimal(FieldDef field, String text) {
        try {
            BigDecimal decimal = new BigDecimal(text.replace(",", ""));
            return decimal.stripTrailingZeros().toPlainString();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(field.label() + "必须是数字：" + text);
        }
    }

    private static String normalizeDate(FieldDef field, String text) {
        for (DateTimeFormatter format : DATE_FORMATS) {
            try {
                return LocalDate.parse(text, format).toString();
            } catch (DateTimeParseException ignored) {
                // 继续尝试下一种格式
            }
        }
        // 兼容 Excel 导出的 "2026/1/2 0:00"
        int space = text.indexOf(' ');
        if (space > 0) {
            return normalizeDate(field, text.substring(0, space));
        }
        throw new IllegalArgumentException(field.label() + "不是合法日期（示例 2026-01-02）：" + text);
    }

    private static String normalizeInstant(FieldDef field, String text) {
        // 带偏移量/时区的 ISO-8601
        try {
            return OffsetDateTime.parse(text).toInstant()
                    .atZone(BusinessTime.zone()).toOffsetDateTime().format(CANONICAL_INSTANT);
        } catch (DateTimeParseException ignored) {
            // 尝试本地日期时间格式
        }
        try {
            Instant instant = Instant.parse(text);
            return instant.atZone(BusinessTime.zone()).toOffsetDateTime().format(CANONICAL_INSTANT);
        } catch (DateTimeParseException ignored) {
            // 继续
        }
        for (DateTimeFormatter format : DATE_TIME_FORMATS) {
            try {
                LocalDateTime local = LocalDateTime.parse(text, format);
                return local.atZone(BusinessTime.zone()).toOffsetDateTime().format(CANONICAL_INSTANT);
            } catch (DateTimeParseException ignored) {
                // 继续
            }
        }
        // 只有日期：按当天 00:00:00 处理
        try {
            LocalDate date = LocalDate.parse(text, DATE_FORMATS.get(0));
            return date.atStartOfDay(BusinessTime.zone()).toOffsetDateTime().format(CANONICAL_INSTANT);
        } catch (DateTimeParseException ignored) {
            // 落到错误
        }
        throw new IllegalArgumentException(
                field.label() + "不是合法时间（示例 2026-01-02 08:30:00）：" + text);
    }

    private static String normalizeBool(FieldDef field, String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        return switch (lower) {
            case "true", "1", "y", "yes", "是", "启用", "有" -> "true";
            case "false", "0", "n", "no", "否", "停用", "无" -> "false";
            default -> throw new IllegalArgumentException(field.label() + "必须是布尔值（是/否）：" + text);
        };
    }

    private static String normalizeEnum(FieldDef field, String text, Map<String, String> aliases) {
        String upper = text.toUpperCase(Locale.ROOT);
        if (field.enumValues() != null) {
            if (field.enumValues().contains(upper)) {
                return upper;
            }
            if (aliases != null) {
                String mapped = aliases.get(text);
                if (mapped != null && field.enumValues().contains(mapped)) {
                    return mapped;
                }
            }
        }
        throw new IllegalArgumentException(
                field.label() + "取值不在允许范围 " + field.enumValues() + "：" + text);
    }

    /** 数据库值 → 规范字符串（用于 UPDATE/UNCHANGED 比较）。 */
    public static String canonicalDbValue(FieldDef field, Object value) {
        if (value == null) {
            return null;
        }
        return switch (field.dataType()) {
            case "INT" -> String.valueOf(((Number) value).longValue());
            case "DECIMAL" -> ((BigDecimal) toDecimal(value)).stripTrailingZeros().toPlainString();
            case "DATE" -> value instanceof LocalDate date ? date.toString() : String.valueOf(value);
            case "TIMESTAMPTZ" -> {
                if (value instanceof java.sql.Timestamp ts) {
                    yield ts.toInstant().atZone(BusinessTime.zone()).toOffsetDateTime()
                            .format(CANONICAL_INSTANT);
                }
                if (value instanceof OffsetDateTime odt) {
                    yield odt.atZoneSameInstant(BusinessTime.zone()).toOffsetDateTime()
                            .format(CANONICAL_INSTANT);
                }
                if (value instanceof Instant instant) {
                    yield instant.atZone(BusinessTime.zone()).toOffsetDateTime()
                            .format(CANONICAL_INSTANT);
                }
                yield String.valueOf(value);
            }
            case "BOOL" -> Boolean.TRUE.equals(value) || "t".equalsIgnoreCase(String.valueOf(value))
                    ? "true" : "false";
            default -> String.valueOf(value);
        };
    }

    private static BigDecimal toDecimal(Object value) {
        return value instanceof BigDecimal decimal ? decimal : new BigDecimal(String.valueOf(value));
    }

    /** 规范字符串 → JDBC 参数。 */
    public static Object toJdbc(FieldDef field, String canonical) {
        if (canonical == null) {
            return null;
        }
        return switch (field.dataType()) {
            case "INT" -> Long.valueOf(canonical);
            case "DECIMAL" -> new BigDecimal(canonical);
            case "DATE" -> LocalDate.parse(canonical);
            case "TIMESTAMPTZ" -> OffsetDateTime.parse(canonical);
            case "BOOL" -> Boolean.valueOf(canonical);
            default -> canonical;
        };
    }

    /** 掩码：保留首尾各 1 位（便于人工核对），其余替换为 *。 */
    public static String mask(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        String trimmed = value.trim();
        if (trimmed.length() <= 2) {
            return "**";
        }
        if (trimmed.length() <= 6) {
            return trimmed.charAt(0) + "****";
        }
        return trimmed.charAt(0) + "******" + trimmed.charAt(trimmed.length() - 1);
    }

    public static ZoneId zone() {
        return BusinessTime.zone();
    }
}
