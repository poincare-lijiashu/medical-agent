package com.medassist.fhir.web;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.fhir.repo.FhirQueries.DateRange;
import com.medassist.fhir.repo.FhirQueries.PatientRef;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * FHIR 检索参数解析与校验。
 *
 * <p>原则：<b>能明确拒绝就不要猜</b>。非法 UUID、非法日期、超出标准的状态值
 * 一律 400（OperationOutcome），而不是静默忽略——静默忽略会让对端以为
 * "查了但没有数据"，在联调期极难定位。
 */
public final class FhirSearchParams {

    public static final Set<String> ENCOUNTER_STATUS = Set.of(
            "planned", "arrived", "triaged", "in-progress", "onleave",
            "finished", "cancelled", "entered-in-error", "unknown");

    public static final Set<String> OBSERVATION_STATUS = Set.of(
            "registered", "preliminary", "final", "amended", "corrected",
            "cancelled", "entered-in-error", "unknown");

    public static final Set<String> MEDICATION_REQUEST_STATUS = Set.of(
            "active", "on-hold", "cancelled", "completed", "entered-in-error",
            "stopped", "draft", "unknown");

    private FhirSearchParams() {
    }

    public static UUID uuid(String raw, String label) {
        if (raw == null || raw.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, label + " 不能为空");
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    label + " 必须是 UUID：" + raw);
        }
    }

    /** {@code patient=Patient/{uuid}} 或 {@code patient={uuid}}；非 UUID 按院内患者号处理。 */
    public static PatientRef patient(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        if (value.startsWith("Patient/")) {
            value = value.substring("Patient/".length());
        }
        if (value.length() > 64) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "patient 参数过长");
        }
        try {
            return PatientRef.ofId(UUID.fromString(value));
        } catch (IllegalArgumentException e) {
            return PatientRef.ofNo(value);
        }
    }

    /**
     * {@code identifier} 解析：支持 {@code 值}、{@code 系统|值} 两种写法。
     *
     * <p>系统映射：
     * <ul>
     *   <li>{@code urn:medassist:patient-no} → 院内患者号；</li>
     *   <li>{@code urn:medassist:crosswalk:HIS} → 跨系统标识（HIS）；</li>
     *   <li>其他系统 → 视为跨系统标识的 system_code（如 {@code HIS|12345}）。</li>
     * </ul>
     */
    public static Identifier identifier(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        int sep = value.indexOf('|');
        if (sep < 0) {
            return new Identifier(null, value);
        }
        String system = value.substring(0, sep).trim();
        String idValue = value.substring(sep + 1).trim();
        if (idValue.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "identifier 的取值部分不能为空");
        }
        if (system.startsWith("urn:medassist:crosswalk:")) {
            system = system.substring("urn:medassist:crosswalk:".length());
        }
        return new Identifier(system.isBlank() ? null : system, idValue);
    }

    public record Identifier(String system, String value) {
    }

    /**
     * 日期检索：支持 {@code ge/gt/le/lt/eq} 前缀，最多两个值（典型是 ge + le）。
     *
     * <p>仅接受日期精度（yyyy-MM-dd）：底层过滤列是就诊日期/业务时间戳，
     * 接受带时分秒的值会给人"按小时过滤了"的错觉，实际只按天。
     */
    public static DateRange date(List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        if (values.size() > 2) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "date 最多支持两个值（如 date=ge2026-01-01&date=le2026-01-31）");
        }
        LocalDate from = null;
        LocalDate to = null;
        for (String raw : values) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String value = raw.trim();
            String prefix = "eq";
            for (String candidate : List.of("ge", "gt", "le", "lt", "eq")) {
                if (value.startsWith(candidate)) {
                    prefix = candidate;
                    value = value.substring(candidate.length());
                    break;
                }
            }
            LocalDate day = parseDay(value);
            switch (prefix) {
                case "ge" -> from = max(from, day);
                case "gt" -> from = max(from, day.plusDays(1));
                case "le" -> to = min(to, day);
                case "lt" -> to = min(to, day.minusDays(1));
                default -> {
                    from = max(from, day);
                    to = min(to, day);
                }
            }
        }
        if (from == null && to == null) {
            return null;
        }
        return new DateRange(from, to);
    }

    public static String status(String raw, Set<String> allowed, String resourceType) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if (!allowed.contains(value)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    resourceType + ".status 不支持：" + raw + "（可选：" + String.join("/", allowed) + "）");
        }
        return value;
    }

    public static String category(String raw) {
        return raw == null || raw.isBlank() ? null : raw.trim().toLowerCase(Locale.ROOT);
    }

    private static LocalDate parseDay(String value) {
        try {
            if (value.length() != 10) {
                throw new DateTimeParseException("length", value, 0);
            }
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "date 仅支持 yyyy-MM-dd 精度：" + value);
        }
    }

    private static LocalDate max(LocalDate current, LocalDate candidate) {
        return current == null || candidate.isAfter(current) ? candidate : current;
    }

    private static LocalDate min(LocalDate current, LocalDate candidate) {
        return current == null || candidate.isBefore(current) ? candidate : current;
    }
}
