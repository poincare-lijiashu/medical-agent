-- ============================================================================
-- 临床核心 V6：住院中途结算（收费单续开）+ 护理执行记录
--
-- 1) 收费单原先"一次就诊一张"（visit_id 全局唯一），住院中途结算后收费单被置为 PAID，
--    后续产生的费用无处可记。本迁移改为"每个就诊最多一张未结（UNPAID）收费单"：
--    中途结算完成后，下一次计费自动续开新单；出院时要求不存在有金额的未结单。
-- 2) 护理执行记录：长嘱按日计费前核对"实际执行"，REFUSED/OMITTED 的医嘱当日不计费，
--    实现"实际执行 vs 计费"对账。唯一键 (order_id, exec_date) 保证重复提交不会双记。
-- ============================================================================

-- ---- 收费单：一就诊多单（未结唯一）----
ALTER TABLE clinical_svc.charge DROP CONSTRAINT IF EXISTS charge_visit_id_key;

CREATE UNIQUE INDEX IF NOT EXISTS ux_charge_visit_unpaid
    ON clinical_svc.charge (visit_id) WHERE status = 'UNPAID';

CREATE INDEX IF NOT EXISTS ix_charge_visit
    ON clinical_svc.charge (visit_id, created_at);

COMMENT ON INDEX clinical_svc.ux_charge_visit_unpaid IS
    '每个就诊最多一张未结收费单（中途结算后续开新单，由该部分唯一索引兜底）';

-- ---- 护理执行记录（追加式，不提供修改接口）----
CREATE TABLE IF NOT EXISTS clinical_svc.nursing_execution (
    id           uuid PRIMARY KEY,
    admission_id uuid NOT NULL REFERENCES clinical_svc.admission (id),
    visit_id     uuid NOT NULL REFERENCES clinical_svc.visit (id),
    order_id     uuid NOT NULL REFERENCES clinical_svc.visit_order (id),
    exec_date    date NOT NULL,
    status       text NOT NULL CHECK (status IN ('EXECUTED', 'REFUSED', 'OMITTED')),
    executor     text NOT NULL,
    note         text,
    created_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ux_nursing_execution_order_date UNIQUE (order_id, exec_date)
);

CREATE INDEX IF NOT EXISTS ix_nursing_execution_visit
    ON clinical_svc.nursing_execution (visit_id, exec_date);
CREATE INDEX IF NOT EXISTS ix_nursing_execution_admission
    ON clinical_svc.nursing_execution (admission_id, exec_date);

COMMENT ON TABLE clinical_svc.nursing_execution IS
    '护理执行记录（EXECUTED 已执行 / REFUSED 患者拒绝 / OMITTED 未执行；按日计费据此核对）';
