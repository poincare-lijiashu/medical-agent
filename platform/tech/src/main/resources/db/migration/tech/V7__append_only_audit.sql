-- ============================================================================
-- V7：审计表追加式库级防护（tech）
--
-- 背景（多智能体评审 SHOULD-FIX #6）：tech_change_log 只靠服务层约定不可改，
-- 直连库仍可篡改标本流转、报告签发与危急值闭环痕迹。
-- ============================================================================

CREATE OR REPLACE FUNCTION tech_svc.reject_audit_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION '% 是追加式审计表，禁止 %', TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'insufficient_privilege';
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_tech_change_log_append_only ON tech_svc.tech_change_log;
CREATE TRIGGER trg_tech_change_log_append_only
    BEFORE UPDATE OR DELETE ON tech_svc.tech_change_log
    FOR EACH ROW EXECUTE FUNCTION tech_svc.reject_audit_mutation();

COMMENT ON FUNCTION tech_svc.reject_audit_mutation() IS
    '审计表只允许 INSERT：UPDATE/DELETE 一律拒绝（合规要求不可否认）';
