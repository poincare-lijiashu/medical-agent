-- ============================================================================
-- V27：CA 签名的可信时间戳证据（第 262 批）
--
-- 背景：ca_signature.signed_at 只是本机时间，时钟可回拨；WS/T 847—2024 与
-- 电子病历规范要求关键文书具备**可信时间源**。RFC 3161 时间戳令牌才是
-- "某时刻该签名值已存在"的第三方证据。
--
-- 口径：
--   * 一条 CA 签名最多一条时间戳令牌；
--   * token 保存令牌原文（Base64），gen_time 为 TSA 签发时间；
--   * 追加式：令牌是证据，不允许改删；撤销签名不删除时间戳。
-- ============================================================================

CREATE TABLE IF NOT EXISTS emr_svc.ca_timestamp (
    -- 不用 ON DELETE CASCADE：时间戳是第三方证据，父行被删时必须报外键错误，
    -- 而不是"顺手把证据一起删掉"（ca_signature 本身允许改验签计数与撤销）。
    ca_signature_id uuid PRIMARY KEY REFERENCES emr_svc.ca_signature (id),
    tsa_url         text NOT NULL,
    token           text NOT NULL,
    gen_time        timestamptz NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now()
);

COMMENT ON TABLE emr_svc.ca_timestamp IS
    'CA 签名的 RFC 3161 时间戳令牌（第三方可信时间证据，只允许 INSERT）';
COMMENT ON COLUMN emr_svc.ca_timestamp.token IS
    '时间戳令牌原文（Base64）；不要只存 gen_time，否则无法离线复核';

CREATE OR REPLACE FUNCTION emr_svc.reject_timestamp_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION '% 是时间戳证据表，禁止 %', TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'insufficient_privilege';
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_ca_timestamp_append_only ON emr_svc.ca_timestamp;
CREATE TRIGGER trg_ca_timestamp_append_only
    BEFORE UPDATE OR DELETE ON emr_svc.ca_timestamp
    FOR EACH ROW EXECUTE FUNCTION emr_svc.reject_timestamp_mutation();
