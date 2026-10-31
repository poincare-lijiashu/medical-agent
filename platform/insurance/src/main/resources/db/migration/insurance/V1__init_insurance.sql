-- ============================================================================
-- 医保域（insurance_svc）初始表结构：医保目录 / 院内映射 / 参保登记 / 结算清单
--
-- 边界说明：
--   * 真实医保结算必须走省级医保接口（专网 + 前置机 + 数字证书），本服务只做
--     "本地可验收"的部分：目录维护、院内项目映射、参保信息快照、结算清单测算与留档；
--   * 结算金额全部由服务端按目录支付类别与参保待遇计算，请求体不接受金额；
--   * 收费明细的唯一真源是临床域，结算清单生成时经内网接口拉取，不复制收费表；
--   * 一张就诊单只允许一张结算清单（DRAFT 可重算，CONFIRMED 不可再改）。
-- ============================================================================

CREATE SCHEMA IF NOT EXISTS insurance_svc;

-- ---- 医保目录（药品/诊疗/耗材/诊断）----
CREATE TABLE IF NOT EXISTS insurance_svc.md_insurance_item (
    id             uuid PRIMARY KEY,
    insurance_code text NOT NULL UNIQUE,                      -- 国家/省级医保编码
    name           text NOT NULL,
    category       text NOT NULL CHECK (category IN ('DRUG', 'SERVICE', 'MATERIAL', 'DIAGNOSIS')),
    pay_category   text NOT NULL CHECK (pay_category IN ('A', 'B', 'C')),
    self_pay_ratio numeric(5, 4) NOT NULL DEFAULT 0
                   CHECK (self_pay_ratio >= 0 AND self_pay_ratio <= 1),
    price_limit    numeric(12, 2) CHECK (price_limit IS NULL OR price_limit >= 0),
    enabled        boolean NOT NULL DEFAULT true,
    version        bigint NOT NULL DEFAULT 0,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS ix_ins_item_category ON insurance_svc.md_insurance_item (category, enabled);

-- ---- 院内收费项目 → 医保目录 映射 ----
CREATE TABLE IF NOT EXISTS insurance_svc.charge_item_mapping (
    id               uuid PRIMARY KEY,
    charge_item_code text NOT NULL UNIQUE,                    -- 对应 clinical_svc.md_charge_item.item_code
    insurance_code   text NOT NULL REFERENCES insurance_svc.md_insurance_item (insurance_code),
    enabled          boolean NOT NULL DEFAULT true,
    version          bigint NOT NULL DEFAULT 0,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now()
);

-- ---- 参保登记（本地快照；真实参保状态以医保局返回为准）----
CREATE TABLE IF NOT EXISTS insurance_svc.insurance_member (
    id                  uuid PRIMARY KEY,
    patient_id          uuid NOT NULL UNIQUE,
    patient_no          text NOT NULL,
    insurance_type      text NOT NULL CHECK (insurance_type IN
                        ('EMPLOYEE', 'RESIDENT', 'RETIRED', 'OTHER')),
    region              text NOT NULL,
    person_category     text,
    deductible          numeric(12, 2) NOT NULL DEFAULT 0 CHECK (deductible >= 0),
    reimbursement_ratio numeric(5, 4) NOT NULL DEFAULT 0.8
                        CHECK (reimbursement_ratio >= 0 AND reimbursement_ratio <= 1),
    cap                 numeric(12, 2) NOT NULL DEFAULT 300000 CHECK (cap >= 0),
    effective_from      date NOT NULL DEFAULT CURRENT_DATE,
    effective_to        date,
    status              text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'DISABLED')),
    version             bigint NOT NULL DEFAULT 0,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_member_period CHECK (effective_to IS NULL OR effective_to >= effective_from)
);
CREATE INDEX IF NOT EXISTS ix_ins_member_status ON insurance_svc.insurance_member (status);

-- ---- 医保结算清单 ----
CREATE TABLE IF NOT EXISTS insurance_svc.settlement_bill (
    id               uuid PRIMARY KEY,
    bill_no          text NOT NULL UNIQUE,                    -- SBYYYYMMDD######
    visit_id         uuid NOT NULL UNIQUE,                    -- 一张就诊单一张清单
    patient_id       uuid NOT NULL,
    patient_no       text NOT NULL,
    member_id        uuid REFERENCES insurance_svc.insurance_member (id),
    total_amount     numeric(12, 2) NOT NULL DEFAULT 0 CHECK (total_amount >= 0),
    eligible_amount  numeric(12, 2) NOT NULL DEFAULT 0 CHECK (eligible_amount >= 0),
    deductible       numeric(12, 2) NOT NULL DEFAULT 0 CHECK (deductible >= 0),
    insurance_amount numeric(12, 2) NOT NULL DEFAULT 0 CHECK (insurance_amount >= 0),
    self_pay_amount  numeric(12, 2) NOT NULL DEFAULT 0 CHECK (self_pay_amount >= 0),
    status           text NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'CONFIRMED')),
    detail           jsonb NOT NULL DEFAULT '[]'::jsonb,      -- 明细快照（含未映射标记）
    created_by       text NOT NULL,
    confirmed_by     text,
    confirmed_at     timestamptz,
    version          bigint NOT NULL DEFAULT 0,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_bill_confirmed CHECK (
        (status = 'CONFIRMED' AND confirmed_by IS NOT NULL AND confirmed_at IS NOT NULL)
        OR status = 'DRAFT'
    )
);
CREATE INDEX IF NOT EXISTS ix_bill_patient ON insurance_svc.settlement_bill (patient_id, created_at DESC);

CREATE SEQUENCE IF NOT EXISTS insurance_svc.bill_no_seq START 1;

-- ---- 审计（追加式）----
CREATE TABLE IF NOT EXISTS insurance_svc.insurance_change_log (
    id          bigserial PRIMARY KEY,
    ts          timestamptz NOT NULL DEFAULT now(),
    actor       text NOT NULL,
    action      text NOT NULL,
    target_type text NOT NULL,
    target_id   text NOT NULL,
    detail      jsonb
);
CREATE INDEX IF NOT EXISTS ix_ins_change_target ON insurance_svc.insurance_change_log (target_type, target_id);
CREATE INDEX IF NOT EXISTS ix_ins_change_ts ON insurance_svc.insurance_change_log (ts DESC);
