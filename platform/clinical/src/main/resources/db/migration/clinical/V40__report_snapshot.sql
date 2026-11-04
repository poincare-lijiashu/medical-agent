-- ============================================================================
-- V40：运营日快照（G17）
--
-- 每天的报表聚合结果落一份不可重复的快照，供"历史回看/趋势对比/订阅推送"使用，
-- 避免每次打开驾驶舱都去扫描明细表。唯一键 (snapshot_date, metric) 保证：
--   * 定时任务重复执行、手动补采同一天，都只会覆盖同一行；
--   * 多副本同时跑定时任务也不会产生重复快照。
-- ============================================================================

CREATE TABLE IF NOT EXISTS clinical_svc.report_snapshot (
    id            uuid PRIMARY KEY,
    snapshot_date date NOT NULL,
    metric        text NOT NULL,
    value         numeric(18, 2) NOT NULL,
    detail        jsonb,
    source        text NOT NULL DEFAULT 'MANUAL'
                  CHECK (source IN ('SCHEDULED', 'MANUAL')),
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ux_report_snapshot_metric UNIQUE (snapshot_date, metric)
);

CREATE INDEX IF NOT EXISTS ix_report_snapshot_date
    ON clinical_svc.report_snapshot (snapshot_date DESC);

COMMENT ON TABLE clinical_svc.report_snapshot IS
    '运营日快照：按 (日期, 指标) 唯一，定时任务与手动补采均幂等覆盖';
COMMENT ON COLUMN clinical_svc.report_snapshot.metric IS
    '指标键：OUTPATIENT_* / INPATIENT_* / BED_*';
COMMENT ON COLUMN clinical_svc.report_snapshot.detail IS
    '指标上下文（如床位使用率的可用床位/在院人数）';
