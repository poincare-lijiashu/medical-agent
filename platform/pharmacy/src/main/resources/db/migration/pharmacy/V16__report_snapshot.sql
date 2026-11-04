-- ============================================================================
-- V16：药事运营日快照（G18）
--
-- 与 clinical V40 同构：(snapshot_date, metric) 唯一，定时任务/手动补采均幂等覆盖。
-- ============================================================================

CREATE TABLE IF NOT EXISTS pharmacy_svc.report_snapshot (
    id            uuid PRIMARY KEY,
    snapshot_date date NOT NULL,
    metric        text NOT NULL,
    value         numeric(18, 2) NOT NULL,
    detail        jsonb,
    source        text NOT NULL DEFAULT 'MANUAL'
                  CHECK (source IN ('SCHEDULED', 'MANUAL')),
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ux_pharmacy_report_snapshot UNIQUE (snapshot_date, metric)
);

CREATE INDEX IF NOT EXISTS ix_pharmacy_report_snapshot_date
    ON pharmacy_svc.report_snapshot (snapshot_date DESC);

COMMENT ON TABLE pharmacy_svc.report_snapshot IS
    '药事日快照：发药笔数/金额，按 (日期, 指标) 唯一';
