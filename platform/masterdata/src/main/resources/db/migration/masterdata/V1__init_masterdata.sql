-- ============================================================================
-- 主数据与字典映射治理（第 269 批，Gate G4 / 计划书 W4.1）
--
-- 设计口径（必须与代码注释一致）：
--   1. 业务表仍是各自域的真源：临床 md_dept / md_charge_item / md_diagnosis /
--      operation_catalog、药事 md_drug、医技 md_exam_item、物资 md_consumable、
--      账号 org / campus。本 schema **不复制业务写入**，只做治理索引与映射。
--   2. 域注册 md_domain 描述"这个域来自哪个服务、哪张表、需要哪些外部系统编码"；
--      源表同步由服务端固定适配器执行（不拼接用户输入的表名，避免 SQL 注入）。
--   3. 条目 md_item 以（域, 本地编码）唯一；状态 ACTIVE / INACTIVE / MISSING_IN_SOURCE。
--      源表里消失的条目**不删除**：置 MISSING_IN_SOURCE 并保留映射，避免"删了以后
--      历史单据的对照关系一起消失"。
--   4. 版本 md_item_version / md_mapping_version 追加式，带 prev_checksum → checksum
--      哈希链：任何一条被改、被删、被重排，链校验都会断开（校验入口见 API）。
--   5. 映射 md_external_map 支持一码多源（同一本地编码对应多个系统）与一源多码
--      （同一外部编码对应多个本地编码）。同一（域, 本地编码, 系统）最多一个主映射，
--      由部分唯一索引兜底；解析结果可能多义，接口必须返回候选而不是随便挑一个。
--   6. 条目/映射的单条变更必须走变更申请 md_change_request，提交人 ≠ 审批人；
--      批量导入（mdm:import）以"预检校验和 + 批次 + 审计 + 版本"治理，逐行留痕。
--   7. 审计 md_change_log、导入行 md_import_row、两类版本表都由触发器拒绝 UPDATE/DELETE。
-- ============================================================================

CREATE SCHEMA IF NOT EXISTS mdm_svc;

-- ---------------------------------------------------------------------------
-- 1. 主数据域注册
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS mdm_svc.md_domain (
    domain_code       text PRIMARY KEY,
    name              text NOT NULL,
    category          text NOT NULL
                      CHECK (category IN ('ORGANIZATION', 'CLINICAL', 'FINANCE', 'MATERIAL', 'OTHER')),
    owner_service     text NOT NULL,                     -- platform-clinical / platform-pharmacy …
    source_schema     text,
    source_table      text,
    code_column       text,
    name_column       text,
    sync_adapter      text,                              -- 服务端适配器枚举；为空表示只登记不同步
    syncable          boolean NOT NULL DEFAULT false,
    required_systems  jsonb NOT NULL DEFAULT '[]'::jsonb, -- 必须建立对照的外部系统（质量口径）
    description       text,
    sort_no           integer NOT NULL DEFAULT 0,
    enabled           boolean NOT NULL DEFAULT true,
    last_synced_at    timestamptz,
    last_sync_status  text CHECK (last_sync_status IN ('OK', 'FAILED')),
    last_sync_summary jsonb,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_md_domain_syncable CHECK (NOT syncable OR sync_adapter IS NOT NULL)
);

COMMENT ON TABLE mdm_svc.md_domain IS
    '主数据域注册：来源服务/表、是否可同步、必须对照的外部系统（质量看板口径）';
COMMENT ON COLUMN mdm_svc.md_domain.required_systems IS
    '必须建立映射的外部系统编码数组；为空表示该域不强制外部对照（如班次、院区）';

