-- ============================================================================
-- 版本与补丁机制（release_svc）——第 273 批
--
-- 设计要点：
--   * 版本是"不可变制品"：组件 + 版本号唯一，记录 commit/镜像摘要/schema 版本；
--     发布后不允许改内容，只能新增更高版本或作废（YANKED）；
--   * 发布列车（train）是"一组组件的联合发布"：车厢引用具体版本，
--     冻结后不允许增删；发布前必须通过兼容矩阵与冻结窗口检查；
--   * 兼容矩阵是显式的 REQUIRES/CONFLICTS 边，不做"版本号大小猜测"；
--   * 补丁有分级与审批：提交人 ≠ 审批人；未审批补丁不能进生产；
--   * 生产部署必须带完整检查单；冻结窗口内禁止部署（HOTFIX 需已发布补丁）；
--   * 部署记录不可修改，回滚是新增一条 ROLLBACK 记录并指回原部署。
-- ============================================================================

CREATE SCHEMA IF NOT EXISTS release_svc;

-- ---------------------------------------------------------------------------
-- 1. 组件与版本
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS release_svc.rel_component (
    code        text PRIMARY KEY,
    name        text NOT NULL,
    kind        text NOT NULL CHECK (kind IN ('SERVICE', 'AI', 'INFRA', 'WEB')),
    owner_group text NOT NULL,
    status      text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE')),
    sort_no     integer NOT NULL DEFAULT 100,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE release_svc.rel_component IS '可发布组件：平台服务、AI 中台、基础设施与前端';

INSERT INTO release_svc.rel_component (code, name, kind, owner_group, sort_no) VALUES
    ('platform-gateway', '统一网关', 'SERVICE', 'PLATFORM_ONCALL', 10),
    ('platform-auth', '用户与权限中心', 'SERVICE', 'PLATFORM_ONCALL', 20),
    ('platform-empi', '患者主索引', 'SERVICE', 'PLATFORM_ONCALL', 30),
    ('platform-clinical', '临床核心', 'SERVICE', 'CLINICAL_ONCALL', 40),
    ('platform-pharmacy', '药房域', 'SERVICE', 'PHARMACY_ONCALL', 50),
    ('platform-tech', '医技域', 'SERVICE', 'TECH_ONCALL', 60),
    ('platform-emr', '病历域', 'SERVICE', 'EMR_ONCALL', 70),
    ('platform-insurance', '医保域', 'SERVICE', 'INSURANCE_ONCALL', 80),
    ('platform-kb', '知识库', 'SERVICE', 'PLATFORM_ONCALL', 90),
    ('platform-material', '物资耗材', 'SERVICE', 'MATERIAL_ONCALL', 100),
    ('platform-fhir', 'FHIR 开放层', 'SERVICE', 'PLATFORM_ONCALL', 110),
    ('platform-masterdata', '主数据治理', 'SERVICE', 'DATA_ONCALL', 120),
    ('platform-migration', '历史数据迁移', 'SERVICE', 'IMPLEMENTATION_ONCALL', 130),
    ('platform-ops', '运营支撑', 'SERVICE', 'PLATFORM_ONCALL', 140),
    ('platform-release', '版本与补丁', 'SERVICE', 'PLATFORM_ONCALL', 150),
    ('ai-core', 'AI 中台', 'AI', 'AI_ONCALL', 200),
    ('web-console', '管理控制台', 'WEB', 'PLATFORM_ONCALL', 210),
    ('infra-postgres', '数据库 PostgreSQL', 'INFRA', 'INFRA_ONCALL', 300)
ON CONFLICT (code) DO NOTHING;

CREATE TABLE IF NOT EXISTS release_svc.rel_version (
    id               uuid PRIMARY KEY,
    component_code   text NOT NULL REFERENCES release_svc.rel_component (code),
    version          text NOT NULL,
    git_commit       text,
    image_digest     text,
    schema_version   text,
    status           text NOT NULL DEFAULT 'DRAFT'
                     CHECK (status IN ('DRAFT', 'RELEASED', 'DEPRECATED', 'YANKED')),
    breaking_changes boolean NOT NULL DEFAULT false,
    changelog        text,
    released_at      timestamptz,
    created_by       text NOT NULL,
    lock_version     bigint NOT NULL DEFAULT 0,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ux_rel_version UNIQUE (component_code, version),
    CONSTRAINT ck_rel_version_released CHECK (
        (status IN ('RELEASED', 'DEPRECATED', 'YANKED') AND released_at IS NOT NULL)
        OR (status = 'DRAFT')
    )
);
CREATE INDEX IF NOT EXISTS ix_rel_version_component
    ON release_svc.rel_version (component_code, created_at DESC);
COMMENT ON TABLE release_svc.rel_version IS
    '组件版本：DRAFT 可补元数据，RELEASED 后不可改内容；YANKED 禁止再进列车';

-- ---------------------------------------------------------------------------
-- 2. 发布列车
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS release_svc.rel_release_train (
    id           uuid PRIMARY KEY,
    train_code   text NOT NULL UNIQUE,
    name         text NOT NULL,
    planned_date date NOT NULL,
    freeze_at    timestamptz NOT NULL,
    status       text NOT NULL DEFAULT 'PLANNED'
                 CHECK (status IN ('PLANNED', 'FROZEN', 'RELEASING', 'RELEASED', 'CANCELLED')),
    notes        text,
    released_at  timestamptz,
    created_by   text NOT NULL,
    version      bigint NOT NULL DEFAULT 0,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_rel_train_freeze CHECK (freeze_at <= planned_date::timestamptz + interval '1 day'),
    CONSTRAINT ck_rel_train_released CHECK (
        (status = 'RELEASED' AND released_at IS NOT NULL) OR (status <> 'RELEASED')
    )
);
COMMENT ON TABLE release_svc.rel_release_train IS
    '发布列车：一组组件的联合发布；冻结后不允许增删车厢';

CREATE TABLE IF NOT EXISTS release_svc.rel_train_item (
    id             uuid PRIMARY KEY,
    train_id       uuid NOT NULL REFERENCES release_svc.rel_release_train (id) ON DELETE CASCADE,
    component_code text NOT NULL REFERENCES release_svc.rel_component (code),
    version_id     uuid NOT NULL REFERENCES release_svc.rel_version (id),
    added_by       text NOT NULL,
    created_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ux_rel_train_item UNIQUE (train_id, component_code)
);
CREATE INDEX IF NOT EXISTS ix_rel_train_item ON release_svc.rel_train_item (train_id);
COMMENT ON TABLE release_svc.rel_train_item IS
    '列车车厢：一个组件在一个列车里只能有一个版本';

-- ---------------------------------------------------------------------------
-- 3. 兼容矩阵
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS release_svc.rel_compatibility (
    id                uuid PRIMARY KEY,
    version_id        uuid NOT NULL REFERENCES release_svc.rel_version (id) ON DELETE CASCADE,
    kind              text NOT NULL CHECK (kind IN ('REQUIRES', 'CONFLICTS')),
    target_version_id uuid NOT NULL REFERENCES release_svc.rel_version (id) ON DELETE CASCADE,
    note              text,
    created_by        text NOT NULL,
    created_at        timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_rel_compat_self CHECK (version_id <> target_version_id),
    CONSTRAINT ux_rel_compat UNIQUE (version_id, kind, target_version_id)
);
CREATE INDEX IF NOT EXISTS ix_rel_compat_version ON release_svc.rel_compatibility (version_id);
COMMENT ON TABLE release_svc.rel_compatibility IS
    '兼容矩阵：显式 REQUIRES/CONFLICTS 边；不做版本号大小的隐式推断';

-- ---------------------------------------------------------------------------
-- 4. 补丁
-- ---------------------------------------------------------------------------
CREATE SEQUENCE IF NOT EXISTS release_svc.rel_patch_no_seq START 1;

CREATE TABLE IF NOT EXISTS release_svc.rel_patch (
    id                uuid PRIMARY KEY,
    patch_no          text NOT NULL UNIQUE,
    title             text NOT NULL,
    severity          text NOT NULL CHECK (severity IN ('CRITICAL', 'HIGH', 'MEDIUM', 'LOW')),
    component_code    text NOT NULL REFERENCES release_svc.rel_component (code),
    affected_versions text[] NOT NULL,
    fixed_version_id  uuid NOT NULL REFERENCES release_svc.rel_version (id),
    advisory          text,
    cve_id            text,
    status            text NOT NULL DEFAULT 'DRAFT'
                      CHECK (status IN ('DRAFT', 'REVIEW', 'APPROVED', 'RELEASED', 'WITHDRAWN')),
    requested_by      text NOT NULL,
    approved_by       text,
    approved_at       timestamptz,
    released_at       timestamptz,
    version           bigint NOT NULL DEFAULT 0,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_rel_patch_approved CHECK (
        (status IN ('APPROVED', 'RELEASED') AND approved_by IS NOT NULL AND approved_at IS NOT NULL)
        OR (status NOT IN ('APPROVED', 'RELEASED'))
    ),
    CONSTRAINT ck_rel_patch_released CHECK (
        (status = 'RELEASED' AND released_at IS NOT NULL) OR (status <> 'RELEASED')
    )
);
CREATE INDEX IF NOT EXISTS ix_rel_patch_component
    ON release_svc.rel_patch (component_code, created_at DESC);
COMMENT ON TABLE release_svc.rel_patch IS
    '安全/缺陷补丁：CRITICAL–LOW；提交、审批、发布三阶段，提交人不能自审';

-- ---------------------------------------------------------------------------
-- 5. 变更冻结窗口
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS release_svc.rel_change_window (
    id         uuid PRIMARY KEY,
    name       text NOT NULL,
    kind       text NOT NULL CHECK (kind IN ('FREEZE', 'MAINTENANCE')),
    start_at   timestamptz NOT NULL,
    end_at     timestamptz NOT NULL,
    scope      text[] NOT NULL DEFAULT '{}',
    reason     text NOT NULL,
    status     text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'CANCELLED')),
    created_by text NOT NULL,
    version    bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_rel_window_time CHECK (end_at > start_at)
);
CREATE INDEX IF NOT EXISTS ix_rel_window_time ON release_svc.rel_change_window (start_at, end_at);
COMMENT ON TABLE release_svc.rel_change_window IS
    '变更窗口：FREEZE 禁止部署；scope 为空表示全院，否则按组件匹配';

