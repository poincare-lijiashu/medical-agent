-- ============================================================================
-- V74：HL7 v2 联调测试台（第 301 批 / 计划书 G2）
--
-- 目标：把 ADT/ORM/ORU 的联调从"手工用工具发一条看一眼"变成可重复的测试：
--   * 端点（host/port/timeout）与测试用例（报文/期望 ACK/必填段）都是配置；
--   * 执行前做本地结构校验（MSH、消息类型、控制号、必填段），发送后解析 MSA；
--   * 每次执行保留 ACK 码、控制号、耗时与报文摘要，失败可回放定位；
--   * 报文可能含患者信息：摘要只保留前 2000 字符，页面明确提示使用合成数据。
-- ============================================================================

CREATE TABLE IF NOT EXISTS clinical_svc.hl7_endpoint (
    id          uuid PRIMARY KEY,
    code        text NOT NULL UNIQUE,
    name        text NOT NULL,
    host        text NOT NULL,
    port        integer NOT NULL CHECK (port BETWEEN 1 AND 65535),
    timeout_ms  integer NOT NULL DEFAULT 5000 CHECK (timeout_ms BETWEEN 500 AND 60000),
    description text,
    status      text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE')),
    version     bigint NOT NULL DEFAULT 0,
    created_by  text NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE clinical_svc.hl7_endpoint IS
    'HL7 联调端点：MLLP host/port/超时；停用后不可执行用例';

CREATE TABLE IF NOT EXISTS clinical_svc.hl7_case (
    id                uuid PRIMARY KEY,
    code              text NOT NULL UNIQUE,
    name              text NOT NULL,
    message_type      text NOT NULL,
    trigger_event     text NOT NULL,
    sample_message    text NOT NULL,
    expected_ack_code text NOT NULL DEFAULT 'AA' CHECK (expected_ack_code IN ('AA', 'AE', 'AR')),
    required_segments text[] NOT NULL DEFAULT '{MSH}',
    status            text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE')),
    version           bigint NOT NULL DEFAULT 0,
    created_by        text NOT NULL,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE clinical_svc.hl7_case IS
    'HL7 测试用例：报文、期望 ACK（AA/AE/AR）与必填段；报文必须使用合成数据';

CREATE SEQUENCE IF NOT EXISTS clinical_svc.hl7_run_no_seq START 1;

CREATE TABLE IF NOT EXISTS clinical_svc.hl7_run (
    id               uuid PRIMARY KEY,
    run_no           text NOT NULL UNIQUE,
    case_id          uuid NOT NULL REFERENCES clinical_svc.hl7_case (id) ON DELETE CASCADE,
    case_code        text NOT NULL,
    endpoint_id      uuid NOT NULL REFERENCES clinical_svc.hl7_endpoint (id) ON DELETE CASCADE,
    endpoint_code    text NOT NULL,
    status           text NOT NULL CHECK (status IN ('PASSED', 'FAILED', 'ERROR')),
    ack_code         text,
    ack_control_id   text,
    ack_message      text,
    request_excerpt  text,
    response_excerpt text,
    error_message    text,
    validation       text,
    duration_ms      integer NOT NULL DEFAULT 0 CHECK (duration_ms >= 0),
    triggered_by     text NOT NULL,
    started_at       timestamptz NOT NULL,
    finished_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS ix_hl7_run_case ON clinical_svc.hl7_run (case_code, started_at DESC);
CREATE INDEX IF NOT EXISTS ix_hl7_run_status ON clinical_svc.hl7_run (status, started_at DESC);
COMMENT ON TABLE clinical_svc.hl7_run IS
    'HL7 联调执行记录：ACK 码/控制号/耗时/报文摘要；摘要只用于定位，不作为业务数据';

-- ---------------------------------------------------------------------------
-- 内置用例（合成数据；可直接对测试端点执行）
-- ---------------------------------------------------------------------------
INSERT INTO clinical_svc.hl7_case
    (id, code, name, message_type, trigger_event, sample_message, expected_ack_code,
     required_segments, status, created_by)
VALUES
    ('c1000000-0000-4000-8000-000000000001', 'ADT-A01', '住院患者入院 ADT^A01', 'ADT', 'A01',
     E'MSH|^~\\&|HIS|DEMO-HOSP|MEDASSIST|DEMO-HOSP|20261113090000||ADT^A01|ADT2026111300001|P|2.5\rEVN|A01|20261113090000\rPID|1||P000001||张三||19800101|M\rPV1|1|I|WARD01^01^01',
     'AA', '{MSH,EVN,PID,PV1}', 'ACTIVE', 'system'),
    ('c1000000-0000-4000-8000-000000000002', 'ORM-O01', '检验申请 ORM^O01', 'ORM', 'O01',
     E'MSH|^~\\&|HIS|DEMO-HOSP|MEDASSIST|DEMO-HOSP|20261113091000||ORM^O01|ORM2026111300001|P|2.5\rPID|1||P000001||张三||19800101|M\rORC|NW|ORD0001\rOBR|1|ORD0001||CBC^血常规',
     'AA', '{MSH,PID,ORC,OBR}', 'ACTIVE', 'system'),
    ('c1000000-0000-4000-8000-000000000003', 'ORU-R01', '检验结果 ORU^R01', 'ORU', 'R01',
     E'MSH|^~\\&|LIS|DEMO-HOSP|MEDASSIST|DEMO-HOSP|20261113092000||ORU^R01|ORU2026111300001|P|2.5\rPID|1||P000001||张三||19800101|M\rOBR|1|ORD0001||CBC^血常规\rOBX|1|NM|WBC||6.2|10^9/L|4.0-10.0|N|||F',
     'AA', '{MSH,PID,OBR,OBX}', 'ACTIVE', 'system')
ON CONFLICT (code) DO NOTHING;