-- ---------------------------------------------------------------------------
-- 2. 主数据条目（治理索引，不是业务真源）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS mdm_svc.md_item (
    id             uuid PRIMARY KEY,
    domain_code    text NOT NULL
                   REFERENCES mdm_svc.md_domain (domain_code),
    local_code     text NOT NULL,
    name           text NOT NULL,
    alias          text,                                 -- 别名/拼音码/检索码
    category       text,                                 -- 域内分类（科室类型/收费类别/剂型…）
    spec           text,                                 -- 规格（药品/耗材）
    unit           text,
    status         text NOT NULL DEFAULT 'ACTIVE'
                   CHECK (status IN ('ACTIVE', 'INACTIVE', 'MISSING_IN_SOURCE')),
    source_system  text NOT NULL DEFAULT 'MANUAL'
                   CHECK (source_system IN ('IN_HOUSE', 'MANUAL', 'IMPORT')),
    source_ref     text,                                 -- 源表主键（用于对账，不参与业务）
    effective_from date,
    effective_to   date,
    attributes     jsonb NOT NULL DEFAULT '{}'::jsonb,   -- 域属性快照（价格等仅用于治理展示）
    fingerprint    text NOT NULL,                        -- 归一化内容指纹，用于同步去抖
    version        bigint NOT NULL DEFAULT 0,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ux_md_item_domain_code UNIQUE (domain_code, local_code),
    CONSTRAINT ck_md_item_effective
        CHECK (effective_to IS NULL OR effective_from IS NULL OR effective_to >= effective_from)
);

CREATE INDEX IF NOT EXISTS ix_md_item_domain_status
    ON mdm_svc.md_item (domain_code, status);
CREATE INDEX IF NOT EXISTS ix_md_item_name
    ON mdm_svc.md_item (domain_code, name);
CREATE INDEX IF NOT EXISTS ix_md_item_source
    ON mdm_svc.md_item (domain_code, source_system);

COMMENT ON TABLE mdm_svc.md_item IS
    '主数据条目治理索引：一物一码；源表消失置 MISSING_IN_SOURCE，不物理删除';
COMMENT ON COLUMN mdm_svc.md_item.attributes IS
    '域属性快照（如价格/医保类别），仅用于治理检索与质量核对，不作为业务计价真源';
COMMENT ON COLUMN mdm_svc.md_item.source_system IS
    'IN_HOUSE=源表同步；MANUAL=变更申请手工建；IMPORT=批量导入建；同步只覆盖 IN_HOUSE';

-- ---------------------------------------------------------------------------
-- 3. 条目版本链（追加式）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS mdm_svc.md_item_version (
    id           bigserial PRIMARY KEY,
    item_id      uuid NOT NULL,
    domain_code  text NOT NULL,
    local_code   text NOT NULL,
    version_no   bigint NOT NULL,
    change_type  text NOT NULL
                 CHECK (change_type IN ('CREATE', 'UPDATE', 'ENABLE', 'DISABLE',
                                        'MISSING', 'RESTORE', 'IMPORT')),
    snapshot     jsonb NOT NULL,                         -- 变更后的完整条目快照
    diff         jsonb,                                  -- {字段: {before, after}}
    reason       text,
    ref_type     text NOT NULL
                 CHECK (ref_type IN ('CHANGE_REQUEST', 'IMPORT', 'SYNC', 'MANUAL')),
    ref_id       text,                                   -- 申请单号 / 导入批次号
    actor        text NOT NULL,
    prev_checksum text,                                  -- 上一条版本的 checksum（首条为空）
    checksum     text NOT NULL,                          -- sha256(prev + 规范化快照)
    created_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ux_md_item_version UNIQUE (item_id, version_no)
);

CREATE INDEX IF NOT EXISTS ix_md_item_version_item
    ON mdm_svc.md_item_version (item_id, version_no DESC);
CREATE INDEX IF NOT EXISTS ix_md_item_version_code
    ON mdm_svc.md_item_version (domain_code, local_code, version_no DESC);

COMMENT ON TABLE mdm_svc.md_item_version IS
    '条目版本链（追加式）：snapshot 为变更后快照，checksum 串联上一条形成防篡改链';

