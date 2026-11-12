-- ============================================================================
-- V2：CI/CD 接入源与流水线回写（第 276 批）
--
-- 目标：流水线不再"发完邮件再让人补记录"。构建/部署事件按接入源令牌回写：
--   1) event_id 幂等：同一事件重复投递只返回首次结果，不重复落部署；
--   2) BUILD 成功可在版本缺失时自动登记 DRAFT（发布仍需人工审批）；
--   3) DEPLOY 复用 DeploymentService 的全部门禁：冻结窗口、生产检查单、回滚链；
--   4) 令牌只存 SHA-256，明文仅在创建/轮换时返回一次。
-- ============================================================================

CREATE TABLE IF NOT EXISTS release_svc.rel_ci_source (
    id                 uuid PRIMARY KEY,
    code               text NOT NULL UNIQUE,
    name               text NOT NULL,
    kind               text NOT NULL DEFAULT 'GENERIC'
                       CHECK (kind IN ('GITHUB', 'GITLAB', 'JENKINS', 'GENERIC')),
    token_hash         text NOT NULL,
    token_hint         text NOT NULL,
    allowed_components text[] NOT NULL DEFAULT '{}',
    status             text NOT NULL DEFAULT 'ACTIVE'
                       CHECK (status IN ('ACTIVE', 'INACTIVE')),
    last_used_at       timestamptz,
    created_by         text NOT NULL,
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now(),
    version            bigint NOT NULL DEFAULT 0
);
COMMENT ON TABLE release_svc.rel_ci_source IS
    'CI 接入源：allowed_components 为空表示不限制；令牌只存 SHA-256';

CREATE TABLE IF NOT EXISTS release_svc.rel_ci_event (
    id             bigserial PRIMARY KEY,
    ts             timestamptz NOT NULL DEFAULT now(),
    source_code    text NOT NULL REFERENCES release_svc.rel_ci_source (code) ON DELETE CASCADE,
    event_id       text NOT NULL,
    kind           text NOT NULL CHECK (kind IN ('BUILD', 'DEPLOY')),
    component_code text NOT NULL,
    version_no     text,
    environment    text,
    action         text,
    result         text,
    deployment_id  uuid REFERENCES release_svc.rel_deployment (id),
    status         text NOT NULL CHECK (status IN ('PROCESSED', 'FAILED')),
    message        text,
    payload        jsonb,
    CONSTRAINT ux_rel_ci_event UNIQUE (source_code, event_id)
);
CREATE INDEX IF NOT EXISTS ix_rel_ci_event_ts
    ON release_svc.rel_ci_event (ts DESC);
CREATE INDEX IF NOT EXISTS ix_rel_ci_event_source
    ON release_svc.rel_ci_event (source_code, ts DESC);
COMMENT ON TABLE release_svc.rel_ci_event IS
    'CI 事件：event_id 幂等；FAILED 允许同 event_id 重试，PROCESSED 直接返回首次结果';

ALTER TABLE release_svc.rel_change_log DROP CONSTRAINT IF EXISTS rel_change_log_action_check;
ALTER TABLE release_svc.rel_change_log ADD CONSTRAINT rel_change_log_action_check CHECK (
    action IN ('COMPONENT_UPSERT', 'VERSION_REGISTER', 'VERSION_PUBLISH',
               'VERSION_DEPRECATE', 'VERSION_YANK', 'TRAIN_CREATE',
               'TRAIN_ITEM_ADD', 'TRAIN_ITEM_REMOVE', 'TRAIN_FREEZE',
               'TRAIN_RELEASE', 'TRAIN_CANCEL', 'COMPAT_ADD', 'COMPAT_REMOVE',
               'PATCH_CREATE', 'PATCH_SUBMIT', 'PATCH_APPROVE',
               'PATCH_RELEASE', 'PATCH_WITHDRAW', 'WINDOW_CREATE',
               'WINDOW_CANCEL', 'DEPLOY_RECORD', 'DEPLOY_ROLLBACK',
               'CI_SOURCE_CREATE', 'CI_SOURCE_ROTATE', 'CI_SOURCE_DISABLE', 'CI_EVENT')
);
