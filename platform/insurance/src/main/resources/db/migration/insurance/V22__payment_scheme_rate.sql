-- ============================================================================
-- 保险域 V22：支付方案费率/点值的生效期版本（G84）
--
-- 背景：V7 的 payment_scheme 只有"当前费率 + 生效日"一列，改费率即覆盖历史。
-- 省局费率/点值通常按年度或调整日下发，补结算、复核、追溯都需要回答
-- "该出院日期当时适用多少费率"——一列当前值答不了这个问题。
--
-- 模型：payment_scheme_rate 每个 (方案, 生效日) 一行，按出院/结算日期取
-- effective_from <= 该日 的最近一条；模拟分组把命中的费率一并固化到快照里。
--
-- 兼容与回填：
--   * 存量方案当前费率作为一条生效记录回填（created_by 取最后修改人）；
--   * payment_scheme.rate_amount / rate_effective_from 保留为"当前费率"，
--     由服务层在新增/修改费率时同步（同一生效日即为对该日费率的更正）；
--   * 唯一键 (scheme_code, effective_from) 保证同一生效日只有一档费率。
-- ============================================================================

CREATE TABLE IF NOT EXISTS insurance_svc.payment_scheme_rate (
    scheme_code     text NOT NULL REFERENCES insurance_svc.payment_scheme (code),
    effective_from  date NOT NULL,
    rate_amount     numeric(12, 4) NOT NULL CHECK (rate_amount >= 0),
    created_by      text NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (scheme_code, effective_from)
);

CREATE INDEX IF NOT EXISTS ix_scheme_rate_effective
    ON insurance_svc.payment_scheme_rate (scheme_code, effective_from DESC);

COMMENT ON TABLE insurance_svc.payment_scheme_rate IS
    '支付方案费率生效期：按日期取当时费率，支持补结算与追溯（G84）';

INSERT INTO insurance_svc.payment_scheme_rate
    (scheme_code, effective_from, rate_amount, created_by)
SELECT code, rate_effective_from, rate_amount, COALESCE(updated_by, 'system')
  FROM insurance_svc.payment_scheme
ON CONFLICT DO NOTHING;