-- ---------------------------------------------------------------------------
-- 4. 外部系统与编码映射
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS mdm_svc.md_external_system (
    system_code text PRIMARY KEY,
    name        text NOT NULL,
    system_type text NOT NULL
                CHECK (system_type IN ('HIS', 'LIS', 'PACS', 'EMR', 'INSURANCE',
                                       'REGION', 'OTHER')),
    vendor      text,
    endpoint    text,
    contact     text,
    status      text NOT NULL DEFAULT 'ACTIVE'
                CHECK (status IN ('ACTIVE', 'INACTIVE')),
    sort_no     integer NOT NULL DEFAULT 0,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now()
);

COMMENT ON TABLE mdm_svc.md_external_system IS
    '外部编码体系注册（HIS/LIS/PACS/EMR/医保国家平台/省平台/区域平台）';

CREATE TABLE IF NOT EXISTS mdm_svc.md_external_map (
    id             uuid PRIMARY KEY,
    domain_code    text NOT NULL,
    local_code     text NOT NULL,
    system_code    text NOT NULL
                   REFERENCES mdm_svc.md_external_system (system_code),
    external_code  text NOT NULL,
    external_name  text,
    map_type       text NOT NULL DEFAULT 'EXACT'
                   CHECK (map_type IN ('EXACT', 'EQUIVALENT', 'GROUP')),
    primary_flag   boolean NOT NULL DEFAULT false,
    status         text NOT NULL DEFAULT 'ACTIVE'
                   CHECK (status IN ('ACTIVE', 'INACTIVE')),
    effective_from date,
    effective_to   date,
    remark         text,
    version        bigint NOT NULL DEFAULT 0,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ux_md_external_map UNIQUE (domain_code, local_code, system_code, external_code),
    CONSTRAINT fk_md_external_map_item
        FOREIGN KEY (domain_code, local_code)
        REFERENCES mdm_svc.md_item (domain_code, local_code) ON DELETE RESTRICT,
    CONSTRAINT ck_md_external_map_effective
        CHECK (effective_to IS NULL OR effective_from IS NULL OR effective_to >= effective_from)
);

-- 同一（域, 本地编码, 系统）最多一个"生效中的主映射"
CREATE UNIQUE INDEX IF NOT EXISTS ux_md_external_map_primary
    ON mdm_svc.md_external_map (domain_code, local_code, system_code)
    WHERE primary_flag AND status = 'ACTIVE';

-- 反向解析：外部编码 → 本地编码（允许一源多码，解析可能多义）
CREATE INDEX IF NOT EXISTS ix_md_external_map_reverse
    ON mdm_svc.md_external_map (system_code, external_code)
    WHERE status = 'ACTIVE';
CREATE INDEX IF NOT EXISTS ix_md_external_map_local
    ON mdm_svc.md_external_map (domain_code, local_code, system_code);

COMMENT ON TABLE mdm_svc.md_external_map IS
    '外部编码映射：一码多源/一源多码；同域同系统下主映射唯一（部分唯一索引）';

CREATE TABLE IF NOT EXISTS mdm_svc.md_mapping_version (
    id            bigserial PRIMARY KEY,
    mapping_id    uuid NOT NULL,
    domain_code   text NOT NULL,
    local_code    text NOT NULL,
    system_code   text NOT NULL,
    external_code text NOT NULL,
    version_no    bigint NOT NULL,
    change_type   text NOT NULL
                  CHECK (change_type IN ('CREATE', 'UPDATE', 'RETIRE')),
    snapshot      jsonb NOT NULL,
    reason        text,
    ref_type      text NOT NULL
                  CHECK (ref_type IN ('CHANGE_REQUEST', 'IMPORT', 'SYNC', 'MANUAL')),
    ref_id        text,
    actor         text NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ux_md_mapping_version UNIQUE (mapping_id, version_no)
);

CREATE INDEX IF NOT EXISTS ix_md_mapping_version_local
    ON mdm_svc.md_mapping_version (domain_code, local_code, system_code, version_no DESC);

COMMENT ON TABLE mdm_svc.md_mapping_version IS
    '映射版本（追加式）：CREATE/UPDATE/RETIRE 全留痕；停用不物理删除';

