-- ============================================================================
-- V5：实施交付、培训考核与上线就绪（第 298 批 / 计划书 G7）
--
-- 目标：把"实施方法论可复制、培训考核可量化"从文档变成可运行的系统能力。
--
-- 设计要点：
--   * 模板（阶段 + 任务）是方法论；项目实例在创建时**快照**任务，模板后续修改
--     不影响在建项目，避免"上线前任务清单被改掉"；
--   * 任务完成必须有证据；必填任务未完成则项目不具备上线条件；
--     例外只能走豁免（WAIVED，必须写原因并留复核人），且计入就绪报告；
--   * 培训课程有题库，考试按项目开考；成绩自动判分，通过率按"唯一学员"统计；
--   * 上线/验收是四眼动作：项目负责人不能自己批准上线或验收；
--   * 项目事件追加式，数据库触发器拒绝 UPDATE/DELETE。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. 实施模板
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ops_svc.impl_template (
    id          uuid PRIMARY KEY,
    code        text NOT NULL UNIQUE,
    name        text NOT NULL,
    description text,
    status      text NOT NULL DEFAULT 'ACTIVE'
                CHECK (status IN ('ACTIVE', 'INACTIVE')),
    version     bigint NOT NULL DEFAULT 0,
    created_by  text NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE ops_svc.impl_template IS
    '实施模板：阶段与任务的方法论定义；项目创建时复制为实例任务';

CREATE TABLE IF NOT EXISTS ops_svc.impl_template_phase (
    id          uuid PRIMARY KEY,
    template_id uuid NOT NULL REFERENCES ops_svc.impl_template (id) ON DELETE CASCADE,
    phase_no    integer NOT NULL CHECK (phase_no BETWEEN 1 AND 20),
    name        text NOT NULL,
    weight      integer NOT NULL CHECK (weight BETWEEN 1 AND 100),
    description text,
    CONSTRAINT ux_impl_template_phase UNIQUE (template_id, phase_no)
);
COMMENT ON TABLE ops_svc.impl_template_phase IS
    '模板阶段：权重用于就绪评分，全部阶段权重之和应为 100';

CREATE TABLE IF NOT EXISTS ops_svc.impl_template_task (
    id            uuid PRIMARY KEY,
    template_id   uuid NOT NULL REFERENCES ops_svc.impl_template (id) ON DELETE CASCADE,
    phase_id      uuid NOT NULL REFERENCES ops_svc.impl_template_phase (id) ON DELETE CASCADE,
    task_no       integer NOT NULL CHECK (task_no BETWEEN 1 AND 999),
    title         text NOT NULL,
    description   text,
    required      boolean NOT NULL DEFAULT true,
    owner_role    text,
    evidence_hint text,
    CONSTRAINT ux_impl_template_task UNIQUE (template_id, task_no)
);
CREATE INDEX IF NOT EXISTS ix_impl_template_task_phase
    ON ops_svc.impl_template_task (phase_id);
COMMENT ON TABLE ops_svc.impl_template_task IS
    '模板任务：required=true 的任务未完成会阻塞上线（除非有豁免）';

-- ---------------------------------------------------------------------------
-- 2. 实施项目与任务实例
-- ---------------------------------------------------------------------------
CREATE SEQUENCE IF NOT EXISTS ops_svc.impl_project_no_seq START 1;

CREATE TABLE IF NOT EXISTS ops_svc.impl_project (
    id                    uuid PRIMARY KEY,
    project_no            text NOT NULL UNIQUE,
    template_id           uuid NOT NULL REFERENCES ops_svc.impl_template (id),
    template_code         text NOT NULL,
    template_name         text NOT NULL,
    tenant_code           text,
    hospital_name         text NOT NULL,
    status                text NOT NULL DEFAULT 'PLANNED'
                          CHECK (status IN ('PLANNED', 'IN_PROGRESS', 'GO_LIVE',
                                            'ACCEPTED', 'CANCELLED')),
    owner                 text NOT NULL,
    start_date            date,
    target_date           date,
    go_live_at            timestamptz,
    approved_by           text,
    acceptance_evidence   text,
    accepted_at           timestamptz,
    cancel_reason         text,
    required_course_codes text[] NOT NULL DEFAULT '{}',
    version               bigint NOT NULL DEFAULT 0,
    created_at            timestamptz NOT NULL DEFAULT now(),
    updated_at            timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_impl_project_go_live CHECK (
        (status IN ('GO_LIVE', 'ACCEPTED') AND go_live_at IS NOT NULL AND approved_by IS NOT NULL)
        OR status NOT IN ('GO_LIVE', 'ACCEPTED')
    ),
    CONSTRAINT ck_impl_project_accept CHECK (
        (status = 'ACCEPTED' AND accepted_at IS NOT NULL AND acceptance_evidence IS NOT NULL)
        OR status <> 'ACCEPTED'
    ),
    CONSTRAINT ck_impl_project_cancel CHECK (
        (status = 'CANCELLED' AND cancel_reason IS NOT NULL)
        OR status <> 'CANCELLED'
    )
);
CREATE INDEX IF NOT EXISTS ix_impl_project_status
    ON ops_svc.impl_project (status, created_at DESC);
COMMENT ON TABLE ops_svc.impl_project IS
    '实施项目：模板快照 + 院区 + 负责人；上线与验收必须由非负责人复核';

CREATE TABLE IF NOT EXISTS ops_svc.impl_project_task (
    id            uuid PRIMARY KEY,
    project_id    uuid NOT NULL REFERENCES ops_svc.impl_project (id) ON DELETE CASCADE,
    phase_no      integer NOT NULL,
    phase_name    text NOT NULL,
    phase_weight  integer NOT NULL,
    task_no       integer NOT NULL,
    title         text NOT NULL,
    required      boolean NOT NULL DEFAULT true,
    owner_role    text,
    evidence_hint text,
    status        text NOT NULL DEFAULT 'PENDING'
                  CHECK (status IN ('PENDING', 'IN_PROGRESS', 'BLOCKED', 'DONE', 'WAIVED')),
    assignee      text,
    due_date      date,
    evidence      text,
    blocked_reason text,
    waiver_reason text,
    waiver_by     text,
    waiver_at     timestamptz,
    completed_by  text,
    completed_at  timestamptz,
    version       bigint NOT NULL DEFAULT 0,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ux_impl_project_task UNIQUE (project_id, task_no),
    CONSTRAINT ck_impl_project_task_done CHECK (
        status <> 'DONE'
        OR (evidence IS NOT NULL AND length(btrim(evidence)) > 0
            AND completed_at IS NOT NULL AND completed_by IS NOT NULL)
    ),
    CONSTRAINT ck_impl_project_task_blocked CHECK (
        status <> 'BLOCKED'
        OR (blocked_reason IS NOT NULL AND length(btrim(blocked_reason)) > 0)
    ),
    CONSTRAINT ck_impl_project_task_waived CHECK (
        status <> 'WAIVED'
        OR (waiver_reason IS NOT NULL AND length(btrim(waiver_reason)) > 0
            AND waiver_by IS NOT NULL AND waiver_at IS NOT NULL)
    )
);
CREATE INDEX IF NOT EXISTS ix_impl_project_task_status
    ON ops_svc.impl_project_task (project_id, status);
COMMENT ON TABLE ops_svc.impl_project_task IS
    '项目任务：完成必须有证据；阻塞必须写原因；豁免必须写原因与复核人';

CREATE TABLE IF NOT EXISTS ops_svc.impl_project_event (
    id         bigserial PRIMARY KEY,
    project_id uuid NOT NULL REFERENCES ops_svc.impl_project (id) ON DELETE CASCADE,
    ts         timestamptz NOT NULL DEFAULT now(),
    actor      text NOT NULL,
    action     text NOT NULL CHECK (action IN ('CREATE', 'START', 'TASK_UPDATE', 'TASK_WAIVE',
                                               'GO_LIVE', 'ACCEPT', 'CANCEL', 'COMMENT')),
    note       text,
    detail     jsonb,
    trace_id   text
);
CREATE INDEX IF NOT EXISTS ix_impl_project_event
    ON ops_svc.impl_project_event (project_id, ts DESC);
COMMENT ON TABLE ops_svc.impl_project_event IS
    '项目事件：状态与任务动作的追加式轨迹';

DROP TRIGGER IF EXISTS trg_impl_project_event_append_only ON ops_svc.impl_project_event;
CREATE TRIGGER trg_impl_project_event_append_only
    BEFORE UPDATE OR DELETE ON ops_svc.impl_project_event
    FOR EACH ROW EXECUTE FUNCTION ops_svc.reject_audit_mutation();

-- ---------------------------------------------------------------------------
-- 3. 培训课程、题库、考试与成绩
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ops_svc.impl_course (
    id               uuid PRIMARY KEY,
    code             text NOT NULL UNIQUE,
    title            text NOT NULL,
    category         text NOT NULL DEFAULT 'SYSTEM'
                     CHECK (category IN ('ROLE', 'SYSTEM', 'PROCESS')),
    required         boolean NOT NULL DEFAULT true,
    duration_minutes integer NOT NULL DEFAULT 60
                     CHECK (duration_minutes BETWEEN 1 AND 1440),
    description      text,
    status           text NOT NULL DEFAULT 'ACTIVE'
                     CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_by       text NOT NULL,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    version          bigint NOT NULL DEFAULT 0
);
COMMENT ON TABLE ops_svc.impl_course IS
    '培训课程：required=true 且被项目引用时，通过率纳入上线就绪';

CREATE TABLE IF NOT EXISTS ops_svc.impl_question (
    id          uuid PRIMARY KEY,
    course_id   uuid NOT NULL REFERENCES ops_svc.impl_course (id) ON DELETE CASCADE,
    question_no integer NOT NULL CHECK (question_no BETWEEN 1 AND 999),
    stem        text NOT NULL,
    options     jsonb NOT NULL,
    answer_key  text NOT NULL,
    score       integer NOT NULL DEFAULT 10 CHECK (score BETWEEN 1 AND 100),
    status      text NOT NULL DEFAULT 'ACTIVE'
                CHECK (status IN ('ACTIVE', 'INACTIVE')),
    CONSTRAINT ux_impl_question UNIQUE (course_id, question_no),
    CONSTRAINT ck_impl_question_options CHECK (jsonb_typeof(options) = 'object')
);
COMMENT ON TABLE ops_svc.impl_question IS
    '题库：options 为 {"A":"...","B":"..."}，answer_key 必须是其中一个选项键';

CREATE SEQUENCE IF NOT EXISTS ops_svc.impl_exam_no_seq START 1;

CREATE TABLE IF NOT EXISTS ops_svc.impl_exam (
    id          uuid PRIMARY KEY,
    exam_no     text NOT NULL UNIQUE,
    project_id  uuid NOT NULL REFERENCES ops_svc.impl_project (id) ON DELETE CASCADE,
    course_id   uuid NOT NULL REFERENCES ops_svc.impl_course (id),
    course_code text NOT NULL,
    title       text NOT NULL,
    pass_score  integer NOT NULL DEFAULT 80 CHECK (pass_score BETWEEN 1 AND 100),
    status      text NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'CLOSED')),
    created_by  text NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    version     bigint NOT NULL DEFAULT 0,
    CONSTRAINT ux_impl_exam UNIQUE (project_id, course_code)
);
COMMENT ON TABLE ops_svc.impl_exam IS
    '项目考试：一门课程在一个项目内只有一场考试，便于通过率口径统一';

