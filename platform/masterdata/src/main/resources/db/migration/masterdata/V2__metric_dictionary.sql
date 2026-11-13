-- ============================================================================
-- V2：指标字典与口径血缘（计划书 W4.5 / Gate G4）
--
-- 解决什么问题
--   同一指标在驾驶舱、月报、医保对账、绩效里各算各的（口径、时间窗、分母不同），
--   出数后没人能回答"这个数按什么口径算的、谁负责、改了会影响哪些报表"。
--   本批把指标变成**有版本、有责任人、有上下游血缘**的治理对象：
--   发布后的口径不可被静默覆盖，修订必须走新版本，影响面可查。
--
-- 语义
--   * metric_definition 保存当前"工作副本"（head），只有 DRAFT 可编辑；
--   * publish 时把 head 快照写入 metric_version（带 SHA-256），并记录
--     published_version / published_at / published_by；
--   * 已发布指标修订 = revise：head 进入下一版本 DRAFT，旧版本仍在历史中，
--     在重新发布前对外口径仍是 published_version；
--   * metric_lineage 记录上下游：UPSTREAM（源表/接口）与 DOWNSTREAM（报表/看板/指标）；
--   * 版本与血缘只增不改（触发器拒绝 UPDATE/DELETE），保证"当时口径"可追溯。
-- ============================================================================

CREATE TABLE IF NOT EXISTS mdm_svc.metric_definition (
    code              text PRIMARY KEY
                      CHECK (code ~ '^[A-Za-z][A-Za-z0-9_]{2,63}$'),
    name              text NOT NULL,
    domain            text NOT NULL
                      CHECK (domain IN ('CLINICAL', 'FINANCE', 'PHARMACY', 'TECH',
                                        'INSURANCE', 'QUALITY', 'AI', 'OPS')),
    definition        text NOT NULL,
    formula           text NOT NULL,
    aggregation       text NOT NULL
                      CHECK (aggregation IN ('SUM', 'COUNT', 'COUNT_DISTINCT', 'AVG',
                                             'RATIO', 'MAX', 'MIN')),
    unit              text NOT NULL,
    time_window       text NOT NULL
                      CHECK (time_window IN ('POINT', 'DAY', 'WEEK', 'MONTH', 'QUARTER',
                                             'YEAR', 'ROLLING_30D')),
    granularity       text NOT NULL
                      CHECK (granularity IN ('HOSPITAL', 'CAMPUS', 'DEPT', 'PATIENT', 'VISIT')),
    source_schema     text,
    source_tables     jsonb NOT NULL DEFAULT '[]'::jsonb
                      CHECK (jsonb_typeof(source_tables) = 'array'),
    owner_dept        text NOT NULL,
    owner_role        text,
    status            text NOT NULL DEFAULT 'DRAFT'
                      CHECK (status IN ('DRAFT', 'PUBLISHED', 'DISABLED')),
    version           bigint NOT NULL DEFAULT 1 CHECK (version > 0),
    published_version bigint,
    published_at      timestamptz,
    published_by      text,
    created_by        text NOT NULL,
    updated_by        text NOT NULL,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_metric_published CHECK (
        (status = 'PUBLISHED'
            AND published_version IS NOT NULL
            AND published_at IS NOT NULL
            AND published_by IS NOT NULL)
        OR status <> 'PUBLISHED'
    )
);
CREATE INDEX IF NOT EXISTS ix_metric_domain_status
    ON mdm_svc.metric_definition (domain, status);
CREATE INDEX IF NOT EXISTS ix_metric_owner
    ON mdm_svc.metric_definition (owner_dept);
COMMENT ON TABLE mdm_svc.metric_definition IS
    '指标字典 head：DRAFT 可编辑；PUBLISHED 只能修订（revise）后再发布；DISABLED 停用但保留历史';

