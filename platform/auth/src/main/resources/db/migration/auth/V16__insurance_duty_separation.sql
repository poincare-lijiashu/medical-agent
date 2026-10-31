-- ============================================================================
-- V16：医保结算确认权独立 + 医保办角色（职责分离）
--
-- 背景（第三轮评测 S-16）：V15 把"目录维护 + 出单 + 确认"都给了 REGISTRAR（窗口），
-- 单人即可完成"改映射（丙类→甲类）→ 出单 → 确认"的全链闭环，属于医保合规风险。
--
-- 新分工：
--   * REGISTRAR（收费/医保窗口）：参保登记 + 出单，**不再持有目录维护与确认权**；
--   * INSURANCE_OFFICER（医保办）：目录维护 + 确认/作废，**不出单**；
--   * ADMIN：全部（保留超级权限用于应急与运维）。
-- 这样任意一条结算链路至少需要两个岗位，且改目录的人无法自己确认自己出的单。
--
-- 不可改已有迁移（Flyway checksum），因此这里显式撤销 REGISTRAR 的 catalog:manage。
-- ============================================================================

INSERT INTO auth_svc.permission (code, name, domain) VALUES
    ('insurance:settlement:confirm', '医保结算确认/作废', 'insurance')
ON CONFLICT (code) DO NOTHING;

INSERT INTO auth_svc.role (code, name, description, builtin) VALUES
    ('INSURANCE_OFFICER', '医保办', '医保目录维护与结算复核确认（与窗口岗分离）', true)
ON CONFLICT (code) DO NOTHING;

-- ADMIN：补新权限
INSERT INTO auth_svc.role_permission (role_code, permission_code) VALUES
    ('ADMIN', 'insurance:settlement:confirm')
ON CONFLICT DO NOTHING;

-- 医保办：目录维护 + 参保查询/登记 + 结算查询/确认（不给 settlement:write）
INSERT INTO auth_svc.role_permission (role_code, permission_code) VALUES
    ('INSURANCE_OFFICER', 'insurance:catalog:manage'),
    ('INSURANCE_OFFICER', 'insurance:member:manage'),
    ('INSURANCE_OFFICER', 'insurance:settlement:read'),
    ('INSURANCE_OFFICER', 'insurance:settlement:confirm'),
    ('INSURANCE_OFFICER', 'empi:patient:read')
ON CONFLICT DO NOTHING;

-- 窗口：撤销目录维护（目录对照属医保办职责）
DELETE FROM auth_svc.role_permission
 WHERE role_code = 'REGISTRAR'
   AND permission_code = 'insurance:catalog:manage';

-- 窗口：保留出单权，明确不授予确认权（若历史数据误授，这里一并收回）
DELETE FROM auth_svc.role_permission
 WHERE role_code = 'REGISTRAR'
   AND permission_code = 'insurance:settlement:confirm';
