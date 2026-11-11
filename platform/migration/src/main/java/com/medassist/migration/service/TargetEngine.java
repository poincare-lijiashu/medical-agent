package com.medassist.migration.service;

import com.medassist.common.security.FieldCipher;
import com.medassist.common.time.BusinessTime;
import com.medassist.migration.catalog.TargetCatalog;
import com.medassist.migration.catalog.TargetCatalog.BindKind;
import com.medassist.migration.catalog.TargetCatalog.ColumnBind;
import com.medassist.migration.catalog.TargetCatalog.Resolution;
import com.medassist.migration.catalog.TargetCatalog.Sensitive;
import com.medassist.migration.domain.MigrationModel;
import com.medassist.migration.domain.MigrationModel.FieldDef;
import com.medassist.migration.support.Values;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 目标表的差异判定、写入与反向恢复。
 *
 * <p>核心原则：
 * <ul>
 *   <li>先按自然键定位目标行，再按<b>源文件实际提供的字段</b>比较；未提供的字段不更新，
 *       避免"只导一列把其他列清空"；</li>
 *   <li>业务冲突检查命中即 CONFLICT，不静默覆盖。例如同一证件号已属于另一个患者；</li>
 *   <li>加密字段比较的是明文/哈希，不是密文——AES-GCM 每次加密 IV 不同，
 *       直接比密文会把"没变化"误判成"变化"；</li>
 *   <li>回滚前校验目标行版本/全列镜像，应用后被人工改过就拒绝回滚，绝不静默覆盖。</li>
 * </ul>
 */
@Component
public class TargetEngine {

    private static final DateTimeFormatter CANONICAL_INSTANT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

    private final NamedParameterJdbcTemplate jdbc;
    private final JdbcTemplate plain;
    private final FieldCipher cipher;

    public TargetEngine(NamedParameterJdbcTemplate jdbc, JdbcTemplate plain, FieldCipher cipher) {
        this.jdbc = jdbc;
        this.plain = plain;
        this.cipher = cipher;
    }

    /** 差异判定结果。 */
    public record Evaluation(
            String action,
            UUID targetPk,
            long beforeVersion,
            String naturalKey,
            Map<String, Object> beforeImage,
            Map<String, Object> resolved,
            String message) {
    }

    /** 应用期冲突（目标行在预检后被改动、或约束冲突）。 */
    public static class ApplyConflict extends RuntimeException {
        public ApplyConflict(String message) {
            super(message, null, false, true);
        }
    }

    /** 回滚期冲突（应用后目标行被改动）。 */
    public static class RollbackConflict extends RuntimeException {
        public RollbackConflict(String message) {
            super(message, null, false, true);
        }
    }

    // ------------------------------------------------------------------ 预检

    public Evaluation evaluate(TargetCatalog.Spec spec, NormalizedRow row) {
        Map<String, Object> resolved = resolveReferences(spec, row.values());
        MapSqlParameterSource params = params(spec, row.values(), resolved);

        for (TargetCatalog.Check check : spec.checks()) {
            List<Integer> hit = jdbc.query(check.sql(), params, (rs, n) -> 1);
            if (!hit.isEmpty()) {
                return new Evaluation(MigrationModel.CONFLICT, null, 0,
                        naturalKey(spec, row.values()), Map.of(), resolved, check.message());
            }
        }

        String matchSql = "SELECT * FROM " + spec.schema() + "." + spec.table()
                + " WHERE " + spec.matchSql();
        List<Map<String, Object>> matches = jdbc.queryForList(matchSql, params);
        if (matches.size() > 1) {
            throw new RowProblem("自然键匹配到 " + matches.size() + " 条目标记录，无法唯一定位");
        }
        if (matches.isEmpty()) {
            return new Evaluation(MigrationModel.CREATE, null, 0,
                    naturalKey(spec, row.values()), Map.of(), resolved, null);
        }

        Map<String, Object> before = lowerKeys(matches.get(0));
        boolean changed = changed(spec, row, resolved, before);
        UUID pk = uuid(before.get(spec.pkColumn()));
        long version = spec.versioned() ? asLong(before.get("version")) : 0L;
        return new Evaluation(changed ? MigrationModel.UPDATE : MigrationModel.UNCHANGED,
                pk, version, naturalKey(spec, row.values()), before, resolved, null);
    }

