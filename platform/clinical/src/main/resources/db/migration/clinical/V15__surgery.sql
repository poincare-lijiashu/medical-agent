-- ============================================================================
-- 临床域 V15：手术麻醉域第一批（ICD-9-CM-3 字典 + 手术记录）
--
-- 背景：病案首页的手术操作此前只能手工录入，导致
--   * DRG/医保分组拿不到真实手术编码（分组权重失真）；
--   * 手术名称/术者各写各的，无法统计；
--   * 与手术收费没有关联，账实分离。
--
-- 本批先落"字典 + 手术记录"两件事，首页与 DRG 改为从已完成手术记录取数：
--   * operation_catalog：ICD-9-CM-3 编码字典（可停用，历史记录保留名称快照）；
--   * surgery_case：一次手术一行，状态 PLANNED → IN_PROGRESS → COMPLETED / CANCELLED。
--
-- 边界：本批不做排台/麻醉单/术中记录（后续批次），只保证"手术事实 + 编码"可信。
-- ============================================================================

CREATE TABLE IF NOT EXISTS clinical_svc.operation_catalog (
    code        text PRIMARY KEY,                       -- ICD-9-CM-3
    name        text NOT NULL,
    category    text,                                   -- 手术/操作大类（普外/骨科/…）
    enabled     boolean NOT NULL DEFAULT true,
    version     bigint NOT NULL DEFAULT 0,
    created_at  timestamptz NOT NULL DEFAULT now()
);

COMMENT ON TABLE clinical_svc.operation_catalog IS
    '手术操作编码字典（ICD-9-CM-3）：停用后不可新选，历史手术记录保留名称快照';

CREATE SEQUENCE IF NOT EXISTS clinical_svc.surgery_case_no_seq START 1;

CREATE TABLE IF NOT EXISTS clinical_svc.surgery_case (
    id              uuid PRIMARY KEY,
    case_no         text NOT NULL UNIQUE,               -- OPYYYYMMDD######
    visit_id        uuid NOT NULL REFERENCES clinical_svc.visit (id),
    admission_id    uuid,
    patient_id      uuid NOT NULL,
    operation_code  text NOT NULL,                      -- 字典编码（不加 FK：字典可停用/替换）
    operation_name  text NOT NULL,                      -- 手术名称快照
    planned_at      timestamptz,
    started_at      timestamptz,
    ended_at        timestamptz,
    surgeon         text,                               -- 术者
    assistant       text,                               -- 一助
    anesthesia_type text CHECK (anesthesia_type IN
                        ('GENERAL', 'REGIONAL', 'LOCAL', 'SEDATION', 'NONE')),
    asa_grade       text CHECK (asa_grade IN ('I', 'II', 'III', 'IV', 'V', 'VI')),
    incision_level  text CHECK (incision_level IN ('I', 'II', 'III', 'IV')),
    status          text NOT NULL DEFAULT 'PLANNED'
                    CHECK (status IN ('PLANNED', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED')),
    cancel_reason   text,
    created_by      text NOT NULL,
    version         bigint NOT NULL DEFAULT 0,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_surgery_time CHECK (
        (started_at IS NULL OR planned_at IS NULL OR started_at >= planned_at - interval '1 day')
        AND (ended_at IS NULL OR started_at IS NULL OR ended_at >= started_at)
    ),
    CONSTRAINT ck_surgery_cancel CHECK (
        (status = 'CANCELLED' AND cancel_reason IS NOT NULL) OR status <> 'CANCELLED'
    )
);

-- 同一就诊同一手术只能有一条"未取消"记录（防止重复录入导致 DRG 重复计权）
CREATE UNIQUE INDEX IF NOT EXISTS ux_surgery_case_active
    ON clinical_svc.surgery_case (visit_id, operation_code)
    WHERE status <> 'CANCELLED';

CREATE INDEX IF NOT EXISTS ix_surgery_case_visit
    ON clinical_svc.surgery_case (visit_id, started_at);

CREATE INDEX IF NOT EXISTS ix_surgery_case_catalog
    ON clinical_svc.operation_catalog (enabled, code);

COMMENT ON TABLE clinical_svc.surgery_case IS
    '手术记录：一次手术一行；病案首页手术操作与 DRG 分组从 COMPLETED 记录取数';

-- ---- 常用手术编码种子（可在后续批次按院方字典扩充/导入）----
INSERT INTO clinical_svc.operation_catalog (code, name, category) VALUES
    ('47.0901', '阑尾切除术', '普外科'),
    ('51.2301', '腹腔镜胆囊切除术', '普外科'),
    ('53.0001', '腹股沟疝修补术', '普外科'),
    ('45.1301', '结肠镜检查', '消化内科'),
    ('74.1',    '剖宫产术', '妇产科'),
    ('81.5101', '全髋关节置换术', '骨科'),
    ('81.5401', '全膝关节置换术', '骨科'),
    ('88.0100', '经皮肺穿刺活检', '呼吸内科'),
    ('39.9501', '血液透析', '肾内科'),
    ('86.2201', '皮肤缝合术', '普外科')
ON CONFLICT (code) DO NOTHING;
