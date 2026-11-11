-- ============================================================================
-- 历史数据迁移台账（mig_svc）——第 270 批
--
-- 设计要点：
--   * 目标注册：每个可迁移目标登记物理表、自然键、依赖顺序；模板、校验、回滚都由它驱动；
--   * 字段字典：模板列 = 字段键，中文标签/类型/必填/枚举/引用目标都在库里，前后端共用一套口径；
--   * 映射档案：源系统列名 → 目标字段 + 转换规则，可复用；源系统换了只加档案，不改代码；
--   * 批次：预检（PREVIEWED）→ 应用（APPLIED）→ 回滚（ROLLED_BACK）/失败（FAILED）；
--     预检结果带文件校验和，应用时逐行复核目标现状，避免"预检干净、应用脏"；
--   * 行台账：raw/normalized 只存脱敏投影，完整载荷加密存 payload_enc（患者证件号/手机号/住址
--     不得以明文落在迁移库里）；before_image/after_image 供差异审计与反向回滚；
--   * 审计：追加式，数据库触发器拒绝 UPDATE/DELETE。
-- ============================================================================

CREATE SCHEMA IF NOT EXISTS mig_svc;

-- ---------------------------------------------------------------------------
-- 1. 目标注册与依赖
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS mig_svc.mig_target (
    code          text PRIMARY KEY,
    name          text NOT NULL,
    category      text NOT NULL
                  CHECK (category IN ('MASTER_DATA', 'PATIENT', 'CLINICAL')),
    target_schema text NOT NULL,
    target_table  text NOT NULL,
    natural_key   text NOT NULL,
    apply_mode    text NOT NULL DEFAULT 'UPSERT'
                  CHECK (apply_mode IN ('UPSERT', 'INSERT_ONLY')),
    sort_no       integer NOT NULL,
    enabled       boolean NOT NULL DEFAULT true,
    description   text,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ux_mig_target_sort UNIQUE (sort_no)
);
COMMENT ON TABLE mig_svc.mig_target IS
    '可迁移目标：物理表 + 自然键 + 依赖顺序；sort_no 同时用于应用顺序与回滚逆序';

CREATE TABLE IF NOT EXISTS mig_svc.mig_target_dependency (
    target_code text NOT NULL REFERENCES mig_svc.mig_target (code),
    depends_on  text NOT NULL REFERENCES mig_svc.mig_target (code),
    PRIMARY KEY (target_code, depends_on),
    CONSTRAINT ck_mig_dependency_self CHECK (target_code <> depends_on)
);
COMMENT ON TABLE mig_svc.mig_target_dependency IS
    '目标依赖：患者必须先于就诊、就诊必须先于医嘱/收费；回滚必须逆序';

-- ---------------------------------------------------------------------------
-- 2. 字段字典（模板与校验的唯一口径）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS mig_svc.mig_field (
    target_code      text NOT NULL REFERENCES mig_svc.mig_target (code),
    field_key        text NOT NULL,
    label            text NOT NULL,
    data_type        text NOT NULL
                     CHECK (data_type IN ('TEXT', 'INT', 'DECIMAL', 'DATE', 'TIMESTAMPTZ', 'BOOL', 'ENUM')),
    required         boolean NOT NULL DEFAULT false,
    max_length       integer,
    enum_values      text[],
    reference_target text REFERENCES mig_svc.mig_target (code),
    description      text,
    sort_no          integer NOT NULL,
    PRIMARY KEY (target_code, field_key)
);
COMMENT ON TABLE mig_svc.mig_field IS
    '字段字典：模板表头 = field_key；中文标签、类型、必填、枚举、引用目标均在此登记';

