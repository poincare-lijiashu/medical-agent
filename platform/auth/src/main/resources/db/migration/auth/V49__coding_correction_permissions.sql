-- ============================================================================
-- G10：病案编码更正权限点
--
-- 分权思路：
--   * coding:correct —— 提交/撤回编码更正申请（病案室编码员）；
--   * coding:approve —— 审批/驳回更正申请（病案室负责人/质控）。
-- 四眼原则：审批人不得是申请人，由 emr 服务在状态变更前强制校验；
-- 与药品报损/耗材退回同一模式，权限点只是第一层。
-- ============================================================================

INSERT INTO auth_svc.permission (code, name, domain) VALUES
    ('emr:coding:correct', '病案编码更正（提交/撤回）', 'emr'),
    ('emr:coding:approve', '病案编码更正审批/驳回',      'emr')
ON CONFLICT (code) DO NOTHING;

INSERT INTO auth_svc.role_permission (role_code, permission_code) VALUES
    ('ADMIN', 'emr:coding:correct'),
    ('ADMIN', 'emr:coding:approve'),
    ('QC',    'emr:coding:correct'),
    ('QC',    'emr:coding:approve')
ON CONFLICT DO NOTHING;
