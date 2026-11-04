-- ============================================================================
-- V4：物资运营日快照（G18）
--
-- 与 clinical V40 / pharmacy V16 同构：(snapshot_date, metric) 唯一，幂等覆盖。
-- ============================================================================

CREATE TABLE IF NOT EXISTS material_svc.report_snapshot (
    id            uuid PRIMARY KEY,
    snapshot_date date NOT NULL,
    metric        text NOT NULL,
    value         numeric(18, 2) NOT NULL,
    detail        jsonb,
    source        text NOT NULL DEFAULT 'MANUAL'
                  CHECK (source IN ('SCHEDULED', 'MANUAL')),
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ux_material_report_snapshot UNIQUE (snapshot_date, metric)
);

CREATE INDEX IF NOT EXISTS ix_material_report_snapshot_date
    ON material_svc.report_snapshot (snapshot_date DESC);

COMMENT ON TABLE material_svc.report_snapshot IS
    '物资日快照：出库单数/件数/金额，按 (日期, 指标) 唯一';
