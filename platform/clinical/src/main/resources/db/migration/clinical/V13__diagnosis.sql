-- ============================================================================
-- 临床 V13：诊断字典（ICD-10）与就诊诊断
--
-- 背景：此前就诊只有"医嘱+收费"，没有结构化诊断。诊断是病历、病案首页、
-- 医保结算清单、DRG/DIP 分组的共同前置数据；用自由文本会导致后续全部返工。
--
-- 设计要点：
--   * 字典 md_diagnosis：编码（ICD-10）唯一，名称/系统分类由院内病案室维护；
--   * 就诊诊断 visit_diagnosis 保存**名称快照**（字典改名不改历史诊断）；
--   * 一个就诊**只能有一条主诊断**：部分唯一索引（WHERE primary_flag）在数据库层兜底；
--   * 同一就诊同一编码只能出现一次：唯一约束（visit_id, code），防止重复勾选。
-- ============================================================================

CREATE TABLE IF NOT EXISTS clinical_svc.md_diagnosis (
    id         uuid PRIMARY KEY,
    code       text NOT NULL UNIQUE,                  -- ICD-10 编码（如 J06.900）
    name       text NOT NULL,
    category   text,                                  -- 系统/章节（呼吸系统、循环系统…）
    enabled    boolean NOT NULL DEFAULT true,
    version    bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS ix_md_diagnosis_name ON clinical_svc.md_diagnosis (name);
COMMENT ON TABLE clinical_svc.md_diagnosis IS '诊断字典（ICD-10）：编码唯一，名称供选择';

CREATE TABLE IF NOT EXISTS clinical_svc.visit_diagnosis (
    id           uuid PRIMARY KEY,
    visit_id     uuid NOT NULL REFERENCES clinical_svc.visit (id),
    code         text NOT NULL,
    name         text NOT NULL,                       -- 下达时的名称快照
    primary_flag boolean NOT NULL DEFAULT false,
    sort_no      int NOT NULL DEFAULT 0,
    diagnosed_by text NOT NULL,
    diagnosed_at timestamptz NOT NULL DEFAULT now(),
    version      bigint NOT NULL DEFAULT 0,
    CONSTRAINT ux_visit_diagnosis_code UNIQUE (visit_id, code)
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_visit_diagnosis_primary
    ON clinical_svc.visit_diagnosis (visit_id) WHERE primary_flag;
CREATE INDEX IF NOT EXISTS ix_visit_diagnosis_visit
    ON clinical_svc.visit_diagnosis (visit_id, sort_no);
COMMENT ON TABLE clinical_svc.visit_diagnosis IS
    '就诊诊断：主诊断唯一（部分唯一索引）；同一编码不可重复；名称保存快照';

-- ---- 常用诊断（最小可用集合，上线前由病案室用正式 ICD-10 目录覆盖）----
INSERT INTO clinical_svc.md_diagnosis (id, code, name, category) VALUES
    ('55555555-5555-5555-5555-555555555501', 'J06.900', '急性上呼吸道感染', '呼吸系统'),
    ('55555555-5555-5555-5555-555555555502', 'J18.900', '肺炎', '呼吸系统'),
    ('55555555-5555-5555-5555-555555555503', 'J44.900', '慢性阻塞性肺疾病', '呼吸系统'),
    ('55555555-5555-5555-5555-555555555504', 'I10.x00', '高血压病', '循环系统'),
    ('55555555-5555-5555-5555-555555555505', 'I25.103', '冠状动脉粥样硬化性心脏病', '循环系统'),
    ('55555555-5555-5555-5555-555555555506', 'E11.900', '2型糖尿病', '内分泌与代谢'),
    ('55555555-5555-5555-5555-555555555507', 'K29.500', '慢性胃炎', '消化系统'),
    ('55555555-5555-5555-5555-555555555508', 'A09.900', '感染性腹泻', '消化系统'),
    ('55555555-5555-5555-5555-555555555509', 'N39.000', '泌尿道感染', '泌尿系统'),
    ('55555555-5555-5555-5555-555555555510', 'R51.x00', '头痛', '症状与体征'),
    ('55555555-5555-5555-5555-555555555511', 'S72.001', '股骨颈骨折', '损伤与中毒'),
    ('55555555-5555-5555-5555-555555555512', 'O80.000', '顺产', '妊娠分娩产褥')
ON CONFLICT (code) DO NOTHING;
