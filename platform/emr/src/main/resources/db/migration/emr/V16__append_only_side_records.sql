-- ============================================================================
-- V16：病历修改记录与签名事实只允许追加（P1-11 旁证表收口）
--
-- 背景：
--   * record_amendment 记录"归档病历改了哪一处、谁批的、改前改后是什么"，
--     是病历法律效力的举证链；
--   * record_signature 是"谁在什么时候对哪份病历签了名"的事实。
-- 两者此前只有服务层"只 INSERT"的约定（实测无 UPDATE/DELETE），
-- 直连库可改写签名时间或删掉修改记录，等同于伪造/销毁病历证据。
-- 复用 V15 已建的 reject_audit_mutation()。
-- ============================================================================

DROP TRIGGER IF EXISTS trg_record_amendment_append_only ON emr_svc.record_amendment;
CREATE TRIGGER trg_record_amendment_append_only
    BEFORE UPDATE OR DELETE ON emr_svc.record_amendment
    FOR EACH ROW EXECUTE FUNCTION emr_svc.reject_audit_mutation();

DROP TRIGGER IF EXISTS trg_record_signature_append_only ON emr_svc.record_signature;
CREATE TRIGGER trg_record_signature_append_only
    BEFORE UPDATE OR DELETE ON emr_svc.record_signature
    FOR EACH ROW EXECUTE FUNCTION emr_svc.reject_audit_mutation();

COMMENT ON TRIGGER trg_record_amendment_append_only ON emr_svc.record_amendment IS
    '病历修改记录只允许 INSERT：改前改后与审批人不可抹除';
COMMENT ON TRIGGER trg_record_signature_append_only ON emr_svc.record_signature IS
    '病历签名事实只允许 INSERT：签名人/时间/证书不可改写';
