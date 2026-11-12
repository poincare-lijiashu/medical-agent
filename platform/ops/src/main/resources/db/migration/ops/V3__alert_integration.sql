-- ============================================================================
-- V3：告警接入源与工单自动化（第 276 批）
--
-- 目标：监控平台（Alertmanager）的告警不再靠人手工转单，而是：
--   1) 接入源令牌鉴权（令牌只存 SHA-256，明文只在创建/轮换时返回一次）；
--   2) 按 fingerprint 去重：重复 firing 只在工单上追加说明，不重复建单；
--   3) severity 映射优先级（可配置 IGNORE 表示只留痕不建单）；
--   4) resolved 自动接单并解决工单（可关闭 auto_resolve）。
--
-- 为什么单独建 ops_alert_state：Alertmanager 每次通知都是全量快照，
-- 只靠工单状态无法回答"这条告警当前是否仍在 firing"；状态表让去重与自动恢复有依据。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. 告警接入源
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ops_svc.ops_alert_source (
    id                     uuid PRIMARY KEY,
    code                   text NOT NULL UNIQUE,
    name                   text NOT NULL,
    kind                   text NOT NULL DEFAULT 'ALERTMANAGER'
                           CHECK (kind IN ('ALERTMANAGER', 'GENERIC')),
    token_hash             text NOT NULL,
    token_hint             text NOT NULL,
    default_service        text REFERENCES ops_svc.ops_service_catalog (service_code),
    severity_map           jsonb NOT NULL
                           DEFAULT '{"critical":"P1","warning":"P2","info":"P3"}'::jsonb,
    auto_resolve           boolean NOT NULL DEFAULT true,
    repeat_comment_minutes integer NOT NULL DEFAULT 60
                           CHECK (repeat_comment_minutes BETWEEN 0 AND 1440),
    status                 text NOT NULL DEFAULT 'ACTIVE'
                           CHECK (status IN ('ACTIVE', 'INACTIVE')),
    last_used_at           timestamptz,
    created_by             text NOT NULL,
    created_at             timestamptz NOT NULL DEFAULT now(),
    updated_at             timestamptz NOT NULL DEFAULT now(),
    version                bigint NOT NULL DEFAULT 0
);
COMMENT ON TABLE ops_svc.ops_alert_source IS
    '告警接入源：令牌只存 SHA-256；severity_map 可把某级别映射为 IGNORE（只留痕不建单）';
COMMENT ON COLUMN ops_svc.ops_alert_source.token_hash IS
    '接入令牌的 SHA-256 十六进制；明文不落库，创建/轮换时仅返回一次';

-- ---------------------------------------------------------------------------
-- 2. 告警当前状态（按 source + fingerprint 唯一）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ops_svc.ops_alert_state (
    source_code  text NOT NULL REFERENCES ops_svc.ops_alert_source (code) ON DELETE CASCADE,
    fingerprint  text NOT NULL,
    status       text NOT NULL CHECK (status IN ('FIRING', 'RESOLVED', 'IGNORED')),
    alertname    text,
    severity     text,
    priority     text,
    service_code text,
    title        text,
    ticket_id    uuid REFERENCES ops_svc.ops_ticket (id),
    starts_at    timestamptz,
    last_seen_at timestamptz NOT NULL DEFAULT now(),
    resolved_at  timestamptz,
    labels       jsonb NOT NULL DEFAULT '{}'::jsonb,
    annotations  jsonb NOT NULL DEFAULT '{}'::jsonb,
    version      bigint NOT NULL DEFAULT 0,
    PRIMARY KEY (source_code, fingerprint)
);
CREATE INDEX IF NOT EXISTS ix_ops_alert_state_ticket
    ON ops_svc.ops_alert_state (ticket_id);
CREATE INDEX IF NOT EXISTS ix_ops_alert_state_firing
    ON ops_svc.ops_alert_state (status, last_seen_at DESC);
COMMENT ON TABLE ops_svc.ops_alert_state IS
    '告警当前状态：firing 重复通知只更新 last_seen_at，resolved 后按 auto_resolve 处理工单';

-- ---------------------------------------------------------------------------
-- 3. 入站事件留痕（追加式）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ops_svc.ops_hook_event (
    id          bigserial PRIMARY KEY,
    ts          timestamptz NOT NULL DEFAULT now(),
    source_code text NOT NULL,
    fingerprint text,
    action      text NOT NULL
                CHECK (action IN ('TICKET_CREATED', 'TICKET_COMMENTED', 'TICKET_RESOLVED',
                                  'IGNORED', 'DEDUPED', 'FAILED')),
    ticket_id   uuid,
    note        text,
    payload     jsonb,
    trace_id    text
);
CREATE INDEX IF NOT EXISTS ix_ops_hook_event_ts
    ON ops_svc.ops_hook_event (ts DESC);
CREATE INDEX IF NOT EXISTS ix_ops_hook_event_source
    ON ops_svc.ops_hook_event (source_code, ts DESC);
CREATE INDEX IF NOT EXISTS ix_ops_hook_event_fingerprint
    ON ops_svc.ops_hook_event (fingerprint, ts DESC);

DROP TRIGGER IF EXISTS trg_ops_hook_event_append_only ON ops_svc.ops_hook_event;
CREATE TRIGGER trg_ops_hook_event_append_only
    BEFORE UPDATE OR DELETE ON ops_svc.ops_hook_event
    FOR EACH ROW EXECUTE FUNCTION ops_svc.reject_audit_mutation();

-- ---------------------------------------------------------------------------
-- 4. 审计动作扩展
-- ---------------------------------------------------------------------------
ALTER TABLE ops_svc.ops_change_log DROP CONSTRAINT IF EXISTS ops_change_log_action_check;
ALTER TABLE ops_svc.ops_change_log ADD CONSTRAINT ops_change_log_action_check CHECK (
    action IN ('TICKET_CREATE', 'TICKET_TRANSITION', 'TICKET_ASSIGN', 'TICKET_COMMENT',
               'TICKET_PRIORITY', 'SLA_SCAN', 'POLICY_UPSERT', 'CALENDAR_UPSERT',
               'SERVICE_UPSERT', 'ROTATION_UPSERT', 'SHIFT_GENERATE', 'SHIFT_OVERRIDE',
               'INCIDENT_CREATE', 'INCIDENT_STATUS', 'INCIDENT_COMMANDER', 'INCIDENT_COMMENT',
               'POSTMORTEM_DRAFT', 'POSTMORTEM_SUBMIT', 'POSTMORTEM_APPROVE',
               'INTEGRATION_SOURCE_CREATE', 'INTEGRATION_SOURCE_ROTATE',
               'INTEGRATION_SOURCE_DISABLE')
);
