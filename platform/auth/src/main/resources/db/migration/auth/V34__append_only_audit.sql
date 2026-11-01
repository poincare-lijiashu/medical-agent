-- ============================================================================
-- V34：审计表追加式库级防护（auth）
--
-- 背景（多智能体评审 SHOULD-FIX #6）：审计表此前只有"服务层不提供更新/删除接口"的约定，
-- 直连库仍可 UPDATE / DELETE 篡改登录、授权与账号变更痕迹。
-- 把"只允许 INSERT"下沉为数据库触发器，任何写入路径都绕不过去。
-- ============================================================================

CREATE OR REPLACE FUNCTION auth_svc.reject_audit_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION '% 是追加式审计表，禁止 %', TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'insufficient_privilege';
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_change_log_append_only ON auth_svc.change_log;
CREATE TRIGGER trg_change_log_append_only
    BEFORE UPDATE OR DELETE ON auth_svc.change_log
    FOR EACH ROW EXECUTE FUNCTION auth_svc.reject_audit_mutation();

COMMENT ON FUNCTION auth_svc.reject_audit_mutation() IS
    '审计表只允许 INSERT：UPDATE/DELETE 一律拒绝（合规要求不可否认）';
