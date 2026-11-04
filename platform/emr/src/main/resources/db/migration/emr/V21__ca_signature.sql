-- ============================================================================
-- V10：CA 电子签名与无纸化（G41）
--
-- ca_signature：对 record_signature 的 CA 签名/验签留痕
--   * 一条 record_signature 最多一条 CA 签名；
--   * 保存内容哈希、证书序列号、算法、签名值、签名时间；
--   * VALID → REVOKED（撤销必须写原因）；
--   * 每次验签更新 verify_count / last_verified_at / last_verify_result。
-- 签名值由 CA 适配层生成（本地 Mock 或厂商 HTTP 接口），数据库不保存证书私钥。
-- ============================================================================

CREATE TABLE IF NOT EXISTS emr_svc.ca_signature (
    id                 uuid PRIMARY KEY,
    signature_id       uuid NOT NULL REFERENCES emr_svc.record_signature (id),
    record_id          uuid NOT NULL REFERENCES emr_svc.medical_record (id),
    revision           integer NOT NULL,
    signer             text NOT NULL,
    sign_type          text NOT NULL,
    content_hash       text NOT NULL,
    provider           text NOT NULL CHECK (provider IN ('MOCK', 'VENDOR')),
    certificate_serial text NOT NULL,
    algorithm          text NOT NULL,
    signature_value    text NOT NULL,
    signed_at          timestamptz NOT NULL,
    status             text NOT NULL DEFAULT 'VALID' CHECK (status IN ('VALID', 'REVOKED')),
    verify_count       integer NOT NULL DEFAULT 0 CHECK (verify_count >= 0),
    last_verified_at   timestamptz,
    last_verify_result boolean,
    revoke_reason      text,
    revoked_by         text,
    revoked_at         timestamptz,
    created_at         timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ux_ca_signature_signature UNIQUE (signature_id),
    CONSTRAINT ck_ca_signature_revoked
        CHECK (status <> 'REVOKED'
               OR (revoke_reason IS NOT NULL AND revoked_by IS NOT NULL AND revoked_at IS NOT NULL))
);

CREATE INDEX IF NOT EXISTS ix_ca_signature_record
    ON emr_svc.ca_signature (record_id, revision, signed_at DESC);

CREATE INDEX IF NOT EXISTS ix_ca_signature_status
    ON emr_svc.ca_signature (status, signed_at DESC);

COMMENT ON TABLE emr_svc.ca_signature IS
    'CA 电子签名：绑定病历签名行，保存证书序列号/签名值/验签结果，撤销留原因';