-- ---------------------------------------------------------------------------
-- 3. 列映射档案
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS mig_svc.mig_mapping_profile (
    id            uuid PRIMARY KEY,
    profile_code  text NOT NULL UNIQUE,
    name          text NOT NULL,
    source_system text NOT NULL,
    target_code   text NOT NULL REFERENCES mig_svc.mig_target (code),
    mappings      jsonb NOT NULL,
    status        text NOT NULL DEFAULT 'ACTIVE'
                  CHECK (status IN ('ACTIVE', 'INACTIVE')),
    version       bigint NOT NULL DEFAULT 0,
    created_by    text NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE mig_svc.mig_mapping_profile IS
    '映射档案：mappings = [{source, field, transform, value, map}]；同一源系统同一目标可多版本';

CREATE INDEX IF NOT EXISTS ix_mig_profile_target
    ON mig_svc.mig_mapping_profile (target_code, source_system, status);

-- ---------------------------------------------------------------------------
-- 4. 迁移批次
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS mig_svc.mig_batch (
    id               uuid PRIMARY KEY,
    batch_no         text NOT NULL UNIQUE,
    target_code      text NOT NULL REFERENCES mig_svc.mig_target (code),
    source_system    text NOT NULL,
    file_name        text,
    profile_code     text,
    checksum         text NOT NULL,
    row_count        integer NOT NULL DEFAULT 0,
    create_count     integer NOT NULL DEFAULT 0,
    update_count     integer NOT NULL DEFAULT 0,
    unchanged_count  integer NOT NULL DEFAULT 0,
    conflict_count   integer NOT NULL DEFAULT 0,
    error_count      integer NOT NULL DEFAULT 0,
    status           text NOT NULL
                     CHECK (status IN ('PREVIEWED', 'APPLIED', 'ROLLED_BACK', 'FAILED')),
    summary          jsonb,
    created_by       text NOT NULL,
    created_at       timestamptz NOT NULL DEFAULT now(),
    applied_at       timestamptz,
    rolled_back_at   timestamptz,
    rolled_back_by   text
);
CREATE INDEX IF NOT EXISTS ix_mig_batch_target
    ON mig_svc.mig_batch (target_code, created_at DESC);
CREATE INDEX IF NOT EXISTS ix_mig_batch_status
    ON mig_svc.mig_batch (status, created_at DESC);
COMMENT ON TABLE mig_svc.mig_batch IS
    '迁移批次：PREVIEWED → APPLIED → ROLLED_BACK；FAILED 表示应用中断且已整体回滚';

-- ---------------------------------------------------------------------------
-- 5. 逐行台账
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS mig_svc.mig_row (
    id           bigserial PRIMARY KEY,
    batch_id     uuid NOT NULL REFERENCES mig_svc.mig_batch (id) ON DELETE CASCADE,
    row_no       integer NOT NULL,
    raw          jsonb NOT NULL,
    normalized   jsonb,
    payload_enc  text,
    action       text NOT NULL
                 CHECK (action IN ('CREATE', 'UPDATE', 'UNCHANGED', 'CONFLICT', 'ERROR')),
    target_pk    uuid,
    natural_key  text,
    before_image jsonb,
    after_image  jsonb,
    message      text,
    applied      boolean NOT NULL DEFAULT false,
    rolled_back  boolean NOT NULL DEFAULT false,
    created_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ux_mig_row UNIQUE (batch_id, row_no)
);
CREATE INDEX IF NOT EXISTS ix_mig_row_batch ON mig_svc.mig_row (batch_id, row_no);
CREATE INDEX IF NOT EXISTS ix_mig_row_pk ON mig_svc.mig_row (target_pk) WHERE target_pk IS NOT NULL;
COMMENT ON TABLE mig_svc.mig_row IS
    '逐行台账：raw/normalized 为脱敏投影；payload_enc 为加密后的完整归一化载荷（供应用/回滚）';
COMMENT ON COLUMN mig_svc.mig_row.before_image IS
    '应用前目标行完整镜像（含密文列），用于差异审计与反向回滚；不对外导出';

-- ---------------------------------------------------------------------------
-- 6. 审计（追加式）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS mig_svc.mig_change_log (
    id         bigserial PRIMARY KEY,
    ts         timestamptz NOT NULL DEFAULT now(),
    actor      text NOT NULL,
    action     text NOT NULL
               CHECK (action IN ('BATCH_PREVIEW', 'BATCH_APPLY', 'BATCH_ROLLBACK',
                                 'PROFILE_CREATE', 'PROFILE_RETIRE', 'BATCH_FAIL')),
    target_code text,
    batch_no    text,
    detail      jsonb,
    client_ip   text,
    trace_id    text
);
CREATE INDEX IF NOT EXISTS ix_mig_change_ts ON mig_svc.mig_change_log (ts DESC);
CREATE INDEX IF NOT EXISTS ix_mig_change_batch ON mig_svc.mig_change_log (batch_no, ts DESC);

CREATE OR REPLACE FUNCTION mig_svc.reject_audit_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION '% 是追加式审计表，禁止 %', TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'insufficient_privilege';
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_mig_change_log_append_only ON mig_svc.mig_change_log;
CREATE TRIGGER trg_mig_change_log_append_only
    BEFORE UPDATE OR DELETE ON mig_svc.mig_change_log
    FOR EACH ROW EXECUTE FUNCTION mig_svc.reject_audit_mutation();

COMMENT ON FUNCTION mig_svc.reject_audit_mutation() IS
    '迁移审计只允许 INSERT：谁在何时对哪一批数据做了什么，不可改写';

-- ===========================================================================
-- 7. 目标种子（顺序 = 应用顺序；回滚取逆序）
-- ===========================================================================
INSERT INTO mig_svc.mig_target
    (code, name, category, target_schema, target_table, natural_key, sort_no, description)
VALUES
    ('CLINICAL_DEPT', '科室字典', 'MASTER_DATA', 'clinical_svc', 'md_dept', 'dept_code', 10,
     '门诊/住院科室；就诊与病区的组织基础，必须先于患者之外的所有临床数据'),
    ('CLINICAL_CHARGE_ITEM', '收费项目字典', 'MASTER_DATA', 'clinical_svc', 'md_charge_item',
     'item_code', 20, '物价收费项目；医嘱金额与费用明细的分类基础'),
    ('CLINICAL_DIAGNOSIS', '诊断字典（ICD-10）', 'MASTER_DATA', 'clinical_svc', 'md_diagnosis',
     'code', 30, 'ICD-10 诊断目录；就诊诊断保存名称快照'),
    ('CLINICAL_WARD', '病区字典', 'MASTER_DATA', 'clinical_svc', 'ward', 'code', 40,
     '病区归属科室；床位与住院登记挂在病区下'),
    ('CLINICAL_BED', '床位字典', 'MASTER_DATA', 'clinical_svc', 'bed', 'ward_code+bed_no', 50,
     '床位编号在病区内唯一；住院登记与转床依赖'),
    ('EMPI_PATIENT', '患者主索引', 'PATIENT', 'empi_svc', 'patient', 'patient_no', 60,
     '患者档案；证件号/手机号/住址加密存储，查重走加盐哈希'),
    ('EMPI_CROSSWALK', '患者跨系统标识', 'PATIENT', 'empi_svc', 'patient_crosswalk',
     'system_code+external_id', 70, '旧 HIS/LIS/PACS/医保标识到患者主索引的映射'),
    ('CLINICAL_VISIT', '门诊就诊', 'CLINICAL', 'clinical_svc', 'visit', 'visit_no', 80,
     '一次挂号 = 一条就诊；保留历史就诊时间与状态'),
    ('CLINICAL_ADMISSION', '住院登记', 'CLINICAL', 'clinical_svc', 'admission', 'admission_no', 90,
     '住院是就诊的延伸；保留入院/出院时间与床位'),
    ('CLINICAL_VISIT_DIAGNOSIS', '就诊诊断', 'CLINICAL', 'clinical_svc', 'visit_diagnosis',
     'visit_no+code', 100, '诊断名称保存快照；同一就诊同一编码仅一条，主诊断唯一'),
    ('CLINICAL_ORDER', '医嘱', 'CLINICAL', 'clinical_svc', 'visit_order', 'order_no', 110,
     '保留历史单号、数量、单价与金额；金额必须等于数量×单价'),
    ('CLINICAL_CHARGE', '收费单', 'CLINICAL', 'clinical_svc', 'charge', 'charge_no', 120,
     '一次就诊一张收费单；已支付必须有支付时间'),
    ('CLINICAL_CHARGE_DETAIL', '费用明细', 'CLINICAL', 'clinical_svc', 'charge_item',
     'charge_no+item_code+order_no', 130, '同一医嘱只能计费一次；挂号费等无医嘱明细 order_no 可为空')
ON CONFLICT (code) DO NOTHING;

INSERT INTO mig_svc.mig_target_dependency (target_code, depends_on) VALUES
    ('CLINICAL_WARD', 'CLINICAL_DEPT'),
    ('CLINICAL_BED', 'CLINICAL_WARD'),
    ('EMPI_CROSSWALK', 'EMPI_PATIENT'),
    ('CLINICAL_VISIT', 'EMPI_PATIENT'),
    ('CLINICAL_VISIT', 'CLINICAL_DEPT'),
    ('CLINICAL_ADMISSION', 'CLINICAL_VISIT'),
    ('CLINICAL_ADMISSION', 'CLINICAL_WARD'),
    ('CLINICAL_ADMISSION', 'CLINICAL_BED'),
    ('CLINICAL_VISIT_DIAGNOSIS', 'CLINICAL_VISIT'),
    ('CLINICAL_ORDER', 'CLINICAL_VISIT'),
    ('CLINICAL_CHARGE', 'CLINICAL_VISIT'),
    ('CLINICAL_CHARGE_DETAIL', 'CLINICAL_CHARGE'),
    ('CLINICAL_CHARGE_DETAIL', 'CLINICAL_ORDER')
ON CONFLICT DO NOTHING;

-- ===========================================================================
-- 8. 字段字典种子
-- ===========================================================================
INSERT INTO mig_svc.mig_field
    (target_code, field_key, label, data_type, required, max_length, enum_values,
     reference_target, description, sort_no)
VALUES
    -- 科室
    ('CLINICAL_DEPT', 'dept_code', '科室编码', 'TEXT', true, 32, NULL, NULL, '院内唯一，建议沿用旧 HIS 编码', 10),
    ('CLINICAL_DEPT', 'name', '科室名称', 'TEXT', true, 128, NULL, NULL, NULL, 20),
    ('CLINICAL_DEPT', 'type', '科室类型', 'ENUM', false, NULL,
     ARRAY['OUTPATIENT', 'INPATIENT', 'BOTH'], NULL, '默认 OUTPATIENT', 30),
    ('CLINICAL_DEPT', 'enabled', '是否启用', 'BOOL', false, NULL, NULL, NULL, '默认启用', 40),

    -- 收费项目
    ('CLINICAL_CHARGE_ITEM', 'item_code', '项目编码', 'TEXT', true, 32, NULL, NULL, '物价编码', 10),
    ('CLINICAL_CHARGE_ITEM', 'name', '项目名称', 'TEXT', true, 128, NULL, NULL, NULL, 20),
    ('CLINICAL_CHARGE_ITEM', 'category', '项目类别', 'ENUM', true, NULL,
     ARRAY['REGISTRATION', 'CONSULTATION', 'DRUG', 'TREATMENT', 'EXAM', 'MATERIAL'], NULL, NULL, 30),
    ('CLINICAL_CHARGE_ITEM', 'unit', '单位', 'TEXT', false, 16, NULL, NULL, '默认「次」', 40),
    ('CLINICAL_CHARGE_ITEM', 'price', '价格', 'DECIMAL', true, NULL, NULL, NULL, '不得为负', 50),
    ('CLINICAL_CHARGE_ITEM', 'enabled', '是否启用', 'BOOL', false, NULL, NULL, NULL, '默认启用', 60),

    -- 诊断字典
    ('CLINICAL_DIAGNOSIS', 'code', '诊断编码', 'TEXT', true, 32, NULL, NULL, 'ICD-10 编码', 10),
    ('CLINICAL_DIAGNOSIS', 'name', '诊断名称', 'TEXT', true, 255, NULL, NULL, NULL, 20),
    ('CLINICAL_DIAGNOSIS', 'category', '系统分类', 'TEXT', false, 64, NULL, NULL, '如「呼吸系统」', 30),
    ('CLINICAL_DIAGNOSIS', 'enabled', '是否启用', 'BOOL', false, NULL, NULL, NULL, '默认启用', 40),

    -- 病区
    ('CLINICAL_WARD', 'code', '病区编码', 'TEXT', true, 32, NULL, NULL, NULL, 10),
    ('CLINICAL_WARD', 'name', '病区名称', 'TEXT', true, 128, NULL, NULL, NULL, 20),
    ('CLINICAL_WARD', 'dept_code', '所属科室编码', 'TEXT', true, 32, NULL, 'CLINICAL_DEPT',
     '必须已存在于科室字典', 30),
    ('CLINICAL_WARD', 'enabled', '是否启用', 'BOOL', false, NULL, NULL, NULL, '默认启用', 40),

    -- 床位
    ('CLINICAL_BED', 'ward_code', '病区编码', 'TEXT', true, 32, NULL, 'CLINICAL_WARD', NULL, 10),
    ('CLINICAL_BED', 'bed_no', '床号', 'TEXT', true, 32, NULL, NULL, '病区内唯一', 20),
    ('CLINICAL_BED', 'status', '床位状态', 'ENUM', false, NULL,
     ARRAY['FREE', 'OCCUPIED', 'RESERVED', 'DISABLED'], NULL, '默认 FREE', 30),

    -- 患者
    ('EMPI_PATIENT', 'patient_no', '患者号', 'TEXT', true, 32, NULL, NULL,
     '院内可读患者号；建议沿用旧院患者号，便于对账', 10),
    ('EMPI_PATIENT', 'name', '姓名', 'TEXT', true, 64, NULL, NULL, NULL, 20),
    ('EMPI_PATIENT', 'gender', '性别', 'ENUM', false, NULL,
     ARRAY['MALE', 'FEMALE', 'UNKNOWN'], NULL, '支持 男/女/未知 自动转换', 30),
    ('EMPI_PATIENT', 'birth_date', '出生日期', 'DATE', false, NULL, NULL, NULL,
     '支持 yyyy-MM-dd / yyyyMMdd', 40),
    ('EMPI_PATIENT', 'id_type', '证件类型', 'ENUM', false, NULL,
     ARRAY['ID_CARD', 'PASSPORT', 'MILITARY', 'HK_MACAO', 'NONE'], NULL, NULL, 50),
    ('EMPI_PATIENT', 'id_no', '证件号', 'TEXT', false, 64, NULL, NULL,
     '加密存储；以哈希查重，明文不落索引', 60),
    ('EMPI_PATIENT', 'phone', '手机号', 'TEXT', false, 32, NULL, NULL,
     '加密存储；以哈希查重', 70),
    ('EMPI_PATIENT', 'address', '住址', 'TEXT', false, 255, NULL, NULL, '加密存储', 80),
    ('EMPI_PATIENT', 'blood_type', '血型', 'TEXT', false, 8, NULL, NULL, NULL, 90),
    ('EMPI_PATIENT', 'allergy', '过敏史', 'TEXT', false, 255, NULL, NULL, NULL, 100),
    ('EMPI_PATIENT', 'status', '档案状态', 'ENUM', false, NULL,
     ARRAY['ACTIVE', 'MERGED', 'DECEASED'], NULL, '默认 ACTIVE；MERGED 需同时提供 merged_into', 110),

    -- 跨系统标识
    ('EMPI_CROSSWALK', 'patient_no', '患者号', 'TEXT', true, 32, NULL, 'EMPI_PATIENT',
     '必须已存在于患者主索引', 10),
    ('EMPI_CROSSWALK', 'system_code', '系统编码', 'TEXT', true, 32, NULL, NULL,
     '如 HIS / LIS / PACS / INSURANCE_NATIONAL', 20),
    ('EMPI_CROSSWALK', 'external_id', '外部标识', 'TEXT', true, 64, NULL, NULL,
     '(系统, 外部标识) 全局唯一', 30),

    -- 就诊
    ('CLINICAL_VISIT', 'visit_no', '就诊号', 'TEXT', true, 32, NULL, NULL,
     '建议沿用旧 HIS 就诊号；对账按此字段', 10),
    ('CLINICAL_VISIT', 'patient_no', '患者号', 'TEXT', true, 32, NULL, 'EMPI_PATIENT',
     '必须已存在于患者主索引', 20),
    ('CLINICAL_VISIT', 'dept_code', '科室编码', 'TEXT', true, 32, NULL, 'CLINICAL_DEPT', NULL, 30),
    ('CLINICAL_VISIT', 'doctor_id', '医生标识', 'TEXT', true, 64, NULL, NULL, '工号或登录名', 40),
    ('CLINICAL_VISIT', 'visit_type', '就诊类型', 'ENUM', false, NULL,
     ARRAY['OUTPATIENT', 'EMERGENCY'], NULL, '默认 OUTPATIENT', 50),
    ('CLINICAL_VISIT', 'status', '就诊状态', 'ENUM', false, NULL,
     ARRAY['REGISTERED', 'IN_TREATMENT', 'FINISHED', 'CANCELLED'], NULL, '默认 REGISTERED', 60),
    ('CLINICAL_VISIT', 'visit_date', '就诊日期', 'DATE', true, NULL, NULL, NULL,
     '支持 yyyy-MM-dd / yyyyMMdd', 70),
    ('CLINICAL_VISIT', 'registered_at', '挂号时间', 'TIMESTAMPTZ', false, NULL, NULL, NULL,
     '支持 yyyy-MM-dd HH:mm:ss / ISO-8601', 80),
    ('CLINICAL_VISIT', 'started_at', '接诊时间', 'TIMESTAMPTZ', false, NULL, NULL, NULL, NULL, 90),
    ('CLINICAL_VISIT', 'finished_at', '结束时间', 'TIMESTAMPTZ', false, NULL, NULL, NULL,
     '已结束就诊必须有值', 100),

    -- 住院
    ('CLINICAL_ADMISSION', 'admission_no', '住院号', 'TEXT', true, 32, NULL, NULL, NULL, 10),
    ('CLINICAL_ADMISSION', 'visit_no', '就诊号', 'TEXT', true, 32, NULL, 'CLINICAL_VISIT',
     '一次就诊只能有一条住院登记', 20),
    ('CLINICAL_ADMISSION', 'patient_no', '患者号', 'TEXT', true, 32, NULL, 'EMPI_PATIENT',
     '必须与该就诊的患者一致', 30),
    ('CLINICAL_ADMISSION', 'ward_code', '病区编码', 'TEXT', true, 32, NULL, 'CLINICAL_WARD', NULL, 40),
    ('CLINICAL_ADMISSION', 'bed_no', '床号', 'TEXT', false, 32, NULL, NULL,
     '与病区编码共同定位床位', 50),
    ('CLINICAL_ADMISSION', 'attending_doctor', '主管医生', 'TEXT', true, 64, NULL, NULL, NULL, 60),
    ('CLINICAL_ADMISSION', 'status', '住院状态', 'ENUM', false, NULL,
     ARRAY['ADMITTED', 'IN_HOSPITAL', 'DISCHARGED', 'CANCELLED'], NULL, '默认 ADMITTED', 70),
    ('CLINICAL_ADMISSION', 'admitted_at', '入院时间', 'TIMESTAMPTZ', false, NULL, NULL, NULL, NULL, 80),
    ('CLINICAL_ADMISSION', 'discharged_at', '出院时间', 'TIMESTAMPTZ', false, NULL, NULL, NULL,
     '已出院必须有值', 90),

    -- 就诊诊断
    ('CLINICAL_VISIT_DIAGNOSIS', 'visit_no', '就诊号', 'TEXT', true, 32, NULL, 'CLINICAL_VISIT', NULL, 10),
    ('CLINICAL_VISIT_DIAGNOSIS', 'code', '诊断编码', 'TEXT', true, 32, NULL, NULL, 'ICD-10 编码', 20),
    ('CLINICAL_VISIT_DIAGNOSIS', 'name', '诊断名称', 'TEXT', true, 255, NULL, NULL, NULL, 30),
    ('CLINICAL_VISIT_DIAGNOSIS', 'primary_flag', '是否主诊断', 'BOOL', false, NULL, NULL, NULL,
     '同一就诊只能有一条主诊断', 40),
    ('CLINICAL_VISIT_DIAGNOSIS', 'sort_no', '排序号', 'INT', false, NULL, NULL, NULL, '默认 0', 50),
    ('CLINICAL_VISIT_DIAGNOSIS', 'diagnosed_by', '诊断医生', 'TEXT', true, 64, NULL, NULL, NULL, 60),
    ('CLINICAL_VISIT_DIAGNOSIS', 'diagnosed_at', '诊断时间', 'TIMESTAMPTZ', false, NULL, NULL, NULL, NULL, 70),

    -- 医嘱
    ('CLINICAL_ORDER', 'order_no', '医嘱号', 'TEXT', true, 32, NULL, NULL, '建议沿用旧 HIS 医嘱号', 10),
    ('CLINICAL_ORDER', 'visit_no', '就诊号', 'TEXT', true, 32, NULL, 'CLINICAL_VISIT', NULL, 20),
    ('CLINICAL_ORDER', 'order_type', '医嘱类型', 'ENUM', true, NULL,
     ARRAY['DRUG', 'TREATMENT', 'EXAM', 'MATERIAL'], NULL, NULL, 30),
    ('CLINICAL_ORDER', 'item_code', '项目编码', 'TEXT', true, 32, NULL, NULL, NULL, 40),
    ('CLINICAL_ORDER', 'item_name', '项目名称', 'TEXT', true, 128, NULL, NULL, NULL, 50),
    ('CLINICAL_ORDER', 'dose', '剂量', 'TEXT', false, 32, NULL, NULL, NULL, 60),
    ('CLINICAL_ORDER', 'frequency', '频次', 'TEXT', false, 32, NULL, NULL, NULL, 70),
    ('CLINICAL_ORDER', 'quantity', '数量', 'INT', true, NULL, NULL, NULL, '必须大于 0', 80),
    ('CLINICAL_ORDER', 'unit', '单位', 'TEXT', true, 16, NULL, NULL, NULL, 90),
    ('CLINICAL_ORDER', 'unit_price', '单价', 'DECIMAL', true, NULL, NULL, NULL, '不得为负', 100),
    ('CLINICAL_ORDER', 'amount', '金额', 'DECIMAL', true, NULL, NULL, NULL,
     '必须等于数量×单价；缺失时自动计算', 110),
    ('CLINICAL_ORDER', 'status', '医嘱状态', 'ENUM', false, NULL,
     ARRAY['PLACED', 'CHARGED', 'CANCELLED'], NULL, '默认 PLACED', 120),
    ('CLINICAL_ORDER', 'ordered_by', '开立医生', 'TEXT', true, 64, NULL, NULL, NULL, 130),
    ('CLINICAL_ORDER', 'created_at', '开立时间', 'TIMESTAMPTZ', false, NULL, NULL, NULL, NULL, 140),

    -- 收费单
    ('CLINICAL_CHARGE', 'charge_no', '收费单号', 'TEXT', true, 32, NULL, NULL, '建议沿用旧 HIS 收费单号', 10),
    ('CLINICAL_CHARGE', 'visit_no', '就诊号', 'TEXT', true, 32, NULL, 'CLINICAL_VISIT',
     '一次就诊只能有一张收费单', 20),
    ('CLINICAL_CHARGE', 'total_amount', '总金额', 'DECIMAL', false, NULL, NULL, NULL,
     '缺失时按费用明细汇总', 30),
    ('CLINICAL_CHARGE', 'status', '收费状态', 'ENUM', false, NULL,
     ARRAY['UNPAID', 'PAID', 'REFUNDED'], NULL, '默认 UNPAID', 40),
    ('CLINICAL_CHARGE', 'payment_method', '支付方式', 'TEXT', false, 32, NULL, NULL, NULL, 50),
    ('CLINICAL_CHARGE', 'paid_at', '支付时间', 'TIMESTAMPTZ', false, NULL, NULL, NULL,
     '已支付必须有值', 60),
    ('CLINICAL_CHARGE', 'created_at', '创建时间', 'TIMESTAMPTZ', false, NULL, NULL, NULL, NULL, 70),

    -- 费用明细
    ('CLINICAL_CHARGE_DETAIL', 'charge_no', '收费单号', 'TEXT', true, 32, NULL, 'CLINICAL_CHARGE', NULL, 10),
    ('CLINICAL_CHARGE_DETAIL', 'order_no', '医嘱号', 'TEXT', false, 32, NULL, 'CLINICAL_ORDER',
     '同一医嘱只能计费一次；无医嘱明细留空', 20),
    ('CLINICAL_CHARGE_DETAIL', 'item_code', '项目编码', 'TEXT', true, 32, NULL, NULL, NULL, 30),
    ('CLINICAL_CHARGE_DETAIL', 'item_name', '项目名称', 'TEXT', true, 128, NULL, NULL, NULL, 40),
    ('CLINICAL_CHARGE_DETAIL', 'category', '项目类别', 'TEXT', true, 32, NULL, NULL, NULL, 50),
    ('CLINICAL_CHARGE_DETAIL', 'amount', '金额', 'DECIMAL', true, NULL, NULL, NULL, '不得为负', 60),
    ('CLINICAL_CHARGE_DETAIL', 'created_at', '创建时间', 'TIMESTAMPTZ', false, NULL, NULL, NULL, NULL, 70)
ON CONFLICT (target_code, field_key) DO NOTHING;
