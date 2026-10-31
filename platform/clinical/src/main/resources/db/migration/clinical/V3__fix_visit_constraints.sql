-- ============================================================================
-- V3：住院引入后的两处约束修正（只前进，不改已应用的 V1/V2）
--
-- 1) visit.visit_type 的 CHECK 只允许 OUTPATIENT/EMERGENCY，住院就诊（INPATIENT）被数据库拒绝
--    ——真实联调暴露；此处放宽为三类就诊类型。
-- 2) "同日同科室重复就诊" 唯一索引原先对门诊与住院一视同仁，导致"上午门诊、下午收住院"被误判。
--    正确语义：门诊重复挂号由该索引拦截（仅 OUTPATIENT）；
--    住院重复入院由 admission 的 ux_admission_patient_active 拦截（与门诊互不影响）。
-- ============================================================================

ALTER TABLE clinical_svc.visit DROP CONSTRAINT IF EXISTS visit_visit_type_check;
ALTER TABLE clinical_svc.visit
    ADD CONSTRAINT visit_visit_type_check
    CHECK (visit_type IN ('OUTPATIENT', 'EMERGENCY', 'INPATIENT'));

DROP INDEX IF EXISTS clinical_svc.ux_visit_active;

CREATE UNIQUE INDEX IF NOT EXISTS ux_visit_active
    ON clinical_svc.visit (patient_id, dept_id, visit_date)
    WHERE status IN ('REGISTERED', 'IN_TREATMENT') AND visit_type = 'OUTPATIENT';
