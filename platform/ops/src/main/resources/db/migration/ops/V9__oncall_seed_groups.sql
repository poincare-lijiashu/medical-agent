-- ============================================================================
-- V9：补齐值班轮换引导数据（评测 D-14 修正）
--
-- V8 的 `WHERE NOT EXISTS (SELECT 1 FROM ops_svc.ops_oncall_rotation)` 是逐行求值：
-- 插入第一行后表就不再为空，后续支撑组被整体跳过（2026-11-13 实测只落了 1 条）。
-- 本迁移改为"逐组判断该组是否已有轮换"，幂等且能补齐全部支撑组；
-- 已配置真实值班表的组不会被覆盖（ON CONFLICT + 存在性判断）。
-- ============================================================================

WITH groups AS (
    SELECT DISTINCT support_group
      FROM ops_svc.ops_service_catalog
     WHERE status = 'ACTIVE'
       AND support_group IS NOT NULL
       AND support_group <> ''
)
INSERT INTO ops_svc.ops_oncall_rotation
    (id, group_code, name, timezone, start_date, cycle_days, members, handover_time, status)
SELECT gen_random_uuid(),
       g.support_group,
       g.support_group || ' 一线值班',
       'Asia/Shanghai',
       CURRENT_DATE - 1,
       7,
       ARRAY['admin01'],
       '08:00',
       'ACTIVE'
  FROM groups g
 WHERE NOT EXISTS (SELECT 1
                     FROM ops_svc.ops_oncall_rotation r
                    WHERE r.group_code = g.support_group)
ON CONFLICT (group_code) DO NOTHING;
