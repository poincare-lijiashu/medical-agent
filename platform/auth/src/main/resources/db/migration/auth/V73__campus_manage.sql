-- ============================================================================
-- V73：院区字典管理（G46）
--   auth:campus:read    院区查看（管理端院区页、账号院区分配弹窗）
--   auth:campus:manage  院区新建/改名/启停
--
-- campus 表在 V72 建表时未带乐观锁版本，这里补 version 供维护操作走 CAS。
-- ============================================================================

ALTER TABLE auth_svc.campus ADD COLUMN IF NOT EXISTS version bigint NOT NULL DEFAULT 0;

INSERT INTO auth_svc.permission (code, name, domain) VALUES
    ('auth:campus:read', '院区查看', 'auth'),
    ('auth:campus:manage', '院区维护', 'auth')
ON CONFLICT (code) DO NOTHING;

INSERT INTO auth_svc.role_permission (role_code, permission_code) VALUES
    ('ADMIN', 'auth:campus:read'),
    ('ADMIN', 'auth:campus:manage')
ON CONFLICT DO NOTHING;
