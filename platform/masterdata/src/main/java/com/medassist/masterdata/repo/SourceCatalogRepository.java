package com.medassist.masterdata.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 源字典读取（只读）。
 *
 * <p>适配器 SQL 全部写死在这里，<b>不接受</b>调用方传入表名/列名：
 * 域注册表只描述来源，真正取数走本类的固定语句，避免"配置表被改成 SQL 注入入口"。
 *
 * <p>读取范围：临床、药事、医技、物资、账号的字典表。生产建议给本服务一个
 * 只授予这些表 SELECT 的账号（见 platform/README.md「主数据治理」）。
 */
@Repository
public class SourceCatalogRepository {

    private final JdbcTemplate jdbc;

    public SourceCatalogRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 源字典的一行。
     *
     * @param code       本地编码（一物一码）
     * @param name       名称
     * @param alias      别名/检索码（源表没有时为 null）
     * @param category   域内分类
     * @param spec       规格
     * @param unit       单位
     * @param enabled    源表是否启用
     * @param sourceRef  源表主键（用于对账）
     * @param attributes 域属性快照（只放标量；不落患者信息）
     */
    public record SourceRow(
            String code,
            String name,
            String alias,
            String category,
            String spec,
            String unit,
            boolean enabled,
            String sourceRef,
            Map<String, Object> attributes) {
    }

