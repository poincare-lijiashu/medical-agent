-- ============================================================================
-- 临床核心 V7：数据库级兜底（第二批评审整改）
--
-- 本迁移只做"即使应用层判断被绕过也不会出账务/病历事故"的兜底，不改变业务语义：
--   1) 预交金余额不可为负：任何写入后校验 sum(amount) >= 0（配合应用层 admission 行锁）；
--   2) 护理执行记录：不得预写未来日期，且只能针对"长期医嘱"；
--   3) 计费幂等键与医嘱模式一致：DAILY: 只能对应长嘱，ORDER: 只能对应临时医嘱；
--   4) 在院患者查询补部分索引（全院欠费预警不再全表扫）。
--
-- 时区说明：业务日历日统一按东八区（与 clinical.support.BusinessTime 一致）。
-- ============================================================================

-- ---- 1. 预交金余额非负 ----
CREATE OR REPLACE FUNCTION clinical_svc.check_deposit_balance() RETURNS trigger AS $$
DECLARE
    balance numeric(12, 2);
BEGIN
    SELECT COALESCE(sum(amount), 0) INTO balance
      FROM clinical_svc.deposit
     WHERE admission_id = NEW.admission_id;

    IF balance < 0 THEN
        RAISE EXCEPTION '预交金余额不能为负（admission_id=%, 余额=%）', NEW.admission_id, balance
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_deposit_balance ON clinical_svc.deposit;
CREATE TRIGGER trg_deposit_balance
    AFTER INSERT OR UPDATE OF amount, admission_id ON clinical_svc.deposit
    FOR EACH ROW EXECUTE FUNCTION clinical_svc.check_deposit_balance();

COMMENT ON FUNCTION clinical_svc.check_deposit_balance() IS
    '预交金余额兜底：sum(amount) 不得为负（应用层已按 admission 行锁串行，这里是第二道保险）';

-- ---- 2. 护理执行记录：不得未来日期 + 只能对长嘱 ----
ALTER TABLE clinical_svc.nursing_execution
    DROP CONSTRAINT IF EXISTS ck_nursing_exec_not_future;
ALTER TABLE clinical_svc.nursing_execution
    ADD CONSTRAINT ck_nursing_exec_not_future
    CHECK (exec_date <= ((created_at AT TIME ZONE 'Asia/Shanghai')::date));

CREATE OR REPLACE FUNCTION clinical_svc.check_nursing_execution_order() RETURNS trigger AS $$
DECLARE
    mode text;
BEGIN
    SELECT order_mode INTO mode
      FROM clinical_svc.visit_order
     WHERE id = NEW.order_id;

    IF mode IS NULL THEN
        RAISE EXCEPTION '护理执行记录引用的医嘱不存在：%', NEW.order_id
            USING ERRCODE = 'foreign_key_violation';
    END IF;
    IF mode <> 'LONG' THEN
        RAISE EXCEPTION '只有长期医嘱可以记录逐日执行（order_id=%, mode=%）', NEW.order_id, mode
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_nursing_execution_order ON clinical_svc.nursing_execution;
CREATE TRIGGER trg_nursing_execution_order
    BEFORE INSERT ON clinical_svc.nursing_execution
    FOR EACH ROW EXECUTE FUNCTION clinical_svc.check_nursing_execution_order();

-- ---- 3. 计费幂等键必须与医嘱模式一致 ----
CREATE OR REPLACE FUNCTION clinical_svc.check_charge_item_source() RETURNS trigger AS $$
DECLARE
    mode text;
BEGIN
    IF NEW.order_id IS NULL THEN
        IF NEW.source_key IS NULL OR NEW.source_key NOT LIKE 'REG:%' THEN
            RAISE EXCEPTION '无医嘱的费用明细必须使用 REG: 前缀（source_key=%）', NEW.source_key
                USING ERRCODE = 'check_violation';
        END IF;
        RETURN NEW;
    END IF;

    SELECT order_mode INTO mode FROM clinical_svc.visit_order WHERE id = NEW.order_id;
    IF mode IS NULL THEN
        RAISE EXCEPTION '费用明细引用的医嘱不存在：%', NEW.order_id
            USING ERRCODE = 'foreign_key_violation';
    END IF;

    IF NEW.source_key LIKE 'DAILY:%' AND mode <> 'LONG' THEN
        RAISE EXCEPTION '按日计费（DAILY:）只能对应长期医嘱（order_id=%, mode=%）', NEW.order_id, mode
            USING ERRCODE = 'check_violation';
    ELSIF NEW.source_key LIKE 'ORDER:%' AND mode <> 'TEMP' THEN
        RAISE EXCEPTION '一次性计费（ORDER:）只能对应临时医嘱（order_id=%, mode=%）', NEW.order_id, mode
            USING ERRCODE = 'check_violation';
    ELSIF NEW.source_key NOT LIKE 'DAILY:%' AND NEW.source_key NOT LIKE 'ORDER:%' THEN
        RAISE EXCEPTION '医嘱费用明细的 source_key 必须以 ORDER: 或 DAILY: 开头（source_key=%）', NEW.source_key
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_charge_item_source ON clinical_svc.charge_item;
CREATE TRIGGER trg_charge_item_source
    BEFORE INSERT ON clinical_svc.charge_item
    FOR EACH ROW EXECUTE FUNCTION clinical_svc.check_charge_item_source();

-- ---- 4. 在院患者部分索引（全院欠费预警）----
CREATE INDEX IF NOT EXISTS ix_admission_active
    ON clinical_svc.admission (admitted_at DESC)
    WHERE status IN ('ADMITTED', 'IN_HOSPITAL');

COMMENT ON INDEX clinical_svc.ix_admission_active IS
    '在院患者（ADMITTED/IN_HOSPITAL）部分索引：支撑 GET /inpatient/alerts 全院查询';
