-- ============================================================================
-- 临床域 V35：会诊申请（跨科室只读授权的业务来源）
--
-- 为什么需要这张表：
--   行级权限默认"医师只看本科室"，但会诊是制度刚需——受邀科室的医师必须能在
--   会诊期间读到主诊科室这位患者的就诊、医嘱、诊断，否则会诊只能靠打电话问。
--   在权限系统里，跨科室可见不是"放宽规则"，而是"由业务事实授予的一次性授权"，
--   这个事实就是本表的 (visit_id, to_dept_id) 且状态为 REQUESTED / ACCEPTED。
--
-- 状态机：
--   REQUESTED --接受--> ACCEPTED --完成--> COMPLETED（终态）
--        \--拒绝--> REJECTED（终态）
--        \--撤回--> CANCELLED（终态）
--   ACCEPTED 也可被主诊科室撤回（患者转院/病情变化）。
--
-- 授权边界：
--   * 只有 ACTIVE（REQUESTED / ACCEPTED）记录才授予只读；
--   * 授权范围是"该就诊的只读"，不含写：受邀医师不能替主诊科室下医嘱、收费；
--   * 完成后立即收回（COMPLETED 不再是 ACTIVE），避免"一次会诊永久可见"。
-- ============================================================================

CREATE SEQUENCE IF NOT EXISTS clinical_svc.consultation_no_seq;

CREATE TABLE IF NOT EXISTS clinical_svc.consultation_request (
    id              uuid PRIMARY KEY,
    consultation_no text NOT NULL UNIQUE,
    visit_id        uuid NOT NULL REFERENCES clinical_svc.visit (id),
    patient_id      uuid NOT NULL,
    patient_no      text NOT NULL,
    from_dept_id    uuid NOT NULL,
    to_dept_id      uuid NOT NULL,
    reason          text NOT NULL CHECK (length(trim(reason)) > 0),
    status          text NOT NULL DEFAULT 'REQUESTED'
                    CHECK (status IN ('REQUESTED', 'ACCEPTED', 'REJECTED', 'COMPLETED', 'CANCELLED')),
    requested_by    text NOT NULL,
    requested_at    timestamptz NOT NULL DEFAULT now(),
    responded_by    text,
    responded_at    timestamptz,
    rejected_reason text,
    completed_by    text,
    completed_at    timestamptz,
    conclusion      text,
    cancelled_by    text,
    cancelled_at    timestamptz,
    cancelled_reason text,
    version         bigint NOT NULL DEFAULT 0,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_consultation_dept_diff CHECK (from_dept_id <> to_dept_id),
    CONSTRAINT ck_consultation_reject CHECK (
        (status = 'REJECTED' AND responded_by IS NOT NULL AND responded_at IS NOT NULL)
        OR status <> 'REJECTED'
    ),
    CONSTRAINT ck_consultation_complete CHECK (
        (status = 'COMPLETED' AND completed_by IS NOT NULL AND completed_at IS NOT NULL)
        OR status <> 'COMPLETED'
    )
);

COMMENT ON TABLE clinical_svc.consultation_request IS
    '会诊申请：ACTIVE（REQUESTED/ACCEPTED）期间向受邀科室授予该就诊的只读权限';

-- 同一就诊对同一受邀科室只允许一条进行中的会诊，避免重复申请与授权叠加
CREATE UNIQUE INDEX IF NOT EXISTS ux_consultation_active
    ON clinical_svc.consultation_request (visit_id, to_dept_id)
    WHERE status IN ('REQUESTED', 'ACCEPTED');

CREATE INDEX IF NOT EXISTS ix_consultation_visit
    ON clinical_svc.consultation_request (visit_id, status);

CREATE INDEX IF NOT EXISTS ix_consultation_inbox
    ON clinical_svc.consultation_request (to_dept_id, status, requested_at DESC);

-- 终态冻结：完成后不允许再改，避免"已完成"被改回进行中变相延续授权
CREATE OR REPLACE FUNCTION clinical_svc.guard_consultation_terminal() RETURNS trigger AS $$
BEGIN
    IF OLD.status IN ('REJECTED', 'COMPLETED', 'CANCELLED') AND NEW.status <> OLD.status THEN
        RAISE EXCEPTION '会诊已结束，状态不能再变更' USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_guard_consultation_terminal ON clinical_svc.consultation_request;
CREATE TRIGGER trg_guard_consultation_terminal
    BEFORE UPDATE ON clinical_svc.consultation_request
    FOR EACH ROW EXECUTE FUNCTION clinical_svc.guard_consultation_terminal();
