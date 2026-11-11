-- ============================================================================
-- 运营支撑（ops_svc）——第 271 批
--
-- 设计要点：
--   * 服务目录是 SLA 的锚：没有责任组的服务，SLA 无法落地；
--   * SLA 按「服务 × 优先级」配置响应/解决时限，可绑定营业日历（工作日/时段/节假日）；
--   * 工单状态机与事件表分离：状态是当前事实，事件是完整轨迹（追加式，不可改删）；
--   * 挂起会暂停 SLA 计时（暂停时长累计进 sla_paused_total_minutes），恢复时顺延截止时间；
--   * 超时不是"看着红"，而是落成 SLA_RESPONSE_BREACH / SLA_RESOLVE_BREACH 事件，可统计违约率；
--   * 审计表追加式，数据库触发器拒绝 UPDATE/DELETE。
-- ============================================================================

CREATE SCHEMA IF NOT EXISTS ops_svc;

-- ---------------------------------------------------------------------------
-- 1. 营业日历
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ops_svc.ops_calendar (
    code       text PRIMARY KEY,
    name       text NOT NULL,
    timezone   text NOT NULL DEFAULT 'Asia/Shanghai',
    workdays   smallint[] NOT NULL DEFAULT ARRAY[1, 2, 3, 4, 5]::smallint[], -- ISO：1=周一
    start_time time NOT NULL DEFAULT '08:00',
    end_time   time NOT NULL DEFAULT '17:00',
    holidays   date[] NOT NULL DEFAULT '{}',
    status     text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE')),
    version    bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE ops_svc.ops_calendar IS
    '营业日历：SLA 按营业时间计算时使用；holidays 为全天不计时的节假日';

INSERT INTO ops_svc.ops_calendar (code, name, timezone, workdays, start_time, end_time)
VALUES
    ('CN_24X7', '7×24 小时', 'Asia/Shanghai', ARRAY[1,2,3,4,5,6,7]::smallint[], '00:00', '23:59'),
    ('CN_8X5', '工作日 8×5', 'Asia/Shanghai', ARRAY[1,2,3,4,5]::smallint[], '08:00', '17:00')
ON CONFLICT (code) DO NOTHING;

-- ---------------------------------------------------------------------------
-- 2. 服务目录
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ops_svc.ops_service_catalog (
    id            uuid PRIMARY KEY,
    service_code  text NOT NULL UNIQUE,
    name          text NOT NULL,
    domain        text NOT NULL
                  CHECK (domain IN ('PLATFORM', 'INFRA', 'SECURITY', 'INTEGRATION', 'OTHER')),
    owner         text,
    support_group text NOT NULL,
    description   text,
    status        text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE')),
    sort_no       integer NOT NULL DEFAULT 100,
    version       bigint NOT NULL DEFAULT 0,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS ix_ops_service_group ON ops_svc.ops_service_catalog (support_group, status);
COMMENT ON TABLE ops_svc.ops_service_catalog IS
    '服务目录：service_code 与平台服务名对齐；support_group 是接单责任组';

INSERT INTO ops_svc.ops_service_catalog
    (id, service_code, name, domain, owner, support_group, description, sort_no)
VALUES
    ('b1000000-0000-0000-0000-000000000001', 'platform-gateway', '统一网关', 'PLATFORM', '平台组', 'PLATFORM_ONCALL', '认证、路由、限流与入口', 10),
    ('b1000000-0000-0000-0000-000000000002', 'platform-auth', '用户与权限中心', 'PLATFORM', '平台组', 'PLATFORM_ONCALL', '登录、角色、数据范围与令牌', 20),
    ('b1000000-0000-0000-0000-000000000003', 'platform-empi', '患者主索引', 'PLATFORM', '平台组', 'PLATFORM_ONCALL', '建档、合并、跨系统标识', 30),
    ('b1000000-0000-0000-0000-000000000004', 'platform-clinical', '临床核心', 'PLATFORM', '临床组', 'CLINICAL_ONCALL', '挂号、接诊、医嘱、收费、住院', 40),
    ('b1000000-0000-0000-0000-000000000005', 'platform-pharmacy', '药房域', 'PLATFORM', '药事组', 'PHARMACY_ONCALL', '药品、库存、审方、发药', 50),
    ('b1000000-0000-0000-0000-000000000006', 'platform-tech', '医技域', 'PLATFORM', '医技组', 'TECH_ONCALL', '检查检验、标本、报告、危急值', 60),
    ('b1000000-0000-0000-0000-000000000007', 'platform-emr', '病历域', 'PLATFORM', '病案组', 'EMR_ONCALL', '病历、质控、签名、归档', 70),
    ('b1000000-0000-0000-0000-000000000008', 'platform-insurance', '医保域', 'PLATFORM', '医保组', 'INSURANCE_ONCALL', '目录、参保、结算清单', 80),
    ('b1000000-0000-0000-0000-000000000009', 'platform-kb', '知识库', 'PLATFORM', '平台组', 'PLATFORM_ONCALL', '文档解析、检索、问答', 90),
    ('b1000000-0000-0000-0000-000000000010', 'platform-material', '物资耗材', 'PLATFORM', '物资组', 'MATERIAL_ONCALL', '目录、库存、高值追溯', 100),
    ('b1000000-0000-0000-0000-000000000011', 'platform-fhir', 'FHIR 开放层', 'INTEGRATION', '平台组', 'PLATFORM_ONCALL', '对外 FHIR R4 只读接口', 110),
    ('b1000000-0000-0000-0000-000000000012', 'platform-masterdata', '主数据治理', 'PLATFORM', '数据组', 'DATA_ONCALL', '主数据域、映射、审批、版本链', 120),
    ('b1000000-0000-0000-0000-000000000013', 'platform-migration', '历史数据迁移', 'PLATFORM', '实施组', 'IMPLEMENTATION_ONCALL', '迁移模板、预检、应用、回滚', 130),
    ('b1000000-0000-0000-0000-000000000014', 'infra-postgres', '数据库 PostgreSQL', 'INFRA', '运维组', 'INFRA_ONCALL', '主库、备份、连接池与慢查询', 200),
    ('b1000000-0000-0000-0000-000000000015', 'infra-network', '网络与负载均衡', 'INFRA', '运维组', 'INFRA_ONCALL', '院内网络、DNS、入口负载', 210),
    ('b1000000-0000-0000-0000-000000000016', 'infra-monitoring', '监控与告警', 'INFRA', '运维组', 'INFRA_ONCALL', 'Prometheus/Grafana/日志平台', 220),
    ('b1000000-0000-0000-0000-000000000017', 'security-access', '安全与账号', 'SECURITY', '安全组', 'SECURITY_ONCALL', '堡垒机、证书、等保与应急', 300)
ON CONFLICT (service_code) DO NOTHING;

-- ---------------------------------------------------------------------------
-- 3. SLA 策略（服务 × 优先级）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ops_svc.ops_sla_policy (
    id                 uuid PRIMARY KEY,
    service_code       text NOT NULL REFERENCES ops_svc.ops_service_catalog (service_code),
    priority           text NOT NULL CHECK (priority IN ('P1', 'P2', 'P3', 'P4')),
    response_minutes   integer NOT NULL CHECK (response_minutes > 0),
    resolve_minutes    integer NOT NULL CHECK (resolve_minutes > 0),
    escalation_minutes integer NOT NULL DEFAULT 0 CHECK (escalation_minutes >= 0),
    business_hours     boolean NOT NULL DEFAULT false,
    calendar_code      text REFERENCES ops_svc.ops_calendar (code),
    status             text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE')),
    version            bigint NOT NULL DEFAULT 0,
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ux_ops_sla UNIQUE (service_code, priority)
);
COMMENT ON TABLE ops_svc.ops_sla_policy IS
    'SLA 策略：响应/解决时限；business_hours=true 时按 calendar_code 的工作时段累加';

-- 默认策略：P1 15 分钟响应/4 小时解决；P2 30 分钟/8 小时；P3 2 小时/1 个工作日；P4 8 小时/3 个工作日
INSERT INTO ops_svc.ops_sla_policy
    (id, service_code, priority, response_minutes, resolve_minutes, escalation_minutes,
     business_hours, calendar_code)
SELECT gen_random_uuid(), c.service_code, p.priority, p.response_minutes, p.resolve_minutes,
       p.escalation_minutes, p.business_hours, p.calendar_code
  FROM ops_svc.ops_service_catalog c
  CROSS JOIN (VALUES
      ('P1', 15, 240, 30, false, 'CN_24X7'),
      ('P2', 30, 480, 60, false, 'CN_24X7'),
      ('P3', 120, 1440, 240, true, 'CN_8X5'),
      ('P4', 480, 4320, 0, true, 'CN_8X5')
  ) AS p(priority, response_minutes, resolve_minutes, escalation_minutes, business_hours, calendar_code)
ON CONFLICT (service_code, priority) DO NOTHING;

-- ---------------------------------------------------------------------------
-- 4. 工单
-- ---------------------------------------------------------------------------
CREATE SEQUENCE IF NOT EXISTS ops_svc.ops_ticket_no_seq START 1;

CREATE TABLE IF NOT EXISTS ops_svc.ops_ticket (
    id                      uuid PRIMARY KEY,
    ticket_no               text NOT NULL UNIQUE,
    title                   text NOT NULL,
    description             text,
    service_code            text NOT NULL REFERENCES ops_svc.ops_service_catalog (service_code),
    priority                text NOT NULL CHECK (priority IN ('P1', 'P2', 'P3', 'P4')),
    reporter                text NOT NULL,
    reporter_dept           text,
    assignee                text,
    assignee_group          text,
    status                  text NOT NULL
                            CHECK (status IN ('NEW', 'ASSIGNED', 'IN_PROGRESS', 'PENDING',
                                              'RESOLVED', 'CLOSED', 'REOPENED', 'CANCELLED')),
    source                  text NOT NULL DEFAULT 'PORTAL'
                            CHECK (source IN ('PHONE', 'EMAIL', 'PORTAL', 'MONITOR', 'ONCALL', 'OTHER')),
    sla_policy_id           uuid REFERENCES ops_svc.ops_sla_policy (id),
    response_due_at         timestamptz,
    resolve_due_at          timestamptz,
    first_response_at       timestamptz,
    resolved_at             timestamptz,
    closed_at               timestamptz,
    reopened_at             timestamptz,
    reopen_count            integer NOT NULL DEFAULT 0,
    pending_reason          text,
    sla_paused_at           timestamptz,
    sla_paused_total_minutes integer NOT NULL DEFAULT 0,
    satisfaction            integer CHECK (satisfaction BETWEEN 1 AND 5),
    resolution              text,
    tags                    text[] NOT NULL DEFAULT '{}',
    sla_response_breached   boolean NOT NULL DEFAULT false,
    sla_resolve_breached    boolean NOT NULL DEFAULT false,
    last_sla_scan_at        timestamptz,
    version                 bigint NOT NULL DEFAULT 0,
    created_at              timestamptz NOT NULL DEFAULT now(),
    updated_at              timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_ops_ticket_resolved CHECK (
        (status IN ('RESOLVED', 'CLOSED') AND resolved_at IS NOT NULL) OR (status NOT IN ('RESOLVED', 'CLOSED'))
    ),
    CONSTRAINT ck_ops_ticket_closed CHECK (
        (status = 'CLOSED' AND closed_at IS NOT NULL) OR (status <> 'CLOSED')
    ),
    CONSTRAINT ck_ops_ticket_pending CHECK (
        (status = 'PENDING' AND pending_reason IS NOT NULL) OR (status <> 'PENDING')
    )
);
CREATE INDEX IF NOT EXISTS ix_ops_ticket_status ON ops_svc.ops_ticket (status, priority, created_at DESC);
CREATE INDEX IF NOT EXISTS ix_ops_ticket_service ON ops_svc.ops_ticket (service_code, created_at DESC);
CREATE INDEX IF NOT EXISTS ix_ops_ticket_assignee ON ops_svc.ops_ticket (assignee, status);
CREATE INDEX IF NOT EXISTS ix_ops_ticket_sla ON ops_svc.ops_ticket (resolve_due_at)
    WHERE status NOT IN ('RESOLVED', 'CLOSED', 'CANCELLED');
COMMENT ON TABLE ops_svc.ops_ticket IS
    '工单：状态机 + SLA 截止时间；挂起暂停 SLA，恢复时顺延截止时间';

-- ---------------------------------------------------------------------------
-- 5. 工单事件（追加式）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ops_svc.ops_ticket_event (
    id          bigserial PRIMARY KEY,
    ticket_id   uuid NOT NULL REFERENCES ops_svc.ops_ticket (id) ON DELETE CASCADE,
    ts          timestamptz NOT NULL DEFAULT now(),
    actor       text NOT NULL,
    action      text NOT NULL
                CHECK (action IN ('CREATE', 'ASSIGN', 'ACCEPT', 'PROGRESS', 'COMMENT',
                                  'PENDING', 'RESUME', 'RESOLVE', 'CLOSE', 'REOPEN', 'CANCEL',
                                  'PRIORITY_CHANGE', 'SLA_RESPONSE_BREACH', 'SLA_RESOLVE_BREACH',
                                  'ESCALATE')),
    from_status text,
    to_status   text,
    note        text,
    detail      jsonb
);
CREATE INDEX IF NOT EXISTS ix_ops_ticket_event ON ops_svc.ops_ticket_event (ticket_id, ts);
COMMENT ON TABLE ops_svc.ops_ticket_event IS
    '工单轨迹：谁在何时把状态从哪里改到哪里、说了什么；只允许追加';

-- ---------------------------------------------------------------------------
-- 6. 审计（追加式）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ops_svc.ops_change_log (
    id         bigserial PRIMARY KEY,
    ts         timestamptz NOT NULL DEFAULT now(),
    actor      text NOT NULL,
    action     text NOT NULL
               CHECK (action IN ('TICKET_CREATE', 'TICKET_TRANSITION', 'TICKET_ASSIGN',
                                 'TICKET_COMMENT', 'TICKET_PRIORITY', 'SLA_SCAN',
                                 'POLICY_UPSERT', 'CALENDAR_UPSERT', 'SERVICE_UPSERT')),
    target_ref text,
    detail     jsonb,
    client_ip  text,
    trace_id   text
);
CREATE INDEX IF NOT EXISTS ix_ops_change_ts ON ops_svc.ops_change_log (ts DESC);
CREATE INDEX IF NOT EXISTS ix_ops_change_target ON ops_svc.ops_change_log (target_ref, ts DESC);

CREATE OR REPLACE FUNCTION ops_svc.reject_audit_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION '% 是追加式审计表，禁止 %', TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'insufficient_privilege';
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_ops_change_log_append_only ON ops_svc.ops_change_log;
CREATE TRIGGER trg_ops_change_log_append_only
    BEFORE UPDATE OR DELETE ON ops_svc.ops_change_log
    FOR EACH ROW EXECUTE FUNCTION ops_svc.reject_audit_mutation();

DROP TRIGGER IF EXISTS trg_ops_ticket_event_append_only ON ops_svc.ops_ticket_event;
CREATE TRIGGER trg_ops_ticket_event_append_only
    BEFORE UPDATE OR DELETE ON ops_svc.ops_ticket_event
    FOR EACH ROW EXECUTE FUNCTION ops_svc.reject_audit_mutation();

COMMENT ON FUNCTION ops_svc.reject_audit_mutation() IS
    '运营审计与工单轨迹只允许 INSERT：处理过程不可改写';
