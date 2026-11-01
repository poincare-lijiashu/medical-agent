-- ============================================================================
-- 临床域 V19：术中收费打包（手术费 / 麻醉费 / 耗材一次计费）
--
-- 为什么需要"打包规则"而不是在代码里写死价格：
--   * 手术费按**术式类别**定价（普外/骨科/妇产…），麻醉费按**麻醉方式**定价，
--     耗材按"每台手术一套"计——这些都是物价与医保政策调整最频繁的地方；
--   * 规则表让物价员改一行数据即可生效，代码只负责"按规则取价 + 幂等入账"。
--
-- 与既有计费的关系（不新造第二套账）：
--   * 费用仍落在 clinical_svc.charge + charge_item；
--   * 幂等键 source_key = 'SURGERY:{caseId}:{itemCode}'：
--     同一台手术同一收费项目只允许计一次，重复点击/重试都不会多收；
--   * 收费项目类别沿用既有 6 分类（手术费/麻醉费记 TREATMENT，耗材记 MATERIAL），
--     不改病案首页的住院费用分类口径（手术收入可按 item_code 前缀 SURG-*/ANES-* 单独统计）。
--
-- 边界：本批只做"从规则到收费单"的自动取价与入账，不做物价调整审批、
-- 医保支付比例、术中耗材条码扫码核销（归属后续批次）。
-- ============================================================================

-- ---- 打包规则 ----
CREATE TABLE IF NOT EXISTS clinical_svc.surgery_charge_rule (
    id          uuid PRIMARY KEY,
    rule_type   text NOT NULL
                CHECK (rule_type IN ('OPERATION_CATEGORY', 'ANESTHESIA_METHOD', 'MATERIAL')),
    match_value text NOT NULL,               -- 术式类别 / 麻醉方式 / ALL（耗材每台适用）
    item_code   text NOT NULL,               -- 指向 md_charge_item.item_code（字典驱动，不建外键）
    quantity    numeric(8, 2) NOT NULL DEFAULT 1 CHECK (quantity > 0),
    sort_no     int NOT NULL DEFAULT 1,
    enabled     boolean NOT NULL DEFAULT true,
    version     bigint NOT NULL DEFAULT 0,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ux_surgery_charge_rule UNIQUE (rule_type, match_value, item_code)
);

CREATE INDEX IF NOT EXISTS ix_surgery_charge_rule_type
    ON clinical_svc.surgery_charge_rule (rule_type, match_value)
    WHERE enabled;

COMMENT ON TABLE clinical_svc.surgery_charge_rule IS
    '手术收费打包规则：术式类别/麻醉方式/耗材 → 收费项目与数量；价格仍取 md_charge_item';

-- ---- 收费项目（示例价，上线前由院内物价字典覆盖）----
INSERT INTO clinical_svc.md_charge_item (id, item_code, name, category, unit, price) VALUES
    ('66666666-6666-6666-6666-666666666601', 'SURG-GENERAL', '手术费（普外）',       'TREATMENT', '次', 800.00),
    ('66666666-6666-6666-6666-666666666602', 'SURG-ORTHO',   '手术费（骨科）',       'TREATMENT', '次', 2000.00),
    ('66666666-6666-6666-6666-666666666603', 'SURG-OBGYN',   '手术费（妇产）',       'TREATMENT', '次', 1500.00),
    ('66666666-6666-6666-6666-666666666604', 'SURG-GI',      '手术费（消化内镜）',   'TREATMENT', '次', 600.00),
    ('66666666-6666-6666-6666-666666666605', 'SURG-RESP',    '手术费（呼吸介入）',   'TREATMENT', '次', 500.00),
    ('66666666-6666-6666-6666-666666666606', 'SURG-NEPH',    '手术费（血液净化）',   'TREATMENT', '次', 700.00),
    ('66666666-6666-6666-6666-666666666607', 'SURG-DEFAULT', '手术费（其他）',       'TREATMENT', '次', 500.00),
    ('66666666-6666-6666-6666-666666666611', 'ANES-GENERAL', '麻醉费（全身麻醉）',   'TREATMENT', '次', 600.00),
    ('66666666-6666-6666-6666-666666666612', 'ANES-REGIONAL','麻醉费（椎管内麻醉）', 'TREATMENT', '次', 400.00),
    ('66666666-6666-6666-6666-666666666613', 'ANES-LOCAL',   '麻醉费（局部麻醉）',   'TREATMENT', '次', 200.00),
    ('66666666-6666-6666-6666-666666666614', 'ANES-SEDATION','麻醉费（镇静）',       'TREATMENT', '次', 300.00),
    ('66666666-6666-6666-6666-666666666621', 'MAT-OPKIT',    '一次性手术耗材包',     'MATERIAL',  '套', 150.00)