CREATE TABLE IF NOT EXISTS ops_svc.impl_attempt (
    id            uuid PRIMARY KEY,
    exam_id       uuid NOT NULL REFERENCES ops_svc.impl_exam (id) ON DELETE CASCADE,
    trainee       text NOT NULL,
    answers       jsonb NOT NULL,
    score         integer NOT NULL CHECK (score BETWEEN 0 AND 100),
    passed        boolean NOT NULL,
    attempt_count integer NOT NULL DEFAULT 1 CHECK (attempt_count >= 1),
    submitted_by  text NOT NULL,
    submitted_at  timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ux_impl_attempt UNIQUE (exam_id, trainee)
);
CREATE INDEX IF NOT EXISTS ix_impl_attempt_exam ON ops_svc.impl_attempt (exam_id);
COMMENT ON TABLE ops_svc.impl_attempt IS
    '考试成绩：同一学员同一考试只保留最新一次成绩，attempt_count 记录重考次数';

-- ---------------------------------------------------------------------------
-- 4. 内置标准实施模板（可直接创建项目；院方可自建模板）
-- ---------------------------------------------------------------------------
INSERT INTO ops_svc.impl_template (id, code, name, description, status, created_by)
VALUES ('a1000000-0000-4000-8000-000000000001', 'STD-HOSP-v1', '标准院内实施模板',
        '适用于三级医院整体上线：启动、环境、数据集成、培训、试运行、上线移交六阶段',
        'ACTIVE', 'system')
