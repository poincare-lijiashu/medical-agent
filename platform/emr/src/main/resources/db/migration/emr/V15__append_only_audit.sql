-- ============================================================================
-- V15：审计表追加式库级防护（emr）
--
-- 背景（多智能体评审 SHOULD-FIX #6）：emr_change_log 只靠服务层约定不可改，
-- 直连库仍可篡改病历书写、签名、归档与病案修改审批痕迹（电子病历分级评价硬要求）。
-- ============================================================================

CREATE OR REPLACE FUNCTION emr_svc.reject_audit_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION '% 是追加式审计表，禁止 %', TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'insufficient_privilege';
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_emr_change_log_append_only ON emr_svc.emr_change_log;
CREATE TRIGGER trg_emr_change_log_append_only
    BEFORE UPDATE OR DELETE ON emr_svc.emr_change_log
    FOR EACH ROW EXECUTE FUNCTION emr_svc.reject_audit_mutation();

COMMENT ON FUNCTION emr_svc.reject_audit_mutation() IS
    '审计表只允许 INSERT：UPDATE/DELETE 一律拒绝（合规要求不可否认）';
