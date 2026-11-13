-- ============================================================================
-- V26：支付方分摊 + 三方对账（计划书 W2.5 / W2.6 / Gate G3）
--
-- 解决什么问题
--   1) 一次结算往往不是"医保 + 自费"两方：还有商保、医疗救助、单位补助等。
--      此前 settlement_bill 只存 insurance_amount / self_pay_amount，无法回答
--      "这笔钱最终由谁承担、退费退给谁"，财务无法按支付方出账。
--   2) §291 的对账只比"本地清单 vs 省局回传"，缺少**临床实收**这一腿：
--      清单已确认但患者根本没交钱、或收费已退但清单未红冲，都发现不了。
--
-- 设计口径（写进财务与医保沟通的唯一口径）
--   * 支付方配置（payer_config）是院内字典：BASIC 医保统筹 / SELF 自费 /
--     COMMERCIAL 商保 / ASSISTANCE 救助；停用只影响新分摊，不改写历史。
--   * 分摊（settlement_allocation）是**追加式台账**：CHARGE 记录应收分摊，
--     REFUND 记录退费（必须指向原分摊并写原因）；只增不改，禁止删除。
--   * 同一清单同一支付方只允许一条 CHARGE（幂等）；退费累计不得超过原分摊金额
--     （服务层校验 + 台账只增，保证可追溯）。
--   * 三方对账：临床实收（status=PAID） vs 本地清单总额 vs 省局回传。
--     差异类别新增 CLINIC_UNDER_COLLECTED（清单有金额但未实收/已退费）
--     与 CLINIC_OVER_COLLECTED（实收大于清单，疑似漏报或退费未红冲）。
-- ============================================================================

-- ---------------------------------------------------------------- 支付方字典

