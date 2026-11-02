-- ============================================================================
-- V30：按日计费红冲（DAILY_RETURN）白名单
--
-- 背景（意见书 P1-1）：护理执行更正/漏执行需要把"当日已计费"的钱退回来，
-- 但 V23 的负向明细白名单只允许 DISPENSE_RETURN / ORDER_RETURN，
-- 没有任何入口能对 DAILY:{orderId}:{date} 记红字，于是"未执行不得收费"无法事后修正。
--
-- 本迁移只做一件事：把 DAILY_RETURN:% 加入负金额白名单，
-- 使"按日计费红冲"可以写一条负向明细并同步减少收费单总额（服务层 CAS）。
-- 其余 source_key 规则一概不变。
-- ============================================================================

CREATE OR REPLACE FUNCTION clinical_svc.check_charge_item_source() RETURNS trigger AS $$
DECLARE
    mode text;
BEGIN
    -- 负金额只允许冲减类红字：发药退药 / 医嘱退药 / 按日计费红冲
    IF NEW.amount < 0 THEN
        IF NEW.source_key IS NULL
           OR (NEW.source_key NOT LIKE 'DISPENSE_RETURN:%'
               AND NEW.source_key NOT LIKE 'ORDER_RETURN:%'
               AND NEW.source_key NOT LIKE 'DAILY_RETURN:%') THEN
            RAISE EXCEPTION '负向费用明细只能由退药/红冲产生（source_key=%）', NEW.source_key
                USING ERRCODE = 'check_violation';
        END IF;
        RETURN NEW;
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
    '费用明细 source_key 兜底：REG/DISPENSE/SURGERY 无医嘱；DAILY/ORDER 与医嘱模式一致；负金额仅限冲减类（DISPENSE_RETURN/ORDER_RETURN/DAILY_RETURN）';