    /** 按适配器读取源字典；适配器未知时返回空（调用方必须先判断 domain.syncable）。 */
    public List<SourceRow> load(String syncAdapter) {
        return switch (syncAdapter) {
            case "CLINICAL_DEPT" -> jdbc.query("""
                    SELECT id::text AS source_ref, dept_code AS code, name, type AS category,
                           enabled
                      FROM clinical_svc.md_dept
                     ORDER BY dept_code
                    """, (rs, n) -> new SourceRow(
                    rs.getString("code"), rs.getString("name"), null,
                    rs.getString("category"), null, null,
                    rs.getBoolean("enabled"), rs.getString("source_ref"), Map.of()));
            case "CLINICAL_CHARGE_ITEM" -> jdbc.query("""
                    SELECT id::text AS source_ref, item_code AS code, name, category, unit, price,
                           enabled
                      FROM clinical_svc.md_charge_item
                     ORDER BY item_code
                    """, (rs, n) -> new SourceRow(
                    rs.getString("code"), rs.getString("name"), null,
                    rs.getString("category"), null, rs.getString("unit"),
                    rs.getBoolean("enabled"), rs.getString("source_ref"),
                    attributes("price", rs.getBigDecimal("price"))));
            case "CLINICAL_DIAGNOSIS" -> jdbc.query("""
                    SELECT id::text AS source_ref, code, name, category, enabled
                      FROM clinical_svc.md_diagnosis
                     ORDER BY code
                    """, (rs, n) -> new SourceRow(
                    rs.getString("code"), rs.getString("name"), null,
                    rs.getString("category"), null, null,
                    rs.getBoolean("enabled"), rs.getString("source_ref"), Map.of()));
            case "CLINICAL_OPERATION" -> jdbc.query("""
                    SELECT code, name, category, enabled
                      FROM clinical_svc.operation_catalog
                     ORDER BY code
                    """, (rs, n) -> new SourceRow(
                    rs.getString("code"), rs.getString("name"), null,
                    rs.getString("category"), null, null,
                    rs.getBoolean("enabled"), rs.getString("code"), Map.of()));
            case "PHARMACY_DRUG" -> jdbc.query("""
                    SELECT id::text AS source_ref, drug_code AS code, name, dosage_form AS category,
                           spec, unit, price, insurance_code, insurance_category,
                           package_unit, package_size, enabled
                      FROM pharmacy_svc.md_drug
                     ORDER BY drug_code
                    """, (rs, n) -> {
                Map<String, Object> attrs = new LinkedHashMap<>();
                put(attrs, "price", rs.getBigDecimal("price"));
                put(attrs, "insuranceCode", rs.getString("insurance_code"));
                put(attrs, "insuranceCategory", rs.getString("insurance_category"));
                put(attrs, "packageUnit", rs.getString("package_unit"));
                put(attrs, "packageSize", rs.getObject("package_size"));
                return new SourceRow(rs.getString("code"), rs.getString("name"), null,
                        rs.getString("category"), rs.getString("spec"), rs.getString("unit"),
                        rs.getBoolean("enabled"), rs.getString("source_ref"), attrs);
            });
            case "TECH_EXAM_ITEM" -> jdbc.query("""
                    SELECT id::text AS source_ref, item_code AS code, name, category,
                           specimen_type, enabled
                      FROM tech_svc.md_exam_item
                     ORDER BY item_code
                    """, (rs, n) -> {
                Map<String, Object> attrs = new LinkedHashMap<>();
                put(attrs, "specimenType", rs.getString("specimen_type"));
                return new SourceRow(rs.getString("code"), rs.getString("name"), null,
                        rs.getString("category"), null, null,
                        rs.getBoolean("enabled"), rs.getString("source_ref"), attrs);
            });
            case "MATERIAL_CONSUMABLE" -> jdbc.query("""
                    SELECT id::text AS source_ref, code, name, category, spec, unit, price,
                           manufacturer, registration_no, enabled
                      FROM material_svc.md_consumable
                     ORDER BY code
                    """, (rs, n) -> {
                Map<String, Object> attrs = new LinkedHashMap<>();
                put(attrs, "price", rs.getBigDecimal("price"));
                put(attrs, "manufacturer", rs.getString("manufacturer"));
                put(attrs, "registrationNo", rs.getString("registration_no"));
                return new SourceRow(rs.getString("code"), rs.getString("name"), null,
                        rs.getString("category"), rs.getString("spec"), rs.getString("unit"),
                        rs.getBoolean("enabled"), rs.getString("source_ref"), attrs);
            });
            case "CLINICAL_NURSING_SHIFT" -> jdbc.query("""
                    SELECT id::text AS source_ref, code, name, start_time, end_time,
                           cross_midnight, enabled
                      FROM clinical_svc.nursing_shift
                     ORDER BY start_time, code
                    """, (rs, n) -> {
                Map<String, Object> attrs = new LinkedHashMap<>();
                put(attrs, "startTime", String.valueOf(rs.getObject("start_time")));
                put(attrs, "endTime", String.valueOf(rs.getObject("end_time")));
                put(attrs, "crossMidnight", rs.getBoolean("cross_midnight"));
                return new SourceRow(rs.getString("code"), rs.getString("name"), null,
                        null, null, null, rs.getBoolean("enabled"),
                        rs.getString("source_ref"), attrs);
            });
            case "AUTH_ORG" -> jdbc.query("""
                    SELECT code, name, parent_code, type, status, sort_no
                      FROM auth_svc.org
                     ORDER BY code
                    """, (rs, n) -> {
                Map<String, Object> attrs = new LinkedHashMap<>();
                put(attrs, "parentCode", rs.getString("parent_code"));
                put(attrs, "sortNo", rs.getObject("sort_no"));
                return new SourceRow(rs.getString("code"), rs.getString("name"), null,
                        rs.getString("type"), null, null,
                        "ACTIVE".equals(rs.getString("status")), rs.getString("code"), attrs);
            });
            case "AUTH_CAMPUS" -> jdbc.query("""
                    SELECT id::text AS source_ref, code, name, status, sort_no
                      FROM auth_svc.campus
                     ORDER BY sort_no, code
                    """, (rs, n) -> {
                Map<String, Object> attrs = new LinkedHashMap<>();
                put(attrs, "sortNo", rs.getObject("sort_no"));
                return new SourceRow(rs.getString("code"), rs.getString("name"), null,
                        null, null, null,
                        "ACTIVE".equals(rs.getString("status")), rs.getString("source_ref"), attrs);
            });
            default -> List.of();
        };
    }

    private static Map<String, Object> attributes(String key, Object value) {
        Map<String, Object> attrs = new LinkedHashMap<>();
        put(attrs, key, value);
        return attrs;
    }

    /** 空值不进快照：避免"字段缺失"与"字段为 null"生成两个不同指纹。 */
    private static void put(Map<String, Object> attrs, String key, Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof BigDecimal decimal) {
            attrs.put(key, decimal.stripTrailingZeros().toPlainString());
            return;
        }
        attrs.put(key, value);
    }
}