-- ---------------------------------------------------------------------------
-- 5. 变更申请与审批（职责分离）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS mdm_svc.md_change_request (
    id              uuid PRIMARY KEY,
    request_no      text NOT NULL,
    change_type     text NOT NULL
                    CHECK (change_type IN ('ITEM_CREATE', 'ITEM_UPDATE', 'ITEM_STATUS',
                                           'MAPPING_UPSERT', 'MAPPING_DELETE')),
    domain_code     text NOT NULL REFERENCES mdm_svc.md_domain (domain_code),
    local_code      text NOT NULL,
    payload         jsonb NOT NULL,
    reason          text NOT NULL CHECK (length(btrim(reason)) >= 2),
    status          text NOT NULL DEFAULT 'SUBMITTED'
                    CHECK (status IN ('SUBMITTED', 'APPLIED', 'REJECTED', 'CANCELLED')),
    submitted_by    text NOT NULL,
    submitted_at    timestamptz NOT NULL DEFAULT now(),
    reviewed_by     text,
    reviewed_at     timestamptz,
    review_comment  text,
    applied_version bigint,
    version         bigint NOT NULL DEFAULT 0,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ux_md_change_request_no UNIQUE (request_no)
);

-- 同一（域, 本地编码）同时只能有一个待审批的条目级申请，避免并行审批互相覆盖
CREATE UNIQUE INDEX IF NOT EXISTS ux_md_change_request_open_item
    ON mdm_svc.md_change_request (domain_code, local_code)
    WHERE status = 'SUBMITTED'
      AND change_type IN ('ITEM_CREATE', 'ITEM_UPDATE', 'ITEM_STATUS');

CREATE INDEX IF NOT EXISTS ix_md_change_request_status
    ON mdm_svc.md_change_request (status, submitted_at DESC);
CREATE INDEX IF NOT EXISTS ix_md_change_request_code
    ON mdm_svc.md_change_request (domain_code, local_code, submitted_at DESC);

COMMENT ON TABLE mdm_svc.md_change_request IS
    '主数据变更申请：SUBMITTED → APPLIED / REJECTED / CANCELLED；提交人不能审批自己的申请';

-- ---------------------------------------------------------------------------
-- 6. 导入批次（预检 → 应用，校验和一致才允许落库）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS mdm_svc.md_import_batch (
    id               uuid PRIMARY KEY,
    batch_no         text NOT NULL,
    mode             text NOT NULL CHECK (mode IN ('PREVIEW', 'APPLY')),
    format           text NOT NULL CHECK (format IN ('JSON', 'CSV')),
    domain_code      text REFERENCES mdm_svc.md_domain (domain_code),
    file_name        text,
    checksum         text NOT NULL,
    row_count        integer NOT NULL DEFAULT 0,
    create_count     integer NOT NULL DEFAULT 0,
    update_count     integer NOT NULL DEFAULT 0,
    unchanged_count  integer NOT NULL DEFAULT 0,
    conflict_count   integer NOT NULL DEFAULT 0,
    error_count      integer NOT NULL DEFAULT 0,
    status           text NOT NULL CHECK (status IN ('PREVIEWED', 'APPLIED', 'FAILED')),
    summary          jsonb,
    created_by       text NOT NULL,
    created_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ux_md_import_batch_no UNIQUE (batch_no)
);

CREATE INDEX IF NOT EXISTS ix_md_import_batch_domain
    ON mdm_svc.md_import_batch (domain_code, created_at DESC);

COMMENT ON TABLE mdm_svc.md_import_batch IS
    '导入批次：PREVIEW 只算差异；APPLY 必须与预检批次校验和一致，防止"预检干净、应用脏"';

