package com.medassist.migration.service;

import com.medassist.migration.catalog.TargetCatalog;
import com.medassist.migration.domain.MigrationModel.FieldDef;
import com.medassist.migration.domain.MigrationModel.MappingProfile;
import com.medassist.migration.domain.MigrationModel.MappingRule;
import com.medassist.migration.support.Values;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 行归一化：把"源系统的一行"变成"目标字段的规范字符串"。
 *
 * <p>这一层不碰数据库：类型、长度、枚举、必填、跨字段业务规则全在这里判定。
 * 好处是上万行预检不需要查库就能筛掉绝大多数脏数据，错误信息也能直接指到列。
 */
@Component
public class RowNormalizer {

    public NormalizedRow normalize(TargetCatalog.Spec spec, MappingProfile profile,
                                   Map<String, String> rawRow) {
        Map<String, String> values = new LinkedHashMap<>();
        Set<String> provided = new LinkedHashSet<>();

        if (profile != null && !profile.mappings().isEmpty()) {
            applyProfile(profile, rawRow, values, provided);
        } else {
            for (FieldDef field : spec.fields()) {
                if (rawRow.containsKey(field.fieldKey())) {
                    provided.add(field.fieldKey());
                    values.put(field.fieldKey(), rawRow.get(field.fieldKey()));
                }
            }
        }

        // 去除未知字段（映射档案可能残留已删除字段），避免"看起来提供了、实际没落库"
        values.keySet().retainAll(spec.fieldKeys());

        Map<String, String> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            FieldDef field = spec.field(entry.getKey());
            try {
                normalized.put(field.fieldKey(),
                        Values.normalize(field, entry.getValue(), spec.enumAliases().get(field.fieldKey())));
            } catch (IllegalArgumentException e) {
                throw new RowProblem(e.getMessage());
            }
        }

        // 空白可选字段回落到目标默认值：既避免 NOT NULL 列被写成 null，
        // 也让"旧系统没维护这个字段"得到与手工录入一致的缺省语义。
        spec.defaults().forEach((field, value) -> {
            if (isBlank(normalized.get(field))) {
                normalized.put(field, value);
                provided.add(field);
            }
        });

        businessRules(spec, normalized);

        for (FieldDef field : spec.fields()) {
            if (field.required() && isBlank(normalized.get(field.fieldKey()))) {
                throw new RowProblem(field.label() + "（" + field.fieldKey() + "）不能为空");
            }
        }

        Map<String, String> createValues = new LinkedHashMap<>(normalized);

