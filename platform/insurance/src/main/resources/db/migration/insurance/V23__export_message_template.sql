-- ============================================================================
-- V23：省局医保报文模板（G85）
--
-- 背景：报盘 XML 的字段名/必填要求随省局接口版本变化，硬编码在 Java 里意味着
--      每次省局调整都要发版；院内也需要在报盘前确认"这次报的字段对不对"。
--
-- 设计：
--   * 模板以 code 唯一，fields 为 JSONB 数组：[{element,source,required,label}]；
--   * 状态机 DRAFT → PUBLISHED → DISABLED，仅草稿可修改；
--   * 全局最多一个 PUBLISHED（部分唯一索引），保证"导出用哪套模板"唯一确定；
--   * 发布时 version+1；批次生成时把 template_code/template_version 快照落库，
--     事后可追溯"这张批次是按哪一版模板报出去的"；
--   * 无已发布模板时回落内置 BUILTIN 布局（template_code='BUILTIN'），
--     首次上线/模板停用期间报盘不中断，且批次仍可区分来源。
-- ============================================================================

CREATE TABLE IF NOT EXISTS insurance_svc.export_message_template (
    id             uuid PRIMARY KEY,
    code           text NOT NULL UNIQUE
                   CHECK (code ~ '^[A-Za-z0-9_-]{2,32}$'),
    name           text NOT NULL CHECK (btrim(name) <> ''),
    version        int NOT NULL DEFAULT 0 CHECK (version >= 0),
    status         text NOT NULL DEFAULT 'DRAFT'
                   CHECK (status IN ('DRAFT', 'PUBLISHED', 'DISABLED')),
    fields         jsonb NOT NULL CHECK (jsonb_typeof(fields) = 'array'),
    created_by     text NOT NULL,
    updated_by     text NOT NULL,
    published_by   text,
    published_at   timestamptz,
    row_version    bigint NOT NULL DEFAULT 0 CHECK (row_version >= 0),
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    -- 草稿不得带发布痕迹；发布/停用必须留下发布人与发布时间
    CONSTRAINT ck_template_publish CHECK (
        (status = 'DRAFT' AND published_by IS NULL AND published_at IS NULL)
        OR (status IN ('PUBLISHED', 'DISABLED')
            AND published_by IS NOT NULL AND published_at IS NOT NULL)
    )
);

-- 全局唯一生效模板：导出时不需要额外"选哪个模板"的参数，也不存在两套口径并存
CREATE UNIQUE INDEX IF NOT EXISTS ux_export_template_single_published
    ON insurance_svc.export_message_template (status)
    WHERE status = 'PUBLISHED';

CREATE INDEX IF NOT EXISTS ix_export_template_updated
    ON insurance_svc.export_message_template (updated_at DESC);

-- 批次快照：记录本报盘使用的模板版本（BUILTIN 为内置布局）
ALTER TABLE insurance_svc.settlement_export_batch
    ADD COLUMN IF NOT EXISTS template_code    text,
    ADD COLUMN IF NOT EXISTS template_version int;

-- 示例草稿：字段与内置布局一致，医保办核对省局接口后可直接发布。
-- 固定主键 + ON CONFLICT：迁移重跑不会产生重复草稿，也不会覆盖人工修改过的内容。
INSERT INTO insurance_svc.export_message_template
    (id, code, name, version, status, fields, created_by, updated_by)
VALUES (
    '6f1c2a7e-9b0d-4f3a-8c5e-2d7b1a9c4e01',
    'PROV_SETTLE_V1',
    '省局医保结算清单报文模板（示例草稿）',
    0,
    'DRAFT',
    '[
      {"element":"billNo","source":"billNo","required":true,"label":"结算单号"},
      {"element":"visitId","source":"visitId","required":false,"label":"就诊ID"},
      {"element":"patientNo","source":"patientNo","required":true,"label":"患者号"},
      {"element":"totalAmount","source":"totalAmount","required":true,"label":"总金额"},
      {"element":"eligibleAmount","source":"eligibleAmount","required":false,"label":"可报金额"},
      {"element":"deductible","source":"deductible","required":false,"label":"起付线"},
      {"element":"insuranceAmount","source":"insuranceAmount","required":true,"label":"医保支付"},
      {"element":"selfPayAmount","source":"selfPayAmount","required":false,"label":"自付金额"},
      {"element":"diagnosisCount","source":"diagnosisCount","required":false,"label":"诊断数"},
      {"element":"primaryDiagnosisCode","source":"primaryDiagnosisCode","required":false,"label":"主诊断编码"},
      {"element":"primaryDiagnosisName","source":"primaryDiagnosisName","required":false,"label":"主诊断名称"},
      {"element":"confirmedBy","source":"confirmedBy","required":false,"label":"确认人"},
      {"element":"confirmedAt","source":"confirmedAt","required":false,"label":"确认时间"}
    ]'::jsonb,
    'system',
    'system'
)
ON CONFLICT (id) DO NOTHING;
