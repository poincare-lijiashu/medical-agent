-- ============================================================================
-- V16：参保变更历史只允许追加（P1-11 旁证表收口）
--
-- 背景：insurance_member_history 记录参保状态/报销比例/封顶额的历史变更，
-- 是"某天按什么比例结算"的唯一依据。此前只有服务层"只 INSERT"的约定（实测无 UPDATE/DELETE），
-- 直连库改写历史会让已结算清单的测算口径无法复现，飞检时无法自证。
-- 复用 V14 已建的 reject_audit_mutation()。
-- ============================================================================

DROP TRIGGER IF EXISTS trg_member_history_append_only ON insurance_svc.insurance_member_history;
CREATE TRIGGER trg_member_history_append_only
    BEFORE UPDATE OR DELETE ON insurance_svc.insurance_member_history
    FOR EACH ROW EXECUTE FUNCTION insurance_svc.reject_audit_mutation();

COMMENT ON TRIGGER trg_member_history_append_only ON insurance_svc.insurance_member_history IS
    '参保变更历史只允许 INSERT：历史比例/封顶额不可改写（结算口径可复现）';