-- ---------------------------------------------------------------------------
-- 6. 部署/回滚记录
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS release_svc.rel_deployment (
    id             uuid PRIMARY KEY,
    component_code text NOT NULL REFERENCES release_svc.rel_component (code),
    version_id     uuid NOT NULL REFERENCES release_svc.rel_version (id),
    environment    text NOT NULL CHECK (environment IN ('DEV', 'STAGING', 'PROD')),
    action         text NOT NULL CHECK (action IN ('DEPLOY', 'ROLLBACK', 'HOTFIX')),
    result         text NOT NULL CHECK (result IN ('SUCCESS', 'FAILED', 'ROLLED_BACK')),
    checklist      jsonb NOT NULL DEFAULT '{}'::jsonb,
    rollback_of    uuid REFERENCES release_svc.rel_deployment (id),
    reason         text,
    actor          text NOT NULL,
    started_at     timestamptz NOT NULL,
    finished_at    timestamptz,
    created_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_rel_deploy_rollback CHECK (
        (action = 'ROLLBACK' AND rollback_of IS NOT NULL) OR (action <> 'ROLLBACK')
    )
);
CREATE INDEX IF NOT EXISTS ix_rel_deploy_component
    ON release_svc.rel_deployment (component_code, environment, started_at DESC);
COMMENT ON TABLE release_svc.rel_deployment IS
    '部署记录：不可修改；回滚是新增 ROLLBACK 记录并指回原部署';

