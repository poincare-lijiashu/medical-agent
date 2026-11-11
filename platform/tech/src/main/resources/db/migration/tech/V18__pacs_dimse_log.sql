-- ============================================================================
-- V18：PACS DIMSE 操作留痕（第 260 批）
--
-- 背景：DIMSE（C-ECHO/C-FIND/C-STORE/C-MOVE）是直接对 PACS/设备发起的网络操作，
-- 既不产生本地影像记录，也不经过 DICOMweb 的调阅审计。没有独立台账时，
-- "谁在什么时候把哪份影像推到了哪个 AE"无法回答，排障与合规都缺证据。
--
-- 口径：
--   * 只记操作事实与结果，不记录影像内容或患者明文（detail 只放计数/UID 等定位信息）；
--   * 追加式：库级触发器拒绝 UPDATE/DELETE；
--   * 与 pacs_access_log（调阅审计）分开：前者是"看"，后者是"传"。
-- ============================================================================

CREATE TABLE IF NOT EXISTS tech_svc.pacs_dimse_log (
    id          bigserial PRIMARY KEY,
    ts          timestamptz NOT NULL DEFAULT now(),
    actor       text NOT NULL,
    operation   text NOT NULL
                CHECK (operation IN ('ECHO', 'FIND_STUDY', 'FIND_SERIES', 'MOVE', 'STORE')),
    remote_ae   text,
    remote_host text,
    studies     integer,
    instances   integer,
    outcome     text NOT NULL CHECK (outcome IN ('OK', 'FAILED')),
    status_code integer,
    duration_ms integer,
    detail      jsonb
);

CREATE INDEX IF NOT EXISTS ix_pacs_dimse_ts
    ON tech_svc.pacs_dimse_log (ts DESC);
CREATE INDEX IF NOT EXISTS ix_pacs_dimse_operation
    ON tech_svc.pacs_dimse_log (operation, ts DESC);

COMMENT ON TABLE tech_svc.pacs_dimse_log IS
    'PACS DIMSE 操作台账（只允许 INSERT）：谁向哪个 AE 做了什么、结果如何';
COMMENT ON COLUMN tech_svc.pacs_dimse_log.detail IS
    '定位信息（Study/Series/Instance UID、子操作计数），不落影像内容与患者明文';

DROP TRIGGER IF EXISTS trg_pacs_dimse_log_append_only ON tech_svc.pacs_dimse_log;
CREATE TRIGGER trg_pacs_dimse_log_append_only
    BEFORE UPDATE OR DELETE ON tech_svc.pacs_dimse_log
    FOR EACH ROW EXECUTE FUNCTION tech_svc.reject_audit_mutation();
