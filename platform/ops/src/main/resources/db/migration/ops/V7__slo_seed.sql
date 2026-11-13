-- ============================================================================
-- V7：SLO 目标种子（评测 E8-05 / D-07）
--
-- 为什么必须内置：错误预算治理只有在"目标存在"时才可能生效。此前 ops_slo 空表，
-- 上线指挥台的 SLO 因子恒为 SKIP，错误预算从未参与任何 Go/No-Go 决策。
-- 目标值取自 docs/SLO-20261111.md 的 SLO 清单（10 条），与运维文档单一事实源对齐。
--
-- 幂等：ON CONFLICT (code) DO NOTHING —— 已由运维调整过的目标不会被迁移覆盖。
-- ============================================================================

INSERT INTO ops_svc.ops_slo
    (id, code, name, service_code, indicator, target_percent, window_days,
     latency_threshold_ms, owner, description, status, created_by)
VALUES
    ('c1000000-0000-0000-0000-000000000001', 'SLO-GW-AVAIL', '网关可用性',
     'platform-gateway', 'AVAILABILITY', 99.950, 30, NULL, '平台组',
     '1 - 5xx/全部请求；快烧 14.4x(5m/1h)、慢烧 6x(30m/6h)', 'ACTIVE', 'system-seed'),
    ('c1000000-0000-0000-0000-000000000002', 'SLO-AUTH-AVAIL', '认证可用性',
     'platform-auth', 'AVAILABILITY', 99.950, 30, NULL, '平台组',
     '1 - 5xx/全部请求；快烧 14.4x(5m/1h)', 'ACTIVE', 'system-seed'),
    ('c1000000-0000-0000-0000-000000000003', 'SLO-CLIN-READ-LAT', '临床核心读延迟',
     'platform-clinical', 'LATENCY', 99.000, 30, 500, '临床组',
     'GET 成功请求 P95 < 500ms', 'ACTIVE', 'system-seed'),
    ('c1000000-0000-0000-0000-000000000004', 'SLO-CLIN-WRITE-LAT', '临床核心写延迟',
     'platform-clinical', 'LATENCY', 99.000, 30, 1000, '临床组',
     '非 GET 成功请求 P95 < 1s', 'ACTIVE', 'system-seed'),
    ('c1000000-0000-0000-0000-000000000005', 'SLO-PHARM-LAT', '药事延迟',
     'platform-pharmacy', 'LATENCY', 99.000, 30, 800, '药事组',
     '成功请求 P95 < 800ms', 'ACTIVE', 'system-seed'),
    ('c1000000-0000-0000-0000-000000000006', 'SLO-TECH-LAT', '医技延迟',
     'platform-tech', 'LATENCY', 99.000, 30, 800, '医技组',
     '成功请求 P95 < 800ms', 'ACTIVE', 'system-seed'),
    ('c1000000-0000-0000-0000-000000000007', 'SLO-AI-AVAIL', 'AI 中台可用性',
     'platform-kb', 'AVAILABILITY', 99.500, 30, NULL, '平台组',
     '1 - 5xx/全部请求（AI 中台经 kb 域登记）', 'ACTIVE', 'system-seed'),
    ('c1000000-0000-0000-0000-000000000008', 'SLO-AI-LAT', 'AI 中台响应延迟',
     'platform-kb', 'LATENCY', 99.000, 30, 2000, '平台组',
     '首包 < 2s（完整响应 < 30s 由集成层补充）', 'ACTIVE', 'system-seed'),
    ('c1000000-0000-0000-0000-000000000009', 'SLO-INT-ACK', '集成通道 ACK 成功率',
     'platform-fhir', 'ERROR_RATE', 99.900, 30, NULL, '平台组',
     'ACK 成功/全部；DEAD < 0.1%', 'ACTIVE', 'system-seed'),
    ('c1000000-0000-0000-0000-000000000010', 'SLO-GW-THROTTLE', '网关限流率',
     'platform-gateway', 'ERROR_RATE', 99.000, 30, NULL, '平台组',
     '429 占比 < 1%（运维护栏）；告警 > 2% 持续 10m', 'ACTIVE', 'system-seed')
ON CONFLICT (code) DO NOTHING;

COMMENT ON TABLE ops_svc.ops_slo IS
    'SLO 定义：目标百分比与窗口；LATENCY 必须有延迟阈值（好事件=低于阈值）；'
    '内置 10 条与 docs/SLO-20261111.md 对齐';
