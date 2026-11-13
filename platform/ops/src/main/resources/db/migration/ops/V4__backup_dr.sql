-- ============================================================================
-- V4：备份策略、备份运行与灾备演练（第 297 批）
--
-- 目标：把"备份做了没有、能不能恢复"从口头承诺变成发布门禁。
--
-- 设计要点：
--   * 备份策略是配置（范围/类型/计划/保留/加密/目标/RPO），备份运行是事实，
--     两者分开：策略改了不能改写历史运行的语义；
--   * 备份运行按 idempotency_key 唯一：备份平台重试上报不会产生两条成功记录；
--     RUNNING 可被同一幂等键终结为 SUCCESS/FAILED，终结后不再允许改写；
--   * 灾备演练必须走 PLANNED → RUNNING → PASSED/FAILED/ABORTED，
--     PASSED 需要：必填步骤全部通过 + 实测 RTO/RPO 不超目标 + 数据一致性核对 +
--     证据非空 + 复核人不是执行人（四眼）；
--   * 发布门禁只认"最近 N 天内 PASSED 且已复核"的演练，过期即视为无演练。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. 备份策略
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ops_svc.ops_backup_policy (
    id                uuid PRIMARY KEY,
    code              text NOT NULL UNIQUE,
    name              text NOT NULL,
    scope_type        text NOT NULL DEFAULT 'ALL'
                      CHECK (scope_type IN ('ALL', 'COMPONENT', 'SCHEMA')),
    scope_ref         text,
    backup_type       text NOT NULL DEFAULT 'FULL'
                      CHECK (backup_type IN ('FULL', 'INCREMENTAL', 'WAL', 'LOGICAL')),
    schedule_cron     text NOT NULL,
    retention_days    integer NOT NULL DEFAULT 30
                      CHECK (retention_days BETWEEN 1 AND 3650),
    encryption        text NOT NULL DEFAULT 'NONE'
                      CHECK (encryption IN ('NONE', 'AES256', 'KMS')),
    storage_target    text NOT NULL,
    rpo_minutes       integer NOT NULL CHECK (rpo_minutes BETWEEN 1 AND 43200),
    owner             text NOT NULL,
    status            text NOT NULL DEFAULT 'ACTIVE'
                      CHECK (status IN ('ACTIVE', 'INACTIVE')),
    last_run_at       timestamptz,
    version           bigint NOT NULL DEFAULT 0,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_ops_backup_policy_scope CHECK (
        (scope_type = 'ALL' AND scope_ref IS NULL)
        OR (scope_type <> 'ALL' AND scope_ref IS NOT NULL AND length(btrim(scope_ref)) > 0)
    )
);
COMMENT ON TABLE ops_svc.ops_backup_policy IS
    '备份策略：范围/类型/计划/保留/加密/目标/RPO；RPO 是承诺，不是建议值';
COMMENT ON COLUMN ops_svc.ops_backup_policy.scope_ref IS
    'scope_type=COMPONENT 时为组件编码，SCHEMA 时为库 schema 名；ALL 时必须为空';

-- ---------------------------------------------------------------------------
-- 2. 备份运行（追加为主，RUNNING 可被同一幂等键终结一次）
-- ---------------------------------------------------------------------------
CREATE SEQUENCE IF NOT EXISTS ops_svc.ops_backup_run_no_seq START 1;

CREATE TABLE IF NOT EXISTS ops_svc.ops_backup_run (
    id               uuid PRIMARY KEY,
    run_no           text NOT NULL UNIQUE,
    policy_id        uuid NOT NULL REFERENCES ops_svc.ops_backup_policy (id),
    policy_code      text NOT NULL,
    backup_type      text NOT NULL
                     CHECK (backup_type IN ('FULL', 'INCREMENTAL', 'WAL', 'LOGICAL')),
    status           text NOT NULL CHECK (status IN ('RUNNING', 'SUCCESS', 'FAILED')),
    idempotency_key  text NOT NULL UNIQUE,
    size_bytes       bigint CHECK (size_bytes IS NULL OR size_bytes >= 0),
    checksum         text,
    storage_location text,
    started_at       timestamptz NOT NULL,
    finished_at      timestamptz,
    error_message    text,
    reported_by      text NOT NULL,
    version          bigint NOT NULL DEFAULT 0,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_ops_backup_run_success CHECK (
        status <> 'SUCCESS'
        OR (finished_at IS NOT NULL AND finished_at >= started_at
            AND checksum IS NOT NULL AND length(btrim(checksum)) > 0
            AND storage_location IS NOT NULL AND length(btrim(storage_location)) > 0
            AND size_bytes IS NOT NULL)
    ),
    CONSTRAINT ck_ops_backup_run_failed CHECK (
        status <> 'FAILED'
        OR (finished_at IS NOT NULL AND finished_at >= started_at
            AND error_message IS NOT NULL AND length(btrim(error_message)) > 0)
    )
);
CREATE INDEX IF NOT EXISTS ix_ops_backup_run_policy
    ON ops_svc.ops_backup_run (policy_code, started_at DESC);
CREATE INDEX IF NOT EXISTS ix_ops_backup_run_status
    ON ops_svc.ops_backup_run (status, started_at DESC);
