-- ============================================================================
-- 医保 V12：分组模拟的输入来源留痕
--
-- 背景：模拟预分组原来只接受调用方手工传入诊断/手术编码，容易与病案首页不一致。
-- 本批让服务端默认从临床域取就诊诊断、从病案首页取手术操作；来源必须落库，
-- 否则事后无法解释"这次入组用的是系统数据还是人工录入"。
--
-- 取值：
--   diagnoses_source: MANUAL（请求显式传入）/ CLINICAL（临床就诊诊断）/ NONE（都没有）
--   procedures_source: MANUAL（请求显式传入）/ HOMEPAGE（病案首页手术操作）/ NONE
-- ============================================================================

ALTER TABLE insurance_svc.payment_group_simulation
    ADD COLUMN IF NOT EXISTS diagnoses_source text NOT NULL DEFAULT 'MANUAL';
ALTER TABLE insurance_svc.payment_group_simulation
    ADD COLUMN IF NOT EXISTS procedures_source text NOT NULL DEFAULT 'MANUAL';

ALTER TABLE insurance_svc.payment_group_simulation
    DROP CONSTRAINT IF EXISTS ck_simulation_diagnoses_source;
ALTER TABLE insurance_svc.payment_group_simulation
    ADD CONSTRAINT ck_simulation_diagnoses_source
    CHECK (diagnoses_source IN ('MANUAL', 'CLINICAL', 'NONE'));

ALTER TABLE insurance_svc.payment_group_simulation
    DROP CONSTRAINT IF EXISTS ck_simulation_procedures_source;
ALTER TABLE insurance_svc.payment_group_simulation
    ADD CONSTRAINT ck_simulation_procedures_source
    CHECK (procedures_source IN ('MANUAL', 'HOMEPAGE', 'NONE'));

COMMENT ON COLUMN insurance_svc.payment_group_simulation.diagnoses_source IS
    '诊断来源：MANUAL 请求传入 / CLINICAL 临床域就诊诊断 / NONE 无诊断';
COMMENT ON COLUMN insurance_svc.payment_group_simulation.procedures_source IS
    '手术来源：MANUAL 请求传入 / HOMEPAGE 病案首页手术操作 / NONE 无手术';
