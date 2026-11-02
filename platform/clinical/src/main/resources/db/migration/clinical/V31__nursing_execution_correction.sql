-- ============================================================================
-- V31：护理执行更正（P1-1 的执行侧闭环）
--
-- 背景：
--   * V6 的 nursing_execution 是"同日同医嘱一条"（唯一键），且 V29 已把它变成追加式
--     （禁止 UPDATE/DELETE），因此"把 EXECUTED 改判为 REFUSED"不能覆盖原记录；
--   * 按日计费据执行状态跳过/计入，所以更正必须能被计费侧看见，否则改了也白改；
--   * V30 已提供按日计费红冲，更正时自动联动即可把多收的钱退回。
--
-- 设计：
--   1. 新增 nursing_execution_correction：只追加的更正事实表；
--   2. 每条执行记录只允许更正一次（唯一键 execution_id）——多次更正说明原始记录本身有问题，
--      应走病案/护理质控流程，而不是无限覆盖；
--   3. 本迁移只建表，不改任何历史行；"有效状态"由查询侧按
--      COALESCE(更正.to_status, 执行.status) 计算。
-- ============================================================================

CREATE TABLE IF NOT EXISTS clinical_svc.nursing_execution_correction (
    id            uuid PRIMARY KEY,
    execution_id  uuid NOT NULL REFERENCES clinical_svc.nursing_execution (id),
    admission_id  uuid NOT NULL REFERENCES clinical_svc.admission (id),
    visit_id      uuid NOT NULL REFERENCES clinical_svc.visit (id),
    order_id      uuid NOT NULL REFERENCES clinical_svc.visit_order (id),
    exec_date     date NOT NULL,
    from_status   text NOT NULL CHECK (from_status IN ('EXECUTED', 'REFUSED', 'OMITTED')),
    to_status     text NOT NULL CHECK (to_status IN ('EXECUTED', 'REFUSED', 'OMITTED')),
    reason        text NOT NULL,
    corrected_by  text NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_nec_status_changed CHECK (from_status <> to_status),
    CONSTRAINT ux_nec_one_correction UNIQUE (execution_id)
);

CREATE INDEX IF NOT EXISTS ix_nec_order_date
    ON clinical_svc.nursing_execution_correction (order_id, exec_date);
CREATE INDEX IF NOT EXISTS ix_nec_visit
    ON clinical_svc.nursing_execution_correction (visit_id, exec_date);

COMMENT ON TABLE clinical_svc.nursing_execution_correction IS
    '护理执行更正：追加式事实记录（from_status→to_status + 原因 + 操作人），每条执行记录只允许更正一次';

-- 与其余旁证表同口径：只允许 INSERT
DROP TRIGGER IF EXISTS trg_nec_append_only ON clinical_svc.nursing_execution_correction;
CREATE TRIGGER trg_nec_append_only
    BEFORE UPDATE OR DELETE ON clinical_svc.nursing_execution_correction
    FOR EACH ROW EXECUTE FUNCTION clinical_svc.reject_audit_mutation();

COMMENT ON TRIGGER trg_nec_append_only ON clinical_svc.nursing_execution_correction IS
    '护理执行更正只允许 INSERT：更正理由与操作人不可改写';
