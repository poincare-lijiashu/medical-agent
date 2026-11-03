-- P1-D2：封顶归年不能依赖"确认时刻"。
--
-- 原口径按 confirmed_at 归年（基金实际支付年度），但草稿生成时只能按"今天"估算年度已用额度：
-- 12/31 生成、1/1 确认时两侧年度桶不同 → 重算金额必然不一致 → 清单永久 409"费用已变化"。
--
-- 改为按**就诊业务日期**归年：生成时由临床返回该就诊日期，落库后确认/对账都读同一个值，
-- 保证"生成时 = 复核时 = 对账时"。历史数据按清单创建时刻（业务时区）回填。
ALTER TABLE insurance_svc.settlement_bill
    ADD COLUMN IF NOT EXISTS service_date date;

UPDATE insurance_svc.settlement_bill
   SET service_date = (created_at AT TIME ZONE 'Asia/Shanghai')::date
 WHERE service_date IS NULL;

ALTER TABLE insurance_svc.settlement_bill
    ALTER COLUMN service_date SET NOT NULL;

COMMENT ON COLUMN insurance_svc.settlement_bill.service_date IS
    '就诊业务日期（门诊挂号日/住院出院日）：封顶年度归集与跨年补结算的口径依据，不随确认时刻变化';

-- 年度已用额度查询：patient + 年度 + 已确认
CREATE INDEX IF NOT EXISTS ix_bill_patient_service_date
    ON insurance_svc.settlement_bill (patient_id, service_date)
    WHERE status = 'CONFIRMED';