        return new NormalizedRow(normalized, createValues, provided,
                rawProjection(rawRow), normalizedProjection(spec, normalized));
    }

    /**
     * 从加密载荷还原（应用/回滚时使用）。
     *
     * <p>载荷里已经是规范字符串，这里只重新做必填与跨字段校验，防止"预检后改了代码规则、
     * 旧批次绕过新规则"。
     */
    public NormalizedRow restore(TargetCatalog.Spec spec, Map<String, String> values) {
        Map<String, String> normalized = new LinkedHashMap<>();
        values.forEach((field, value) -> {
            if (spec.findField(field).isPresent()) {
                normalized.put(field, value);
            }
        });
        spec.defaults().forEach((field, value) -> {
            if (isBlank(normalized.get(field))) {
                normalized.put(field, value);
            }
        });
        for (FieldDef field : spec.fields()) {
            if (field.required() && isBlank(normalized.get(field.fieldKey()))) {
                throw new RowProblem(field.label() + "（" + field.fieldKey() + "）不能为空");
            }
        }
        businessRules(spec, normalized);

        Map<String, String> createValues = new LinkedHashMap<>(normalized);
        return new NormalizedRow(normalized, createValues, normalized.keySet(), Map.of(), Map.of());
    }

    // ------------------------------------------------------------------ 映射档案

    private void applyProfile(MappingProfile profile, Map<String, String> rawRow,
                              Map<String, String> values, Set<String> provided) {
        for (MappingRule rule : profile.mappings()) {
            if (rule.field() == null || rule.field().isBlank()) {
                throw new RowProblem("映射档案存在未指定目标字段的规则");
            }
            String transform = rule.transform() == null ? "TRIM"
                    : rule.transform().trim().toUpperCase(Locale.ROOT);
            if ("CONSTANT".equals(transform)) {
                values.put(rule.field(), rule.value());
                provided.add(rule.field());
                continue;
            }
            boolean present = rawRow.containsKey(rule.source());
            String raw = present ? rawRow.get(rule.source()) : null;
            String value = applyTransform(transform, raw, rule);
            if (isBlank(value) && "DEFAULT".equals(transform)) {
                value = rule.value();
            }
            if (present) {
                provided.add(rule.field());
                values.put(rule.field(), value);
            } else if (!isBlank(value)) {
                provided.add(rule.field());
                values.put(rule.field(), value);
            }
        }
    }

    private String applyTransform(String transform, String raw, MappingRule rule) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.isEmpty()) {
            return null;
        }
        return switch (transform) {
            case "UPPER" -> value.toUpperCase(Locale.ROOT);
            case "LOWER" -> value.toLowerCase(Locale.ROOT);
            case "ENUM_MAP" -> {
                if (rule.mapping() == null) {
                    yield value;
                }
                String mapped = rule.mapping().get(value);
                yield mapped == null ? value : mapped;
            }
            case "DATE", "DATETIME", "DECIMAL", "INT", "BOOL", "DEFAULT", "TRIM" -> value;
            default -> value;
        };
    }

    // ------------------------------------------------------------------ 业务规则

    private void businessRules(TargetCatalog.Spec spec, Map<String, String> v) {
        switch (spec.code()) {
            case "CLINICAL_ORDER" -> orderRules(v);
            case "CLINICAL_CHARGE" -> chargeRules(v);
            case "CLINICAL_CHARGE_ITEM" -> nonNegative(v, "amount", "金额");
            case "CLINICAL_ADMISSION" -> admissionRules(v);
            case "CLINICAL_VISIT" -> timeOrder(v, "started_at", "finished_at", "接诊时间", "结束时间");
            case "EMPI_PATIENT" -> patientRules(v);
            default -> {
                // 其余目标没有跨字段规则
            }
        }
        if ("CLINICAL_ORDER".equals(spec.code())) {
            nonNegative(v, "unit_price", "单价");
            nonNegative(v, "amount", "金额");
            positive(v, "quantity", "数量");
        }
        if ("CLINICAL_CHARGE".equals(spec.code())) {
            nonNegative(v, "total_amount", "总金额");
        }
    }

    private void orderRules(Map<String, String> v) {
        String quantity = v.get("quantity");
        String price = v.get("unit_price");
        String amount = v.get("amount");
        if (quantity != null && price != null) {
            BigDecimal expected = new BigDecimal(quantity).multiply(new BigDecimal(price));
            if (isBlank(amount)) {
                v.put("amount", expected.stripTrailingZeros().toPlainString());
            } else {
                BigDecimal actual = new BigDecimal(amount);
                if (expected.subtract(actual).abs().compareTo(new BigDecimal("0.005")) > 0) {
                    throw new RowProblem("金额与数量×单价不一致："
                            + expected.stripTrailingZeros().toPlainString() + " ≠ " + amount);
                }
            }
        }
    }

    private void chargeRules(Map<String, String> v) {
        String status = v.get("status");
        String paidAt = v.get("paid_at");
        if ("PAID".equals(status) && isBlank(paidAt)) {
            throw new RowProblem("收费状态为 PAID 时必须提供支付时间 paid_at");
        }
        if ("REFUNDED".equals(status) && isBlank(paidAt)) {
            throw new RowProblem("收费状态为 REFUNDED 时必须提供原支付时间 paid_at");
        }
    }

    private void admissionRules(Map<String, String> v) {
        String status = v.get("status");
        String dischargedAt = v.get("discharged_at");
        if ("DISCHARGED".equals(status) && isBlank(dischargedAt)) {
            throw new RowProblem("住院状态为 DISCHARGED 时必须提供出院时间 discharged_at");
        }
        timeOrder(v, "admitted_at", "discharged_at", "入院时间", "出院时间");
    }

    private void patientRules(Map<String, String> v) {
        if ("MERGED".equals(v.get("status"))) {
            throw new RowProblem("迁移不支持直接写入 MERGED 状态；请先迁移为 ACTIVE，"
                    + "再用患者合并功能建立合并指针");
        }
    }

    private void timeOrder(Map<String, String> v, String fromField, String toField,
                           String fromLabel, String toLabel) {
        String from = v.get(fromField);
        String to = v.get(toField);
        if (isBlank(from) || isBlank(to)) {
            return;
        }
        try {
            OffsetDateTime fromTime = OffsetDateTime.parse(from);
            OffsetDateTime toTime = OffsetDateTime.parse(to);
            if (toTime.isBefore(fromTime)) {
                throw new RowProblem(toLabel + "不能早于" + fromLabel);
            }
        } catch (DateTimeParseException ignored) {
            // 格式问题在 Values.normalize 已拦截
        }
    }

    private void nonNegative(Map<String, String> v, String field, String label) {
        String value = v.get(field);
        if (!isBlank(value) && new BigDecimal(value).compareTo(BigDecimal.ZERO) < 0) {
            throw new RowProblem(label + "不得为负：" + value);
        }
    }

    private void positive(Map<String, String> v, String field, String label) {
        String value = v.get(field);
        if (!isBlank(value) && new BigDecimal(value).compareTo(BigDecimal.ZERO) <= 0) {
            throw new RowProblem(label + "必须大于 0：" + value);
        }
    }

    // ------------------------------------------------------------------ 脱敏投影

    private Map<String, Object> rawProjection(Map<String, String> rawRow) {
        Map<String, Object> projection = new LinkedHashMap<>();
        rawRow.forEach((key, value) ->
                projection.put(key, Values.sensitive(key) ? Values.mask(value) : value));
        return projection;
    }

    private Map<String, Object> normalizedProjection(TargetCatalog.Spec spec,
                                                     Map<String, String> normalized) {
        Map<String, Object> projection = new LinkedHashMap<>();
        normalized.forEach((field, value) -> {
            boolean sensitive = spec.sensitive().stream().anyMatch(s -> s.field().equals(field))
                    || Values.sensitive(field);
            projection.put(field, sensitive ? Values.mask(value) : value);
        });
        return projection;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
