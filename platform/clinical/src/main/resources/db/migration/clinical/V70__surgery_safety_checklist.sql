-- ============================================================================
-- V70：手术安全核查三时点（第 265 批，三级医院评审 / 患者安全目标）
--
-- 三个时点（顺序不可颠倒）：
--   PRE_ANESTHESIA 麻醉前：身份、部位标记、术式、知情同意、过敏、气道、失血风险、
--                  影像资料、设备
--   TIME_OUT       手术开始前：团队介绍、患者/部位/术式、预防性抗菌药物、
--                  影像展示、器械灭菌
--   SIGN_OUT       离室前：术式记录、标本标识、器械/纱布清点、设备问题、
--                  恢复计划、失血量记录
--
-- 口径：
--   * 每次提交是一条**新增**证据（追加式，库级触发器拒绝 UPDATE/DELETE），
--     同一时点多次提交以最新一条为准；不做覆盖，避免"事后补勾"；
--   * 核查项模板由服务端固定（代码 + 标签 + 是否必需），客户端只提交勾选了哪些，
--     不能自定义标签，"看起来完整"的伪模板进不了库；
--   * 未完成必需项 → complete=false，开台/离室门禁按 complete 判断。
-- ============================================================================

CREATE TABLE IF NOT EXISTS clinical_svc.surgery_safety_check (
    id            uuid PRIMARY KEY,
    case_id       uuid NOT NULL REFERENCES clinical_svc.surgery_case (id),
    phase         text NOT NULL
                  CHECK (phase IN ('PRE_ANESTHESIA', 'TIME_OUT', 'SIGN_OUT')),
    checked_by    text NOT NULL,
    checked_at    timestamptz NOT NULL DEFAULT now(),
    items         jsonb NOT NULL DEFAULT '[]'::jsonb,
    missing_items jsonb NOT NULL DEFAULT '[]'::jsonb,
    issues        text,
    complete      boolean NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS ix_surgery_safety_check_case
    ON clinical_svc.surgery_safety_check (case_id, phase, checked_at DESC);

COMMENT ON TABLE clinical_svc.surgery_safety_check IS
    '手术安全核查三时点证据：只允许 INSERT，同一时点以最新一条为当前结论';

DROP TRIGGER IF EXISTS trg_surgery_safety_check_append_only
    ON clinical_svc.surgery_safety_check;
CREATE TRIGGER trg_surgery_safety_check_append_only
    BEFORE UPDATE OR DELETE ON clinical_svc.surgery_safety_check
    FOR EACH ROW EXECUTE FUNCTION clinical_svc.reject_audit_mutation();
