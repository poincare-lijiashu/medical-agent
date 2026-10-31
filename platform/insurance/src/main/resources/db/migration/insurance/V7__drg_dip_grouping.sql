-- ============================================================================
-- V7：DRG/DIP 支付方案、分组权重与院内模拟预分组（N5 第二批 C）
--
-- 定位说明（必须写清楚，避免被误读为"官方分组器"）：
--   * 省局下发的 DRG 分组规则/权重、DIP 病种分值是权威数据，本平台不内置；
--   * 本迁移提供**院内模拟预分组**所需的配置与留痕：方案（含费率/点值）、
--     分组（权重/分值）、入组规则（诊断/手术前缀，按优先级匹配）；
--   * 分组结果仅用于院内预算测算与病案质控提示，不替代省局结算；
--     每次模拟都固化输入、命中规则、权重、费率与支付标准，可追溯当时口径。
--
-- 数据模型：
--   payment_scheme             方案：DRG（费率）/ DIP（点值），费率变化走 CAS + 审计
--   payment_group              方案下的分组：weight 为 DRG 权重或 DIP 分值
--   payment_group_rule         入组规则：DIAGNOSIS / PROCEDURE / DIAG_AND_PROC，priority 升序首个命中
--   payment_group_simulation   每次模拟的固化快照（含未入组原因）
-- ============================================================================

CREATE TABLE IF NOT EXISTS insurance_svc.payment_scheme (
    code                 text PRIMARY KEY,
    name                 text NOT NULL,
    type                 text NOT NULL CHECK (type IN ('DRG', 'DIP')),
    rate_amount          numeric(12, 4) NOT NULL CHECK (rate_amount >= 0),
    rate_effective_from  date NOT NULL,
    enabled              boolean NOT NULL DEFAULT true,
    version              bigint NOT NULL DEFAULT 0,
    updated_by           text,
    updated_at           timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS insurance_svc.payment_group (
    scheme_code  text NOT NULL REFERENCES insurance_svc.payment_scheme (code),
    group_code   text NOT NULL,
    group_name   text NOT NULL,
    weight       numeric(10, 4) NOT NULL CHECK (weight >= 0),
    enabled      boolean NOT NULL DEFAULT true,
    version      bigint NOT NULL DEFAULT 0,
    updated_at   timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (scheme_code, group_code)
);

CREATE TABLE IF NOT EXISTS insurance_svc.payment_group_rule (
    id           uuid PRIMARY KEY,
    scheme_code  text NOT NULL REFERENCES insurance_svc.payment_scheme (code),
    priority     int NOT NULL CHECK (priority > 0),
    match_type   text NOT NULL CHECK (match_type IN ('DIAGNOSIS', 'PROCEDURE', 'DIAG_AND_PROC')),
    diag_prefix  text,
    proc_prefix  text,
    group_code   text NOT NULL,
    enabled      boolean NOT NULL DEFAULT true,
    created_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_rule_prefix CHECK (
        (match_type = 'DIAGNOSIS' AND diag_prefix IS NOT NULL AND proc_prefix IS NULL)
        OR (match_type = 'PROCEDURE' AND proc_prefix IS NOT NULL AND diag_prefix IS NULL)
        OR (match_type = 'DIAG_AND_PROC' AND diag_prefix IS NOT NULL AND proc_prefix IS NOT NULL)
    ),
    CONSTRAINT ux_rule_priority UNIQUE (scheme_code, priority),
    CONSTRAINT fk_rule_group FOREIGN KEY (scheme_code, group_code)
        REFERENCES insurance_svc.payment_group (scheme_code, group_code)
);

CREATE INDEX IF NOT EXISTS ix_rule_scheme_priority
    ON insurance_svc.payment_group_rule (scheme_code, priority);

CREATE TABLE IF NOT EXISTS insurance_svc.payment_group_simulation (
    id               uuid PRIMARY KEY,
    visit_id         uuid NOT NULL,
    scheme_code      text NOT NULL,
    group_code       text,
    matched          boolean NOT NULL,
    reason           text,
    weight           numeric(10, 4),
    rate_amount      numeric(12, 4) NOT NULL,
    payment_standard numeric(12, 2),
    diagnoses        jsonb NOT NULL DEFAULT '[]'::jsonb,
    procedures       jsonb NOT NULL DEFAULT '[]'::jsonb,
    input_hash       text NOT NULL,
    created_by       text NOT NULL,
    created_at       timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS ix_simulation_visit
    ON insurance_svc.payment_group_simulation (visit_id, created_at DESC);
CREATE INDEX IF NOT EXISTS ix_simulation_scheme
    ON insurance_svc.payment_group_simulation (scheme_code, created_at DESC);
