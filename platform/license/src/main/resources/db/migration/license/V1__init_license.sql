-- ============================================================================
-- 授权与许可（license_svc）——第 279 批（计划书 W9.2）
--
-- 为什么授权要单独成域：
--   * 授权书是**厂商对医院的承诺**（版本/模块/配额/期限），不是业务数据；
--     放在业务库里，任何有业务写权限的人都能改掉自己的授权；
--   * 离线医院是常态：授权必须能在无外网环境下自证，不能依赖在线激活服务；
--   * 承诺一旦生效就要可审计：谁导入、谁吊销、什么时候到期，必须留痕且不可改。
--
-- 关键设计：
--   * 授权书 = 规范化 JSON + Ed25519 签名。payload 存原文与 SHA-256，
--     导入时按"重新序列化后的规范化字节"验签，避免 JSON 键序/空白造成误判；
--   * (instance_code) WHERE status='ACTIVE' 唯一：一个部署实例同时只有一份生效授权，
--     新授权导入时旧授权转 SUPERSEDED，历史版本可回查；
--   * 到期不删数据：状态由 not_before/expires_at/grace_days 现算（ACTIVE / GRACE / EXPIRED），
--     吊销是显式动作（REVOKED），与"过期"是两件事；
--   * 审计表与用量表追加式，数据库触发器拒绝 UPDATE/DELETE。
-- ============================================================================

CREATE SCHEMA IF NOT EXISTS license_svc;

-- ---------------------------------------------------------------------------
-- 1. 产品版本矩阵（Edition）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS license_svc.lic_edition (
    code                 text PRIMARY KEY,
    name                 text NOT NULL,
    description          text,
    modules              text[] NOT NULL DEFAULT '{}',
    max_beds             integer,
    max_users            integer,
    max_ai_calls_per_day integer,
    sort_no              integer NOT NULL DEFAULT 100,
    status               text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at           timestamptz NOT NULL DEFAULT now(),
    updated_at           timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE license_svc.lic_edition IS
    '产品版本矩阵：模块清单与配额上限是"版本能做什么"的唯一口径，报价按此组合';
COMMENT ON COLUMN license_svc.lic_edition.modules IS
    '该版本默认包含的模块码，与 lic_license.modules 同一套编码';

INSERT INTO license_svc.lic_edition
    (code, name, description, modules, max_beds, max_users, max_ai_calls_per_day, sort_no)
VALUES
    ('BASIC', '基础版', '门急诊与住院基本闭环，不含 AI 与开放平台',
     ARRAY['clinical','pharmacy','emr','mdm'], 200, 150, 0, 10),
    ('STANDARD', '标准版', '在基础版之上增加医技、医保、物资与知识库',
     ARRAY['clinical','pharmacy','emr','mdm','tech','insurance','material','kb'], 500, 500, 2000, 20),
    ('ENTERPRISE', '全院版', '全院全模块，含 AI 中台、FHIR 开放层、运维与发布治理',
     ARRAY['clinical','pharmacy','emr','mdm','tech','insurance','material','kb','ai','fhir','ops','release','integration','migration'],
     2000, 5000, 20000, 30),
    ('SAAS', '云订阅版', '按订阅周期授权，模块可拆分加购；配额以院区为单位统计',
     ARRAY['clinical','pharmacy','emr','mdm','tech','kb','ai'], 300, 300, 5000, 40)
ON CONFLICT (code) DO NOTHING;

-- ---------------------------------------------------------------------------
-- 2. 授权书
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS license_svc.lic_license (
    id                   uuid PRIMARY KEY,
    license_no           text NOT NULL UNIQUE,
    instance_code        text NOT NULL,
    hospital_name        text NOT NULL,
    edition_code         text NOT NULL,
    modules              text[] NOT NULL DEFAULT '{}',
    max_beds             integer,
    max_users            integer,
    max_ai_calls_per_day integer,
    not_before           timestamptz NOT NULL,
    expires_at           timestamptz NOT NULL,
    grace_days           integer NOT NULL DEFAULT 30
                         CHECK (grace_days BETWEEN 0 AND 365),
    status               text NOT NULL DEFAULT 'ACTIVE'
                         CHECK (status IN ('ACTIVE', 'SUPERSEDED', 'REVOKED')),
    payload              text NOT NULL,
    payload_hash         text NOT NULL,
    signature            text NOT NULL,
    key_id               text NOT NULL,
    imported_by          text NOT NULL,
    imported_at          timestamptz NOT NULL DEFAULT now(),
    issued_at            timestamptz NOT NULL,
    revoked_by           text,
    revoked_at           timestamptz,
    revoke_reason        text
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_lic_active_instance
    ON license_svc.lic_license (instance_code) WHERE status = 'ACTIVE';
CREATE INDEX IF NOT EXISTS ix_lic_status_expiry
    ON license_svc.lic_license (status, expires_at DESC);
COMMENT ON TABLE license_svc.lic_license IS
    '授权书：payload 是厂商签名的原文，signature 为 Ed25519 签名；旧授权 SUPERSEDED 保留可回查';
COMMENT ON COLUMN license_svc.lic_license.payload_hash IS
    '规范化 payload 的 SHA-256：验签失败的排查锚点，也是"授权内容未变更"的证明';

-- ---------------------------------------------------------------------------
-- 3. 配额用量（按日快照）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS license_svc.lic_usage (
    instance_code text NOT NULL,
    usage_date    date NOT NULL,
    active_beds   integer NOT NULL DEFAULT 0 CHECK (active_beds >= 0),
    active_users  integer NOT NULL DEFAULT 0 CHECK (active_users >= 0),
    ai_calls      integer NOT NULL DEFAULT 0 CHECK (ai_calls >= 0),
    source        text NOT NULL DEFAULT 'MANUAL',
    captured_at   timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (instance_code, usage_date)
);
COMMENT ON TABLE license_svc.lic_usage IS
    '配额用量日快照：超限只告警不阻断（临床连续性优先），由运营人工介入';

-- ---------------------------------------------------------------------------
-- 4. 变更审计（追加式）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS license_svc.lic_change_log (
    id        bigserial PRIMARY KEY,
    ts        timestamptz NOT NULL DEFAULT now(),
    actor     text NOT NULL,
    action    text NOT NULL,
    target    text,
    detail    jsonb NOT NULL DEFAULT '{}'::jsonb,
    trace_id  text
);
CREATE INDEX IF NOT EXISTS ix_lic_change_ts ON license_svc.lic_change_log (ts DESC);

CREATE OR REPLACE FUNCTION license_svc.reject_audit_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION '% 是追加式审计表，禁止 %', TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'insufficient_privilege';
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_lic_change_log_append_only ON license_svc.lic_change_log;
CREATE TRIGGER trg_lic_change_log_append_only
    BEFORE UPDATE OR DELETE ON license_svc.lic_change_log
    FOR EACH ROW EXECUTE FUNCTION license_svc.reject_audit_mutation();