    // ------------------------------------------------------------------ 应用

    /** 写入一行并返回目标主键；调用方负责事务。 */
    public UUID apply(TargetCatalog.Spec spec, NormalizedRow row, Evaluation evaluation) {
        return switch (evaluation.action()) {
            case MigrationModel.CREATE -> insert(spec, row.createValues(), row.provided(), evaluation.resolved());
            case MigrationModel.UPDATE -> update(spec, row, evaluation);
            default -> evaluation.targetPk();
        };
    }

    private UUID insert(TargetCatalog.Spec spec, Map<String, String> values, Set<String> provided,
                        Map<String, Object> resolved) {
        UUID id = "id".equals(spec.pkColumn()) ? UUID.randomUUID() : null;
        List<String> columns = new ArrayList<>();
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (id != null) {
            columns.add("id");
            params.addValue("id", id);
        }

        for (ColumnBind bind : spec.binds()) {
            if (!provided.contains(bind.field())) {
                continue;
            }
            columns.add(bind.column());
            params.addValue(bind.column(), Values.toJdbc(spec.field(bind.field()), values.get(bind.field())));
        }
        for (Sensitive sensitive : spec.sensitive()) {
            if (!provided.contains(sensitive.field())) {
                continue;
            }
            String plain = values.get(sensitive.field());
            columns.add(sensitive.encColumn());
            params.addValue(sensitive.encColumn(), cipher.encrypt(plain));
            if (sensitive.hashColumn() != null) {
                columns.add(sensitive.hashColumn());
                params.addValue(sensitive.hashColumn(), cipher.hash(plain));
            }
        }
        for (Resolution resolution : spec.resolutions()) {
            if (!resolved.containsKey(resolution.column())) {
                continue;
            }
            columns.add(resolution.column());
            params.addValue(resolution.column(), resolved.get(resolution.column()));
        }
        if (spec.versioned()) {
            columns.add("version");
            params.addValue("version", 0L);
        }

        String sql = "INSERT INTO " + spec.schema() + "." + spec.table()
                + " (" + String.join(", ", columns) + ") VALUES (:"
                + String.join(", :", columns) + ")";
        jdbc.update(sql, params);
        return id != null ? id : uuid(resolved.get(spec.pkColumn()));
    }

    private UUID update(TargetCatalog.Spec spec, NormalizedRow row, Evaluation evaluation) {
        List<String> sets = new ArrayList<>();
        MapSqlParameterSource params = new MapSqlParameterSource();
        Map<String, String> values = row.values();

        for (ColumnBind bind : spec.binds()) {
            if (!row.provided().contains(bind.field())) {
                continue;
            }
            sets.add(bind.column() + " = :" + bind.column());
            params.addValue(bind.column(), Values.toJdbc(spec.field(bind.field()), values.get(bind.field())));
        }
        for (Sensitive sensitive : spec.sensitive()) {
            if (!row.provided().contains(sensitive.field())) {
                continue;
            }
            String plain = values.get(sensitive.field());
            sets.add(sensitive.encColumn() + " = :" + sensitive.encColumn());
            params.addValue(sensitive.encColumn(), cipher.encrypt(plain));
            if (sensitive.hashColumn() != null) {
                sets.add(sensitive.hashColumn() + " = :" + sensitive.hashColumn());
                params.addValue(sensitive.hashColumn(), cipher.hash(plain));
            }
        }
        for (Resolution resolution : spec.resolutions()) {
            boolean provided = resolution.fields().stream().anyMatch(row.provided()::contains);
            if (!provided || !evaluation.resolved().containsKey(resolution.column())) {
                continue;
            }
            sets.add(resolution.column() + " = :" + resolution.column());
            params.addValue(resolution.column(), evaluation.resolved().get(resolution.column()));
        }

        if (spec.versioned()) {
            sets.add("version = version + 1");
        }
        if (spec.hasUpdatedAt()) {
            sets.add("updated_at = now()");
        }
        if (sets.isEmpty()) {
            return evaluation.targetPk();
        }

        String where = spec.pkColumn() + " = :__pk";
        params.addValue("__pk", evaluation.targetPk());
        if (spec.versioned()) {
            where += " AND version = :__version";
            params.addValue("__version", evaluation.beforeVersion());
        }
        String sql = "UPDATE " + spec.schema() + "." + spec.table()
                + " SET " + String.join(", ", sets) + " WHERE " + where;
        int changed = jdbc.update(sql, params);
        if (changed == 0) {
            throw new ApplyConflict("目标行已被他人修改（乐观锁版本不匹配），整批中止");
        }
        return evaluation.targetPk();
    }

