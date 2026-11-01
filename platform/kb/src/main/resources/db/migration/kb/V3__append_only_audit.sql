-- ============================================================================
-- V3：审计表追加式库级防护（kb）
--
-- 背景（多智能体评审 SHOULD-FIX #6）：kb_change_log / kb_answer_log 只靠服务层约定不可改，
-- 直连库仍可篡改空间与文档的写操作痕迹，以及"无证据拒答"的问答审计。
-- ============================================================================

CREATE OR REPLACE FUNCTION kb_svc.reject_audit_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION '% 是追加式审计表，禁止 %', TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'insufficient_privilege';
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_kb_change_log_append_only ON kb_svc.kb_change_log;
CREATE TRIGGER trg_kb_change_log_append_only
    BEFORE UPDATE OR DELETE ON kb_svc.kb_change_log
    FOR EACH ROW EXECUTE FUNCTION kb_svc.reject_audit_mutation();

DROP TRIGGER IF EXISTS trg_kb_answer_log_append_only ON kb_svc.kb_answer_log;
CREATE TRIGGER trg_kb_answer_log_append_only
    BEFORE UPDATE OR DELETE ON kb_svc.kb_answer_log
    FOR EACH ROW EXECUTE FUNCTION kb_svc.reject_audit_mutation();

COMMENT ON FUNCTION kb_svc.reject_audit_mutation() IS
    '审计表只允许 INSERT：UPDATE/DELETE 一律拒绝（合规要求不可否认）';
