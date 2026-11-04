-- ============================================================================
-- V48：临床路径（G29）
--
-- pathway_template：路径模板，DRAFT → PUBLISHED → DISABLED。
--   发布后模板内容不可修改（只能停用后新建版本），保证已入径患者的节点快照可追溯。
-- pathway_node：模板节点（第 N 天第 M 项）。
-- pathway_instance：入径实例，ACTIVE → COMPLETED / EXITED；
--   同一住院就诊只允许一条 ACTIVE 实例（部分唯一索引兜底）。
-- pathway_task：入径时按节点生成的任务快照（名称/分类/必做），PENDING → DONE / SKIPPED。
--   跳过即「变异」，必须写原因；任务表与模板解耦，模板后续停用不影响在途路径。
-- ============================================================================

CREATE TABLE IF NOT EXISTS clinical_svc.pathway_template (
    id             uuid PRIMARY KEY,
    code           text NOT NULL,
    name           text NOT NULL,
    diagnosis_code text,
    dept_id        uuid REFERENCES clinical_svc.md_dept (id),
    day_count      integer NOT NULL DEFAULT 1 CHECK (day_count BETWEEN 1 AND 60),
    status         text NOT NULL DEFAULT 'DRAFT'
                   CHECK (status IN ('DRAFT', 'PUBLISHED', 'DISABLED')),
    created_by     text NOT NULL,
    published_by   text,
    published_at   timestamptz,
    version        bigint NOT NULL DEFAULT 0,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ux_pathway_template_code UNIQUE (code),
    CONSTRAINT ck_pathway_template_published
        CHECK (status <> 'PUBLISHED' OR (published_by IS NOT NULL AND published_at IS NOT NULL))
);

CREATE TABLE IF NOT EXISTS clinical_svc.pathway_node (
    id          uuid PRIMARY KEY,
    template_id uuid NOT NULL REFERENCES clinical_svc.pathway_template (id) ON DELETE CASCADE,
    day_no      integer NOT NULL CHECK (day_no BETWEEN 1 AND 60),
    seq         integer NOT NULL CHECK (seq BETWEEN 1 AND 99),
    name        text NOT NULL,
    category    text NOT NULL
                CHECK (category IN ('ASSESSMENT', 'ORDER', 'NURSING', 'EXAM', 'SURGERY', 'OTHER')),
    required    boolean NOT NULL DEFAULT true,
    description text,
    CONSTRAINT ux_pathway_node_slot UNIQUE (template_id, day_no, seq)
);

CREATE SEQUENCE IF NOT EXISTS clinical_svc.pathway_instance_no_seq;

CREATE TABLE IF NOT EXISTS clinical_svc.pathway_instance (
    id           uuid PRIMARY KEY,
    instance_no  text NOT NULL,
    template_id  uuid NOT NULL REFERENCES clinical_svc.pathway_template (id),
    visit_id     uuid NOT NULL REFERENCES clinical_svc.visit (id),
    patient_id   uuid NOT NULL,
    patient_no   text,
    dept_id      uuid REFERENCES clinical_svc.md_dept (id),
    status       text NOT NULL DEFAULT 'ACTIVE'
                 CHECK (status IN ('ACTIVE', 'COMPLETED', 'EXITED')),
    current_day  integer NOT NULL DEFAULT 1 CHECK (current_day BETWEEN 1 AND 60),
    start_date   date NOT NULL,
    end_date     date,
    exit_reason  text,
    created_by   text NOT NULL,
    version      bigint NOT NULL DEFAULT 0,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ux_pathway_instance_no UNIQUE (instance_no),
    CONSTRAINT ck_pathway_instance_end
        CHECK (status = 'ACTIVE' OR (end_date IS NOT NULL AND (status <> 'EXITED' OR exit_reason IS NOT NULL)))
);

-- 同一住院就诊只允许一条进行中的路径
CREATE UNIQUE INDEX IF NOT EXISTS ux_pathway_instance_active_visit
    ON clinical_svc.pathway_instance (visit_id)
    WHERE status = 'ACTIVE';

CREATE INDEX IF NOT EXISTS ix_pathway_instance_status
    ON clinical_svc.pathway_instance (status, created_at DESC);

