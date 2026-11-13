-- ============================================================================
-- V4：临床域数据质量规则（评测 D-23）
--
-- 背景：原有 4 条规则只覆盖 MASTER_DATA / METRIC 两个域、COMPLETENESS / TIMELINESS
--       两个维度，临床业务表（医嘱、护理执行、危急值）没有任何质量探针，
--       "医嘱开了没执行""危急值没人通知"这类问题只能靠人眼看报表。
--
-- 规则一律是只读单值探针（SELECT），由 DataQualityService.assertSafeSql 白名单校验，
-- 失败自动开问题单并累加次数；阈值按临床可接受口径设定，不按当前实测值回填。
-- 幂等：ON CONFLICT (code) DO NOTHING，重复执行不覆盖治理员调过的阈值。
-- ============================================================================

INSERT INTO mdm_svc.md_dq_rule
    (id, code, name, domain, dimension, sql_text, comparator, threshold, threshold2, unit,
     severity, owner, status, created_by)
VALUES
    -- 1) 已收费的药品/治疗医嘱没有执行记录：护士站漏执行或执行未回写
    ('b1000000-0000-4000-8000-000000000101', 'DQ-CLINICAL-EXEC-MISSING',
     '已收费医嘱无执行记录占比', 'CLINICAL', 'COMPLETENESS',
     'SELECT CASE WHEN count(*) = 0 THEN 0 ELSE round(100.0 * count(*) FILTER (WHERE e.order_id IS NULL) / count(*), 4) END FROM clinical_svc.visit_order o LEFT JOIN (SELECT DISTINCT order_id FROM clinical_svc.nursing_execution) e ON e.order_id = o.id WHERE o.status = ''CHARGED'' AND o.order_type IN (''DRUG'', ''TREATMENT'')',
     'LTE', 10, NULL, 'PERCENT', 'HIGH', 'clinical-steward', 'ACTIVE', 'system'),

    -- 2) 执行记录指向不存在的医嘱：外键缺失导致的脏数据（孤儿行）
    ('b1000000-0000-4000-8000-000000000102', 'DQ-CLINICAL-EXEC-ORPHAN',
     '执行记录无对应医嘱数量', 'CLINICAL', 'CONSISTENCY',
     'SELECT count(*) FROM clinical_svc.nursing_execution e WHERE e.order_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM clinical_svc.visit_order o WHERE o.id = e.order_id)',
     'LTE', 0, NULL, 'COUNT', 'HIGH', 'clinical-steward', 'ACTIVE', 'system'),

    -- 3) 危急值未通知：报告已出但 notified_at 一直为空（近 90 天）
    ('b1000000-0000-4000-8000-000000000103', 'DQ-CLINICAL-CRITICAL-NOTIFY',
     '危急值超时未通知占比', 'CLINICAL', 'TIMELINESS',
     'SELECT CASE WHEN count(*) = 0 THEN 0 ELSE round(100.0 * count(*) FILTER (WHERE notified_at IS NULL) / count(*), 4) END FROM tech_svc.critical_value WHERE created_at >= now() - interval ''90 days''',
     'LTE', 5, NULL, 'PERCENT', 'HIGH', 'clinical-steward', 'ACTIVE', 'system'),

    -- 4) 药品医嘱缺剂量/频次：给药五要素不全，护士无法核对
    ('b1000000-0000-4000-8000-000000000104', 'DQ-CLINICAL-DOSE-ROUTE',
     '药品医嘱缺剂量或频次占比', 'CLINICAL', 'COMPLETENESS',
     'SELECT CASE WHEN count(*) = 0 THEN 0 ELSE round(100.0 * count(*) FILTER (WHERE coalesce(dose, '''') = '''' OR coalesce(frequency, '''') = '''') / count(*), 4) END FROM clinical_svc.visit_order WHERE order_type = ''DRUG'' AND status <> ''CANCELLED''',
     'LTE', 2, NULL, 'PERCENT', 'MEDIUM', 'clinical-steward', 'ACTIVE', 'system')
ON CONFLICT (code) DO NOTHING;