ON CONFLICT (code) DO NOTHING;

INSERT INTO ops_svc.impl_template_phase (id, template_id, phase_no, name, weight, description) VALUES
    ('a2000000-0000-4000-8000-000000000001', 'a1000000-0000-4000-8000-000000000001', 1, '项目启动与范围', 10, '章程、组织、里程碑与范围边界'),
    ('a2000000-0000-4000-8000-000000000002', 'a1000000-0000-4000-8000-000000000001', 2, '环境与基础设施', 15, '网络、部署、监控、备份与安全基线'),
    ('a2000000-0000-4000-8000-000000000003', 'a1000000-0000-4000-8000-000000000001', 3, '数据与集成', 25, '主数据、迁移对账、HIS/LIS/PACS/医保联调'),
    ('a2000000-0000-4000-8000-000000000004', 'a1000000-0000-4000-8000-000000000001', 4, '培训与考核', 20, '分角色培训、考核通过率与材料归档'),
    ('a2000000-0000-4000-8000-000000000005', 'a1000000-0000-4000-8000-000000000001', 5, '试运行与验收', 20, '准入、回归、峰值验证与问题清零'),
    ('a2000000-0000-4000-8000-000000000006', 'a1000000-0000-4000-8000-000000000001', 6, '上线与移交', 10, '上线审批、部署回滚、运维移交与验收签署')
