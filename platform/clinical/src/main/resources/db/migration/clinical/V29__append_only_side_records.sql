-- ============================================================================
-- V29：护理执行与病区库存流水只允许追加（P1-11 旁证表收口）
--
-- 背景：
--   * nursing_execution 是"护士是否执行/拒执行"的事实记录，是"未执行不得收费"的依据，
--     也是护理质量与不良事件的追溯来源；
--   * ward_stock_txn 是病区二级库存的流水（收药/退药/报损），余额由流水推导。
-- 两张表此前只有服务层"只 INSERT"的约定（实测代码中无任何 UPDATE/DELETE），
-- 直连库改写或删除会让护理执行记录与库存余额同时失真。
-- 复用 V22 已建的 reject_audit_mutation()。
-- ============================================================================

DROP TRIGGER IF EXISTS trg_nursing_execution_append_only ON clinical_svc.nursing_execution;
CREATE TRIGGER trg_nursing_execution_append_only
    BEFORE UPDATE OR DELETE ON clinical_svc.nursing_execution
    FOR EACH ROW EXECUTE FUNCTION clinical_svc.reject_audit_mutation();

DROP TRIGGER IF EXISTS trg_ward_stock_txn_append_only ON clinical_svc.ward_stock_txn;
CREATE TRIGGER trg_ward_stock_txn_append_only
    BEFORE UPDATE OR DELETE ON clinical_svc.ward_stock_txn
    FOR EACH ROW EXECUTE FUNCTION clinical_svc.reject_audit_mutation();

COMMENT ON TRIGGER trg_nursing_execution_append_only ON clinical_svc.nursing_execution IS
    '护理执行记录只允许 INSERT：执行/拒执行事实不可改写（未执行不得收费的举证依据）';
COMMENT ON TRIGGER trg_ward_stock_txn_append_only ON clinical_svc.ward_stock_txn IS
    '病区库存流水只允许 INSERT：余额由流水推导，改写流水等于篡改账实';
