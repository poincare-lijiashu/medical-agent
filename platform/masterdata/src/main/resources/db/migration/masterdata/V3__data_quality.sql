-- ============================================================================
-- V3：数据质量规则、执行与问题闭环（第 299 批 / 计划书 G4）
--
-- 目标：指标字典与主数据"有定义"不等于"有质量"。本批把质量规则变成可执行探针：
--   * 规则是声明式 SQL 探针（SELECT/WITH，单值结果），由治理员维护；
--   * 每次执行留运行记录（PASSED/FAILED/ERROR + 观测值 + 耗时）；
--   * 失败自动开问题单，同一规则只保留一个未闭环问题，重复失败累加次数；
--   * 关闭问题必须写解决方案；豁免必须写原因并由数据治理负责人（mdm:approve）批准。
--
-- 安全边界：探针只允许只读 SQL（服务端正则白名单 + 查询超时），
-- 规则维护与执行分离授权；探针结果只取第一列数值，不返回患者明文。
-- ============================================================================

CREATE TABLE IF NOT EXISTS mdm_svc.md_dq_rule (
    id          uuid PRIMARY KEY,
    code        text NOT NULL UNIQUE,
    name        text NOT NULL,
    domain      text NOT NULL
                CHECK (domain IN ('MASTER_DATA', 'METRIC', 'CLINICAL', 'INTEGRATION', 'FINANCE')),
    dimension   text NOT NULL
                CHECK (dimension IN ('COMPLETENESS', 'UNIQUENESS', 'TIMELINESS',
                                     'CONSISTENCY', 'REFERENTIAL', 'VALIDITY', 'VOLUME')),
    sql_text    text NOT NULL,
    comparator  text NOT NULL CHECK (comparator IN ('LTE', 'GTE', 'EQ', 'BETWEEN')),
    threshold   numeric(18, 4) NOT NULL,
    threshold2  numeric(18, 4),
    unit        text NOT NULL DEFAULT 'PERCENT'
                CHECK (unit IN ('PERCENT', 'COUNT', 'MINUTES')),
    severity    text NOT NULL DEFAULT 'MEDIUM'
                CHECK (severity IN ('BLOCKER', 'HIGH', 'MEDIUM', 'LOW')),
    owner       text NOT NULL,
    status      text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE')),
    version     bigint NOT NULL DEFAULT 0,
    created_by  text NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_md_dq_rule_threshold CHECK (
        (comparator = 'BETWEEN' AND threshold2 IS NOT NULL AND threshold2 >= threshold)
        OR comparator <> 'BETWEEN'
    )
);
COMMENT ON TABLE mdm_svc.md_dq_rule IS
    '数据质量规则：SQL 探针只允许 SELECT/WITH 且查询超时；结果取第一列数值';

CREATE SEQUENCE IF NOT EXISTS mdm_svc.md_dq_run_no_seq START 1;

