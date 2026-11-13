-- ============================================================================
-- V6：SLO 与错误预算（第 300 批 / 计划书 G6）
--
-- 目标：把"可靠性"从口号变成可计算的承诺：
--   * SLO 定义（服务 + 指标 + 目标 + 窗口 + 延迟阈值 + 负责人）；
--   * 快照上报（monitoring/exporter 或人工导入 total/good 计数），幂等键去重；
--   * 查询时按滚动窗口聚合，计算达标率、错误预算、剩余比例与燃烧率；
--   * 状态：HEALTHY / AT_RISK（剩余预算 <25%）/ BREACHED（低于目标）/ NO_DATA。
--
-- 为什么不在查询时直接打 Prometheus：SLO 页面必须在监控不可达时仍可解释历史；
-- 快照是"已结算的事实"，Prometheus 只是采集源之一。
-- ============================================================================

CREATE TABLE IF NOT EXISTS ops_svc.ops_slo (
    id                   uuid PRIMARY KEY,
    code                 text NOT NULL UNIQUE,
    name                 text NOT NULL,
    service_code         text NOT NULL REFERENCES ops_svc.ops_service_catalog (service_code),
    indicator            text NOT NULL
                         CHECK (indicator IN ('AVAILABILITY', 'LATENCY', 'ERROR_RATE')),
    target_percent       numeric(6, 3) NOT NULL
                         CHECK (target_percent > 0 AND target_percent < 100),
    window_days          integer NOT NULL DEFAULT 30
                         CHECK (window_days BETWEEN 1 AND 90),
    latency_threshold_ms integer CHECK (latency_threshold_ms IS NULL OR latency_threshold_ms > 0),
    owner                text NOT NULL,
    description          text,
    status               text NOT NULL DEFAULT 'ACTIVE'
                         CHECK (status IN ('ACTIVE', 'INACTIVE')),
    version              bigint NOT NULL DEFAULT 0,
    created_by           text NOT NULL,
    created_at           timestamptz NOT NULL DEFAULT now(),
    updated_at           timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_ops_slo_latency CHECK (
        indicator <> 'LATENCY' OR latency_threshold_ms IS NOT NULL
    )
);
CREATE INDEX IF NOT EXISTS ix_ops_slo_service ON ops_svc.ops_slo (service_code, status);
COMMENT ON TABLE ops_svc.ops_slo IS
    'SLO 定义：目标百分比与窗口；LATENCY 必须有延迟阈值（好事件=低于阈值）';

CREATE TABLE IF NOT EXISTS ops_svc.ops_slo_snapshot (
    id              uuid PRIMARY KEY,
    slo_id          uuid NOT NULL REFERENCES ops_svc.ops_slo (id) ON DELETE CASCADE,
    window_start    timestamptz NOT NULL,
    window_end      timestamptz NOT NULL,
    total_events    bigint NOT NULL CHECK (total_events >= 0),
    good_events     bigint NOT NULL CHECK (good_events >= 0),
    source          text NOT NULL DEFAULT 'MANUAL'
                    CHECK (source IN ('MANUAL', 'EXPORTER', 'SYNTHETIC')),
    idempotency_key text NOT NULL UNIQUE,
    note            text,
    reported_by     text NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_ops_slo_snapshot_window CHECK (window_end > window_start),
    CONSTRAINT ck_ops_slo_snapshot_events CHECK (good_events <= total_events)
);
CREATE INDEX IF NOT EXISTS ix_ops_slo_snapshot_window
    ON ops_svc.ops_slo_snapshot (slo_id, window_end DESC);
COMMENT ON TABLE ops_svc.ops_slo_snapshot IS
    'SLO 快照：窗口内的 total/good 计数；幂等键唯一，重试不重复记账';

-- ---------------------------------------------------------------------------
-- 审计动作扩展
-- ---------------------------------------------------------------------------
ALTER TABLE ops_svc.ops_change_log DROP CONSTRAINT IF EXISTS ops_change_log_action_check;
ALTER TABLE ops_svc.ops_change_log ADD CONSTRAINT ops_change_log_action_check CHECK (
    action IN ('TICKET_CREATE', 'TICKET_TRANSITION', 'TICKET_ASSIGN', 'TICKET_COMMENT',
               'TICKET_PRIORITY', 'SLA_SCAN', 'POLICY_UPSERT', 'CALENDAR_UPSERT',
               'SERVICE_UPSERT', 'ROTATION_UPSERT', 'SHIFT_GENERATE', 'SHIFT_OVERRIDE',
               'INCIDENT_CREATE', 'INCIDENT_STATUS', 'INCIDENT_COMMANDER', 'INCIDENT_COMMENT',
               'POSTMORTEM_DRAFT', 'POSTMORTEM_SUBMIT', 'POSTMORTEM_APPROVE',
               'INTEGRATION_SOURCE_CREATE', 'INTEGRATION_SOURCE_ROTATE',
               'INTEGRATION_SOURCE_DISABLE',
               'BACKUP_POLICY_UPSERT', 'BACKUP_RUN_REPORT',
               'DR_DRILL_CREATE', 'DR_DRILL_START', 'DR_DRILL_STEP',
               'DR_DRILL_PASS', 'DR_DRILL_FAIL', 'DR_DRILL_ABORT',
               'IMPL_TEMPLATE_UPSERT', 'IMPL_PROJECT_CREATE', 'IMPL_PROJECT_START',
               'IMPL_TASK_UPDATE', 'IMPL_TASK_WAIVE', 'IMPL_PROJECT_GO_LIVE',
               'IMPL_PROJECT_ACCEPT', 'IMPL_PROJECT_CANCEL',
               'IMPL_COURSE_UPSERT', 'IMPL_EXAM_CREATE', 'IMPL_EXAM_CLOSE',
               'IMPL_ATTEMPT_RECORD',
               'SLO_UPSERT', 'SLO_STATUS', 'SLO_SNAPSHOT')
);
