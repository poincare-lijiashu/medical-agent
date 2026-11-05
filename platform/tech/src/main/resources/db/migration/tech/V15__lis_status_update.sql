-- ============================================================================
-- V15：LIS 入站消息允许"处理状态"更新，原文与幂等键仍不可改（G67，评审 §3.5/§3.6）
--
-- V8 给 lis_inbound_message 挂了 reject_audit_mutation()，UPDATE/DELETE 一律拒绝。
-- 但 §3.5 要求"先 INSERT PROCESSING 占位 → 处理副作用 → UPDATE 回写结果"，
-- 原样保留会直接把回写打成 500。
--
-- 口径调整：报文原文（raw_message/message_hash/message_control_id/sending_app/
-- application_no/actor/received_at）仍然**一字不可改**，DELETE 仍然禁止；只放开
-- 状态机列（status / error_message / application_id / report_id）。
-- 这样"接口争议时能证明原文是什么"的审计承诺不变，同时占位-回写-重放可以落地。
-- ============================================================================

CREATE OR REPLACE FUNCTION tech_svc.guard_lis_message_update() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'lis_inbound_message 是入站留档，禁止 DELETE';
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id
       OR NEW.message_control_id IS DISTINCT FROM OLD.message_control_id
       OR NEW.message_type IS DISTINCT FROM OLD.message_type
       OR NEW.sending_app IS DISTINCT FROM OLD.sending_app
       OR NEW.application_no IS DISTINCT FROM OLD.application_no
       OR NEW.raw_message IS DISTINCT FROM OLD.raw_message
       OR NEW.message_hash IS DISTINCT FROM OLD.message_hash
       OR NEW.actor IS DISTINCT FROM OLD.actor
       OR NEW.received_at IS DISTINCT FROM OLD.received_at THEN
        RAISE EXCEPTION 'LIS 入站报文只允许更新处理状态，原文与幂等键不可改';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_lis_inbound_append_only ON tech_svc.lis_inbound_message;
CREATE TRIGGER trg_lis_inbound_state_guard
    BEFORE UPDATE OR DELETE ON tech_svc.lis_inbound_message
    FOR EACH ROW EXECUTE FUNCTION tech_svc.guard_lis_message_update();

COMMENT ON TRIGGER trg_lis_inbound_state_guard ON tech_svc.lis_inbound_message IS
    'LIS 入站报文：原文/幂等键不可改、禁止删除；仅允许更新处理状态（PROCESSING/PROCESSED/IGNORED/FAILED）';
