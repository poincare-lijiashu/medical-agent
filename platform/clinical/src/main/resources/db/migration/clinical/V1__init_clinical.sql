-- ============================================================================
-- 临床核心（clinical_svc）初始表结构：科室 / 收费项目 / 号源 / 就诊 / 医嘱 / 收费
--
-- 设计约束（对应 docs/CLINICAL-CORE-ROADMAP.md）：
--   * 患者主索引归 EMPI（empi_svc.patient），本 schema 只存 patient_id 引用与
--     patient_no 快照，**不建跨 schema 外键**——服务拆分到独立库时不需要改表；
--   * 号源扣减走「行锁 + CAS」，同一患者同日同科室只允许一个在途就诊（部分唯一索引兜底）；
--   * 医嘱金额按收费项目主数据计价，客户端不得传价；
--   * 收费按 order_id 唯一幂等：重复结算不会产生第二笔费用；
--   * 所有写操作落 clinical_change_log（追加式，不提供删除接口）。
-- ============================================================================

CREATE SCHEMA IF NOT EXISTS clinical_svc;

-- ---- 科室主数据 ----
CREATE TABLE IF NOT EXISTS clinical_svc.md_dept (
    id         uuid PRIMARY KEY,
    dept_code  text NOT NULL UNIQUE,
    name       text NOT NULL,
    type       text NOT NULL DEFAULT 'OUTPATIENT'
               CHECK (type IN ('OUTPATIENT', 'INPATIENT', 'BOTH')),
    enabled    boolean NOT NULL DEFAULT true,
    version    bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE clinical_svc.md_dept IS '科室主数据（上线前由院内组织架构同步）';

-- ---- 收费项目主数据（物价）----
CREATE TABLE IF NOT EXISTS clinical_svc.md_charge_item (
    id         uuid PRIMARY KEY,
    item_code  text NOT NULL UNIQUE,
    name       text NOT NULL,
    category   text NOT NULL
               CHECK (category IN ('REGISTRATION', 'CONSULTATION', 'DRUG', 'TREATMENT', 'EXAM', 'MATERIAL')),
    unit       text NOT NULL DEFAULT '次',
    price      numeric(12, 2) NOT NULL CHECK (price >= 0),
    enabled    boolean NOT NULL DEFAULT true,
    version    bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE clinical_svc.md_charge_item IS '收费项目主数据（价格唯一真源，医嘱计价只读这里）';

-- ---- 号源（排班容量）----
CREATE TABLE IF NOT EXISTS clinical_svc.reg_slot (
    id         uuid PRIMARY KEY,
    dept_id    uuid NOT NULL REFERENCES clinical_svc.md_dept (id),
    doctor_id  text NOT NULL,
    work_date  date NOT NULL,
    period     text NOT NULL CHECK (period IN ('AM', 'PM', 'NIGHT')),
    total      int  NOT NULL CHECK (total > 0),
    booked     int  NOT NULL DEFAULT 0 CHECK (booked >= 0),
    version    bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_slot_capacity CHECK (booked <= total),
    CONSTRAINT ux_slot UNIQUE (dept_id, doctor_id, work_date, period)
);
CREATE INDEX IF NOT EXISTS ix_slot_query ON clinical_svc.reg_slot (work_date, dept_id);
COMMENT ON TABLE clinical_svc.reg_slot IS '号源：total 容量 / booked 已挂号数（扣减必须行锁 + CAS）';

-- ---- 就诊（门诊一次挂号 = 一条 visit）----
CREATE TABLE IF NOT EXISTS clinical_svc.visit (
    id            uuid PRIMARY KEY,
    visit_no      text NOT NULL UNIQUE,             -- VYYYYMMDD######
    patient_id    uuid NOT NULL,                    -- EMPI 患者主键（跨服务引用，不建外键）
    patient_no    text NOT NULL,                    -- 挂号时快照，便于收费/病历打印
    dept_id       uuid NOT NULL REFERENCES clinical_svc.md_dept (id),
    doctor_id     text NOT NULL,
    slot_id       uuid REFERENCES clinical_svc.reg_slot (id),
    visit_type    text NOT NULL DEFAULT 'OUTPATIENT'
                  CHECK (visit_type IN ('OUTPATIENT', 'EMERGENCY')),
    status        text NOT NULL DEFAULT 'REGISTERED'
                  CHECK (status IN ('REGISTERED', 'IN_TREATMENT', 'FINISHED', 'CANCELLED')),
    visit_date    date NOT NULL,
    registered_at timestamptz NOT NULL DEFAULT now(),
    started_at    timestamptz,
    finished_at   timestamptz,
    version       bigint NOT NULL DEFAULT 0,
    updated_at    timestamptz NOT NULL DEFAULT now()
);
-- 同一患者同一天同一科室只允许一个"在途"就诊（已取消/已结束不占额度）
CREATE UNIQUE INDEX IF NOT EXISTS ux_visit_active
    ON clinical_svc.visit (patient_id, dept_id, visit_date)
    WHERE status IN ('REGISTERED', 'IN_TREATMENT');
CREATE INDEX IF NOT EXISTS ix_visit_patient ON clinical_svc.visit (patient_id, visit_date DESC);
CREATE INDEX IF NOT EXISTS ix_visit_dept_date ON clinical_svc.visit (dept_id, visit_date);
COMMENT ON TABLE clinical_svc.visit IS '门诊就诊（状态机：REGISTERED→IN_TREATMENT→FINISHED，或 REGISTERED→CANCELLED）';

-- ---- 医嘱（开立即计费前提；金额按主数据计算）----
CREATE TABLE IF NOT EXISTS clinical_svc.visit_order (
    id         uuid PRIMARY KEY,
    order_no   text NOT NULL UNIQUE,                -- OYYYYMMDD######
    visit_id   uuid NOT NULL REFERENCES clinical_svc.visit (id),
    order_type text NOT NULL
               CHECK (order_type IN ('DRUG', 'TREATMENT', 'EXAM', 'MATERIAL')),
    item_code  text NOT NULL,
    item_name  text NOT NULL,
    dose       text,
    frequency  text,
    quantity   int NOT NULL CHECK (quantity > 0),
    unit       text NOT NULL,
    unit_price numeric(12, 2) NOT NULL CHECK (unit_price >= 0),
    amount     numeric(12, 2) NOT NULL CHECK (amount >= 0),
    status     text NOT NULL DEFAULT 'PLACED'
               CHECK (status IN ('PLACED', 'CHARGED', 'CANCELLED')),
    ordered_by text NOT NULL,
    version    bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS ix_order_visit ON clinical_svc.visit_order (visit_id, status);
COMMENT ON TABLE clinical_svc.visit_order IS '医嘱（PLACED→CHARGED / PLACED→CANCELLED；金额由服务端按主数据计算）';

-- ---- 收费单（一次就诊一张，幂等）----
CREATE TABLE IF NOT EXISTS clinical_svc.charge (
    id             uuid PRIMARY KEY,
    charge_no      text NOT NULL UNIQUE,            -- CYYYYMMDD######
    visit_id       uuid NOT NULL UNIQUE REFERENCES clinical_svc.visit (id),
    total_amount   numeric(12, 2) NOT NULL DEFAULT 0 CHECK (total_amount >= 0),
    status         text NOT NULL DEFAULT 'UNPAID'
                   CHECK (status IN ('UNPAID', 'PAID', 'REFUNDED')),
    payment_method text,
    paid_at        timestamptz,
    version        bigint NOT NULL DEFAULT 0,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_charge_paid CHECK (
        (status = 'PAID' AND paid_at IS NOT NULL) OR (status <> 'PAID')
    )
);
CREATE INDEX IF NOT EXISTS ix_charge_status ON clinical_svc.charge (status, created_at);

-- ---- 费用明细（order_id 唯一：同一医嘱不可重复计费）----
CREATE TABLE IF NOT EXISTS clinical_svc.charge_item (
    id         uuid PRIMARY KEY,
    charge_id  uuid NOT NULL REFERENCES clinical_svc.charge (id) ON DELETE CASCADE,
    order_id   uuid UNIQUE,                         -- 挂号费为 NULL；唯一约束保证医嘱只计一次
    item_code  text NOT NULL,
    item_name  text NOT NULL,
    category   text NOT NULL,
    amount     numeric(12, 2) NOT NULL CHECK (amount >= 0),
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS ix_charge_item_charge ON clinical_svc.charge_item (charge_id);
COMMENT ON TABLE clinical_svc.charge_item IS '费用明细（order_id 唯一 → 结算幂等的数据库级保证）';

-- ---- 审计（追加式）----
CREATE TABLE IF NOT EXISTS clinical_svc.clinical_change_log (
    id          bigserial PRIMARY KEY,
    ts          timestamptz NOT NULL DEFAULT now(),
    actor       text NOT NULL,
    action      text NOT NULL,
    target_type text NOT NULL,
    target_id   text NOT NULL,
    detail      jsonb
);
CREATE INDEX IF NOT EXISTS ix_clinical_change_target
    ON clinical_svc.clinical_change_log (target_type, target_id);
CREATE INDEX IF NOT EXISTS ix_clinical_change_ts ON clinical_svc.clinical_change_log (ts DESC);

-- ---- 单号序列 ----
CREATE SEQUENCE IF NOT EXISTS clinical_svc.visit_no_seq START 1;
CREATE SEQUENCE IF NOT EXISTS clinical_svc.order_no_seq START 1;
CREATE SEQUENCE IF NOT EXISTS clinical_svc.charge_no_seq START 1;

-- ============================================================================
-- 初始主数据（示例，上线前必须由院内物价/组织架构字典替换或同步覆盖）
-- 说明：这里只放"能让流程跑通的最小集合"，不造业务数据（患者/医嘱/费用一律不预置）。
-- ============================================================================
INSERT INTO clinical_svc.md_dept (id, dept_code, name, type)
VALUES
    ('11111111-1111-1111-1111-111111111111', 'DEPT-IM', '内科门诊', 'OUTPATIENT'),
    ('22222222-2222-2222-2222-222222222222', 'DEPT-SURG', '外科门诊', 'OUTPATIENT')
ON CONFLICT (dept_code) DO NOTHING;

INSERT INTO clinical_svc.md_charge_item (id, item_code, name, category, unit, price)
VALUES
    ('33333333-3333-3333-3333-333333333333', 'REG-GENERAL', '普通门诊诊查费', 'CONSULTATION', '次', 10.00),
    ('33333333-3333-3333-3333-333333333334', 'REG-EXPERT', '专家门诊诊查费', 'CONSULTATION', '次', 30.00),
    ('33333333-3333-3333-3333-333333333335', 'REG-EMERGENCY', '急诊诊查费', 'CONSULTATION', '次', 15.00),
    ('33333333-3333-3333-3333-333333333336', 'TRT-IV', '静脉输液', 'TREATMENT', '次', 8.00),
    ('33333333-3333-3333-3333-333333333337', 'EXM-CBC', '血常规', 'EXAM', '次', 25.00)
ON CONFLICT (item_code) DO NOTHING;
