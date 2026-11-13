-- ============================================================================
-- V8：值班轮换引导数据（评测 D-14）
--
-- 问题：值班表只在人工"生成"后才存在，未生成时 /ops/oncall/today 返回空，
--       超时工单升级时找不到值班人（升级时限 escalation_minutes 无人消费）。
-- 做法：仅在值班轮换表为空时，按服务目录的支撑组各建一个引导班次表，
--       成员暂为 admin01。正式上线前必须按院内真实值班表替换（成员/周期/交接时间）。
-- 幂等：只在没有任何轮换记录时插入，重复执行不会覆盖已配置的值班表。
-- ============================================================================

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
  FROM (SELECT DISTINCT support_group
          FROM ops_svc.ops_service_catalog
         WHERE status = 'ACTIVE'
           AND support_group IS NOT NULL
           AND support_group <> '') AS g
 WHERE NOT EXISTS (SELECT 1 FROM ops_svc.ops_oncall_rotation)
ON CONFLICT (group_code) DO NOTHING;
