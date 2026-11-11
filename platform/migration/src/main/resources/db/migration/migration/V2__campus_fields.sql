-- ============================================================================
-- V2：院区字段（第 270 批）
--
-- 背景：clinical_svc.visit / charge / ward 在后续批次增加了 NOT NULL 的 campus_id
-- （多院区隔离边界）。迁移如果不提供院区，会在 INSERT 阶段触发 not-null 约束。
--
-- 口径：
--   * campus_code 可选；源数据提供时按编码解析（仅 ACTIVE）；
--   * 未提供时，仅当系统只有**一个**启用院区才自动采用；多院区必须由源数据明确指定，
--     迁移工具绝不猜测院区归属（猜错等于把患者数据放进别家院区的账）。
-- ============================================================================

INSERT INTO mig_svc.mig_field
    (target_code, field_key, label, data_type, required, max_length, enum_values,
     reference_target, description, sort_no)
VALUES
    ('CLINICAL_WARD', 'campus_code', '院区编码', 'TEXT', false, 32, NULL, NULL,
     '留空时仅在系统只有一个启用院区时自动采用', 40),
    ('CLINICAL_VISIT', 'campus_code', '院区编码', 'TEXT', false, 32, NULL, NULL,
     '留空时仅在系统只有一个启用院区时自动采用', 110),
    ('CLINICAL_CHARGE', 'campus_code', '院区编码', 'TEXT', false, 32, NULL, NULL,
     '留空时仅在系统只有一个启用院区时自动采用', 80)
ON CONFLICT (target_code, field_key) DO NOTHING;
