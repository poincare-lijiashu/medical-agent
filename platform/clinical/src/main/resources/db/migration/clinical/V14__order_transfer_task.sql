-- ============================================================================
-- 临床域 V14：医嘱转送补偿任务（第六轮 R6-MF-02 / MF-03）
--
-- 背景：
--   * 转送在开嘱事务提交后执行，若下游已建单但本地回写失败，本地记 FAILED，
--     此后撤销/停嘱因 "非 TRANSFERRED" 静默跳过 → 处方仍可发药（用药安全缺口）；
--   * 停嘱过去把"下游作废"和"医疗决定"绑在一个事务里，处方已发药时下游 409，
--     整个停嘱回滚 → 医师无法停嘱（流程死锁）。
--
-- 本表把"下游作废"从主事务里解耦：主事务只保证医疗状态落库，作废失败则登记
-- 一条 PENDING 任务，由后台 worker 反复重试直到 DONE（下游按 orderId 幂等）。
-- ============================================================================

CREATE TABLE IF NOT EXISTS clinical_svc.order_transfer_task (
    id          uuid PRIMARY KEY,
    order_id    uuid NOT NULL,
    visit_id    uuid NOT NULL,
    action      text NOT NULL DEFAULT 'CANCEL' CHECK (action IN ('CANCEL')),
    channel     text NOT NULL CHECK (channel IN ('PHARMACY', 'TECH')),
    reason      text NOT NULL,
    actor       text,
    status      text NOT NULL DEFAULT 'PENDING'
                CHECK (status IN ('PENDING', 'DONE', 'FAILED')),
    attempts    int NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    last_error  text,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now()
);

-- 同一医嘱同一动作只允许有一条待处理任务（重复登记由唯一索引拦掉，应用层忽略冲突）
CREATE UNIQUE INDEX IF NOT EXISTS ux_order_transfer_task_pending
    ON clinical_svc.order_transfer_task (order_id, action)
    WHERE status = 'PENDING';

CREATE INDEX IF NOT EXISTS ix_order_transfer_task_scan
    ON clinical_svc.order_transfer_task (status, updated_at);

COMMENT ON TABLE clinical_svc.order_transfer_task IS
    '医嘱转送补偿任务：停嘱/撤销后下游作废失败时登记，由后台 worker 重试（尽力而为，不回滚医疗决定）';
