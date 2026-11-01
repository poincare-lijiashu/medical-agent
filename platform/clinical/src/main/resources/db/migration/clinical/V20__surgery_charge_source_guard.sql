-- ============================================================================
-- 临床域 V20：允许"无医嘱的手术打包费用明细"
--
-- 背景：V7/V10 的 check_charge_item_source 要求"没有 order_id 的费用明细"必须是
--   REG:（挂号）或 DISPENSE:（发药）前缀。术中收费打包生成的明细同样没有医嘱，
--   幂等键是 SURGERY:{caseId}:{itemCode}，因此需要把 SURGERY: 纳入白名单。
--
-- 为什么用新迁移而不是改 V10：V10 已在真实库执行，Flyway 校验会拒绝改动历史脚本；
-- 约束类兜底一律走"新版本替换函数"，历史版本保持原样可追溯。
--
-- 只放宽前缀白名单，其余规则（负金额仅限退药冲减、医嘱模式与 DAILY/ORDER 一致）不变。
-- ============================================================================

CREATE OR REPLACE FUNCTION clinical_svc.check_charge_item_source() RETURNS trigger AS $$
DECLARE
    mode text;
BEGIN
    -- 负金额只允许退药冲减（红字），防止绕过应用层写负数
    IF NEW.amount < 0
       AND (NEW.source_key IS NULL OR NEW.source_key NOT LIKE 'DISPENSE_RETURN:%') THEN
        RAISE EXCEPTION '负向费用明细只能由退药冲减产生（source_key=%）', NEW.source_key
            USING ERRCODE = 'check_violation';
    END IF;

    IF NEW.order_id IS NULL THEN
        IF NEW.source_key IS NULL
           OR (NEW.source_key NOT LIKE 'REG:%'
               AND NEW.source_key NOT LIKE 'DISPENSE:%'
               AND NEW.source_key NOT LIKE 'SURGERY:%') THEN
            RAISE EXCEPTION '无医嘱的费用明细必须使用 REG: / DISPENSE: / SURGERY: 前缀（source_key=%）', NEW.source_key
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

COMMENT ON FUNCTION clinical_svc.check_charge_item_source() IS
    '费用明细 source_key 兜底：REG/DISPENSE/SURGERY 无医嘱；DAILY/ORDER 与医嘱模式一致；负金额仅限退药冲减';
