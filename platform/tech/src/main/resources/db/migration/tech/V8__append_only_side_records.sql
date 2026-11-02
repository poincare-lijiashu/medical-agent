-- ============================================================================
-- V8：LIS 入站报文只允许追加（P1-11 旁证表收口）
--
-- 背景：lis_inbound_message 保存检验科室推送的原始报文，是"结果什么时候到、原文是什么"
-- 的唯一凭据（接口争议、结果回溯都依赖它）。此前只有服务层"只 INSERT"的约定（实测无 UPDATE/DELETE）。
-- 复用 V7 已建的 reject_audit_mutation()。
-- ============================================================================

DROP TRIGGER IF EXISTS trg_lis_inbound_append_only ON tech_svc.lis_inbound_message;
CREATE TRIGGER trg_lis_inbound_append_only
    BEFORE UPDATE OR DELETE ON tech_svc.lis_inbound_message
    FOR EACH ROW EXECUTE FUNCTION tech_svc.reject_audit_mutation();

COMMENT ON TRIGGER trg_lis_inbound_append_only ON tech_svc.lis_inbound_message IS
    'LIS 入站报文只允许 INSERT：原始报文不可改写（接口争议与结果回溯凭据）';