CREATE TABLE IF NOT EXISTS mdm_svc.metric_version (
    id          bigserial PRIMARY KEY,
    metric_code text NOT NULL REFERENCES mdm_svc.metric_definition (code),
    version_no  bigint NOT NULL CHECK (version_no > 0),
    change_type text NOT NULL
                CHECK (change_type IN ('CREATE', 'UPDATE', 'PUBLISH', 'REVISE', 'DISABLE')),
    snapshot    jsonb NOT NULL,
    checksum    text NOT NULL,
    actor       text NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ux_metric_version_event UNIQUE (metric_code, version_no, change_type)
);
CREATE INDEX IF NOT EXISTS ix_metric_version_code
    ON mdm_svc.metric_version (metric_code, version_no DESC);
COMMENT ON TABLE mdm_svc.metric_version IS
    '指标版本链（追加式）：发布快照带 SHA-256，事后可还原"当时口径"';

CREATE TABLE IF NOT EXISTS mdm_svc.metric_lineage (
    id          uuid PRIMARY KEY,
    metric_code text NOT NULL REFERENCES mdm_svc.metric_definition (code),
    direction   text NOT NULL CHECK (direction IN ('UPSTREAM', 'DOWNSTREAM')),
    ref_type    text NOT NULL
                CHECK (ref_type IN ('TABLE', 'API', 'REPORT', 'DASHBOARD', 'METRIC')),
    ref_code    text NOT NULL,
    description text,
    created_by  text NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ux_metric_lineage UNIQUE (metric_code, direction, ref_type, ref_code)
);
CREATE INDEX IF NOT EXISTS ix_metric_lineage_ref
    ON mdm_svc.metric_lineage (direction, ref_type, ref_code);
COMMENT ON TABLE mdm_svc.metric_lineage IS
    '指标血缘：UPSTREAM 源表/接口，DOWNSTREAM 报表/看板/派生指标；只增不改';

-- 版本与血缘是治理证据：禁止修改/删除
CREATE OR REPLACE FUNCTION mdm_svc.guard_metric_history() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION '指标版本与血缘不允许修改或删除（口径必须可追溯）'
        USING ERRCODE = 'insufficient_privilege';
END;
$$ LANGUAGE plpgsql;

-- 审计动作白名单扩展：指标治理动作也要落同一条审计链
ALTER TABLE mdm_svc.md_change_log DROP CONSTRAINT IF EXISTS md_change_log_action_check;
ALTER TABLE mdm_svc.md_change_log
    ADD CONSTRAINT ck_md_change_log_action CHECK (action IN (
        'SYNC', 'IMPORT_PREVIEW', 'IMPORT_APPLY',
        'REQUEST_SUBMIT', 'REQUEST_APPROVE', 'REQUEST_REJECT',
        'REQUEST_CANCEL', 'MAPPING_RESOLVE', 'VERSION_VERIFY',
        'METRIC_CREATE', 'METRIC_UPDATE', 'METRIC_REVISE',
        'METRIC_PUBLISH', 'METRIC_DISABLE', 'METRIC_LINEAGE'
    ));

DROP TRIGGER IF EXISTS trg_metric_version_guard ON mdm_svc.metric_version;
CREATE TRIGGER trg_metric_version_guard
    BEFORE UPDATE OR DELETE ON mdm_svc.metric_version
    FOR EACH ROW EXECUTE FUNCTION mdm_svc.guard_metric_history();

DROP TRIGGER IF EXISTS trg_metric_lineage_guard ON mdm_svc.metric_lineage;
CREATE TRIGGER trg_metric_lineage_guard
    BEFORE UPDATE OR DELETE ON mdm_svc.metric_lineage
    FOR EACH ROW EXECUTE FUNCTION mdm_svc.guard_metric_history();

-- ---------------------------------------------------------------- 基线指标（DRAFT）
-- 只放"已存在报表在用"的指标，避免凭空造口径；发布需信息科/质控审批。

INSERT INTO mdm_svc.metric_definition
    (code, name, domain, definition, formula, aggregation, unit, time_window, granularity,
     source_schema, source_tables, owner_dept, owner_role, status, created_by, updated_by)
