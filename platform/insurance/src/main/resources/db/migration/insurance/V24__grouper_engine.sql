-- ============================================================================
-- V24：DRG/DIP 2.0 可配置分组引擎（第 263 批）
--
-- 定位（与 V7 的"模拟预分组"区分）：
--   * V7 是"ICD 前缀 + 优先级"的院内测算，不能表达 MDC→ADRG→DRG 层级与 CC/MCC；
--   * 本迁移提供**可配置分组引擎**的规则载体：规则包（版本化）、MDC 归并、
--     ADRG 匹配、DRG 细分（含 CC/MCC）、DIP 病种目录；
--   * 国家/省局规则与病种目录由院方导入，平台不内置权威全量规则；
--     未导入规则包时引擎显式失败，不做"猜一个分组"。
--
-- 版本与追溯：
--   * 规则包一旦 ACTIVE 不允许再导入同名版本（同 code 只允许一个 ACTIVE）；
--   * 每次分组固化规则包 code/version、输入哈希与命中轨迹（trace），
--     可回答"当时用的是哪版规则、为什么入这个组"；
--   * grouper_result 追加式：库级触发器拒绝 UPDATE/DELETE。
-- ============================================================================

CREATE TABLE IF NOT EXISTS insurance_svc.grouper_rule_set (
    id             uuid PRIMARY KEY,
    code           text NOT NULL,
    version        text NOT NULL,
    name           text NOT NULL,
    type           text NOT NULL CHECK (type IN ('DRG', 'DIP')),
    effective_from date NOT NULL,
    source         text NOT NULL CHECK (source IN ('NATIONAL', 'PROVINCIAL', 'LOCAL', 'VENDOR')),
    checksum       text NOT NULL,
    status         text NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'ACTIVE', 'RETIRED')),
    imported_by    text NOT NULL,
    imported_at    timestamptz NOT NULL DEFAULT now(),
    activated_by   text,
    activated_at   timestamptz,
    CONSTRAINT ux_grouper_rule_set UNIQUE (code, version)
);

-- 同一规则包 code 只允许一个生效版本：避免"两版规则同时在跑"的口径分叉
CREATE UNIQUE INDEX IF NOT EXISTS ux_grouper_rule_set_active
    ON insurance_svc.grouper_rule_set (code) WHERE status = 'ACTIVE';

CREATE INDEX IF NOT EXISTS ix_grouper_rule_set_type
    ON insurance_svc.grouper_rule_set (type, status, effective_from DESC);

COMMENT ON TABLE insurance_svc.grouper_rule_set IS
    'DRG/DIP 分组规则包（版本化）：规则内容由院方导入，ACTIVE 版本参与分组';

-- ---------------------------------------------------------------- MDC 归并

CREATE TABLE IF NOT EXISTS insurance_svc.grouper_mdc_rule (
    id          uuid PRIMARY KEY,
    rule_set_id uuid NOT NULL REFERENCES insurance_svc.grouper_rule_set (id) ON DELETE CASCADE,
    priority    int NOT NULL CHECK (priority > 0),
    mdc_code    text NOT NULL,
    mdc_name    text NOT NULL,
    diag_from   text NOT NULL,
    diag_to     text NOT NULL,
    enabled     boolean NOT NULL DEFAULT true,
    CONSTRAINT ux_grouper_mdc_priority UNIQUE (rule_set_id, priority)
);

CREATE INDEX IF NOT EXISTS ix_grouper_mdc_set
    ON insurance_svc.grouper_mdc_rule (rule_set_id, priority);

-- ---------------------------------------------------------------- ADRG 匹配

CREATE TABLE IF NOT EXISTS insurance_svc.grouper_adrg_rule (
    id          uuid PRIMARY KEY,
    rule_set_id uuid NOT NULL REFERENCES insurance_svc.grouper_rule_set (id) ON DELETE CASCADE,
    priority    int NOT NULL CHECK (priority > 0),
    adrg_code   text NOT NULL,
    adrg_name   text NOT NULL,
    mdc_code    text NOT NULL,
    match_type  text NOT NULL CHECK (match_type IN ('PROCEDURE', 'DIAGNOSIS', 'DIAG_AND_PROC')),
    diag_prefix text,
    proc_prefix text,
    enabled     boolean NOT NULL DEFAULT true,
    CONSTRAINT ux_grouper_adrg_priority UNIQUE (rule_set_id, priority),
    CONSTRAINT ck_grouper_adrg_shape CHECK (
        (match_type = 'PROCEDURE' AND proc_prefix IS NOT NULL AND diag_prefix IS NULL)
        OR (match_type = 'DIAGNOSIS' AND diag_prefix IS NOT NULL AND proc_prefix IS NULL)
        OR (match_type = 'DIAG_AND_PROC' AND diag_prefix IS NOT NULL AND proc_prefix IS NOT NULL)
    )
);

CREATE INDEX IF NOT EXISTS ix_grouper_adrg_set
    ON insurance_svc.grouper_adrg_rule (rule_set_id, mdc_code, priority);

-- ---------------------------------------------------------------- DRG 细分组