ON CONFLICT (id) DO NOTHING;

INSERT INTO ops_svc.impl_template_task
    (id, template_id, phase_id, task_no, title, description, required, owner_role, evidence_hint) VALUES
    ('a3000000-0000-4000-8000-000000000001', 'a1000000-0000-4000-8000-000000000001', 'a2000000-0000-4000-8000-000000000001', 1, '项目章程与范围确认', '确认建设范围、边界与不在范围清单', true, 'OPS_MANAGER', '签字章程/会议纪要地址'),
    ('a3000000-0000-4000-8000-000000000002', 'a1000000-0000-4000-8000-000000000001', 'a2000000-0000-4000-8000-000000000001', 2, '组织架构与通讯录', '院方领导小组、信息科、厂商责任人对齐', true, 'OPS_MANAGER', '通讯录与职责表'),
    ('a3000000-0000-4000-8000-000000000003', 'a1000000-0000-4000-8000-000000000001', 'a2000000-0000-4000-8000-000000000001', 3, '里程碑与上线计划', '明确各阶段时间与关键依赖', true, 'OPS_MANAGER', '里程碑计划表'),
    ('a3000000-0000-4000-8000-000000000004', 'a1000000-0000-4000-8000-000000000001', 'a2000000-0000-4000-8000-000000000002', 4, '环境清单与网络策略', '域名、端口、证书、防火墙与白名单', true, 'OPS_AGENT', '网络策略单'),
    ('a3000000-0000-4000-8000-000000000005', 'a1000000-0000-4000-8000-000000000001', 'a2000000-0000-4000-8000-000000000002', 5, '预发环境部署与健康检查', '按发布手册部署并跑通健康检查', true, 'OPS_AGENT', '部署记录与健康检查截图'),
    ('a3000000-0000-4000-8000-000000000006', 'a1000000-0000-4000-8000-000000000001', 'a2000000-0000-4000-8000-000000000002', 6, '监控、日志与备份接入', 'Prometheus/Grafana/Loki 与备份策略', true, 'OPS_AGENT', '监控面板与备份策略截图'),
    ('a3000000-0000-4000-8000-000000000007', 'a1000000-0000-4000-8000-000000000001', 'a2000000-0000-4000-8000-000000000002', 7, '安全基线核查', '口令、证书、最小权限与审计开启', true, 'OPS_AGENT', '基线核查表'),
    ('a3000000-0000-4000-8000-000000000008', 'a1000000-0000-4000-8000-000000000001', 'a2000000-0000-4000-8000-000000000003', 8, '主数据字典对齐', '药品、耗材、收费、科室、人员编码对齐', true, 'INTEGRATION', '字典映射清单'),
    ('a3000000-0000-4000-8000-000000000009', 'a1000000-0000-4000-8000-000000000001', 'a2000000-0000-4000-8000-000000000003', 9, '历史数据迁移预检', '批次预检、错误行清零或确认不迁移', true, 'INTEGRATION', '预检报告'),
    ('a3000000-0000-4000-8000-000000000010', 'a1000000-0000-4000-8000-000000000001', 'a2000000-0000-4000-8000-000000000003', 10, '迁移对账', '行数与金额勾稽一致', true, 'INTEGRATION', '对账报表'),
    ('a3000000-0000-4000-8000-000000000011', 'a1000000-0000-4000-8000-000000000001', 'a2000000-0000-4000-8000-000000000003', 11, 'HIS/LIS/PACS 接口联调', 'ADT/ORM/ORU 与影像调阅联调通过', true, 'INTEGRATION', '联调用例与回执'),
    ('a3000000-0000-4000-8000-000000000012', 'a1000000-0000-4000-8000-000000000001', 'a2000000-0000-4000-8000-000000000003', 12, '医保接口联调', '目录映射、结算与对账联调', true, 'INTEGRATION', '联调记录'),
    ('a3000000-0000-4000-8000-000000000013', 'a1000000-0000-4000-8000-000000000001', 'a2000000-0000-4000-8000-000000000004', 13, '分角色培训计划', '医生/护士/药师/收费/运维分角色排课', true, 'OPS_MANAGER', '培训计划表'),
    ('a3000000-0000-4000-8000-000000000014', 'a1000000-0000-4000-8000-000000000001', 'a2000000-0000-4000-8000-000000000004', 14, '关键用户培训', '科室骨干与超级用户完成培训', true, 'OPS_AGENT', '签到表与课件'),
    ('a3000000-0000-4000-8000-000000000015', 'a1000000-0000-4000-8000-000000000001', 'a2000000-0000-4000-8000-000000000004', 15, '全员考核通过率 ≥95%', '按课程统计唯一学员通过率', true, 'OPS_MANAGER', '考试成绩汇总'),
    ('a3000000-0000-4000-8000-000000000016', 'a1000000-0000-4000-8000-000000000001', 'a2000000-0000-4000-8000-000000000004', 16, '培训材料归档', '课件、录像、签到与考核记录归档', true, 'OPS_AGENT', '归档目录'),
    ('a3000000-0000-4000-8000-000000000017', 'a1000000-0000-4000-8000-000000000001', 'a2000000-0000-4000-8000-000000000005', 17, '试运行范围与准入', '明确试运行科室、病区与时间窗', true, 'OPS_MANAGER', '试运行方案'),
    ('a3000000-0000-4000-8000-000000000018', 'a1000000-0000-4000-8000-000000000001', 'a2000000-0000-4000-8000-000000000005', 18, '冒烟与业务回归', '挂号-就诊-医嘱-收费-药房主链路回归', true, 'OPS_AGENT', '回归用例结果'),
    ('a3000000-0000-4000-8000-000000000019', 'a1000000-0000-4000-8000-000000000001', 'a2000000-0000-4000-8000-000000000005', 19, '性能与峰值验证', '按容量模型压测并对比 SLO', true, 'OPS_AGENT', '压测报告'),
    ('a3000000-0000-4000-8000-000000000020', 'a1000000-0000-4000-8000-000000000001', 'a2000000-0000-4000-8000-000000000005', 20, '问题清零或豁免', '遗留问题有责任人与豁免依据', true, 'OPS_MANAGER', '问题清单'),
    ('a3000000-0000-4000-8000-000000000021', 'a1000000-0000-4000-8000-000000000001', 'a2000000-0000-4000-8000-000000000006', 21, '上线审批与灾备演练', '上线窗口、演练通过记录与回滚预案', true, 'OPS_MANAGER', '审批单与演练编号'),
    ('a3000000-0000-4000-8000-000000000022', 'a1000000-0000-4000-8000-000000000001', 'a2000000-0000-4000-8000-000000000006', 22, '生产部署与回滚验证', '记录部署与回滚目标', true, 'OPS_AGENT', '部署记录'),
    ('a3000000-0000-4000-8000-000000000023', 'a1000000-0000-4000-8000-000000000001', 'a2000000-0000-4000-8000-000000000006', 23, '运维移交与值班确认', '交接运维手册、账号与值班表', true, 'OPS_MANAGER', '移交清单'),
    ('a3000000-0000-4000-8000-000000000024', 'a1000000-0000-4000-8000-000000000001', 'a2000000-0000-4000-8000-000000000006', 24, '验收签署', '院方签署验收报告', true, 'OPS_MANAGER', '验收报告')
