-- ============================================================================
-- 临床域 V36：转科（变更主诊科室）
--
-- 为什么单独一张表：
--   visit.dept_id 只能保存"当前主诊科室"，改掉之后原科室就无从追溯。
--   转科记录是病案与质控的必查项（"这个患者从哪个科转来、为什么转"），
--   因此每次变更追加一行，永远不覆盖。
--
-- 边界：
--   * 只允许住院且仍在院（admission.status IN ('ADMITTED','IN_HOSPITAL')）的就诊转科；
--   * 目标科室必须是启用中的住院科室（type IN ('INPATIENT','BOTH')）；
--   * 表 append-only：UPDATE / DELETE 一律由触发器拒绝，避免"改历史"。
-- ============================================================================

CREATE TABLE IF NOT EXISTS clinical_svc.visit_transfer (
    id             uuid PRIMARY KEY,
    visit_id       uuid NOT NULL REFERENCES clinical_svc.visit (id),
    from_dept_id   uuid NOT NULL REFERENCES clinical_svc.md_dept (id),
    to_dept_id     uuid NOT NULL REFERENCES clinical_svc.md_dept (id),
    reason         text NOT NULL CHECK (length(trim(reason)) > 0),
    transferred_by text NOT NULL,
    transferred_at timestamptz NOT NULL DEFAULT now(),
    version        bigint NOT NULL DEFAULT 0,
    CONSTRAINT ck_visit_transfer_dept_diff CHECK (from_dept_id <> to_dept_id)
);

COMMENT ON TABLE clinical_svc.visit_transfer IS
    '转科记录（append-only）：每次主诊科室变更追加一行';

CREATE INDEX IF NOT EXISTS ix_visit_transfer_visit
    ON clinical_svc.visit_transfer (visit_id, transferred_at DESC);

CREATE OR REPLACE FUNCTION clinical_svc.guard_visit_transfer_append_only() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION '转科记录不允许修改或删除' USING ERRCODE = 'check_violation';
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_guard_visit_transfer_append_only ON clinical_svc.visit_transfer;
CREATE TRIGGER trg_guard_visit_transfer_append_only
    BEFORE UPDATE OR DELETE ON clinical_svc.visit_transfer
    FOR EACH ROW EXECUTE FUNCTION clinical_svc.guard_visit_transfer_append_only();