ON CONFLICT (item_code) DO NOTHING;

-- ---- 打包规则：术式类别（与 operation_catalog.category 对齐）+ 兜底 ----
INSERT INTO clinical_svc.surgery_charge_rule
    (id, rule_type, match_value, item_code, quantity, sort_no) VALUES
    ('55555555-5555-5555-5555-555555555501', 'OPERATION_CATEGORY', '普外科',   'SURG-GENERAL', 1, 1),
    ('55555555-5555-5555-5555-555555555502', 'OPERATION_CATEGORY', '骨科',     'SURG-ORTHO',   1, 1),
    ('55555555-5555-5555-5555-555555555503', 'OPERATION_CATEGORY', '妇产科',   'SURG-OBGYN',   1, 1),
    ('55555555-5555-5555-5555-555555555504', 'OPERATION_CATEGORY', '消化内科', 'SURG-GI',      1, 1),
    ('55555555-5555-5555-5555-555555555505', 'OPERATION_CATEGORY', '呼吸内科', 'SURG-RESP',    1, 1),
    ('55555555-5555-5555-5555-555555555506', 'OPERATION_CATEGORY', '肾内科',   'SURG-NEPH',    1, 1),
    ('55555555-5555-5555-5555-555555555507', 'OPERATION_CATEGORY', 'DEFAULT',  'SURG-DEFAULT', 1, 99)
ON CONFLICT (rule_type, match_value, item_code) DO NOTHING;

-- ---- 打包规则：麻醉方式 ----
INSERT INTO clinical_svc.surgery_charge_rule
    (id, rule_type, match_value, item_code, quantity, sort_no) VALUES
    ('55555555-5555-5555-5555-555555555511', 'ANESTHESIA_METHOD', 'GENERAL',  'ANES-GENERAL',  1, 1),
    ('55555555-5555-5555-5555-555555555512', 'ANESTHESIA_METHOD', 'REGIONAL', 'ANES-REGIONAL', 1, 1),
    ('55555555-5555-5555-5555-555555555513', 'ANESTHESIA_METHOD', 'LOCAL',    'ANES-LOCAL',    1, 1),
    ('55555555-5555-5555-5555-555555555514', 'ANESTHESIA_METHOD', 'SEDATION', 'ANES-SEDATION', 1, 1)
ON CONFLICT (rule_type, match_value, item_code) DO NOTHING;

-- ---- 打包规则：耗材（ALL = 每台手术一套）----
INSERT INTO clinical_svc.surgery_charge_rule
    (id, rule_type, match_value, item_code, quantity, sort_no) VALUES
    ('55555555-5555-5555-5555-555555555521', 'MATERIAL', 'ALL', 'MAT-OPKIT', 1, 1)
ON CONFLICT (rule_type, match_value, item_code) DO NOTHING;

-- ---- 计费幂等键：把 source_key 语义补进注释（不改表结构）----
COMMENT ON COLUMN clinical_svc.charge_item.source_key IS
    '计费幂等键：ORDER:{orderId} / DAILY:{orderId}:{date} / REG:{uuid} / SURGERY:{caseId}:{itemCode}';
