-- ============================================================================
-- V4：患者合并日志只允许追加（P1-11 旁证表收口）
--
-- 背景：patient_merge_log 记录"谁把哪两个患者合并成哪一个"，
-- 只有服务层"不提供更新/删除接口"的约定；直连库可 UPDATE/DELETE 抹掉合并痕迹，
-- 事后无法解释"患者主索引为什么变成这个"。复用 V3 已建的 reject_audit_mutation()。
-- ============================================================================

DROP TRIGGER IF EXISTS trg_patient_merge_log_append_only ON empi_svc.patient_merge_log;
CREATE TRIGGER trg_patient_merge_log_append_only
    BEFORE UPDATE OR DELETE ON empi_svc.patient_merge_log
    FOR EACH ROW EXECUTE FUNCTION empi_svc.reject_audit_mutation();

COMMENT ON TRIGGER trg_patient_merge_log_append_only ON empi_svc.patient_merge_log IS
    '患者合并日志只允许 INSERT：合并事实不可改写（数据溯源与不可否认）';