VALUES
    ('OUTPATIENT_PAID_AMOUNT', '门诊实收金额', 'FINANCE',
     '统计期内已结算收费单的实收金额合计；按收费时间归属，退费按退费发生额冲减',
     'sum(charge.total_amount) where charge.status = ''PAID'' and paid_at in [start, end)',
     'SUM', '元', 'DAY', 'CAMPUS', 'clinical_svc', '["charge"]', '财务科', '收费处',
     'DRAFT', 'system', 'system'),
    ('OUTPATIENT_VISIT_COUNT', '门诊就诊人次', 'CLINICAL',
     '统计期内实际发生就诊的人次（按就诊主键去重），不含已取消挂号',
     'count(distinct visit.id) where visit.status <> ''CANCELLED''',
     'COUNT_DISTINCT', '人次', 'DAY', 'DEPT', 'clinical_svc', '["visit"]', '医务科', '门诊部',
     'DRAFT', 'system', 'system'),
    ('OUTPATIENT_REFUND_AMOUNT', '门诊退费金额', 'FINANCE',
     '统计期内发生的退费金额合计，按退费时刻归日',
     'sum(refund.amount) where refund.created_at in [start, end)',
     'SUM', '元', 'DAY', 'CAMPUS', 'clinical_svc', '["charge"]', '财务科', '收费处',
     'DRAFT', 'system', 'system'),
    ('INPATIENT_DISCHARGED_COUNT', '出院人次', 'CLINICAL',
     '统计期内办理出院的住院人次；以出院时间归属，不含取消入院',
     'count(admission.id) where admission.status = ''DISCHARGED'' and discharged_at in [start, end)',
     'COUNT', '人次', 'DAY', 'CAMPUS', 'clinical_svc', '["admission"]', '医务科', '住院处',
     'DRAFT', 'system', 'system'),
    ('INPATIENT_AVG_STAY_DAYS', '平均住院日', 'QUALITY',
     '统计期内出院患者的实际住院天数均值（出院时间 - 入院时间，按天向上取整）',
     'avg(ceil(discharged_at - admitted_at)) for discharged admissions in window',
     'AVG', '天', 'MONTH', 'HOSPITAL', 'clinical_svc', '["admission"]', '医务科', '病案室',
     'DRAFT', 'system', 'system'),
    ('BED_OCCUPANCY_PERCENT', '床位使用率', 'QUALITY',
     '时点指标：实际占用床位数 / 可开放床位数 × 100%，不含停用床位',
     'occupied_beds / usable_beds * 100 at point in time',
     'RATIO', '%', 'POINT', 'CAMPUS', 'clinical_svc', '["bed", "admission"]', '护理部', '护理部',
     'DRAFT', 'system', 'system'),
    ('INSURANCE_SETTLEMENT_AMOUNT', '医保结算金额', 'INSURANCE',
     '统计期内已确认医保结算清单的总额合计（含基金与个人自付），作废清单不计入',
     'sum(settlement_bill.total_amount) where status = ''CONFIRMED'' and service_date in [start, end]',
     'SUM', '元', 'DAY', 'CAMPUS', 'insurance_svc', '["settlement_bill"]', '医保办', '医保办',
     'DRAFT', 'system', 'system'),
    ('INSURANCE_RECON_OPEN_DIFFS', '医保对账未处理差异', 'INSURANCE',
     '时点指标：最近一个对账批次中状态为 OPEN 的差异工单数',
     'count(settlement_recon_diff.id) where status = ''OPEN''',
     'COUNT', '条', 'POINT', 'HOSPITAL', 'insurance_svc',
     '["settlement_recon_diff", "settlement_recon_batch"]', '医保办', '医保办',
     'DRAFT', 'system', 'system'),
    ('DRUG_RATIO_PERCENT', '药占比', 'FINANCE',
     '统计期内药品收入 / 医疗收入总额 × 100%；以收费明细类别 DRUG 汇总',
     'sum(drug_amount) / sum(total_amount) * 100',
     'RATIO', '%', 'MONTH', 'HOSPITAL', 'clinical_svc', '["charge_item"]', '财务科', '财务科',
     'DRAFT', 'system', 'system'),
    ('CRITICAL_VALUE_5MIN_RATE', '危急值5分钟通知率', 'QUALITY',
     '统计期内危急值在 5 分钟内通知到临床的比例（通知时间 - 报告时间 ≤ 5 分钟）',
     'notified_within_5min / total_critical_values * 100',
     'RATIO', '%', 'MONTH', 'HOSPITAL', 'tech_svc', '["critical_value"]', '医务科', '质控办',
     'DRAFT', 'system', 'system'),
    ('HANDOVER_SIGN_RATE', '交班签字率', 'QUALITY',
     '统计期内已完成双方签字的交班记录 / 应交班记录总数 × 100%',
     'signed_handovers / total_handovers * 100',
     'RATIO', '%', 'DAY', 'DEPT', 'clinical_svc', '["handover"]', '护理部', '护理部',
     'DRAFT', 'system', 'system'),
    ('TRANSFUSION_CLOSED_LOOP_RATE', '输血闭环完整率', 'QUALITY',
     '统计期内完成申请-配血-发血-输注-观察全流程的输血记录占比',
     'closed_transfusions / total_transfusions * 100',
     'RATIO', '%', 'MONTH', 'HOSPITAL', 'clinical_svc',
     '["transfusion_request", "transfusion_record"]', '输血科', '输血科',
     'DRAFT', 'system', 'system'),
    ('SURGERY_SAFETY_CHECK_RATE', '手术安全核查完成率', 'QUALITY',
     '统计期内完成麻醉前/手术开始前/离室前三时点核查的手术占比',
     'completed_checklists / total_surgeries * 100',
     'RATIO', '%', 'MONTH', 'HOSPITAL', 'clinical_svc', '["surgery_safety_check"]', '医务科', '手术室',
     'DRAFT', 'system', 'system'),
    ('MR_CODING_ACCURACY', '病案编码准确率', 'QUALITY',
     '抽查病案中主诊断与主要手术编码正确的比例；以病案质控复核结果为准',
     'correct_coded_records / sampled_records * 100',
     'RATIO', '%', 'MONTH', 'HOSPITAL', 'emr_svc', '["homepage", "coding_review"]', '病案室', '病案室',
     'DRAFT', 'system', 'system'),
    ('AI_QC_DEFECT_RATE', 'AI质控缺陷率', 'AI',
     'AI 内涵质控发现缺陷的病历数 / 质控病历总数 × 100%；人工复核后以复核结论为准',
     'defect_records / reviewed_records * 100',
     'RATIO', '%', 'MONTH', 'HOSPITAL', 'emr_svc', '["qc_result"]', '质控办', '质控办',
     'DRAFT', 'system', 'system')
