-- ============================================================================
-- V72：传染病上报与漏报监测（计划书 W1.9 / Gate G1）
--
-- 解决什么问题
--   传染病报告是法定动作：诊断后必须在时限内（甲类 2 小时、乙/丙类 24 小时）
--   向疾控上报。此前系统只有诊断，没有"这条诊断该不该报、报没报、是否超时"的闭环，
--   漏报只能在检查时靠人工翻病历发现。
--
-- 设计要点
--   * infection_disease：法定传染病目录（甲/乙/丙类 + ICD-10 前缀 + 上报时限）；
--   * infection_report：一张报告卡对应"一次就诊 × 一种传染病"，AUTO 由诊断匹配生成，
--     MANUAL 由医生/防保科补录；状态机 DRAFT→SUBMITTED→ACCEPTED/RETURNED，
--     RETURNED 可重新提交，DRAFT/RETURNED 可作废（保留原因，不物理删除）；
--   * 时限按"诊断时间 + 病种时限"计算，提交时间晚于截止即判为迟报；
--   * infection_report_event：状态流转追加式留痕，禁止改删；
--   * 漏报视图 = 已过截止仍未提交的 DRAFT + 迟报的 SUBMITTED/ACCEPTED。
-- ============================================================================

CREATE TABLE IF NOT EXISTS clinical_svc.infection_disease (
    id            uuid PRIMARY KEY,
    code          text NOT NULL UNIQUE,
    name          text NOT NULL,
    disease_class text NOT NULL CHECK (disease_class IN ('A', 'B', 'C')),
    icd_prefixes  jsonb NOT NULL DEFAULT '[]'::jsonb
                  CHECK (jsonb_typeof(icd_prefixes) = 'array'),
    report_hours  int NOT NULL CHECK (report_hours > 0),
    enabled       boolean NOT NULL DEFAULT true,
    sort_no       int NOT NULL DEFAULT 100,
    remark        text,
    version       bigint NOT NULL DEFAULT 0,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS ix_infection_disease_enabled
    ON clinical_svc.infection_disease (enabled, sort_no);
COMMENT ON TABLE clinical_svc.infection_disease IS
    '法定传染病目录：甲类 2 小时、乙/丙类 24 小时；ICD 前缀用于诊断自动匹配';

CREATE TABLE IF NOT EXISTS clinical_svc.infection_report (
    id               uuid PRIMARY KEY,
    report_no        text NOT NULL UNIQUE,
    visit_id         uuid NOT NULL REFERENCES clinical_svc.visit (id),
    patient_id       uuid NOT NULL,
    patient_no       text NOT NULL,
    disease_code     text NOT NULL REFERENCES clinical_svc.infection_disease (code),
    disease_name     text NOT NULL,
    disease_class    text NOT NULL CHECK (disease_class IN ('A', 'B', 'C')),
    icd_code         text,
    icd_name         text,
    onset_date       date,
    diagnosed_at     timestamptz NOT NULL,
    report_deadline  timestamptz NOT NULL,
    status           text NOT NULL DEFAULT 'DRAFT'
                     CHECK (status IN ('DRAFT', 'SUBMITTED', 'ACCEPTED', 'RETURNED', 'VOID')),
    source           text NOT NULL DEFAULT 'AUTO' CHECK (source IN ('AUTO', 'MANUAL')),
    reporter         text,
    reporter_dept    text,
    submitted_by     text,
    submitted_at     timestamptz,
    cdc_receipt_no   text,
    cdc_receipt_at   timestamptz,
    return_reason    text,
    void_reason      text,
    created_by       text NOT NULL,
    updated_by       text NOT NULL,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    version          bigint NOT NULL DEFAULT 0,
    CONSTRAINT ck_infection_report_submitted CHECK (
        status IN ('DRAFT', 'VOID')
        OR (submitted_by IS NOT NULL AND submitted_at IS NOT NULL)
    ),
    CONSTRAINT ck_infection_report_returned CHECK (
        status <> 'RETURNED' OR return_reason IS NOT NULL
    ),
    CONSTRAINT ck_infection_report_void CHECK (
        status <> 'VOID' OR void_reason IS NOT NULL
    )
);
-- 同一次就诊的同一种传染病只允许一张有效报告卡；作废后可重新生成
CREATE UNIQUE INDEX IF NOT EXISTS ux_infection_report_active
    ON clinical_svc.infection_report (visit_id, disease_code)
    WHERE status <> 'VOID';
CREATE INDEX IF NOT EXISTS ix_infection_report_status
    ON clinical_svc.infection_report (status, report_deadline);
CREATE INDEX IF NOT EXISTS ix_infection_report_visit
    ON clinical_svc.infection_report (visit_id);
CREATE INDEX IF NOT EXISTS ix_infection_report_diagnosed
    ON clinical_svc.infection_report (diagnosed_at DESC);
CREATE SEQUENCE IF NOT EXISTS clinical_svc.infection_report_no_seq START 1;
COMMENT ON TABLE clinical_svc.infection_report IS
    '传染病报告卡：DRAFT→SUBMITTED→ACCEPTED/RETURNED；RETURNED 可重报，DRAFT/RETURNED 可作废';

CREATE TABLE IF NOT EXISTS clinical_svc.infection_report_event (
    id          bigserial PRIMARY KEY,
    report_id   uuid NOT NULL REFERENCES clinical_svc.infection_report (id),
    action      text NOT NULL
                CHECK (action IN ('CREATE', 'SUBMIT', 'ACCEPT', 'RETURN', 'VOID', 'RESUBMIT')),
    from_status text,
    to_status   text NOT NULL,
    actor       text NOT NULL,
    note        text,
    created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS ix_infection_event_report
    ON clinical_svc.infection_report_event (report_id, created_at DESC);
COMMENT ON TABLE clinical_svc.infection_report_event IS
    '报告卡状态流转留痕（追加式）：谁在何时把卡从什么状态改成什么状态';

CREATE OR REPLACE FUNCTION clinical_svc.guard_infection_event() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION '传染病上报留痕不允许修改或删除'
        USING ERRCODE = 'insufficient_privilege';
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_infection_event_append_only
    ON clinical_svc.infection_report_event;
CREATE TRIGGER trg_infection_event_append_only
    BEFORE UPDATE OR DELETE ON clinical_svc.infection_report_event
    FOR EACH ROW EXECUTE FUNCTION clinical_svc.guard_infection_event();

-- 自动扫描水位：只处理上次水位之后的诊断，避免每轮全表重扫
CREATE TABLE IF NOT EXISTS clinical_svc.infection_scan_state (
    id                int PRIMARY KEY CHECK (id = 1),
    last_diagnosed_at timestamptz,
    last_run_at       timestamptz,
    last_created      int NOT NULL DEFAULT 0
);
INSERT INTO clinical_svc.infection_scan_state (id, last_diagnosed_at, last_run_at, last_created)
VALUES (1, now() - interval '7 days', now(), 0)
ON CONFLICT (id) DO NOTHING;
COMMENT ON TABLE clinical_svc.infection_scan_state IS
    '传染病自动扫描水位：last_diagnosed_at 之前的诊断不再重复处理';

-- ---------------------------------------------------------------- 法定传染病基线目录
-- 只列常用病种；上线前由防保科按属地疾控目录核对启用。
INSERT INTO clinical_svc.infection_disease
    (id, code, name, disease_class, icd_prefixes, report_hours, enabled, sort_no, remark)
VALUES
    (gen_random_uuid(), '01', '鼠疫', 'A', '["A20"]', 2, true, 10, '甲类'),
    (gen_random_uuid(), '02', '霍乱', 'A', '["A00"]', 2, true, 20, '甲类'),
    (gen_random_uuid(), '03', '传染性非典型肺炎', 'B', '["U04"]', 24, true, 30, '乙类按甲类管理'),
    (gen_random_uuid(), '04', '艾滋病', 'B', '["B20","B21","B22","B23","B24"]', 24, true, 40, NULL),
    (gen_random_uuid(), '05', '病毒性肝炎', 'B', '["B15","B16","B17","B18","B19"]', 24, true, 50, NULL),
    (gen_random_uuid(), '06', '脊髓灰质炎', 'B', '["A80"]', 24, true, 60, NULL),
    (gen_random_uuid(), '07', '人感染高致病性禽流感', 'B', '["J09"]', 24, true, 70, NULL),
    (gen_random_uuid(), '08', '麻疹', 'B', '["B05"]', 24, true, 80, NULL),
    (gen_random_uuid(), '09', '流行性出血热', 'B', '["A98.5"]', 24, true, 90, NULL),
    (gen_random_uuid(), '10', '狂犬病', 'B', '["A82"]', 24, true, 100, NULL),
    (gen_random_uuid(), '11', '流行性乙型脑炎', 'B', '["A83.0"]', 24, true, 110, NULL),
    (gen_random_uuid(), '12', '登革热', 'B', '["A90","A91"]', 24, true, 120, NULL),
    (gen_random_uuid(), '13', '炭疽', 'B', '["A22"]', 24, true, 130, NULL),
    (gen_random_uuid(), '14', '细菌性痢疾', 'B', '["A03"]', 24, true, 140, NULL),
    (gen_random_uuid(), '15', '肺结核', 'B', '["A15","A16"]', 24, true, 150, NULL),
    (gen_random_uuid(), '16', '伤寒', 'B', '["A01"]', 24, true, 160, NULL),
    (gen_random_uuid(), '17', '流行性脑脊髓膜炎', 'B', '["A39.0"]', 24, true, 170, NULL),
    (gen_random_uuid(), '18', '百日咳', 'B', '["A37"]', 24, true, 180, NULL),
    (gen_random_uuid(), '19', '白喉', 'B', '["A36"]', 24, true, 190, NULL),
    (gen_random_uuid(), '20', '新生儿破伤风', 'B', '["A33"]', 24, true, 200, NULL),
    (gen_random_uuid(), '21', '猩红热', 'B', '["A38"]', 24, true, 210, NULL),
    (gen_random_uuid(), '22', '布鲁氏菌病', 'B', '["A23"]', 24, true, 220, NULL),
    (gen_random_uuid(), '23', '淋病', 'B', '["A54"]', 24, true, 230, NULL),
    (gen_random_uuid(), '24', '梅毒', 'B', '["A51","A52","A53"]', 24, true, 240, NULL),
    (gen_random_uuid(), '25', '钩端螺旋体病', 'B', '["A27"]', 24, true, 250, NULL),
    (gen_random_uuid(), '26', '血吸虫病', 'B', '["B65"]', 24, true, 260, NULL),
    (gen_random_uuid(), '27', '疟疾', 'B', '["B50","B51","B52","B53","B54"]', 24, true, 270, NULL),
    (gen_random_uuid(), '28', '人感染H7N9禽流感', 'B', '["J09.1"]', 24, false, 280,
     '与高致病性禽流感 ICD 重叠，默认停用，按属地目录启用'),
    (gen_random_uuid(), '29', '流行性感冒', 'C', '["J10","J11"]', 24, true, 290, NULL),
    (gen_random_uuid(), '30', '流行性腮腺炎', 'C', '["B26"]', 24, true, 300, NULL),
    (gen_random_uuid(), '31', '风疹', 'C', '["B06"]', 24, true, 310, NULL),
    (gen_random_uuid(), '32', '急性出血性结膜炎', 'C', '["B30.9"]', 24, true, 320, NULL),
    (gen_random_uuid(), '33', '麻风病', 'C', '["A30"]', 24, true, 330, NULL),
    (gen_random_uuid(), '34', '流行性和地方性斑疹伤寒', 'C', '["A75"]', 24, true, 340, NULL),
    (gen_random_uuid(), '35', '黑热病', 'C', '["B55"]', 24, true, 350, NULL),
    (gen_random_uuid(), '36', '包虫病', 'C', '["B67"]', 24, true, 360, NULL),
    (gen_random_uuid(), '37', '丝虫病', 'C', '["B74"]', 24, true, 370, NULL),
    (gen_random_uuid(), '38', '其他感染性腹泻', 'C', '["A09"]', 24, true, 380, NULL)
ON CONFLICT (code) DO NOTHING;
