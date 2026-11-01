-- ============================================================================
-- V3：审计表追加式库级防护（empi）
--
-- 背景（多智能体评审 SHOULD-FIX #6）：审计表此前只有"服务层不提供更新/删除接口"的约定，
-- 直连库（或新增的写入路径）仍可 UPDATE / DELETE 篡改患者变更痕迹。
-- 医疗合规要求审计不可否认，因此把"只允许 INSERT"下沉为数据库触发器。
-- ============================================================================

CREATE OR REPLACE FUNCTION empi_svc.reject_audit_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION '% 是追加式审计表，禁止 %', TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'insufficient_privilege';
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_change_log_append_only ON empi_svc.change_log;
CREATE TRIGGER trg_change_log_append_only
    BEFORE UPDATE OR DELETE ON empi_svc.change_log
    FOR EACH ROW EXECUTE FUNCTION empi_svc.reject_audit_mutation();

COMMENT ON FUNCTION empi_svc.reject_audit_mutation() IS
    '审计表只允许 INSERT：UPDATE/DELETE 一律拒绝（合规要求不可否认）';
