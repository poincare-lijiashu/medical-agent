-- ============================================================================
-- V7：审计表追加式库级防护（pharmacy）
--
-- 背景（多智能体评审 SHOULD-FIX #6）：pharmacy_change_log 与 prescription_audit
-- 此前只有"服务层不提供更新/删除接口"的约定，直连库仍可篡改处方审核与发退药痕迹，
-- 麻醉药品专册的旁证链会被一并污染。
-- ============================================================================

CREATE OR REPLACE FUNCTION pharmacy_svc.reject_audit_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION '% 是追加式审计表，禁止 %', TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'insufficient_privilege';
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_pharmacy_change_log_append_only ON pharmacy_svc.pharmacy_change_log;
CREATE TRIGGER trg_pharmacy_change_log_append_only
    BEFORE UPDATE OR DELETE ON pharmacy_svc.pharmacy_change_log
    FOR EACH ROW EXECUTE FUNCTION pharmacy_svc.reject_audit_mutation();

DROP TRIGGER IF EXISTS trg_prescription_audit_append_only ON pharmacy_svc.prescription_audit;
CREATE TRIGGER trg_prescription_audit_append_only
    BEFORE UPDATE OR DELETE ON pharmacy_svc.prescription_audit
    FOR EACH ROW EXECUTE FUNCTION pharmacy_svc.reject_audit_mutation();

-- 麻醉药品专册：V6 只挡了"改事实字段"，仍可整行 DELETE 抹掉登记。
-- 法定专册必须可追溯，因此删除一律拒绝（回收状态更新走 UPDATE，不受影响）。
DROP TRIGGER IF EXISTS trg_narcotic_register_no_delete ON pharmacy_svc.narcotic_register;
CREATE TRIGGER trg_narcotic_register_no_delete
    BEFORE DELETE ON pharmacy_svc.narcotic_register
    FOR EACH ROW EXECUTE FUNCTION pharmacy_svc.reject_audit_mutation();

COMMENT ON FUNCTION pharmacy_svc.reject_audit_mutation() IS
    '审计表只允许 INSERT：UPDATE/DELETE 一律拒绝（合规要求不可否认）';
