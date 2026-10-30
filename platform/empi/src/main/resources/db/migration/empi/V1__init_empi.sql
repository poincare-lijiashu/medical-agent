-- ============================================================================
-- 患者主索引（EMPI）初始表结构
-- 关键设计（计划书 D2.2 / D7.4）：
--   * 敏感字段（证件号/手机号/住址）加密存储（AES-GCM，密钥经环境变量注入；
--     生产切换国密 SM4，接口不变）
--   * 查重走哈希列（SHA-256，不可逆），明文永不落库明文索引
--   * 合并采用「标记 + 指针」：源档案 status=MERGED、merged_into 指向主档案，
--     保留原始记录与跨系统标识，满足病历追溯与审计要求
-- ============================================================================

CREATE SCHEMA IF NOT EXISTS empi_svc;

CREATE SEQUENCE IF NOT EXISTS empi_svc.patient_no_seq START 1;

CREATE TABLE IF NOT EXISTS empi_svc.patient (
    id          uuid PRIMARY KEY,
    patient_no  text NOT NULL UNIQUE,              -- 院内可读患者号 PYYYYMMDD######
    name        text NOT NULL,
    gender      text NOT NULL DEFAULT 'UNKNOWN'
                CHECK (gender IN ('MALE', 'FEMALE', 'UNKNOWN')),
    birth_date  date,
    id_type     text,                              -- ID_CARD/PASSPORT/MILITARY/HK_MACAO/NONE
    id_no_enc   text,                              -- 证件号密文（Base64(iv+ciphertext+GCM tag)）
    id_no_hash  text,                              -- SHA-256(id_type|id_no) 十六进制，仅用于查重
    phone_enc   text,
    phone_hash  text,
    address_enc text,
    blood_type  text,
    allergy     text,
    status      text NOT NULL DEFAULT 'ACTIVE'
                CHECK (status IN ('ACTIVE', 'MERGED', 'DECEASED')),
    merged_into uuid REFERENCES empi_svc.patient (id),
    version     bigint NOT NULL DEFAULT 0,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_merged_pointer CHECK (
        (status = 'MERGED' AND merged_into IS NOT NULL)
        OR (status <> 'MERGED')
    )
);
-- 证件号查重：仅在有效档案间唯一（已合并档案可保留原证件号）
CREATE UNIQUE INDEX IF NOT EXISTS ux_patient_idno
    ON empi_svc.patient (id_type, id_no_hash)
    WHERE id_no_hash IS NOT NULL AND status <> 'MERGED';
CREATE INDEX IF NOT EXISTS ix_patient_phone_hash ON empi_svc.patient (phone_hash);
CREATE INDEX IF NOT EXISTS ix_patient_name ON empi_svc.patient (name);
CREATE INDEX IF NOT EXISTS ix_patient_status ON empi_svc.patient (status);
COMMENT ON TABLE empi_svc.patient IS '患者主索引（敏感字段加密，查重走哈希）';
COMMENT ON COLUMN empi_svc.patient.version IS '乐观锁版本号';

-- ---- 跨系统标识映射（HIS/LIS/PACS/医保/区域平台）----
CREATE TABLE IF NOT EXISTS empi_svc.patient_crosswalk (
    patient_id  uuid NOT NULL REFERENCES empi_svc.patient (id) ON DELETE CASCADE,
    system_code text NOT NULL,                     -- HIS / LIS / PACS / INSURANCE / REGIONAL
    external_id text NOT NULL,                     -- 外部系统标识
    created_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (system_code, external_id)
);
CREATE INDEX IF NOT EXISTS ix_crosswalk_patient ON empi_svc.patient_crosswalk (patient_id);
COMMENT ON TABLE empi_svc.patient_crosswalk IS '患者跨系统标识映射（(系统,外部ID) 唯一）';

-- ---- 合并操作留痕（不可删改；病历追溯必需）----
CREATE TABLE IF NOT EXISTS empi_svc.patient_merge_log (
    id         bigserial PRIMARY KEY,
    ts         timestamptz NOT NULL DEFAULT now(),
    from_id    uuid NOT NULL,
    to_id      uuid NOT NULL,
    operator   text NOT NULL,
    reason     text NOT NULL,
    moved_crosswalk int NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS ix_merge_from ON empi_svc.patient_merge_log (from_id);
CREATE INDEX IF NOT EXISTS ix_merge_to ON empi_svc.patient_merge_log (to_id);
COMMENT ON TABLE empi_svc.patient_merge_log IS '患者合并操作留痕（谁把哪条并到哪条、原因、迁移标识数）';

-- ---- 变更审计 ----
CREATE TABLE IF NOT EXISTS empi_svc.change_log (
    id          bigserial PRIMARY KEY,
    ts          timestamptz NOT NULL DEFAULT now(),
    actor       text NOT NULL,
    action      text NOT NULL,
    target_type text NOT NULL,
    target_id   text NOT NULL,
    detail      jsonb
);
CREATE INDEX IF NOT EXISTS ix_empi_change_target ON empi_svc.change_log (target_type, target_id);