CREATE TABLE IF NOT EXISTS clinical_svc.pathway_task (
    id          uuid PRIMARY KEY,
    instance_id uuid NOT NULL REFERENCES clinical_svc.pathway_instance (id) ON DELETE CASCADE,
    node_id     uuid REFERENCES clinical_svc.pathway_node (id),
    day_no      integer NOT NULL,
    seq         integer NOT NULL,
    name        text NOT NULL,
    category    text NOT NULL,
    required    boolean NOT NULL DEFAULT true,
    status      text NOT NULL DEFAULT 'PENDING'
                CHECK (status IN ('PENDING', 'DONE', 'SKIPPED')),
    done_by     text,
    done_at     timestamptz,
    note        text,
    created_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ux_pathway_task_slot UNIQUE (instance_id, day_no, seq),
    CONSTRAINT ck_pathway_task_done
        CHECK (status = 'PENDING' OR (done_by IS NOT NULL AND done_at IS NOT NULL))
);

CREATE INDEX IF NOT EXISTS ix_pathway_task_instance
    ON clinical_svc.pathway_task (instance_id, day_no, seq);

COMMENT ON TABLE clinical_svc.pathway_template IS
    '临床路径模板：发布后内容不可改，已入径患者按任务快照执行';
COMMENT ON TABLE clinical_svc.pathway_task IS
    '路径任务快照：PENDING/DONE/SKIPPED，跳过必须写原因（变异）';

-- ---- 演示模板：社区获得性肺炎（成人）7 天路径 ----
INSERT INTO clinical_svc.pathway_template
    (id, code, name, diagnosis_code, day_count, status, created_by, published_by, published_at)
VALUES
    ('c9a00000-0000-0000-0000-000000000001', 'CP-PNEUMONIA', '社区获得性肺炎（成人）',
     'J18.900', 7, 'PUBLISHED', 'system', 'system', now())
ON CONFLICT (code) DO NOTHING;

INSERT INTO clinical_svc.pathway_node (id, template_id, day_no, seq, name, category, required, description)
VALUES
    (gen_random_uuid(), 'c9a00000-0000-0000-0000-000000000001', 1, 1, '入院评估与病情分级', 'ASSESSMENT', true, '记录生命体征、氧合与 CURB-65 评分'),
    (gen_random_uuid(), 'c9a00000-0000-0000-0000-000000000001', 1, 2, '血常规 + CRP + 降钙素原', 'EXAM', true, null),
    (gen_random_uuid(), 'c9a00000-0000-0000-0000-000000000001', 1, 3, '胸部正侧位影像', 'EXAM', true, null),
    (gen_random_uuid(), 'c9a00000-0000-0000-0000-000000000001', 1, 4, '经验性抗感染医嘱', 'ORDER', true, '按指南选择并在 4 小时内给药'),
    (gen_random_uuid(), 'c9a00000-0000-0000-0000-000000000001', 2, 1, '病情与用药反应评估', 'ASSESSMENT', true, null),
    (gen_random_uuid(), 'c9a00000-0000-0000-0000-000000000001', 2, 2, '抗感染治疗执行', 'NURSING', true, null),
    (gen_random_uuid(), 'c9a00000-0000-0000-0000-000000000001', 3, 1, '疗效评估', 'ASSESSMENT', true, '体温、呼吸症状与炎症指标趋势'),
    (gen_random_uuid(), 'c9a00000-0000-0000-0000-000000000001', 3, 2, '复查血常规 + CRP', 'EXAM', false, null),
    (gen_random_uuid(), 'c9a00000-0000-0000-0000-000000000001', 5, 1, '疗效再评估', 'ASSESSMENT', true, null),
    (gen_random_uuid(), 'c9a00000-0000-0000-0000-000000000001', 5, 2, '胸部影像复查', 'EXAM', false, '无加重可不行复查'),
    (gen_random_uuid(), 'c9a00000-0000-0000-0000-000000000001', 7, 1, '出院评估', 'ASSESSMENT', true, '符合出院标准方可出院'),
    (gen_random_uuid(), 'c9a00000-0000-0000-0000-000000000001', 7, 2, '出院带药医嘱', 'ORDER', true, '口服序贯治疗，注明疗程'),
    (gen_random_uuid(), 'c9a00000-0000-0000-0000-000000000001', 7, 3, '出院宣教与随访安排', 'NURSING', true, null)
ON CONFLICT (template_id, day_no, seq) DO NOTHING;