ON CONFLICT (id) DO NOTHING;

INSERT INTO ops_svc.impl_course
    (id, code, title, category, required, duration_minutes, description, status, created_by) VALUES
    ('a4000000-0000-4000-8000-000000000001', 'SYS-BASIC', '系统总览与登录安全', 'SYSTEM', true, 45,
     '控制台导航、角色权限、登录与改密、审计意识', 'ACTIVE', 'system'),
    ('a4000000-0000-4000-8000-000000000002', 'ROLE-DOCTOR', '医生站核心流程', 'ROLE', true, 90,
     '接诊、医嘱、病历、临床路径与 AI 辅助', 'ACTIVE', 'system'),
    ('a4000000-0000-4000-8000-000000000003', 'ROLE-NURSE', '护理站与条码执行', 'ROLE', true, 60,
     '床位、医嘱执行、条码核对、交班与移动护理', 'ACTIVE', 'system')
ON CONFLICT (code) DO NOTHING;

INSERT INTO ops_svc.impl_question
    (id, course_id, question_no, stem, options, answer_key, score, status) VALUES
    ('a5000000-0000-4000-8000-000000000001', 'a4000000-0000-4000-8000-000000000001', 1,
     '发现账号疑似被盗用时，第一步应该做什么？',
     '{"A":"继续观察","B":"立即改密并上报信息科","C":"把密码告诉同事","D":"换浏览器登录"}'::jsonb, 'B', 50, 'ACTIVE'),
    ('a5000000-0000-4000-8000-000000000002', 'a4000000-0000-4000-8000-000000000001', 2,
     '关于操作留痕，下列说法正确的是？',
     '{"A":"审计日志可以随手删除","B":"共用账号不影响追溯","C":"所有关键操作应可追溯到人","D":"下班后操作不需要留痕"}'::jsonb, 'C', 50, 'ACTIVE'),
    ('a5000000-0000-4000-8000-000000000003', 'a4000000-0000-4000-8000-000000000002', 1,
     '开立医嘱前，系统要求核对的核心信息不包括？',
     '{"A":"患者身份","B":"过敏史","C":"药品相互作用","D":"医师个人排班"}'::jsonb, 'D', 50, 'ACTIVE'),
    ('a5000000-0000-4000-8000-000000000004', 'a4000000-0000-4000-8000-000000000002', 2,
     '病历提交后发现关键信息错误，正确做法是？',
     '{"A":"直接覆盖原记录","B":"走病案修改申请并留痕","C":"删除后重写","D":"等归档后再说"}'::jsonb, 'B', 50, 'ACTIVE'),
    ('a5000000-0000-4000-8000-000000000005', 'a4000000-0000-4000-8000-000000000003', 1,
     '执行给药前，条码核对的最小要求是？',
     '{"A":"只核对床号","B":"患者腕带 + 药品条码","C":"只看医嘱名称","D":"同事口头确认即可"}'::jsonb, 'B', 50, 'ACTIVE'),
    ('a5000000-0000-4000-8000-000000000006', 'a4000000-0000-4000-8000-000000000003', 2,
     '交班时发现患者风险信息缺失，应该？',
     '{"A":"忽略，等下一班处理","B":"补录并在交班报告标注","C":"仅口头告知","D":"删除风险标记"}'::jsonb, 'B', 50, 'ACTIVE')