CREATE TABLE IF NOT EXISTS mdm_svc.md_dq_run (
    id             uuid PRIMARY KEY,
    run_no         text NOT NULL UNIQUE,
    rule_id        uuid NOT NULL REFERENCES mdm_svc.md_dq_rule (id) ON DELETE CASCADE,
    rule_code      text NOT NULL,
    status         text NOT NULL CHECK (status IN ('PASSED', 'FAILED', 'ERROR')),
    observed_value numeric(18, 4),
    passed         boolean NOT NULL DEFAULT false,
    error_message  text,
    run_mode       text NOT NULL DEFAULT 'MANUAL' CHECK (run_mode IN ('MANUAL', 'SCHEDULED')),
    triggered_by   text NOT NULL,
    duration_ms    integer NOT NULL DEFAULT 0 CHECK (duration_ms >= 0),
    started_at     timestamptz NOT NULL,
    finished_at    timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS ix_md_dq_run_rule ON mdm_svc.md_dq_run (rule_id, started_at DESC);
CREATE INDEX IF NOT EXISTS ix_md_dq_run_status ON mdm_svc.md_dq_run (status, started_at DESC);
COMMENT ON TABLE mdm_svc.md_dq_run IS
    '质量执行记录：观测值、通过与否、错误与耗时；规则修改不回溯历史';

CREATE SEQUENCE IF NOT EXISTS mdm_svc.md_dq_issue_no_seq START 1;

CREATE TABLE IF NOT EXISTS mdm_svc.md_dq_issue (
    id               uuid PRIMARY KEY,
    issue_no         text NOT NULL UNIQUE,
    rule_id          uuid NOT NULL REFERENCES mdm_svc.md_dq_rule (id) ON DELETE CASCADE,
    rule_code        text NOT NULL,
    rule_name        text NOT NULL,
    severity         text NOT NULL CHECK (severity IN ('BLOCKER', 'HIGH', 'MEDIUM', 'LOW')),
    status           text NOT NULL DEFAULT 'OPEN'
                     CHECK (status IN ('OPEN', 'ACKNOWLEDGED', 'RESOLVED', 'WAIVED')),
    owner            text NOT NULL,
    first_run_id     uuid NOT NULL REFERENCES mdm_svc.md_dq_run (id),
    last_run_id      uuid NOT NULL REFERENCES mdm_svc.md_dq_run (id),
    occurrence_count integer NOT NULL DEFAULT 1 CHECK (occurrence_count >= 1),
    observed_value   numeric(18, 4),
    acknowledged_by  text,
    acknowledged_at  timestamptz,
    resolution       text,
    resolved_at      timestamptz,
    waiver_reason    text,
    waiver_by        text,
    waiver_at        timestamptz,
    version          bigint NOT NULL DEFAULT 0,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_md_dq_issue_resolved CHECK (
        (status = 'RESOLVED' AND resolution IS NOT NULL AND resolved_at IS NOT NULL)
        OR status <> 'RESOLVED'
    ),
    CONSTRAINT ck_md_dq_issue_waived CHECK (
        (status = 'WAIVED' AND waiver_reason IS NOT NULL AND waiver_by IS NOT NULL
            AND waiver_at IS NOT NULL)
        OR status <> 'WAIVED'
    )
);
CREATE INDEX IF NOT EXISTS ix_md_dq_issue_status
    ON mdm_svc.md_dq_issue (status, severity, updated_at DESC);
-- 同一规则同时只允许一个未闭环问题：重复失败累加次数，而不是刷屏
CREATE UNIQUE INDEX IF NOT EXISTS ux_md_dq_issue_open
    ON mdm_svc.md_dq_issue (rule_id) WHERE status IN ('OPEN', 'ACKNOWLEDGED');
COMMENT ON TABLE mdm_svc.md_dq_issue IS
    '质量问题：失败自动开单；解决/豁免必须留原因与责任人';

-- ---------------------------------------------------------------------------
-- 内置规则（可直接手动执行；也可由调度器周期执行）
-- ---------------------------------------------------------------------------
INSERT INTO mdm_svc.md_dq_rule
    (id, code, name, domain, dimension, sql_text, comparator, threshold, threshold2, unit,
     severity, owner, status, created_by)
VALUES
    ('b1000000-0000-4000-8000-000000000001', 'DQ-MDM-MAPPING', '主数据外部映射缺失率',
     'MASTER_DATA', 'COMPLETENESS',
     'SELECT CASE WHEN count(*) = 0 THEN 0 ELSE round(100.0 * count(*) FILTER (WHERE m.id IS NULL) / count(*), 4) END FROM mdm_svc.md_item i LEFT JOIN mdm_svc.md_external_map m ON m.domain_code = i.domain_code AND m.local_code = i.local_code AND m.status = ''ACTIVE'' WHERE i.status = ''ACTIVE''',
     'LTE', 5, NULL, 'PERCENT', 'HIGH', 'data-steward', 'ACTIVE', 'system'),
    ('b1000000-0000-4000-8000-000000000002', 'DQ-METRIC-PUBLISH', '指标未发布占比',
     'METRIC', 'COMPLETENESS',
     'SELECT CASE WHEN count(*) = 0 THEN 0 ELSE round(100.0 * count(*) FILTER (WHERE published_version IS NULL) / count(*), 4) END FROM mdm_svc.metric_definition WHERE status <> ''DISABLED''',
     'LTE', 10, NULL, 'PERCENT', 'MEDIUM', 'data-steward', 'ACTIVE', 'system'),
    ('b1000000-0000-4000-8000-000000000003', 'DQ-METRIC-LINEAGE', '已发布指标无上游血缘占比',
     'METRIC', 'COMPLETENESS',
     'SELECT CASE WHEN count(*) = 0 THEN 0 ELSE round(100.0 * count(*) FILTER (WHERE l.code IS NULL) / count(*), 4) END FROM mdm_svc.metric_definition m LEFT JOIN (SELECT DISTINCT metric_code AS code FROM mdm_svc.metric_lineage WHERE direction = ''UPSTREAM'') l ON l.code = m.code WHERE m.status = ''PUBLISHED''',
     'LTE', 20, NULL, 'PERCENT', 'MEDIUM', 'data-steward', 'ACTIVE', 'system'),
    ('b1000000-0000-4000-8000-000000000004', 'DQ-MDM-SYNC-STALE', '可同步主数据域超期未同步数量',
     'MASTER_DATA', 'TIMELINESS',
     'SELECT count(*) FROM mdm_svc.md_domain WHERE syncable = true AND enabled = true AND (last_synced_at IS NULL OR last_synced_at < now() - interval ''2 days'')',
     'LTE', 0, NULL, 'COUNT', 'HIGH', 'data-steward', 'ACTIVE', 'system')
ON CONFLICT (code) DO NOTHING;

-- ---------------------------------------------------------------------------
-- 审计动作扩展
-- ---------------------------------------------------------------------------
ALTER TABLE mdm_svc.md_change_log DROP CONSTRAINT IF EXISTS md_change_log_action_check;
ALTER TABLE mdm_svc.md_change_log DROP CONSTRAINT IF EXISTS ck_md_change_log_action;
ALTER TABLE mdm_svc.md_change_log ADD CONSTRAINT ck_md_change_log_action CHECK (
    action IN ('SYNC', 'IMPORT_PREVIEW', 'IMPORT_APPLY',
               'REQUEST_SUBMIT', 'REQUEST_APPROVE', 'REQUEST_REJECT',
               'REQUEST_CANCEL', 'MAPPING_RESOLVE', 'VERSION_VERIFY',
               'METRIC_CREATE', 'METRIC_UPDATE', 'METRIC_REVISE',
               'METRIC_PUBLISH', 'METRIC_DISABLE', 'METRIC_LINEAGE',
               'DQ_RULE_UPSERT', 'DQ_RUN', 'DQ_ISSUE_ACK',
               'DQ_ISSUE_RESOLVE', 'DQ_ISSUE_WAIVE')
);