ON CONFLICT (code) DO NOTHING;

-- 基线指标的初始版本快照（与定义同源，保证一进来就有可追溯版本）
INSERT INTO mdm_svc.metric_version
    (metric_code, version_no, change_type, snapshot, checksum, actor)
SELECT d.code, 1, 'CREATE', s.snapshot,
       encode(sha256(convert_to(s.snapshot::text, 'UTF8')), 'hex'), 'system'
  FROM mdm_svc.metric_definition d
  CROSS JOIN LATERAL (
        SELECT jsonb_build_object(
            'code', d.code, 'name', d.name, 'domain', d.domain,
            'definition', d.definition, 'formula', d.formula,
            'aggregation', d.aggregation, 'unit', d.unit,
            'timeWindow', d.time_window, 'granularity', d.granularity,
            'sourceSchema', d.source_schema, 'sourceTables', d.source_tables,
            'ownerDept', d.owner_dept, 'ownerRole', d.owner_role,
            'status', d.status, 'version', d.version,
            'publishedVersion', d.published_version) AS snapshot
  ) s
 WHERE d.created_by = 'system'
ON CONFLICT (metric_code, version_no, change_type) DO NOTHING;

-- 基线指标的上游血缘：源表逐个登记（与 source_tables 一致）
INSERT INTO mdm_svc.metric_lineage
    (id, metric_code, direction, ref_type, ref_code, description, created_by)
SELECT gen_random_uuid(), d.code, 'UPSTREAM', 'TABLE',
       COALESCE(d.source_schema || '.', '') || t.value,
       '基线指标声明的源表', 'system'
  FROM mdm_svc.metric_definition d
  CROSS JOIN LATERAL jsonb_array_elements_text(d.source_tables) AS t(value)
 WHERE d.created_by = 'system'
ON CONFLICT (metric_code, direction, ref_type, ref_code) DO NOTHING;