CREATE TABLE IF NOT EXISTS insurance_svc.payer_config (
    code        text PRIMARY KEY CHECK (code ~ '^[A-Z0-9_]{2,32}$'),
    name        text NOT NULL,
    payer_type  text NOT NULL
                CHECK (payer_type IN ('BASIC', 'SELF', 'COMMERCIAL', 'ASSISTANCE')),
    enabled     boolean NOT NULL DEFAULT true,
    sort_no     int NOT NULL DEFAULT 100,
    remark      text,
    version     bigint NOT NULL DEFAULT 0,
    updated_by  text NOT NULL,
    updated_at  timestamptz NOT NULL DEFAULT now(),
    created_at  timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE insurance_svc.payer_config IS
    '支付方字典：BASIC 医保统筹 / SELF 自费 / COMMERCIAL 商保 / ASSISTANCE 救助；停用不改写历史分摊';

INSERT INTO insurance_svc.payer_config
    (code, name, payer_type, enabled, sort_no, remark, updated_by)
VALUES
    ('BASIC_INSURANCE', '医保统筹', 'BASIC', true, 10,
     '金额必须等于清单统筹支付，由系统按清单带入', 'system'),
    ('SELF_PAY', '个人自费', 'SELF', true, 20,
     '默认承接清单个人自付金额', 'system'),
    ('COMMERCIAL_INSURANCE', '商业保险', 'COMMERCIAL', true, 30,
     '商保直付/补充报销，按协议分摊', 'system'),
    ('MEDICAL_ASSISTANCE', '医疗救助', 'ASSISTANCE', true, 40,
     '民政/慈善救助资金', 'system')
ON CONFLICT (code) DO NOTHING;

-- ---------------------------------------------------------------- 分摊台账

CREATE TABLE IF NOT EXISTS insurance_svc.settlement_allocation (
    id          uuid PRIMARY KEY,
    bill_id     uuid NOT NULL REFERENCES insurance_svc.settlement_bill (id),
    visit_id    uuid NOT NULL,
    payer_code  text NOT NULL REFERENCES insurance_svc.payer_config (code),
    payer_type  text NOT NULL
                CHECK (payer_type IN ('BASIC', 'SELF', 'COMMERCIAL', 'ASSISTANCE')),
    kind        text NOT NULL CHECK (kind IN ('CHARGE', 'REFUND')),
    original_id uuid REFERENCES insurance_svc.settlement_allocation (id),
    amount      numeric(14, 2) NOT NULL CHECK (amount > 0),
    reason      text,
    operator    text NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_allocation_kind CHECK (
        (kind = 'CHARGE' AND original_id IS NULL)
        OR (kind = 'REFUND' AND original_id IS NOT NULL AND reason IS NOT NULL)
    )
);
-- 同一清单同一支付方只有一条应收分摊：重复提交天然幂等，调整须先退费再重分摊
CREATE UNIQUE INDEX IF NOT EXISTS ux_allocation_charge
    ON insurance_svc.settlement_allocation (bill_id, payer_code) WHERE kind = 'CHARGE';
CREATE INDEX IF NOT EXISTS ix_allocation_bill
    ON insurance_svc.settlement_allocation (bill_id, created_at);
CREATE INDEX IF NOT EXISTS ix_allocation_original
    ON insurance_svc.settlement_allocation (original_id);
CREATE INDEX IF NOT EXISTS ix_allocation_visit
    ON insurance_svc.settlement_allocation (visit_id, created_at DESC);
COMMENT ON TABLE insurance_svc.settlement_allocation IS
    '结算分摊台账（追加式）：CHARGE 应收分摊，REFUND 退费（指向原分摊并写原因）；禁止改删';

-- 台账是财务事实：禁止修改与删除（红冲只能用 REFUND 新增一条）
CREATE OR REPLACE FUNCTION insurance_svc.guard_settlement_allocation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION '结算分摊台账不允许修改或删除，红冲请新增 REFUND 记录'
        USING ERRCODE = 'insufficient_privilege';
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_allocation_guard ON insurance_svc.settlement_allocation;
CREATE TRIGGER trg_allocation_guard
    BEFORE UPDATE OR DELETE ON insurance_svc.settlement_allocation
    FOR EACH ROW EXECUTE FUNCTION insurance_svc.guard_settlement_allocation();

-- ---------------------------------------------------------------- 三方对账扩展

ALTER TABLE insurance_svc.settlement_recon_batch
    ADD COLUMN IF NOT EXISTS clinic_total numeric(14, 2) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS clinic_refund_total numeric(14, 2) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS clinic_mismatch_count int NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS clinic_unmatched_count int NOT NULL DEFAULT 0;

COMMENT ON COLUMN insurance_svc.settlement_recon_batch.clinic_total IS
    '期间内已确认清单对应就诊的临床实收合计（收费单 status=PAID）';
COMMENT ON COLUMN insurance_svc.settlement_recon_batch.clinic_refund_total IS
    '期间内已确认清单对应就诊的已退费合计（收费单 status=REFUNDED）';
COMMENT ON COLUMN insurance_svc.settlement_recon_batch.clinic_mismatch_count IS
    '临床实收与清单总额不一致的就诊数（含未实收）';

ALTER TABLE insurance_svc.settlement_recon_diff
    ADD COLUMN IF NOT EXISTS clinic_total numeric(14, 2);

COMMENT ON COLUMN insurance_svc.settlement_recon_diff.clinic_total IS
    '该就诊临床实收金额；省局/清单差异为 NULL，仅三方差异填写';

ALTER TABLE insurance_svc.settlement_recon_diff
    DROP CONSTRAINT IF EXISTS settlement_recon_diff_category_check;
ALTER TABLE insurance_svc.settlement_recon_diff
    ADD CONSTRAINT ck_recon_diff_category CHECK (
        category IN ('NOT_SETTLED', 'SETTLE_FAILED', 'AMOUNT_MISMATCH',
                     'VOIDED_AFTER_SETTLE', 'CLINIC_UNDER_COLLECTED',
                     'CLINIC_OVER_COLLECTED')
    );