    /** 写入后完整镜像（含版本），用于回滚时的"是否被改动"判定。 */
    public Map<String, Object> snapshot(TargetCatalog.Spec spec, UUID pk) {
        MapSqlParameterSource params = new MapSqlParameterSource("pk", pk);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM " + spec.schema() + "." + spec.table()
                        + " WHERE " + spec.pkColumn() + " = :pk", params);
        return rows.isEmpty() ? Map.of() : canonical(lowerKeys(rows.get(0)));
    }

    public Map<String, Object> snapshotByMatch(TargetCatalog.Spec spec, Map<String, Object> params) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM " + spec.schema() + "." + spec.table()
                        + " WHERE " + spec.matchSql(), new MapSqlParameterSource(params));
        return rows.isEmpty() ? Map.of() : canonical(lowerKeys(rows.get(0)));
    }

    private Map<String, Object> currentSnapshot(TargetCatalog.Spec spec, Evaluation evaluation,
                                                Map<String, Object> afterImage) {
        if ("id".equals(spec.pkColumn())) {
            return snapshot(spec, evaluation.targetPk());
        }
        MapSqlParameterSource params = new MapSqlParameterSource();
        String where = identityWhere(spec, afterImage, params);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM " + spec.schema() + "." + spec.table() + " WHERE " + where, params);
        return rows.size() == 1 ? canonical(lowerKeys(rows.get(0))) : Map.of();
    }

    /** 写入后的目标行镜像：有 id 主键按主键取，无 id 主键（跨系统标识）按自然键取。 */
    public Map<String, Object> snapshotAfterApply(TargetCatalog.Spec spec, NormalizedRow row,
                                                  Evaluation evaluation) {
        if ("id".equals(spec.pkColumn())) {
            return snapshot(spec, evaluation.targetPk());
        }
        return snapshotByMatch(spec, params(spec, row.values(), evaluation.resolved()).getValues());
    }

    /**
     * 比对"应用后镜像"与"当前行"的业务内容；version/updated_at 是回滚链自身推进的元数据，
     * 不参与比对。返回可读差异（最多 3 个字段），无差异返回 null。
     */
    private static String contentDiff(Map<String, Object> expected, Map<String, Object> actual) {
        Set<String> keys = new LinkedHashSet<>(expected.keySet());
        keys.addAll(actual.keySet());
        List<String> diffs = new ArrayList<>();
        for (String key : keys) {
            if (key.equals("version") || key.equals("updated_at")) {
                continue;
            }
            Object left = expected.get(key);
            Object right = actual.get(key);
            if (!Objects.equals(left, right)) {
                diffs.add(key + " 期望 " + left + " / 当前 " + right);
                if (diffs.size() >= 3) {
                    break;
                }
            }
        }
        return diffs.isEmpty() ? null : String.join("；", diffs);
    }

    /** 供台账存储：把数据库原始值转成规范字符串（时间/数字/UUID/布尔统一口径）。 */
    public static Map<String, Object> canonicalImage(Map<String, Object> row) {
        return row == null || row.isEmpty() ? Map.of() : canonical(lowerKeys(row));
    }

    // ------------------------------------------------------------------ 回滚

    public void rollback(TargetCatalog.Spec spec, NormalizedRow row, Evaluation evaluation,
                         Map<String, Object> afterImage) {
        if (afterImage.isEmpty()) {
            throw new RollbackConflict("缺少应用后镜像，无法确认目标行状态：" + evaluation.naturalKey());
        }
        // 回滚前先比对"当前行"与"应用后镜像"：业务内容被人工改过就拒绝，不做静默覆盖。
        // version/updated_at 是回滚链自身会推进的元数据，不参与内容比对：
        // 先回滚较晚的批次会把 version 抬高，若把它们算进去，链式回滚永远无法完成。
        Map<String, Object> current = currentSnapshot(spec, evaluation, afterImage);
        if (current.isEmpty()) {
            throw new RollbackConflict("目标行已不存在，无法回滚：" + evaluation.naturalKey());
        }
        String diff = contentDiff(afterImage, current);
        if (diff != null) {
            throw new RollbackConflict("目标行在应用后已被修改（" + diff + "），拒绝回滚："
                    + evaluation.naturalKey());
        }
        switch (evaluation.action()) {
            case MigrationModel.CREATE -> deleteCreated(spec, evaluation, current);
            case MigrationModel.UPDATE -> restore(spec, evaluation, current);
            default -> {
                // UNCHANGED 不需要回滚
            }
        }
    }

    private void deleteCreated(TargetCatalog.Spec spec, Evaluation evaluation,
                               Map<String, Object> current) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        String where;
        if ("id".equals(spec.pkColumn())) {
            where = "id = :pk";
            params.addValue("pk", evaluation.targetPk());
            if (spec.versioned()) {
                where += " AND version = :version";
                params.addValue("version", asLong(current.get("version")));
            }
        } else {
            where = identityWhere(spec, current, params);
        }
        int deleted = jdbc.update("DELETE FROM " + spec.schema() + "." + spec.table()
                + " WHERE " + where, params);
        if (deleted == 0) {
            throw new RollbackConflict("目标行已被修改或删除，拒绝回滚：" + evaluation.naturalKey());
        }
    }

    private void restore(TargetCatalog.Spec spec, Evaluation evaluation,
                         Map<String, Object> current) {
        Map<String, Object> before = evaluation.beforeImage();
        if (before.isEmpty()) {
            throw new RollbackConflict("缺少应用前镜像，无法回滚：" + evaluation.naturalKey());
        }
        MapSqlParameterSource params = new MapSqlParameterSource();
        String where;
        if ("id".equals(spec.pkColumn())) {
            where = "id = :pk";
            params.addValue("pk", evaluation.targetPk());
        } else {
            where = identityWhere(spec, current, params);
        }
        if (spec.versioned()) {
            // 用回滚时刻的当前版本做 CAS，而不是应用时刻的版本：
            // 链式回滚中较晚批次已把版本抬高，用旧版本会永远更新不到行。
            where += " AND version = :version";
            params.addValue("version", asLong(current.get("version")));
        }

        List<String> sets = new ArrayList<>();
        for (Map.Entry<String, Object> entry : before.entrySet()) {
            String column = entry.getKey();
            if (column.equals("id") || column.equals("version") || column.equals("updated_at")) {
                continue;
            }
            sets.add(column + " = :set_" + column);
            params.addValue("set_" + column, bindValue(column, value(entry.getValue())));
        }
        if (spec.versioned()) {
            sets.add("version = version + 1");
        }
        if (spec.hasUpdatedAt()) {
            sets.add("updated_at = now()");
        }
        int changed = jdbc.update("UPDATE " + spec.schema() + "." + spec.table()
                + " SET " + String.join(", ", sets) + " WHERE " + where, params);
        if (changed == 0) {
            throw new RollbackConflict("目标行已被修改，拒绝静默覆盖：" + evaluation.naturalKey());
        }
    }

    private String identityWhere(TargetCatalog.Spec spec, Map<String, Object> afterImage,
                                 MapSqlParameterSource params) {
        List<String> parts = new ArrayList<>();
        for (String column : spec.matchColumns()) {
            Object value = afterImage.get(column);
            if (value == null) {
                parts.add(column + " IS NULL");
            } else {
                String name = "k_" + column;
                parts.add(column + " = :" + name);
                params.addValue(name, bindValue(column, value(value)));
            }
        }
        return String.join(" AND ", parts);
    }

    // ------------------------------------------------------------------ 内部

    private Map<String, Object> resolveReferences(TargetCatalog.Spec spec, Map<String, String> values) {
        Map<String, Object> resolved = new LinkedHashMap<>();
        for (Resolution resolution : spec.resolutions()) {
            boolean anyPresent = resolution.fields().stream()
                    .anyMatch(field -> !isBlank(values.get(field)));
            boolean allPresent = resolution.fields().stream()
                    .allMatch(field -> !isBlank(values.get(field)));
            if (!allPresent) {
                if (resolution.fallbackSql() != null && !anyPresent) {
                    List<UUID> candidates = jdbc.query(resolution.fallbackSql(),
                            new MapSqlParameterSource(), (rs, n) -> rs.getObject(1, UUID.class));
                    if (candidates.size() == 1) {
                        resolved.put(resolution.column(), candidates.get(0));
                        continue;
                    }
                    if (candidates.size() > 1) {
                        throw new RowProblem("存在多个可选" + resolution.label() + "，必须明确提供 "
                                + String.join("/", resolution.fields()));
                    }
                }
                if (anyPresent && resolution.required()) {
                    throw new RowProblem("缺少" + resolution.label() + "定位字段（"
                            + String.join("/", resolution.fields()) + "）");
                }
                continue;
            }
            MapSqlParameterSource params = new MapSqlParameterSource();
            resolution.fields().forEach(field -> params.addValue(field, values.get(field)));
            List<UUID> ids = jdbc.query(resolution.sql(), params,
                    (rs, n) -> rs.getObject(1, UUID.class));
            if (ids.isEmpty()) {
                throw new RowProblem("未找到" + resolution.label() + "："
                        + describe(values, resolution.fields()));
            }
            if (ids.size() > 1) {
                throw new RowProblem(resolution.label() + "匹配到多条记录，无法确定引用："
                        + describe(values, resolution.fields()));
            }
            resolved.put(resolution.column(), ids.get(0));
        }
        return resolved;
    }

    private MapSqlParameterSource params(TargetCatalog.Spec spec, Map<String, String> values,
                                         Map<String, Object> resolved) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        // 校验 SQL 会把参数直接与强类型列比较（date/timestamptz/numeric），
        // 必须按字段类型绑定，不能把规范字符串原样塞进 JDBC（PG 不会隐式把 varchar 当 date）。
        values.forEach((field, value) -> {
            FieldDef def = spec.findField(field).orElse(null);
            params.addValue(field, def == null ? value : Values.toJdbc(def, value));
        });
        resolved.forEach(params::addValue);
        for (Sensitive sensitive : spec.sensitive()) {
            if (sensitive.hashColumn() != null) {
                // 即使明文为空也要提供参数：冲突检查 SQL 引用了该参数，缺失会被 JDBC 判为错误
                params.addValue(sensitive.hashColumn(), cipher.hash(values.get(sensitive.field())));
            }
        }
        for (Resolution resolution : spec.resolutions()) {
            if (!params.hasValue(resolution.column())) {
                params.addValue(resolution.column(), null);
            }
        }
        for (String column : spec.matchColumns()) {
            if (!params.hasValue(column)) {
                params.addValue(column, null);
            }
        }
        return params;
    }

    private boolean changed(TargetCatalog.Spec spec, NormalizedRow row,
                            Map<String, Object> resolved, Map<String, Object> before) {
        for (ColumnBind bind : spec.binds()) {
            if (!row.provided().contains(bind.field())) {
                continue;
            }
            FieldDef field = spec.field(bind.field());
            String current = Values.canonicalDbValue(field, before.get(bind.column()));
            if (!Objects.equals(current, row.values().get(bind.field()))) {
                return true;
            }
        }
        for (Sensitive sensitive : spec.sensitive()) {
            if (!row.provided().contains(sensitive.field())) {
                continue;
            }
            String plain = row.values().get(sensitive.field());
            if (sensitive.hashColumn() != null) {
                String currentHash = (String) before.get(sensitive.hashColumn());
                if (!Objects.equals(cipher.hash(plain), currentHash)) {
                    return true;
                }
            } else {
                String currentPlain = cipher.decrypt((String) before.get(sensitive.encColumn()));
                if (!Objects.equals(nullIfBlank(plain), nullIfBlank(currentPlain))) {
                    return true;
                }
            }
        }
        for (Resolution resolution : spec.resolutions()) {
            boolean provided = resolution.fields().stream().anyMatch(row.provided()::contains);
            if (!provided || !resolved.containsKey(resolution.column())) {
                continue;
            }
            Object current = before.get(resolution.column());
            if (!Objects.equals(String.valueOf(resolved.get(resolution.column())),
                    current == null ? null : String.valueOf(current))) {
                return true;
            }
        }
        return false;
    }

    private String naturalKey(TargetCatalog.Spec spec, Map<String, String> values) {
        List<String> parts = new ArrayList<>();
        for (String field : spec.matchFields()) {
            String value = values.get(field);
            parts.add(field + "=" + (value == null ? "" : value));
        }
        return String.join(",", parts);
    }

    private static Map<String, Object> lowerKeys(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>();
        row.forEach((key, value) -> result.put(key.toLowerCase(), value));
        return result;
    }

    private static Map<String, Object> canonical(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>();
        row.forEach((key, value) -> result.put(key, value(value)));
        return result;
    }

    /** 数据库值 → 规范字符串（时间/数字/布尔/UUID 统一口径）。 */
    private static String value(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Timestamp ts) {
            return ts.toInstant().atZone(BusinessTime.zone()).toOffsetDateTime()
                    .format(CANONICAL_INSTANT);
        }
        if (raw instanceof OffsetDateTime odt) {
            return odt.atZoneSameInstant(BusinessTime.zone()).toOffsetDateTime()
                    .format(CANONICAL_INSTANT);
        }
        if (raw instanceof Instant instant) {
            return instant.atZone(BusinessTime.zone()).toOffsetDateTime()
                    .format(CANONICAL_INSTANT);
        }
        if (raw instanceof LocalDate date) {
            return date.toString();
        }
        if (raw instanceof Date date) {
            return date.toLocalDate().toString();
        }
        if (raw instanceof BigDecimal decimal) {
            return decimal.stripTrailingZeros().toPlainString();
        }
        return String.valueOf(raw);
    }

    /** 规范字符串 → JDBC 参数（按列名推断类型；目标表列名来自代码常量）。 */
    private static Object bindValue(String column, String value) {
        if (value == null) {
            return null;
        }
        return switch (column) {
            case "id", "patient_id", "visit_id", "dept_id", "ward_id", "bed_id",
                 "charge_id", "order_id", "merged_into" -> UUID.fromString(value);
            case "version" -> Long.valueOf(value);
            case "created_at", "updated_at", "registered_at", "started_at", "finished_at",
                 "admitted_at", "discharged_at", "diagnosed_at", "paid_at" ->
                    OffsetDateTime.parse(value);
            case "birth_date", "visit_date" -> LocalDate.parse(value);
            case "price", "unit_price", "amount", "total_amount" -> new BigDecimal(value);
            case "enabled", "primary_flag" -> Boolean.valueOf(value);
            case "sort_no", "quantity", "booked", "total" -> Long.valueOf(value);
            default -> value;
        };
    }

    private static long asLong(Object value) {
        if (value == null) {
            return 0L;
        }
        return value instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(value));
    }

    private static UUID uuid(Object value) {
        if (value == null) {
            return null;
        }
        return value instanceof UUID id ? id : UUID.fromString(String.valueOf(value));
    }

    private static String describe(Map<String, String> values, List<String> fields) {
        List<String> parts = new ArrayList<>();
        for (String field : fields) {
            parts.add(field + "=" + values.get(field));
        }
        return String.join(", ", parts);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String nullIfBlank(String value) {
        return isBlank(value) ? null : value;
    }
}