CREATE TABLE IF NOT EXISTS mdm_svc.md_import_row (
    id         bigserial PRIMARY KEY,
    batch_id   uuid NOT NULL REFERENCES mdm_svc.md_import_batch (id),
    row_no     integer NOT NULL,
    local_code text,
    action     text NOT NULL
               CHECK (action IN ('CREATE', 'UPDATE', 'UNCHANGED', 'CONFLICT', 'ERROR')),
    message    text,
    detail     jsonb,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS ix_md_import_row_batch
    ON mdm_svc.md_import_row (batch_id, row_no);

COMMENT ON TABLE mdm_svc.md_import_row IS
    '导入逐行结果（追加式）：冲突/错误行必须可定位到行号与原因，便于回滚与重导';

-- ---------------------------------------------------------------------------
-- 7. 审计（追加式）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS mdm_svc.md_change_log (
    id          bigserial PRIMARY KEY,
    ts          timestamptz NOT NULL DEFAULT now(),
    actor       text NOT NULL,
    action      text NOT NULL
                CHECK (action IN ('SYNC', 'IMPORT_PREVIEW', 'IMPORT_APPLY',
                                  'REQUEST_SUBMIT', 'REQUEST_APPROVE', 'REQUEST_REJECT',
                                  'REQUEST_CANCEL', 'MAPPING_RESOLVE', 'VERSION_VERIFY')),
    domain_code text,
    local_code  text,
    ref_id      text,
    detail      jsonb,
    client_ip   text,
    trace_id    text
);

CREATE INDEX IF NOT EXISTS ix_md_change_log_ts ON mdm_svc.md_change_log (ts DESC);
CREATE INDEX IF NOT EXISTS ix_md_change_log_actor ON mdm_svc.md_change_log (actor, ts DESC);
CREATE INDEX IF NOT EXISTS ix_md_change_log_domain
    ON mdm_svc.md_change_log (domain_code, local_code, ts DESC);

COMMENT ON TABLE mdm_svc.md_change_log IS
    '主数据审计（只允许 INSERT）：谁在何时对哪个域/编码做了什么，detail 不落患者明文';

CREATE OR REPLACE FUNCTION mdm_svc.reject_audit_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION '% 是追加式证据表，禁止 %', TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'insufficient_privilege';
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION mdm_svc.reject_audit_mutation() IS
    '版本/导入行/审计只允许 INSERT：UPDATE/DELETE 一律拒绝';

DROP TRIGGER IF EXISTS trg_md_item_version_append_only ON mdm_svc.md_item_version;
CREATE TRIGGER trg_md_item_version_append_only
    BEFORE UPDATE OR DELETE ON mdm_svc.md_item_version
    FOR EACH ROW EXECUTE FUNCTION mdm_svc.reject_audit_mutation();

DROP TRIGGER IF EXISTS trg_md_mapping_version_append_only ON mdm_svc.md_mapping_version;
CREATE TRIGGER trg_md_mapping_version_append_only
    BEFORE UPDATE OR DELETE ON mdm_svc.md_mapping_version
    FOR EACH ROW EXECUTE FUNCTION mdm_svc.reject_audit_mutation();

DROP TRIGGER IF EXISTS trg_md_import_row_append_only ON mdm_svc.md_import_row;
CREATE TRIGGER trg_md_import_row_append_only
    BEFORE UPDATE OR DELETE ON mdm_svc.md_import_row
    FOR EACH ROW EXECUTE FUNCTION mdm_svc.reject_audit_mutation();

DROP TRIGGER IF EXISTS trg_md_change_log_append_only ON mdm_svc.md_change_log;
CREATE TRIGGER trg_md_change_log_append_only
    BEFORE UPDATE OR DELETE ON mdm_svc.md_change_log
    FOR EACH ROW EXECUTE FUNCTION mdm_svc.reject_audit_mutation();

-- ---------------------------------------------------------------------------
-- 8. 域注册种子（来源表与适配器口径在此固定；改口径必须走新迁移）
-- ---------------------------------------------------------------------------
INSERT INTO mdm_svc.md_domain
    (domain_code, name, category, owner_service, source_schema, source_table,
     code_column, name_column, sync_adapter, syncable, required_systems, description, sort_no)
VALUES
    ('DEPT', '科室', 'ORGANIZATION', 'platform-clinical', 'clinical_svc', 'md_dept',
     'dept_code', 'name', 'CLINICAL_DEPT', true, '[]'::jsonb,
     '门诊/住院科室；与 auth 组织架构通过编码对齐', 10),
    ('CHARGE_ITEM', '收费项目', 'FINANCE', 'platform-clinical', 'clinical_svc', 'md_charge_item',
     'item_code', 'name', 'CLINICAL_CHARGE_ITEM', true, '["INSURANCE_NATIONAL"]'::jsonb,
     '物价收费项目；价格真源仍在 clinical，本域只做索引与医保对照', 20),
    ('DIAGNOSIS', '诊断（ICD-10）', 'CLINICAL', 'platform-clinical', 'clinical_svc', 'md_diagnosis',
     'code', 'name', 'CLINICAL_DIAGNOSIS', true, '["INSURANCE_NATIONAL"]'::jsonb,
     'ICD-10 诊断字典；病案首页、DRG/DIP 与医保结算清单共同依赖', 30),
    ('OPERATION', '手术操作（ICD-9-CM-3）', 'CLINICAL', 'platform-clinical', 'clinical_svc',
     'operation_catalog', 'code', 'name', 'CLINICAL_OPERATION', true,
     '["INSURANCE_NATIONAL"]'::jsonb,
     '手术操作编码；手术分级与医保手术编码对照', 40),
    ('DRUG', '药品', 'MATERIAL', 'platform-pharmacy', 'pharmacy_svc', 'md_drug',
     'drug_code', 'name', 'PHARMACY_DRUG', true, '["INSURANCE_NATIONAL"]'::jsonb,
     '院内药品字典；医保药品编码已有列，本域统一纳管映射与版本', 50),
    ('EXAM_ITEM', '检查检验项目', 'CLINICAL', 'platform-tech', 'tech_svc', 'md_exam_item',
     'item_code', 'name', 'TECH_EXAM_ITEM', true, '[]'::jsonb,
     '检查/检验项目；LIS/PACS 外部编码按需对照', 60),
    ('CONSUMABLE', '耗材', 'MATERIAL', 'platform-material', 'material_svc', 'md_consumable',
     'code', 'name', 'MATERIAL_CONSUMABLE', true, '["INSURANCE_NATIONAL"]'::jsonb,
     '一般/高值/植入耗材；UDI 与医保耗材编码后续批次扩展', 70),
    ('SHIFT', '护理班次', 'OTHER', 'platform-clinical', 'clinical_svc', 'nursing_shift',
     'code', 'name', 'CLINICAL_NURSING_SHIFT', true, '[]'::jsonb,
     '班次时间窗主数据；跨午夜归属口径由临床服务负责', 80),
    ('ORG', '组织架构', 'ORGANIZATION', 'platform-auth', 'auth_svc', 'org',
     'code', 'name', 'AUTH_ORG', true, '[]'::jsonb,
     '医院/科室/病区树；账号与数据范围的组织基础', 90),
    ('CAMPUS', '院区', 'ORGANIZATION', 'platform-auth', 'auth_svc', 'campus',
     'code', 'name', 'AUTH_CAMPUS', true, '[]'::jsonb,
     '多院区隔离边界；与令牌 campuses 声明一致', 100)
ON CONFLICT (domain_code) DO NOTHING;

-- 外部编码体系：只登记"确实存在的对端"，不预置任何院方编码
INSERT INTO mdm_svc.md_external_system (system_code, name, system_type, vendor, status, sort_no)
VALUES
    ('HIS', '院内 HIS', 'HIS', NULL, 'ACTIVE', 10),
    ('LIS', '检验系统 LIS', 'LIS', NULL, 'ACTIVE', 20),
    ('PACS', '影像系统 PACS', 'PACS', NULL, 'ACTIVE', 30),
    ('EMR', '电子病历 EMR', 'EMR', NULL, 'ACTIVE', 40),
    ('INSURANCE_NATIONAL', '国家医保信息平台', 'INSURANCE', '国家医保局', 'ACTIVE', 50),
    ('INSURANCE_PROVINCE', '省医保信息平台', 'INSURANCE', NULL, 'ACTIVE', 60),
    ('REGION_PLATFORM', '区域全民健康信息平台', 'REGION', NULL, 'ACTIVE', 70)
ON CONFLICT (system_code) DO NOTHING;
