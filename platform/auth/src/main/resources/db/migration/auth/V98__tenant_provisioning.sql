-- ============================================================================
-- V98：租户开通编排与保留策略（第 284 批 / ADR-001 P3）
--
-- 物理隔离之后，"开通一家医院"不再是插一行台账，而是一条可重入的流水线：
--   校验规格 → 建/复用租户目录 → 探测 Cell 库结构 → Cell 内登记租户
--   → 建首个管理员 → 授权检查 → 激活 → 健康检查
--
-- 为什么把步骤落库：
--   * 院方数据库/网络经常在交付窗口不可达，运维需要知道"卡在哪一步"而不是重头再来；
--   * 每一步都做幂等（重跑不会重复建管理员、不会重复激活），失败后可直接 resume；
--   * 开通是变更审计的一部分：谁在什么时候为哪家医院做了哪一步，必须可追溯。
--
-- 安全边界：
--   * 一次性管理员口令**不落库**：只在创建响应里返回一次；丢失只能走重置密码流程；
--   * CLOSED 是终态，数据按合同保留期冻结；保留期与原因写在租户台账上，不靠口口相传。
-- ============================================================================

-- ---- 1. 开通任务 ----
CREATE TABLE IF NOT EXISTS auth_svc.provision_job (
    id           uuid PRIMARY KEY,
    tenant_code  text NOT NULL,
    instance_code text NOT NULL,
    status       text NOT NULL CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED')),
    requested_by text NOT NULL,
    request      jsonb NOT NULL DEFAULT '{}'::jsonb,
    result       jsonb NOT NULL DEFAULT '{}'::jsonb,
    started_at   timestamptz NOT NULL DEFAULT now(),
    finished_at  timestamptz,
    created_at   timestamptz NOT NULL DEFAULT now()
);

-- 同一家医院同时只允许一个进行中的开通任务：两个运维同时点"开通"不能各跑一半
CREATE UNIQUE INDEX IF NOT EXISTS ux_provision_active
    ON auth_svc.provision_job (tenant_code) WHERE status = 'RUNNING';
CREATE INDEX IF NOT EXISTS ix_provision_tenant
    ON auth_svc.provision_job (tenant_code, started_at DESC);

COMMENT ON TABLE auth_svc.provision_job IS
    'Cell 开通任务：同步执行但逐步落库，失败可 resume；口令不入库';

-- ---- 2. 开通步骤 ----
CREATE TABLE IF NOT EXISTS auth_svc.provision_step (
    id          bigserial PRIMARY KEY,
    job_id      uuid NOT NULL REFERENCES auth_svc.provision_job (id) ON DELETE CASCADE,
    step_no     integer NOT NULL,
    step_code   text NOT NULL,
    status      text NOT NULL CHECK (status IN ('SUCCEEDED', 'FAILED', 'SKIPPED')),
    detail      jsonb NOT NULL DEFAULT '{}'::jsonb,
    error       text,
    started_at  timestamptz NOT NULL DEFAULT now(),
    finished_at timestamptz,
    CONSTRAINT ux_provision_step UNIQUE (job_id, step_code)
);
CREATE INDEX IF NOT EXISTS ix_provision_step_job
    ON auth_svc.provision_step (job_id, step_no);

COMMENT ON TABLE auth_svc.provision_step IS
    '开通步骤结果：唯一键 (job_id, step_code)，resume 时覆盖重跑，不产生重复行';

-- ---- 3. 租户保留策略 ----
ALTER TABLE auth_svc.tenant
    ADD COLUMN IF NOT EXISTS closed_at timestamptz,
    ADD COLUMN IF NOT EXISTS closed_reason text,
    ADD COLUMN IF NOT EXISTS retention_until date;

COMMENT ON COLUMN auth_svc.tenant.closed_at IS '注销时间（终态）；停用不写该字段';
COMMENT ON COLUMN auth_svc.tenant.closed_reason IS '注销原因（合同终止/欠费/院方要求下线等）';
COMMENT ON COLUMN auth_svc.tenant.retention_until IS
    '数据保留截止日：注销后按合同保留，到期后由数据处置流程归档/销毁（不由本服务删除）';

-- ---- 4. Cell 数据库坐标不可复用 ----
-- 一院一 Cell 的前提是"一个业务库只属于一家医院"。没有这条约束，
-- 复制一套开通脚本就能让两家医院落进同一个库，物理隔离立刻失效。
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_indexes
                    WHERE schemaname = 'auth_svc' AND indexname = 'ux_tenant_cell_db') THEN
        CREATE UNIQUE INDEX ux_tenant_cell_db
            ON auth_svc.tenant (lower(db_host), db_name)
         WHERE db_host IS NOT NULL AND db_name IS NOT NULL;
    END IF;
END
$$;
