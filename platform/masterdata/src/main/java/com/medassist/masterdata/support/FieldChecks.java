package com.medassist.masterdata.support;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 主数据字段校验（变更申请与批量导入共用）。
 *
 * <p>共用一套校验的原因：导入与单条申请如果各写一套，必然出现"单条能过、批量报错"
 * 或反向的漂移。所有主数据文本都按"去首尾空格、超长拒绝、空串视为未填"处理。
 */
public final class FieldChecks {

    /** 本地编码：字母/数字开头，允许点、下划线、冒号、短横线；最长 64。 */
    private static final Pattern CODE_PATTERN =
            Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:\\-]{0,63}$");

    private FieldChecks() {
    }

    public static String requireCode(String code) {
        if (code == null || !CODE_PATTERN.matcher(code.trim()).matches()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "本地编码必须以字母或数字开头，只能包含字母、数字、点、下划线、冒号、短横线，长度 1–64");
        }
        return code.trim();
    }

    public static String requireText(Object value, String label, int max) {
        if (!(value instanceof String text) || text.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "缺少必填字段：" + label);
        }
        String trimmed = text.trim();
        if (trimmed.length() > max) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, label + "最多 " + max + " 个字符");
        }
        return trimmed;
    }

    public static String optionalText(Object value, int max) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "字段格式不正确，应为文本");
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > max) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "文本最长 " + max + " 个字符");
        }
        return trimmed;
    }

    public static String optionalEnum(Object value, Set<String> allowed, String defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (!(value instanceof String text) || !allowed.contains(text.trim())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "取值不合法，只能是 " + String.join(" / ", new LinkedHashSet<>(allowed)));
        }
        return text.trim();
    }

    public static boolean bool(Map<String, Object> fields, String key) {
        Object value = fields.get(key);
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean flag) {
            return flag;
        }
        throw new BusinessException(ErrorCode.VALIDATION_FAILED, key + " 必须是布尔值");
    }

    public static long longValue(Object value, long defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        throw new BusinessException(ErrorCode.MDM_CHANGE_REQUEST_INVALID, "变更申请版本字段损坏");
    }

    /** 行数/条数上限校验。 */
    public static int within(int actual, int max, String label) {
        if (actual < 1) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, label + "不能为空");
        }
        if (actual > max) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    label + "最多 " + max + " 条，请拆分后再提交");
        }
        return actual;
    }

    public static Map<String, Object> attributes(Object value) {
        if (value == null) {
            return Map.of();
        }
        if (!(value instanceof Map<?, ?> raw)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "attributes 必须是对象");
        }
        if (raw.size() > 50) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "attributes 最多 50 个键");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            String key = String.valueOf(entry.getKey());
            if (key.isBlank() || key.length() > 64) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "attributes 键必须非空且不超过 64 个字符");
            }
            Object item = entry.getValue();
            if (item == null || item instanceof Number || item instanceof Boolean) {
                result.put(key, item);
            } else if (item instanceof String text) {
                if (text.length() > 500) {
                    throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                            "attributes 文本值最多 500 个字符");
                }
                result.put(key, text);
            } else {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "attributes 值只能是文本、数字、布尔或空值");
            }
        }
        return result;
    }

    public static String optionalDate(Object value) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "日期格式应为 yyyy-MM-dd");
        }
        if (text.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(text.trim()).toString();
        } catch (DateTimeParseException e) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "日期格式应为 yyyy-MM-dd");
        }
    }

    public static void checkDateOrder(String from, String to) {
        if (from != null && to != null && LocalDate.parse(to).isBefore(LocalDate.parse(from))) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "失效日期不能早于生效日期");
        }
    }

    public static String iso(LocalDate date) {
        return date == null ? null : date.toString();
    }

    public static LocalDate parseDate(String value) {
        return value == null ? null : LocalDate.parse(value);
    }
}