ON CONFLICT (id) DO NOTHING;

-- ---------------------------------------------------------------------------
-- 5. 审计动作扩展
-- ---------------------------------------------------------------------------
ALTER TABLE ops_svc.ops_change_log DROP CONSTRAINT IF EXISTS ops_change_log_action_check;
ALTER TABLE ops_svc.ops_change_log ADD CONSTRAINT ops_change_log_action_check CHECK (
    action IN ('TICKET_CREATE', 'TICKET_TRANSITION', 'TICKET_ASSIGN', 'TICKET_COMMENT',
               'TICKET_PRIORITY', 'SLA_SCAN', 'POLICY_UPSERT', 'CALENDAR_UPSERT',
               'SERVICE_UPSERT', 'ROTATION_UPSERT', 'SHIFT_GENERATE', 'SHIFT_OVERRIDE',
               'INCIDENT_CREATE', 'INCIDENT_STATUS', 'INCIDENT_COMMANDER', 'INCIDENT_COMMENT',
               'POSTMORTEM_DRAFT', 'POSTMORTEM_SUBMIT', 'POSTMORTEM_APPROVE',
               'INTEGRATION_SOURCE_CREATE', 'INTEGRATION_SOURCE_ROTATE',
               'INTEGRATION_SOURCE_DISABLE',
               'BACKUP_POLICY_UPSERT', 'BACKUP_RUN_REPORT',
               'DR_DRILL_CREATE', 'DR_DRILL_START', 'DR_DRILL_STEP',
               'DR_DRILL_PASS', 'DR_DRILL_FAIL', 'DR_DRILL_ABORT',
               'IMPL_TEMPLATE_UPSERT', 'IMPL_PROJECT_CREATE', 'IMPL_PROJECT_START',
               'IMPL_TASK_UPDATE', 'IMPL_TASK_WAIVE', 'IMPL_PROJECT_GO_LIVE',
               'IMPL_PROJECT_ACCEPT', 'IMPL_PROJECT_CANCEL',
               'IMPL_COURSE_UPSERT', 'IMPL_EXAM_CREATE', 'IMPL_EXAM_CLOSE',
               'IMPL_ATTEMPT_RECORD')
);
