-- ============================================================================
-- G4：病案编码与 ICD 字典权限点
--
-- 分权：
--   * icd:read     —— 编码时检索 ICD-10 / ICD-9-CM-3 字典；
--   * icd:manage   —— 字典新增与启停（病案室主任/信息科），直接决定全院编码口径；
--   * coding:read  —— 查看待编码队列与编码详情；
--   * coding:write —— 保存编码草稿；
--   * coding:submit—— 提交编码（回写首页诊断/手术，之后才可归档）。
-- 编码与归档分属不同岗位：编码员完成编码，质控/病案室负责人归档，满足四眼要求。
-- ============================================================================

INSERT INTO auth_svc.permission (code, name, domain) VALUES
    ('emr:icd:read',     'ICD 字典查询',           'emr'),
    ('emr:icd:manage',   'ICD 字典维护与启停',       'emr'),
    ('emr:coding:read',  '病案编码队列与详情查询',   'emr'),
    ('emr:coding:write', '病案编码草稿保存',         'emr'),
    ('emr:coding:submit','病案编码提交（回写首页）',  'emr')
ON CONFLICT (code) DO NOTHING;

-- ADMIN：全部
INSERT INTO auth_svc.role_permission (role_code, permission_code) VALUES
    ('ADMIN', 'emr:icd:read'),
    ('ADMIN', 'emr:icd:manage'),
    ('ADMIN', 'emr:coding:read'),
    ('ADMIN', 'emr:coding:write'),
    ('ADMIN', 'emr:coding:submit')
ON CONFLICT DO NOTHING;

-- QC（病案质控/编码员）：编码全流程 + 字典查询；字典维护按院内岗位再单独授予
INSERT INTO auth_svc.role_permission (role_code, permission_code) VALUES
    ('QC', 'emr:icd:read'),
    ('QC', 'emr:coding:read'),
    ('QC', 'emr:coding:write'),
    ('QC', 'emr:coding:submit')
ON CONFLICT DO NOTHING;

-- DOCTOR：只读编码结果（出院小结/首页沟通时核对）
INSERT INTO auth_svc.role_permission (role_code, permission_code) VALUES
    ('DOCTOR', 'emr:icd:read'),
    ('DOCTOR', 'emr:coding:read')
ON CONFLICT DO NOTHING;
