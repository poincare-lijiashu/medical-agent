package com.medassist.migration.catalog;

import com.medassist.migration.domain.MigrationModel.FieldDef;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 迁移目标目录（代码侧口径）。
 *
 * <p>为什么目标定义在代码里而不是全放库：自然键匹配 SQL、引用解析、加密列、业务冲突检查
 * 都是"这个目标特有的物理事实"，放库只能存字符串再由人拼 SQL，等于把安全边界交给配置。
 * 库里保留目标/字段的可读元数据（供模板与前端），代码侧保留可执行口径，两者由
 * {@code MigrationService} 启动时校验一致。
 */
public final class TargetCatalog {

    public enum BindKind {
        DIRECT,
        ENCRYPT,
        HASH
    }

    /** 字段 → 表列（敏感列通过 ENCRYPT/HASH 绑定）。 */
    public record ColumnBind(String field, String column, BindKind kind) {
    }

    /** 敏感字段分组：同一明文写入密文列与（可选）哈希列。 */
    public record Sensitive(String field, String encColumn, String hashColumn) {
    }

    /**
     * 引用解析：用源字段值查其他目标的主键（如 patient_no → patient.id）。
     *
     * <p>{@code fallbackSql} 用于"单院区部署可省略"的字段（如院区）：源值缺失时执行该 SQL，
     * 只有唯一候选才采用；多候选必须由源数据明确指定，绝不猜。
     */
    public record Resolution(String column, List<String> fields, String sql,
                             String label, boolean required, String fallbackSql) {

        public Resolution(String column, List<String> fields, String sql,
                          String label, boolean required) {
            this(column, fields, sql, label, required, null);
        }
    }

    /** 业务冲突检查：命中即 CONFLICT（不静默覆盖）。 */
    public record Check(String sql, String message) {
    }

    /** 目标执行口径。 */
    public record Spec(
            String code,
            String schema,
            String table,
            String pkColumn,
            List<FieldDef> fields,
            List<ColumnBind> binds,
            List<Sensitive> sensitive,
            List<Resolution> resolutions,
            String matchSql,
            List<String> matchFields,
            List<String> matchColumns,
            List<Check> checks,
            boolean versioned,
            boolean hasCreatedAt,
            boolean hasUpdatedAt,
            String amountColumn,
            Map<String, String> defaults,
            Map<String, Map<String, String>> enumAliases) {

        public FieldDef field(String key) {
            return fields.stream().filter(f -> f.fieldKey().equals(key)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "目标 " + code + " 未定义字段 " + key));
        }

        public Optional<FieldDef> findField(String key) {
            return fields.stream().filter(f -> f.fieldKey().equals(key)).findFirst();
        }

