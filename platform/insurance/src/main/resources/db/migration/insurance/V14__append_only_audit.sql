-- ============================================================================
-- V14：审计表追加式库级防护（insurance）
--
-- 背景（多智能体评审 SHOULD-FIX #6）：insurance_change_log 只靠服务层约定不可改，
-- 直连库仍可篡改清单确认、报盘批次与回执登记痕迹（涉及医保基金结算，属资金红线）。
-- ============================================================================

CREATE OR REPLACE FUNCTION insurance_svc.reject_audit_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION '% 是追加式审计表，禁止 %', TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'insufficient_privilege';
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_insurance_change_log_append_only ON insurance_svc.insurance_change_log;
CREATE TRIGGER trg_insurance_change_log_append_only
    BEFORE UPDATE OR DELETE ON insurance_svc.insurance_change_log
    FOR EACH ROW EXECUTE FUNCTION insurance_svc.reject_audit_mutation();

COMMENT ON FUNCTION insurance_svc.reject_audit_mutation() IS
    '审计表只允许 INSERT：UPDATE/DELETE 一律拒绝（合规要求不可否认）';
