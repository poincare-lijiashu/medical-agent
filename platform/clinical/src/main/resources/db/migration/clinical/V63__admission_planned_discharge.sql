-- 预计出院日（第 252 批）
--
-- 为什么加：出院协调目前只有“能不能走”（出院预检），没有“计划什么时候走”，
-- 护士站无法回答“今天预出院几人 / 谁已经超期”。该列只用于计划与预警，不参与出院门禁。
--
-- 可为空：历史数据与急诊入院本来就没有计划日；强制必填会让入院流程多一步无意义输入。
ALTER TABLE clinical_svc.admission
    ADD COLUMN planned_discharge_date date;

COMMENT ON COLUMN clinical_svc.admission.planned_discharge_date IS
    '预计出院日（业务日期，仅用于床位计划与超期预警，不阻断出院）';

-- 病区按计划日筛选（今日预出院 / 超期未出院）只关心在院患者；
-- 部分索引避免把已出院的历史行也装进来。
CREATE INDEX ix_admission_planned_discharge
    ON clinical_svc.admission (ward_id, planned_discharge_date)
    WHERE status IN ('ADMITTED', 'IN_HOSPITAL')
      AND planned_discharge_date IS NOT NULL;
