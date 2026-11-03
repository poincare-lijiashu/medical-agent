-- ============================================================================
-- EMR V18：ICD 字典与病案编码（G4）
--
-- 业务口径（与国内病案管理一致）：
--   临床首页提交 → 病案室按 ICD-10 / ICD-9-CM-3 编码 → 编码完成 → 质控归档。
--   编码结果回写 record_homepage.diagnoses/procedures（下游 HQMS 上报、DRG 入组都读这一份），
--   但写入受触发器约束：只有 homepage_coding 状态为 CODED 时才允许改动已提交首页的诊断/手术。
--
-- 为什么单独建 homepage_coding 而不给首页加十几个字段：
--   * 编码过程需要草稿、版本与并发控制，和首页"生成快照"生命周期不同；
--   * 首页 record 是超宽表（40+ 字段），再塞编码字段会让映射与快照语义更难维护；
--   * 编码记录天然是"一次住院一份"，唯一键即可保证不重复。
-- ============================================================================

-- ---- ICD-10 诊断字典 ----
CREATE TABLE IF NOT EXISTS emr_svc.icd_diagnosis (
    code       text PRIMARY KEY,                              -- ICD-10 编码（如 I25.101）
    name       text NOT NULL,
    chapter    text,                                          -- 章（如 IX 循环系统疾病）
    category   text,                                          -- 类目块（如 I20-I25）
    enabled    boolean NOT NULL DEFAULT true,
    version    bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS ix_icd_diagnosis_category
    ON emr_svc.icd_diagnosis (category, code);

-- ---- ICD-9-CM-3 手术操作字典 ----
CREATE TABLE IF NOT EXISTS emr_svc.icd_procedure (
    code       text PRIMARY KEY,                              -- ICD-9-CM-3 编码（如 36.0601）
    name       text NOT NULL,
    category   text,                                          -- 手术/操作分类
    enabled    boolean NOT NULL DEFAULT true,
    version    bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

-- 名称模糊检索走 pg_trgm（V4 已尝试安装；不可用时退化为顺序扫描，不影响功能）
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'pg_trgm') THEN
        EXECUTE 'CREATE INDEX IF NOT EXISTS ix_icd_diagnosis_name_trgm
                     ON emr_svc.icd_diagnosis USING gin (name gin_trgm_ops)';
        EXECUTE 'CREATE INDEX IF NOT EXISTS ix_icd_procedure_name_trgm
                     ON emr_svc.icd_procedure USING gin (name gin_trgm_ops)';
    ELSE
        RAISE NOTICE '跳过 ICD 名称 trgm 索引：pg_trgm 不可用';
    END IF;
END $$;

-- ---- 病案编码（一次住院一份）----
CREATE TABLE IF NOT EXISTS emr_svc.homepage_coding (
    id          uuid PRIMARY KEY,
    homepage_id uuid NOT NULL UNIQUE REFERENCES emr_svc.record_homepage (id),
    status      text NOT NULL DEFAULT 'CODING'
                CHECK (status IN ('CODING', 'CODED')),
    coder       text NOT NULL,
    coded_at    timestamptz,
    note        text,
    diagnoses   jsonb NOT NULL DEFAULT '[]'::jsonb,           -- [{code,name,primary}]
    procedures  jsonb NOT NULL DEFAULT '[]'::jsonb,           -- [{code,name,date,surgeon,anesthesia,incisionLevel}]
    version     bigint NOT NULL DEFAULT 0,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_coding_coded CHECK (status <> 'CODED' OR coded_at IS NOT NULL)
);
CREATE INDEX IF NOT EXISTS ix_homepage_coding_status
    ON emr_svc.homepage_coding (status, updated_at DESC);

-- ---- 首页触发器：编码完成才允许归档 / 才允许改诊断与手术操作 ----
CREATE OR REPLACE FUNCTION emr_svc.guard_homepage() RETURNS trigger AS $$
DECLARE
    coded boolean;
BEGIN
    IF OLD.status = 'ARCHIVED'
       AND (NEW.status <> 'ARCHIVED'
            OR NEW.diagnoses IS DISTINCT FROM OLD.diagnoses
            OR NEW.procedures IS DISTINCT FROM OLD.procedures
            OR NEW.fee_summary IS DISTINCT FROM OLD.fee_summary
            OR NEW.total_amount <> OLD.total_amount) THEN
        RAISE EXCEPTION '病案首页已归档，不允许退回或修改快照'
            USING ERRCODE = 'check_violation';
    END IF;

    SELECT EXISTS (SELECT 1 FROM emr_svc.homepage_coding c
                    WHERE c.homepage_id = NEW.id AND c.status = 'CODED')
      INTO coded;

    -- 归档门槛：病案编码未完成不得归档（编码是归档前的最后一道业务关口）
    IF NEW.status = 'ARCHIVED' AND OLD.status <> 'ARCHIVED' AND NOT coded THEN
        RAISE EXCEPTION '病案首页尚未完成编码，不允许归档'
            USING ERRCODE = 'check_violation';
    END IF;

    IF OLD.status = 'SUBMITTED'
       AND (NEW.status = 'DRAFT'
            OR NEW.visit_id <> OLD.visit_id
            OR NEW.patient_id <> OLD.patient_id) THEN
        RAISE EXCEPTION '病案首页已提交，不允许退回草稿或更换就诊'
            USING ERRCODE = 'check_violation';
    END IF;

    IF OLD.status = 'SUBMITTED'
       AND (NEW.fee_summary IS DISTINCT FROM OLD.fee_summary
            OR NEW.total_amount <> OLD.total_amount
            OR NEW.discharged_at IS DISTINCT FROM OLD.discharged_at) THEN
        RAISE EXCEPTION '病案首页已提交，临床费用/出院时间快照不可修改'
            USING ERRCODE = 'check_violation';
    END IF;

    -- 编码回写：已提交首页的诊断/手术只有在编码完成后才允许改（否则临床快照仍不可动）
    IF OLD.status = 'SUBMITTED'
       AND (NEW.diagnoses IS DISTINCT FROM OLD.diagnoses
            OR NEW.procedures IS DISTINCT FROM OLD.procedures)
       AND NOT coded THEN
        RAISE EXCEPTION '病案首页已提交，只有完成编码后才能修改诊断/手术操作'
            USING ERRCODE = 'check_violation';
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION emr_svc.guard_homepage() IS
    '病案首页兜底：状态不可回退；已归档快照不可改；编码完成前不得归档或改诊断/手术';

-- ============================================================================
-- 初始 ICD 字典（示例；上线前由病案室导入国家临床版 ICD-10 / ICD-9-CM-3 全量目录）
-- ============================================================================
INSERT INTO emr_svc.icd_diagnosis (code, name, chapter, category) VALUES
    ('I10.x00',    '原发性高血压',                     'IX 循环系统疾病',        'I10-I15'),
    ('I25.101',    '冠状动脉粥样硬化性心脏病',           'IX 循环系统疾病',        'I20-I25'),
    ('I21.900',    '急性心肌梗死',                     'IX 循环系统疾病',        'I20-I25'),
    ('I63.900',    '脑梗死',                           'IX 循环系统疾病',        'I60-I69'),
    ('E11.900',    '2型糖尿病',                        'IV 内分泌、营养和代谢疾病', 'E10-E14'),
    ('J18.900',    '肺炎',                             'X 呼吸系统疾病',         'J12-J18'),
    ('J44.100',    '慢性阻塞性肺疾病伴急性加重',         'X 呼吸系统疾病',         'J40-J47'),
    ('K35.900',    '急性阑尾炎',                       'XI 消化系统疾病',        'K35-K38'),
    ('K80.201',    '胆囊结石',                         'XI 消化系统疾病',        'K80-K87'),
    ('N20.000',    '肾结石',                           'XIV 泌尿生殖系统疾病',    'N20-N23'),
    ('S72.001',    '股骨颈骨折',                       'XIX 损伤、中毒和外因',    'S70-S79'),
    ('C34.900',    '肺恶性肿瘤',                       'II 肿瘤',               'C30-C39'),
    ('O80.000',    '顺产',                             'XV 妊娠、分娩和产褥期',   'O80-O84'),
    ('R07.400',    '胸痛',                             'XVIII 症状、体征和临床与实验室异常', 'R00-R09'),
    ('A09.900',    '感染性腹泻',                       'I 某些传染病和寄生虫病',  'A00-A09')
ON CONFLICT (code) DO NOTHING;

INSERT INTO emr_svc.icd_procedure (code, name, category) VALUES
    ('47.0901', '阑尾切除术',             '消化系统手术'),
    ('51.2301', '腹腔镜胆囊切除术',         '消化系统手术'),
    ('36.0601', '冠状动脉药物洗脱支架植入术', '心血管手术'),
    ('88.5601', '冠状动脉造影术',           '心血管检查与操作'),
    ('81.5401', '全膝关节置换术',           '肌肉骨骼系统手术'),
    ('81.5101', '全髋关节置换术',           '肌肉骨骼系统手术'),
    ('79.3501', '股骨骨折切开复位内固定术',   '肌肉骨骼系统手术'),
    ('74.1001', '剖宫产术',                '产科手术')
ON CONFLICT (code) DO NOTHING;

COMMENT ON TABLE emr_svc.homepage_coding IS
    '病案编码：一次住院一份；CODED 后回写首页诊断/手术并允许归档';
