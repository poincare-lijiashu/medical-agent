-- ============================================================================
-- V2：值班轮换、重大事件与复盘（第 272 批）
--
-- 设计要点：
--   * 值班按「组 × 周期」轮换，排班是生成出来的事实（不是每次查询现算），
--     允许人工覆盖并留审计；覆盖不覆盖历史，只影响指定日期；
--   * 事件（Incident）与工单是两级：工单是"一个请求"，事件是"一次影响面"，
--     一个事件可以关联一个主工单，也可独立于工单存在；
--   * 复盘与事件一对一；复盘必须经过"草稿 → 提交复核 → 审批"，
--     作者不能审批自己的复盘（与主数据审批同规格）；
--   * 事件轨迹追加式，数据库触发器拒绝 UPDATE/DELETE。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. 值班轮换
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ops_svc.ops_oncall_rotation (
    id            uuid PRIMARY KEY,
    group_code    text NOT NULL UNIQUE,
    name          text NOT NULL,
    timezone      text NOT NULL DEFAULT 'Asia/Shanghai',
    start_date    date NOT NULL,
    cycle_days    integer NOT NULL CHECK (cycle_days BETWEEN 1 AND 90),
    members       text[] NOT NULL,
    handover_time time NOT NULL DEFAULT '08:00',
    status        text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE')),
    version       bigint NOT NULL DEFAULT 0,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE ops_svc.ops_oncall_rotation IS
    '值班轮换：成员按顺序轮转，每 cycle_days 天一换；主/备由相邻成员自动配对';

CREATE TABLE IF NOT EXISTS ops_svc.ops_oncall_shift (
    id            uuid PRIMARY KEY,
    rotation_id   uuid NOT NULL REFERENCES ops_svc.ops_oncall_rotation (id) ON DELETE CASCADE,
    shift_date    date NOT NULL,
    primary_user  text NOT NULL,
    secondary_user text,
    note          text,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ux_ops_shift UNIQUE (rotation_id, shift_date)
);
CREATE INDEX IF NOT EXISTS ix_ops_shift_date ON ops_svc.ops_oncall_shift (shift_date);
COMMENT ON TABLE ops_svc.ops_oncall_shift IS
    '值班班次：某组某天的主/备值班人；人工覆盖会更新 note 并写审计';

-- ---------------------------------------------------------------------------
-- 2. 重大事件
-- ---------------------------------------------------------------------------
CREATE SEQUENCE IF NOT EXISTS ops_svc.ops_incident_no_seq START 1;

CREATE TABLE IF NOT EXISTS ops_svc.ops_incident (
    id                 uuid PRIMARY KEY,
    incident_no        text NOT NULL UNIQUE,
    title              text NOT NULL,
    severity           text NOT NULL CHECK (severity IN ('SEV1', 'SEV2', 'SEV3', 'SEV4')),
    status             text NOT NULL DEFAULT 'OPEN'
                       CHECK (status IN ('OPEN', 'MITIGATING', 'MONITORING', 'RESOLVED',
                                         'CLOSED', 'REOPENED')),
    commander          text NOT NULL,
    detected_at        timestamptz NOT NULL DEFAULT now(),
    started_at         timestamptz NOT NULL DEFAULT now(),
    resolved_at        timestamptz,
    closed_at          timestamptz,
    affected_services  text[] NOT NULL,
    ticket_id          uuid REFERENCES ops_svc.ops_ticket (id),
    summary            text,
    root_cause         text,
    impact             text,
    version            bigint NOT NULL DEFAULT 0,
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_ops_incident_resolved CHECK (
        (status IN ('RESOLVED', 'CLOSED') AND resolved_at IS NOT NULL)
        OR (status NOT IN ('RESOLVED', 'CLOSED'))
    ),
    CONSTRAINT ck_ops_incident_closed CHECK (
        (status = 'CLOSED' AND closed_at IS NOT NULL) OR (status <> 'CLOSED')
    )
);
CREATE INDEX IF NOT EXISTS ix_ops_incident_status ON ops_svc.ops_incident (status, severity, detected_at DESC);
CREATE INDEX IF NOT EXISTS ix_ops_incident_services ON ops_svc.ops_incident USING gin (affected_services);
COMMENT ON TABLE ops_svc.ops_incident IS
    '重大事件：一次影响面（可跨服务），有指挥人、影响范围与关联工单';

CREATE TABLE IF NOT EXISTS ops_svc.ops_incident_event (
    id          bigserial PRIMARY KEY,
    incident_id uuid NOT NULL REFERENCES ops_svc.ops_incident (id) ON DELETE CASCADE,
    ts          timestamptz NOT NULL DEFAULT now(),
    actor       text NOT NULL,
    action      text NOT NULL CHECK (action IN ('CREATE', 'STATUS_CHANGE', 'COMMANDER',
                                                'COMMENT', 'ACTION')),
    from_status text,
    to_status   text,
    note        text,
    detail      jsonb
);
CREATE INDEX IF NOT EXISTS ix_ops_incident_event ON ops_svc.ops_incident_event (incident_id, ts);
COMMENT ON TABLE ops_svc.ops_incident_event IS
    '事件轨迹：状态、指挥人、评论与处置动作；只允许追加';

-- ---------------------------------------------------------------------------
-- 3. 复盘
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ops_svc.ops_postmortem (
    id                   uuid PRIMARY KEY,
    incident_id          uuid NOT NULL UNIQUE REFERENCES ops_svc.ops_incident (id) ON DELETE CASCADE,
    author               text NOT NULL,
    status               text NOT NULL DEFAULT 'DRAFT'
                         CHECK (status IN ('DRAFT', 'REVIEWED', 'APPROVED')),
    timeline             jsonb NOT NULL DEFAULT '[]'::jsonb,
    root_cause           text,
    contributing_factors text[] NOT NULL DEFAULT '{}',
    corrective_actions   jsonb NOT NULL DEFAULT '[]'::jsonb,
    approved_by          text,
    approved_at          timestamptz,
    version              bigint NOT NULL DEFAULT 0,
    created_at           timestamptz NOT NULL DEFAULT now(),
    updated_at           timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_ops_postmortem_approved CHECK (
        (status = 'APPROVED' AND approved_by IS NOT NULL AND approved_at IS NOT NULL)
        OR (status <> 'APPROVED')
    )
);
COMMENT ON TABLE ops_svc.ops_postmortem IS
    '事件复盘：时间线/根因/促成因素/纠正措施；DRAFT → REVIEWED → APPROVED，作者不能审批';

-- ---------------------------------------------------------------------------
-- 4. 审计动作扩展
-- ---------------------------------------------------------------------------
ALTER TABLE ops_svc.ops_change_log DROP CONSTRAINT IF EXISTS ops_change_log_action_check;
ALTER TABLE ops_svc.ops_change_log ADD CONSTRAINT ops_change_log_action_check CHECK (
    action IN ('TICKET_CREATE', 'TICKET_TRANSITION', 'TICKET_ASSIGN', 'TICKET_COMMENT',
               'TICKET_PRIORITY', 'SLA_SCAN', 'POLICY_UPSERT', 'CALENDAR_UPSERT',
               'SERVICE_UPSERT', 'ROTATION_UPSERT', 'SHIFT_GENERATE', 'SHIFT_OVERRIDE',
               'INCIDENT_CREATE', 'INCIDENT_STATUS', 'INCIDENT_COMMANDER', 'INCIDENT_COMMENT',
               'POSTMORTEM_DRAFT', 'POSTMORTEM_SUBMIT', 'POSTMORTEM_APPROVE')
);

DROP TRIGGER IF EXISTS trg_ops_incident_event_append_only ON ops_svc.ops_incident_event;
CREATE TRIGGER trg_ops_incident_event_append_only
    BEFORE UPDATE OR DELETE ON ops_svc.ops_incident_event
    FOR EACH ROW EXECUTE FUNCTION ops_svc.reject_audit_mutation();