COMMENT ON TABLE ops_svc.ops_backup_run IS
    '备份运行事实：idempotency_key 唯一，备份平台重试上报不会重复记账';

-- ---------------------------------------------------------------------------
-- 3. 灾备演练
-- ---------------------------------------------------------------------------
CREATE SEQUENCE IF NOT EXISTS ops_svc.ops_dr_drill_no_seq START 1;

CREATE TABLE IF NOT EXISTS ops_svc.ops_dr_drill (
    id                    uuid PRIMARY KEY,
    drill_no              text NOT NULL UNIQUE,
    title                 text NOT NULL,
    scope                 text NOT NULL,
    environment           text NOT NULL DEFAULT 'PROD'
                          CHECK (environment IN ('PROD', 'STAGING', 'DRILL')),
    status                text NOT NULL DEFAULT 'PLANNED'
                          CHECK (status IN ('PLANNED', 'RUNNING', 'PASSED', 'FAILED', 'ABORTED')),
    backup_run_id         uuid REFERENCES ops_svc.ops_backup_run (id),
    rto_target_minutes    integer NOT NULL CHECK (rto_target_minutes BETWEEN 1 AND 43200),
    rto_actual_minutes    integer CHECK (rto_actual_minutes IS NULL OR rto_actual_minutes >= 0),
    rpo_target_minutes    integer NOT NULL CHECK (rpo_target_minutes BETWEEN 1 AND 43200),
    rpo_actual_minutes    integer CHECK (rpo_actual_minutes IS NULL OR rpo_actual_minutes >= 0),
    data_loss_minutes     integer CHECK (data_loss_minutes IS NULL OR data_loss_minutes >= 0),
    consistency_verified  boolean NOT NULL DEFAULT false,
    executor              text NOT NULL,
    verifier              text,
    verified_at           timestamptz,
    evidence              text,
    note                  text,
    started_at            timestamptz,
    completed_at          timestamptz,
    version               bigint NOT NULL DEFAULT 0,
    created_at            timestamptz NOT NULL DEFAULT now(),
    updated_at            timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_ops_dr_drill_passed CHECK (
        status <> 'PASSED'
        OR (completed_at IS NOT NULL
            AND verifier IS NOT NULL AND length(btrim(verifier)) > 0
            AND verified_at IS NOT NULL
            AND verifier <> executor
            AND consistency_verified
            AND rto_actual_minutes IS NOT NULL AND rto_actual_minutes <= rto_target_minutes
            AND rpo_actual_minutes IS NOT NULL AND rpo_actual_minutes <= rpo_target_minutes
            AND evidence IS NOT NULL AND length(btrim(evidence)) > 0)
    ),
    CONSTRAINT ck_ops_dr_drill_failed CHECK (
        status NOT IN ('FAILED', 'ABORTED')
        OR (completed_at IS NOT NULL
            AND note IS NOT NULL AND length(btrim(note)) > 0)
    )
);
CREATE INDEX IF NOT EXISTS ix_ops_dr_drill_gate
    ON ops_svc.ops_dr_drill (scope, environment, status, completed_at DESC);
COMMENT ON TABLE ops_svc.ops_dr_drill IS
    '灾备演练：PASSED 必须满足必填步骤通过、实测 RTO/RPO 达标、一致性核对、证据与四眼复核';
COMMENT ON COLUMN ops_svc.ops_dr_drill.scope IS
    '演练覆盖范围：ALL（全院）或组件编码；发布门禁按 scope=组件 或 ALL 匹配';

CREATE TABLE IF NOT EXISTS ops_svc.ops_dr_drill_step (
    id           uuid PRIMARY KEY,
    drill_id     uuid NOT NULL REFERENCES ops_svc.ops_dr_drill (id) ON DELETE CASCADE,
    step_no      integer NOT NULL CHECK (step_no BETWEEN 1 AND 99),
    name         text NOT NULL,
    status       text NOT NULL DEFAULT 'PENDING'
                 CHECK (status IN ('PENDING', 'PASSED', 'FAILED', 'SKIPPED')),
    required     boolean NOT NULL DEFAULT true,
    evidence     text,
    note         text,
    actor        text,
    started_at   timestamptz,
    completed_at timestamptz,
    CONSTRAINT ux_ops_dr_drill_step UNIQUE (drill_id, step_no),
    CONSTRAINT ck_ops_dr_drill_step_passed CHECK (
        status <> 'PASSED'
        OR (evidence IS NOT NULL AND length(btrim(evidence)) > 0)
    )
);
CREATE INDEX IF NOT EXISTS ix_ops_dr_drill_step ON ops_svc.ops_dr_drill_step (drill_id, step_no);
COMMENT ON TABLE ops_svc.ops_dr_drill_step IS
    '演练步骤：PASSED 必须有证据（日志/截图地址/还原结果），SKIPPED 只能用于非必填步骤';

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
               'INTEGRATION_SOURCE_DISABLE',
               'BACKUP_POLICY_UPSERT', 'BACKUP_RUN_REPORT',
               'DR_DRILL_CREATE', 'DR_DRILL_START', 'DR_DRILL_STEP',
               'DR_DRILL_PASS', 'DR_DRILL_FAIL', 'DR_DRILL_ABORT')
);