-- ---------------------------------------------------------------------------
-- 7. 审计
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS release_svc.rel_change_log (
    id         bigserial PRIMARY KEY,
    ts         timestamptz NOT NULL DEFAULT now(),
    actor      text NOT NULL,
    action     text NOT NULL
               CHECK (action IN ('COMPONENT_UPSERT', 'VERSION_REGISTER', 'VERSION_PUBLISH',
                                 'VERSION_DEPRECATE', 'VERSION_YANK', 'TRAIN_CREATE',
                                 'TRAIN_ITEM_ADD', 'TRAIN_ITEM_REMOVE', 'TRAIN_FREEZE',
                                 'TRAIN_RELEASE', 'TRAIN_CANCEL', 'COMPAT_ADD', 'COMPAT_REMOVE',
                                 'PATCH_CREATE', 'PATCH_SUBMIT', 'PATCH_APPROVE',
                                 'PATCH_RELEASE', 'PATCH_WITHDRAW', 'WINDOW_CREATE',
                                 'WINDOW_CANCEL', 'DEPLOY_RECORD', 'DEPLOY_ROLLBACK')),
    target_ref text,
    detail     jsonb,
    client_ip  text,
    trace_id   text
);
CREATE INDEX IF NOT EXISTS ix_rel_change_ts ON release_svc.rel_change_log (ts DESC);

CREATE OR REPLACE FUNCTION release_svc.reject_audit_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION '% 是追加式审计表，禁止 %', TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'insufficient_privilege';
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_rel_change_log_append_only ON release_svc.rel_change_log;
CREATE TRIGGER trg_rel_change_log_append_only
    BEFORE UPDATE OR DELETE ON release_svc.rel_change_log
    FOR EACH ROW EXECUTE FUNCTION release_svc.reject_audit_mutation();
