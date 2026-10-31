-- ============================================================================
-- V3：病历书写时限质控
--
-- 三甲要求：入院记录 24 小时内完成、首次病程记录 8 小时内完成。
-- 时限基准取"患者入院时间"（clinical 域），在创建病历时通过内部接口取回并固化到
-- visit_started_at —— 病历域不跨库直查，也不信任前端传入的时间。
--
-- 质控在提交时按 now - visit_started_at 计算；没有住院信息（门诊/未取到）时该规则跳过。
-- ============================================================================

ALTER TABLE emr_svc.medical_record
    ADD COLUMN IF NOT EXISTS visit_started_at timestamptz;

COMMENT ON COLUMN emr_svc.medical_record.visit_started_at IS
    '创建病历时从临床域取回的入院时间，用于书写时限质控；门诊病历为空';

ALTER TABLE emr_svc.qc_rule DROP CONSTRAINT IF EXISTS qc_rule_rule_type_check;
ALTER TABLE emr_svc.qc_rule ADD CONSTRAINT qc_rule_rule_type_check
    CHECK (rule_type IN ('REQUIRED_FIELD', 'MIN_LENGTH', 'SIGNATURE_REQUIRED', 'TIMELINESS'));

INSERT INTO emr_svc.qc_rule
    (id, rule_code, name, doc_type, rule_type, field_key, threshold, severity, message)
VALUES
    ('d4444444-4444-4444-4444-444444444444', 'QC-ADM-TIME', '入院记录 24 小时内完成',
     'ADMISSION_NOTE', 'TIMELINESS', NULL, 24, 'MAJOR',
     '入院记录应在患者入院后 24 小时内完成'),
    ('d5555555-5555-5555-5555-555555555555', 'QC-FC-TIME', '首次病程 8 小时内完成',
     'FIRST_COURSE', 'TIMELINESS', NULL, 8, 'MAJOR',
     '首次病程记录应在患者入院后 8 小时内完成')
ON CONFLICT (rule_code) DO NOTHING;