        public Set<String> fieldKeys() {
            Set<String> keys = new LinkedHashSet<>();
            fields.forEach(f -> keys.add(f.fieldKey()));
            return keys;
        }
    }

    private static final Map<String, Spec> SPECS = build();

    private TargetCatalog() {
    }

    public static List<Spec> all() {
        return new ArrayList<>(SPECS.values());
    }

    public static Optional<Spec> find(String code) {
        return Optional.ofNullable(SPECS.get(code));
    }

    public static Spec require(String code) {
        return find(code).orElseThrow(() -> new IllegalArgumentException("未知迁移目标：" + code));
    }

    // ------------------------------------------------------------------ 构造

    private static Map<String, Spec> build() {
        Map<String, Spec> map = new LinkedHashMap<>();
        for (Spec spec : List.of(
                dept(), chargeItemDict(), diagnosisDict(), ward(), bed(),
                patient(), crosswalk(), visit(), admission(), visitDiagnosis(),
                order(), charge(), chargeItem())) {
            map.put(spec.code(), spec);
        }
        return Map.copyOf(map);
    }

    private static Spec dept() {
        List<FieldDef> fields = List.of(
                f("dept_code", "科室编码", "TEXT", true, 32, null, null, 10),
                f("name", "科室名称", "TEXT", true, 128, null, null, 20),
                f("type", "科室类型", "ENUM", false, null,
                        List.of("OUTPATIENT", "INPATIENT", "BOTH"), null, 30),
                f("enabled", "是否启用", "BOOL", false, null, null, null, 40));
        return new Spec("CLINICAL_DEPT", "clinical_svc", "md_dept", "id", fields,
                binds(b("dept_code", "dept_code"), b("name", "name"), b("type", "type"),
                        b("enabled", "enabled")),
                List.of(), List.of(),
                "dept_code = :dept_code", List.of("dept_code"), List.of("dept_code"),
                List.of(),
                true, true, true, null,
                defaults("type", "OUTPATIENT", "enabled", "true"), Map.of());
    }

    private static Spec chargeItemDict() {
        List<FieldDef> fields = List.of(
                f("item_code", "项目编码", "TEXT", true, 32, null, null, 10),
                f("name", "项目名称", "TEXT", true, 128, null, null, 20),
                f("category", "项目类别", "ENUM", true, null,
                        List.of("REGISTRATION", "CONSULTATION", "DRUG", "TREATMENT", "EXAM", "MATERIAL"),
                        null, 30),
                f("unit", "单位", "TEXT", false, 16, null, null, 40),
                f("price", "价格", "DECIMAL", true, null, null, null, 50),
                f("enabled", "是否启用", "BOOL", false, null, null, null, 60));
        Map<String, Map<String, String>> aliases = Map.of("category", Map.of(
                "挂号", "REGISTRATION", "诊查", "CONSULTATION", "药品", "DRUG",
                "治疗", "TREATMENT", "检查", "EXAM", "耗材", "MATERIAL"));
        return new Spec("CLINICAL_CHARGE_ITEM", "clinical_svc", "md_charge_item", "id", fields,
                binds(b("item_code", "item_code"), b("name", "name"), b("category", "category"),
                        b("unit", "unit"), b("price", "price"), b("enabled", "enabled")),
                List.of(), List.of(),
                "item_code = :item_code", List.of("item_code"), List.of("item_code"),
                List.of(new Check("SELECT 1 FROM clinical_svc.md_charge_item"
                                + " WHERE item_code = :item_code AND price < 0",
                        "价格不得为负")),
                true, true, true, "price",
                defaults("unit", "次", "enabled", "true"), aliases);
    }

    private static Spec diagnosisDict() {
        List<FieldDef> fields = List.of(
                f("code", "诊断编码", "TEXT", true, 32, null, null, 10),
                f("name", "诊断名称", "TEXT", true, 255, null, null, 20),
                f("category", "系统分类", "TEXT", false, 64, null, null, 30),
                f("enabled", "是否启用", "BOOL", false, null, null, null, 40));
        return new Spec("CLINICAL_DIAGNOSIS", "clinical_svc", "md_diagnosis", "id", fields,
                binds(b("code", "code"), b("name", "name"), b("category", "category"),
                        b("enabled", "enabled")),
                List.of(), List.of(),
                "code = :code", List.of("code"), List.of("code"),
                List.of(),
                true, true, true, null,
                defaults("enabled", "true"), Map.of());
    }

    private static Spec ward() {
        List<FieldDef> fields = List.of(
                f("code", "病区编码", "TEXT", true, 32, null, null, 10),
                f("name", "病区名称", "TEXT", true, 128, null, null, 20),
                f("dept_code", "所属科室编码", "TEXT", true, 32, null, "CLINICAL_DEPT", 30),
                f("campus_code", "院区编码", "TEXT", false, 32, null, null, 40),
                f("enabled", "是否启用", "BOOL", false, null, null, null, 50));
        return new Spec("CLINICAL_WARD", "clinical_svc", "ward", "id", fields,
                binds(b("code", "code"), b("name", "name"), b("enabled", "enabled")),
                List.of(),
                List.of(new Resolution("dept_id", List.of("dept_code"),
                                "SELECT id FROM clinical_svc.md_dept WHERE dept_code = :dept_code",
                                "科室", true),
                        new Resolution("campus_id", List.of("campus_code"),
                                "SELECT id FROM auth_svc.campus"
                                        + " WHERE code = :campus_code AND status = 'ACTIVE'",
                                "院区", false,
                                "SELECT id FROM auth_svc.campus WHERE status = 'ACTIVE'"
                                        + " ORDER BY code")),
                "code = :code", List.of("code"), List.of("code"),
                List.of(),
                true, true, true, null,
                defaults("enabled", "true"), Map.of());
    }

    private static Spec bed() {
        List<FieldDef> fields = List.of(
                f("ward_code", "病区编码", "TEXT", true, 32, null, "CLINICAL_WARD", 10),
                f("bed_no", "床号", "TEXT", true, 32, null, null, 20),
                f("status", "床位状态", "ENUM", false, null,
                        List.of("FREE", "OCCUPIED", "RESERVED", "DISABLED"), null, 30));
        Map<String, Map<String, String>> aliases = Map.of("status", Map.of(
                "空闲", "FREE", "占用", "OCCUPIED", "预约", "RESERVED", "停用", "DISABLED"));
        return new Spec("CLINICAL_BED", "clinical_svc", "bed", "id", fields,
                binds(b("bed_no", "bed_no"), b("status", "status")),
                List.of(),
                List.of(new Resolution("ward_id", List.of("ward_code"),
                        "SELECT id FROM clinical_svc.ward WHERE code = :ward_code",
                        "病区", true)),
                "ward_id = :ward_id AND bed_no = :bed_no",
                List.of("ward_code", "bed_no"), List.of("ward_id", "bed_no"),
                List.of(),
                true, true, true, null,
                defaults("status", "FREE"), aliases);
    }

    private static Spec patient() {
        List<FieldDef> fields = List.of(
                f("patient_no", "患者号", "TEXT", true, 32, null, null, 10),
                f("name", "姓名", "TEXT", true, 64, null, null, 20),
                f("gender", "性别", "ENUM", false, null,
                        List.of("MALE", "FEMALE", "UNKNOWN"), null, 30),
                f("birth_date", "出生日期", "DATE", false, null, null, null, 40),
                f("id_type", "证件类型", "ENUM", false, null,
                        List.of("ID_CARD", "PASSPORT", "MILITARY", "HK_MACAO", "NONE"), null, 50),
                f("id_no", "证件号", "TEXT", false, 64, null, null, 60),
                f("phone", "手机号", "TEXT", false, 32, null, null, 70),
                f("address", "住址", "TEXT", false, 255, null, null, 80),
                f("blood_type", "血型", "TEXT", false, 8, null, null, 90),
                f("allergy", "过敏史", "TEXT", false, 255, null, null, 100),
                f("status", "档案状态", "ENUM", false, null,
                        List.of("ACTIVE", "MERGED", "DECEASED"), null, 110));
        Map<String, Map<String, String>> aliases = new LinkedHashMap<>();
        aliases.put("gender", Map.of("男", "MALE", "女", "FEMALE", "未知", "UNKNOWN", "不详", "UNKNOWN"));
        aliases.put("id_type", Map.of("身份证", "ID_CARD", "护照", "PASSPORT", "军官证", "MILITARY",
                "港澳通行证", "HK_MACAO", "无", "NONE"));
        return new Spec("EMPI_PATIENT", "empi_svc", "patient", "id", fields,
                binds(b("patient_no", "patient_no"), b("name", "name"), b("gender", "gender"),
                        b("birth_date", "birth_date"), b("id_type", "id_type"),
                        b("blood_type", "blood_type"), b("allergy", "allergy"),
                        b("status", "status")),
                List.of(new Sensitive("id_no", "id_no_enc", "id_no_hash"),
                        new Sensitive("phone", "phone_enc", "phone_hash"),
                        new Sensitive("address", "address_enc", null)),
                List.of(),
                "patient_no = :patient_no", List.of("patient_no"), List.of("patient_no"),
                List.of(new Check("SELECT 1 FROM empi_svc.patient"
                                + " WHERE id_type = :id_type AND id_no_hash = :id_no_hash"
                                + "   AND status <> 'MERGED' AND patient_no <> :patient_no",
                        "证件号已被其他患者占用（同证件类型下查重）")),
                true, true, true, null,
                defaults("gender", "UNKNOWN", "status", "ACTIVE"), aliases);
    }

    private static Spec crosswalk() {
        List<FieldDef> fields = List.of(
                f("patient_no", "患者号", "TEXT", true, 32, null, "EMPI_PATIENT", 10),
                f("system_code", "系统编码", "TEXT", true, 32, null, null, 20),
                f("external_id", "外部标识", "TEXT", true, 64, null, null, 30));
        return new Spec("EMPI_CROSSWALK", "empi_svc", "patient_crosswalk", "patient_id", fields,
                binds(b("system_code", "system_code"), b("external_id", "external_id")),
                List.of(),
                List.of(new Resolution("patient_id", List.of("patient_no"),
                        "SELECT id FROM empi_svc.patient WHERE patient_no = :patient_no",
                        "患者", true)),
                "system_code = :system_code AND external_id = :external_id",
                List.of("system_code", "external_id"), List.of("system_code", "external_id"),
                List.of(new Check("SELECT 1 FROM empi_svc.patient_crosswalk"
                                + " WHERE system_code = :system_code AND external_id = :external_id"
                                + "   AND patient_id <> :patient_id",
                        "该外部标识已映射到其他患者，不能静默改绑")),
                false, true, false, null, Map.of(), Map.of());
    }

    private static Spec visit() {
        List<FieldDef> fields = List.of(
                f("visit_no", "就诊号", "TEXT", true, 32, null, null, 10),
                f("patient_no", "患者号", "TEXT", true, 32, null, "EMPI_PATIENT", 20),
                f("dept_code", "科室编码", "TEXT", true, 32, null, "CLINICAL_DEPT", 30),
                f("doctor_id", "医生标识", "TEXT", true, 64, null, null, 40),
                f("visit_type", "就诊类型", "ENUM", false, null,
                        List.of("OUTPATIENT", "EMERGENCY"), null, 50),
                f("status", "就诊状态", "ENUM", false, null,
                        List.of("REGISTERED", "IN_TREATMENT", "FINISHED", "CANCELLED"), null, 60),
                f("visit_date", "就诊日期", "DATE", true, null, null, null, 70),
                f("registered_at", "挂号时间", "TIMESTAMPTZ", false, null, null, null, 80),
                f("started_at", "接诊时间", "TIMESTAMPTZ", false, null, null, null, 90),
                f("finished_at", "结束时间", "TIMESTAMPTZ", false, null, null, null, 100),
                f("campus_code", "院区编码", "TEXT", false, 32, null, null, 110));
        Map<String, Map<String, String>> aliases = new LinkedHashMap<>();
        aliases.put("visit_type", Map.of("门诊", "OUTPATIENT", "急诊", "EMERGENCY"));
        aliases.put("status", Map.of("已挂号", "REGISTERED", "就诊中", "IN_TREATMENT",
                "已完成", "FINISHED", "已结束", "FINISHED", "已取消", "CANCELLED"));
        return new Spec("CLINICAL_VISIT", "clinical_svc", "visit", "id", fields,
                binds(b("visit_no", "visit_no"), b("patient_no", "patient_no"),
                        b("doctor_id", "doctor_id"),
                        b("visit_type", "visit_type"), b("status", "status"),
                        b("visit_date", "visit_date"), b("registered_at", "registered_at"),
                        b("started_at", "started_at"), b("finished_at", "finished_at")),
                List.of(),
                List.of(new Resolution("patient_id", List.of("patient_no"),
                                "SELECT id FROM empi_svc.patient WHERE patient_no = :patient_no",
                                "患者", true),
                        new Resolution("dept_id", List.of("dept_code"),
                                "SELECT id FROM clinical_svc.md_dept WHERE dept_code = :dept_code",
                                "科室", true),
                        new Resolution("campus_id", List.of("campus_code"),
                                "SELECT id FROM auth_svc.campus"
                                        + " WHERE code = :campus_code AND status = 'ACTIVE'",
                                "院区", false,
                                "SELECT id FROM auth_svc.campus WHERE status = 'ACTIVE'"
                                        + " ORDER BY code")),
                "visit_no = :visit_no", List.of("visit_no"), List.of("visit_no"),
                List.of(
                        new Check("SELECT 1 FROM clinical_svc.visit"
                                        + " WHERE visit_no = :visit_no AND patient_no <> :patient_no",
                                "该就诊号已存在且属于其他患者，不能改绑"),
                        new Check("SELECT 1 FROM clinical_svc.visit"
                                        + " WHERE patient_id = :patient_id AND dept_id = :dept_id"
                                        + "   AND visit_date = :visit_date"
                                        + "   AND status IN ('REGISTERED', 'IN_TREATMENT')"
                                        + "   AND visit_no <> :visit_no",
                                "同一患者同一天同一科室已有在途就诊")),
                true, false, true, null,
                defaults("visit_type", "OUTPATIENT", "status", "REGISTERED"), aliases);
    }

    private static Spec admission() {
        List<FieldDef> fields = List.of(
                f("admission_no", "住院号", "TEXT", true, 32, null, null, 10),
                f("visit_no", "就诊号", "TEXT", true, 32, null, "CLINICAL_VISIT", 20),
                f("patient_no", "患者号", "TEXT", true, 32, null, "EMPI_PATIENT", 30),
                f("ward_code", "病区编码", "TEXT", true, 32, null, "CLINICAL_WARD", 40),
                f("bed_no", "床号", "TEXT", false, 32, null, null, 50),
                f("attending_doctor", "主管医生", "TEXT", true, 64, null, null, 60),
                f("status", "住院状态", "ENUM", false, null,
                        List.of("ADMITTED", "IN_HOSPITAL", "DISCHARGED", "CANCELLED"), null, 70),
                f("admitted_at", "入院时间", "TIMESTAMPTZ", false, null, null, null, 80),
                f("discharged_at", "出院时间", "TIMESTAMPTZ", false, null, null, null, 90));
        Map<String, Map<String, String>> aliases = Map.of("status", Map.of(
                "已登记", "ADMITTED", "在院", "IN_HOSPITAL", "已出院", "DISCHARGED", "已取消", "CANCELLED"));
        return new Spec("CLINICAL_ADMISSION", "clinical_svc", "admission", "id", fields,
                binds(b("admission_no", "admission_no"), b("patient_no", "patient_no"),
                        b("attending_doctor", "attending_doctor"),
                        b("status", "status"), b("admitted_at", "admitted_at"),
                        b("discharged_at", "discharged_at")),
                List.of(),
                List.of(new Resolution("visit_id", List.of("visit_no"),
                                "SELECT id FROM clinical_svc.visit WHERE visit_no = :visit_no",
                                "就诊", true),
                        new Resolution("patient_id", List.of("patient_no"),
                                "SELECT id FROM empi_svc.patient WHERE patient_no = :patient_no",
                                "患者", true),
                        new Resolution("ward_id", List.of("ward_code"),
                                "SELECT id FROM clinical_svc.ward WHERE code = :ward_code",
                                "病区", true),
                        new Resolution("bed_id", List.of("ward_code", "bed_no"),
                                "SELECT id FROM clinical_svc.bed WHERE ward_id ="
                                        + " (SELECT id FROM clinical_svc.ward WHERE code = :ward_code)"
                                        + " AND bed_no = :bed_no",
                                "床位", false)),
                "admission_no = :admission_no", List.of("admission_no"), List.of("admission_no"),
                List.of(
                        new Check("SELECT 1 FROM clinical_svc.admission"
                                        + " WHERE admission_no = :admission_no AND patient_no <> :patient_no",
                                "该住院号已存在且属于其他患者，不能改绑"),
                        new Check("SELECT 1 FROM clinical_svc.admission"
                                        + " WHERE visit_id = :visit_id AND admission_no <> :admission_no",
                                "该就诊已存在另一条住院登记"),
                        new Check("SELECT 1 FROM clinical_svc.visit"
                                        + " WHERE visit_no = :visit_no AND patient_no <> :patient_no",
                                "住院登记的患者号与该就诊不一致"),
                        new Check("SELECT 1 FROM clinical_svc.admission"
                                        + " WHERE bed_id = :bed_id AND status IN ('ADMITTED', 'IN_HOSPITAL')"
                                        + "   AND admission_no <> :admission_no",
                                "该床位已有在院患者")),
                true, false, true, null,
                defaults("status", "ADMITTED"), aliases);
    }

    private static Spec visitDiagnosis() {
        List<FieldDef> fields = List.of(
                f("visit_no", "就诊号", "TEXT", true, 32, null, "CLINICAL_VISIT", 10),
                f("code", "诊断编码", "TEXT", true, 32, null, null, 20),
                f("name", "诊断名称", "TEXT", true, 255, null, null, 30),
                f("primary_flag", "是否主诊断", "BOOL", false, null, null, null, 40),
                f("sort_no", "排序号", "INT", false, null, null, null, 50),
                f("diagnosed_by", "诊断医生", "TEXT", true, 64, null, null, 60),
                f("diagnosed_at", "诊断时间", "TIMESTAMPTZ", false, null, null, null, 70));
        return new Spec("CLINICAL_VISIT_DIAGNOSIS", "clinical_svc", "visit_diagnosis", "id", fields,
                binds(b("code", "code"), b("name", "name"), b("primary_flag", "primary_flag"),
                        b("sort_no", "sort_no"), b("diagnosed_by", "diagnosed_by"),
                        b("diagnosed_at", "diagnosed_at")),
                List.of(),
                List.of(new Resolution("visit_id", List.of("visit_no"),
                        "SELECT id FROM clinical_svc.visit WHERE visit_no = :visit_no",
                        "就诊", true)),
                "visit_id = :visit_id AND code = :code",
                List.of("visit_no", "code"), List.of("visit_id", "code"),
                List.of(new Check("SELECT 1 FROM clinical_svc.visit_diagnosis"
                                + " WHERE visit_id = :visit_id AND primary_flag AND code <> :code",
                        "该就诊已有其他主诊断，迁移后会出现两条主诊断")),
                true, false, false, null,
                defaults("primary_flag", "false", "sort_no", "0"), Map.of());
    }

    private static Spec order() {
        List<FieldDef> fields = List.of(
                f("order_no", "医嘱号", "TEXT", true, 32, null, null, 10),
                f("visit_no", "就诊号", "TEXT", true, 32, null, "CLINICAL_VISIT", 20),
                f("order_type", "医嘱类型", "ENUM", true, null,
                        List.of("DRUG", "TREATMENT", "EXAM", "MATERIAL"), null, 30),
                f("item_code", "项目编码", "TEXT", true, 32, null, null, 40),
                f("item_name", "项目名称", "TEXT", true, 128, null, null, 50),
                f("dose", "剂量", "TEXT", false, 32, null, null, 60),
                f("frequency", "频次", "TEXT", false, 32, null, null, 70),
                f("quantity", "数量", "INT", true, null, null, null, 80),
                f("unit", "单位", "TEXT", true, 16, null, null, 90),
                f("unit_price", "单价", "DECIMAL", true, null, null, null, 100),
                // 金额可省略：由数量×单价计算；提供时必须一致
                f("amount", "金额", "DECIMAL", false, null, null, null, 110),
                f("status", "医嘱状态", "ENUM", false, null,
                        List.of("PLACED", "CHARGED", "CANCELLED"), null, 120),
                f("ordered_by", "开立医生", "TEXT", true, 64, null, null, 130),
                f("created_at", "开立时间", "TIMESTAMPTZ", false, null, null, null, 140));
        Map<String, Map<String, String>> aliases = new LinkedHashMap<>();
        aliases.put("order_type", Map.of("药品", "DRUG", "治疗", "TREATMENT",
                "检查", "EXAM", "耗材", "MATERIAL"));
        aliases.put("status", Map.of("已开立", "PLACED", "已计费", "CHARGED", "已取消", "CANCELLED"));
        return new Spec("CLINICAL_ORDER", "clinical_svc", "visit_order", "id", fields,
                binds(b("order_no", "order_no"), b("order_type", "order_type"),
                        b("item_code", "item_code"), b("item_name", "item_name"),
                        b("dose", "dose"), b("frequency", "frequency"),
                        b("quantity", "quantity"), b("unit", "unit"),
                        b("unit_price", "unit_price"), b("amount", "amount"),
                        b("status", "status"), b("ordered_by", "ordered_by"),
                        b("created_at", "created_at")),
                List.of(),
                List.of(new Resolution("visit_id", List.of("visit_no"),
                        "SELECT id FROM clinical_svc.visit WHERE visit_no = :visit_no",
                        "就诊", true)),
                "order_no = :order_no", List.of("order_no"), List.of("order_no"),
                List.of(new Check("SELECT 1 FROM clinical_svc.visit_order"
                                + " WHERE order_no = :order_no AND visit_id <> :visit_id",
                        "该医嘱号已存在且属于其他就诊，不能改绑")),
                true, true, true, "amount",
                defaults("status", "PLACED"), aliases);
    }

    private static Spec charge() {
        List<FieldDef> fields = List.of(
                f("charge_no", "收费单号", "TEXT", true, 32, null, null, 10),
                f("visit_no", "就诊号", "TEXT", true, 32, null, "CLINICAL_VISIT", 20),
                f("total_amount", "总金额", "DECIMAL", false, null, null, null, 30),
                f("status", "收费状态", "ENUM", false, null,
                        List.of("UNPAID", "PAID", "REFUNDED"), null, 40),
                f("payment_method", "支付方式", "TEXT", false, 32, null, null, 50),
                f("paid_at", "支付时间", "TIMESTAMPTZ", false, null, null, null, 60),
                f("created_at", "创建时间", "TIMESTAMPTZ", false, null, null, null, 70),
                f("campus_code", "院区编码", "TEXT", false, 32, null, null, 80));
        Map<String, Map<String, String>> aliases = Map.of("status", Map.of(
                "未支付", "UNPAID", "已支付", "PAID", "已退费", "REFUNDED", "已退", "REFUNDED"));
        return new Spec("CLINICAL_CHARGE", "clinical_svc", "charge", "id", fields,
                binds(b("charge_no", "charge_no"), b("total_amount", "total_amount"),
                        b("status", "status"), b("payment_method", "payment_method"),
                        b("paid_at", "paid_at"), b("created_at", "created_at")),
                List.of(),
                List.of(new Resolution("visit_id", List.of("visit_no"),
                                "SELECT id FROM clinical_svc.visit WHERE visit_no = :visit_no",
                                "就诊", true),
                        new Resolution("campus_id", List.of("campus_code"),
                                "SELECT id FROM auth_svc.campus"
                                        + " WHERE code = :campus_code AND status = 'ACTIVE'",
                                "院区", false,
                                "SELECT id FROM auth_svc.campus WHERE status = 'ACTIVE'"
                                        + " ORDER BY code")),
                "charge_no = :charge_no", List.of("charge_no"), List.of("charge_no"),
                List.of(
                        new Check("SELECT 1 FROM clinical_svc.charge"
                                        + " WHERE charge_no = :charge_no AND visit_id <> :visit_id",
                                "该收费单号已存在且属于其他就诊，不能改绑"),
                        new Check("SELECT 1 FROM clinical_svc.charge"
                                        + " WHERE visit_id = :visit_id AND charge_no <> :charge_no",
                                "该就诊已存在另一张收费单（一次就诊只允许一张）")),
                true, true, true, "total_amount",
                defaults("status", "UNPAID"), aliases);
    }

    private static Spec chargeItem() {
        List<FieldDef> fields = List.of(
                f("charge_no", "收费单号", "TEXT", true, 32, null, "CLINICAL_CHARGE", 10),
                f("order_no", "医嘱号", "TEXT", false, 32, null, "CLINICAL_ORDER", 20),
                f("item_code", "项目编码", "TEXT", true, 32, null, null, 30),
                f("item_name", "项目名称", "TEXT", true, 128, null, null, 40),
                f("category", "项目类别", "TEXT", true, 32, null, null, 50),
                f("amount", "金额", "DECIMAL", true, null, null, null, 60),
                f("created_at", "创建时间", "TIMESTAMPTZ", false, null, null, null, 70));
        return new Spec("CLINICAL_CHARGE_DETAIL", "clinical_svc", "charge_item", "id", fields,
                binds(b("item_code", "item_code"), b("item_name", "item_name"),
                        b("category", "category"), b("amount", "amount"),
                        b("created_at", "created_at")),
                List.of(),
                List.of(new Resolution("charge_id", List.of("charge_no"),
                                "SELECT id FROM clinical_svc.charge WHERE charge_no = :charge_no",
                                "收费单", true),
                        new Resolution("order_id", List.of("order_no"),
                                "SELECT id FROM clinical_svc.visit_order WHERE order_no = :order_no",
                                "医嘱", false)),
                "charge_id = :charge_id AND item_code = :item_code"
                        + " AND order_id IS NOT DISTINCT FROM :order_id",
                List.of("charge_no", "item_code", "order_no"),
                List.of("charge_id", "item_code", "order_id"),
                List.of(new Check("SELECT 1 FROM clinical_svc.charge_item"
                                + " WHERE order_id = :order_id"
                                + "   AND NOT (charge_id = :charge_id AND item_code = :item_code)",
                        "该医嘱已计入其他费用明细（同一医嘱只能计费一次）")),
                false, true, false, "amount", Map.of(), Map.of());
    }

    // ------------------------------------------------------------------ 辅助

    private static FieldDef f(String key, String label, String type, boolean required,
                              Integer maxLength, List<String> enums, String ref, int sortNo) {
        return new FieldDef(key, label, type, required, maxLength, enums, ref, null, sortNo);
    }

    private static ColumnBind b(String field, String column) {
        return new ColumnBind(field, column, BindKind.DIRECT);
    }

    private static List<ColumnBind> binds(ColumnBind... binds) {
        return List.of(binds);
    }

    private static Map<String, String> defaults(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return Map.copyOf(map);
    }
}
