-- ============================================================================
-- V4：参保历史（追加式）
--
-- 背景（第三轮评测 S-12）：
--   * insurance_member 是"当前状态"表，按 patientId UPSERT 覆盖，改过什么没有痕迹；
--   * 停用（DISABLED）后客户端只要不传 status，就会被默认值 ACTIVE "复活"，
--     相当于把医保停用变成了可被任意覆盖的弱状态。
--
-- 处理：
--   1) 新增 insurance_member_history 追加式历史表（只插不改），记录每次创建/变更/停用/启用；
--   2) 应用层规则：已停用的参保必须**显式**提交 status=ACTIVE 才能恢复（留痕为 ENABLE），
--      未显式提交的一律 409。
-- ============================================================================

CREATE TABLE IF NOT EXISTS insurance_svc.insurance_member_history (
    id                  uuid PRIMARY KEY,
    member_id           uuid NOT NULL,
    patient_id          uuid NOT NULL,
    patient_no          text NOT NULL,
    insurance_type      text NOT NULL,
    region              text NOT NULL,
    person_category     text,
    deductible          numeric(12, 2) NOT NULL,
    reimbursement_ratio numeric(6, 4) NOT NULL,
    cap                 numeric(12, 2) NOT NULL,
    effective_from      date NOT NULL,
    effective_to        date,
    status              text NOT NULL,
    version             bigint NOT NULL,
    change_type         text NOT NULL CHECK (change_type IN ('CREATE', 'UPDATE', 'DISABLE', 'ENABLE')),
    changed_by          text NOT NULL,
    changed_at          timestamptz NOT NULL DEFAULT now(),
    note                text
);

CREATE INDEX IF NOT EXISTS ix_member_history_patient
    ON insurance_svc.insurance_member_history (patient_id, changed_at DESC);

CREATE INDEX IF NOT EXISTS ix_member_history_member
    ON insurance_svc.insurance_member_history (member_id, changed_at DESC);

COMMENT ON TABLE insurance_svc.insurance_member_history IS
    '参保信息变更历史（追加式，只插入不更新），用于追溯待遇参数与启停状态变化';