CREATE TABLE IF NOT EXISTS insurance_svc.grouper_drg_def (
    rule_set_id uuid NOT NULL REFERENCES insurance_svc.grouper_rule_set (id) ON DELETE CASCADE,
    drg_code    text NOT NULL,
    drg_name    text NOT NULL,
    adrg_code   text NOT NULL,
    severity    text NOT NULL CHECK (severity IN ('NONE', 'CC', 'MCC')),
    weight      numeric(10, 4) NOT NULL CHECK (weight >= 0),
    PRIMARY KEY (rule_set_id, drg_code)
);

CREATE INDEX IF NOT EXISTS ix_grouper_drg_adrg
    ON insurance_svc.grouper_drg_def (rule_set_id, adrg_code, severity);

-- ---------------------------------------------------------------- CC / MCC

CREATE TABLE IF NOT EXISTS insurance_svc.grouper_cc_rule (
    id          uuid PRIMARY KEY,
    rule_set_id uuid NOT NULL REFERENCES insurance_svc.grouper_rule_set (id) ON DELETE CASCADE,
    severity    text NOT NULL CHECK (severity IN ('CC', 'MCC')),
    diag_prefix text NOT NULL,
    CONSTRAINT ux_grouper_cc UNIQUE (rule_set_id, severity, diag_prefix)
);

-- 主诊断 A 时，次要诊断 B 不计入 CC/MCC（排除表由规则包导入）
CREATE TABLE IF NOT EXISTS insurance_svc.grouper_cc_exclusion (
    id                     uuid PRIMARY KEY,
    rule_set_id            uuid NOT NULL REFERENCES insurance_svc.grouper_rule_set (id) ON DELETE CASCADE,
    primary_diag_prefix    text NOT NULL,
    secondary_diag_prefix  text NOT NULL,
    CONSTRAINT ux_grouper_cc_exclusion
        UNIQUE (rule_set_id, primary_diag_prefix, secondary_diag_prefix)
);

-- ---------------------------------------------------------------- DIP 病种目录

CREATE TABLE IF NOT EXISTS insurance_svc.grouper_dip_catalog (
    id          uuid PRIMARY KEY,
    rule_set_id uuid NOT NULL REFERENCES insurance_svc.grouper_rule_set (id) ON DELETE CASCADE,
    dip_code    text NOT NULL,
    dip_name    text NOT NULL,
    diag_code   text NOT NULL,
    proc_code   text,
    type        text NOT NULL CHECK (type IN ('CORE', 'COMPOSITE')),
    score       numeric(10, 4) NOT NULL CHECK (score >= 0)
);

-- proc_code 可为空：NULL 在唯一约束里互不相等，用 COALESCE 保证"同病种无手术"只一条
CREATE UNIQUE INDEX IF NOT EXISTS ux_grouper_dip_catalog
    ON insurance_svc.grouper_dip_catalog (rule_set_id, diag_code, COALESCE(proc_code, '-'));

CREATE INDEX IF NOT EXISTS ix_grouper_dip_diag
    ON insurance_svc.grouper_dip_catalog (rule_set_id, diag_code);

-- ---------------------------------------------------------------- 分组结果

CREATE TABLE IF NOT EXISTS insurance_svc.grouper_result (
    id                   uuid PRIMARY KEY,
    visit_id             uuid NOT NULL,
    rule_set_id          uuid REFERENCES insurance_svc.grouper_rule_set (id),
    rule_set_code        text,
    rule_set_version     text,
    engine               text NOT NULL CHECK (engine IN ('LOCAL', 'REMOTE', 'OFFICIAL')),
    type                 text NOT NULL CHECK (type IN ('DRG', 'DIP')),
    mdc_code             text,
    adrg_code            text,
    drg_code             text,
    dip_code             text,
    severity             text CHECK (severity IN ('NONE', 'CC', 'MCC')),
    weight               numeric(10, 4),
    score                numeric(10, 4),
    payment_standard     numeric(12, 2),
    matched              boolean NOT NULL,
    reason               text,
    trace                jsonb NOT NULL DEFAULT '[]'::jsonb,
    principal_diagnosis  text NOT NULL,
    secondary_diagnoses  jsonb NOT NULL DEFAULT '[]'::jsonb,
    procedures           jsonb NOT NULL DEFAULT '[]'::jsonb,
    input_hash           text NOT NULL,
    created_by           text NOT NULL,
    created_at           timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS ix_grouper_result_visit
    ON insurance_svc.grouper_result (visit_id, created_at DESC);

CREATE INDEX IF NOT EXISTS ix_grouper_result_engine
    ON insurance_svc.grouper_result (visit_id, engine, created_at DESC);

COMMENT ON TABLE insurance_svc.grouper_result IS
    'DRG/DIP 分组结果快照（LOCAL 预分组 / REMOTE 远端分组 / OFFICIAL 省局正式结果），只允许 INSERT';

CREATE OR REPLACE FUNCTION insurance_svc.reject_grouper_result_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION '% 是分组结果证据表，禁止 %', TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'insufficient_privilege';
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_grouper_result_append_only ON insurance_svc.grouper_result;
CREATE TRIGGER trg_grouper_result_append_only
    BEFORE UPDATE OR DELETE ON insurance_svc.grouper_result
    FOR EACH ROW EXECUTE FUNCTION insurance_svc.reject_grouper_result_mutation();
