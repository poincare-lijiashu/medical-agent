-- ============================================================================
-- V25：医保结算日结/月结对账与差异工单（计划书 W2.5 / Gate G3）
--
-- 解决什么问题
--   已有"单就诊对账"（清单金额 vs 当前收费重算），但没有"按期间的批量对账"：
--   医保办无法回答"昨天/上月一共有多少已确认清单没结算成功、金额差多少、谁在处理"。
--   结算失败只留在 realtime_settlement.last_error 里，没人汇总就永远发现不了。
--
-- 口径（写进对账报表的唯一口径）
--   * 期间按**就诊业务日期** service_date 归属，不按确认时间——与财务结算日一致；
--   * 本地金额取已确认清单（CONFIRMED）；省局金额取最近一笔 SETTLE 的响应报文；
--   * 只对**真有差异**的清单建工单：
--       NOT_SETTLED          已确认但无成功 SETTLE（未提交/在途/失败都会到这里）
--       SETTLE_FAILED        最近一笔 SETTLE 为 FAILED（附失败原因，供催办）
--       AMOUNT_MISMATCH      省局回传金额可解析且与本地不一致（> 0.01 元）
--       VOIDED_AFTER_SETTLE  清单已作废但省局已结算成功（必须先冲销）
--   * 省局响应未回传金额时不伪造差异，只计 unverified_count（对账不可验证的张数）；
--   * 批次按 (period_type, period_from, period_to) 唯一：重算幂等；
--     已 CLOSED 的批次不可重算、不可改差异——历史结论必须可复查。
-- ============================================================================

CREATE TABLE IF NOT EXISTS insurance_svc.settlement_recon_batch (
    id                    uuid PRIMARY KEY,
    period_type           text NOT NULL CHECK (period_type IN ('DAY', 'MONTH')),
    period_from           date NOT NULL,
    period_to             date NOT NULL,
    status                text NOT NULL DEFAULT 'COMPUTED'
                          CHECK (status IN ('COMPUTED', 'CLOSED')),
    bill_count            int NOT NULL DEFAULT 0 CHECK (bill_count >= 0),
    settled_count         int NOT NULL DEFAULT 0 CHECK (settled_count >= 0),
    not_settled_count     int NOT NULL DEFAULT 0 CHECK (not_settled_count >= 0),
    failed_count          int NOT NULL DEFAULT 0 CHECK (failed_count >= 0),
    mismatch_count        int NOT NULL DEFAULT 0 CHECK (mismatch_count >= 0),
    voided_after_settle_count int NOT NULL DEFAULT 0 CHECK (voided_after_settle_count >= 0),
    unverified_count      int NOT NULL DEFAULT 0 CHECK (unverified_count >= 0),
    open_diff_count       int NOT NULL DEFAULT 0 CHECK (open_diff_count >= 0),
    local_total           numeric(14, 2) NOT NULL DEFAULT 0,
    local_insurance       numeric(14, 2) NOT NULL DEFAULT 0,
    local_self_pay        numeric(14, 2) NOT NULL DEFAULT 0,
    counterparty_insurance numeric(14, 2) NOT NULL DEFAULT 0,
    counterparty_self_pay  numeric(14, 2) NOT NULL DEFAULT 0,
    diff_amount           numeric(14, 2) NOT NULL DEFAULT 0,
    computed_by           text NOT NULL,
    computed_at           timestamptz NOT NULL DEFAULT now(),
    closed_by             text,
    closed_at             timestamptz,
    version               bigint NOT NULL DEFAULT 0,
    CONSTRAINT ux_recon_period UNIQUE (period_type, period_from, period_to),
    CONSTRAINT ck_recon_period CHECK (period_to >= period_from),
    CONSTRAINT ck_recon_closed CHECK (
        (status = 'CLOSED' AND closed_by IS NOT NULL AND closed_at IS NOT NULL)
        OR status = 'COMPUTED'
    )
);
CREATE INDEX IF NOT EXISTS ix_recon_batch_period
    ON insurance_svc.settlement_recon_batch (period_from DESC, period_type);

CREATE TABLE IF NOT EXISTS insurance_svc.settlement_recon_diff (
    id                     uuid PRIMARY KEY,
    batch_id               uuid NOT NULL REFERENCES insurance_svc.settlement_recon_batch (id),
    bill_id                uuid NOT NULL,
    bill_no                text NOT NULL,
    visit_id               uuid NOT NULL,
    category               text NOT NULL
                           CHECK (category IN ('NOT_SETTLED', 'SETTLE_FAILED',
                                               'AMOUNT_MISMATCH', 'VOIDED_AFTER_SETTLE')),
    local_insurance        numeric(14, 2) NOT NULL DEFAULT 0,
    local_self_pay         numeric(14, 2) NOT NULL DEFAULT 0,
    counterparty_insurance numeric(14, 2),
    counterparty_self_pay  numeric(14, 2),
    diff_amount            numeric(14, 2) NOT NULL DEFAULT 0,
    detail                 text,          -- 失败原因等；不含患者姓名/证件号
    status                 text NOT NULL DEFAULT 'OPEN'
                           CHECK (status IN ('OPEN', 'RESOLVED', 'IGNORED', 'AUTO_CLOSED')),
    resolved_by            text,
    resolved_at            timestamptz,
    resolve_note           text,
    created_at             timestamptz NOT NULL DEFAULT now(),
    updated_at             timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ux_recon_diff UNIQUE (batch_id, bill_id, category),
    CONSTRAINT ck_recon_diff_resolved CHECK (
        (status IN ('RESOLVED', 'IGNORED')
            AND resolved_by IS NOT NULL AND resolved_at IS NOT NULL AND resolve_note IS NOT NULL)
        OR status IN ('OPEN', 'AUTO_CLOSED')
    )
);
CREATE INDEX IF NOT EXISTS ix_recon_diff_batch
    ON insurance_svc.settlement_recon_diff (batch_id, status, category);
CREATE INDEX IF NOT EXISTS ix_recon_diff_bill
    ON insurance_svc.settlement_recon_diff (bill_id);

COMMENT ON TABLE insurance_svc.settlement_recon_batch IS
    '医保结算对账批次：按就诊业务日期归属，重算幂等；CLOSED 后不可改（历史结论可复查）';
COMMENT ON TABLE insurance_svc.settlement_recon_diff IS
    '对账差异工单：OPEN→RESOLVED/IGNORED 必须留处理人与说明；重算不再出现的 OPEN 自动关闭';

-- 批次是财务结论：禁止删除；关闭后不可再改
CREATE OR REPLACE FUNCTION insurance_svc.guard_recon_batch() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION '对账批次不允许删除（财务结论必须可复查）'
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    IF OLD.status = 'CLOSED' THEN
        RAISE EXCEPTION '对账批次已关闭，不允许修改'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_recon_batch_guard ON insurance_svc.settlement_recon_batch;
CREATE TRIGGER trg_recon_batch_guard
    BEFORE UPDATE OR DELETE ON insurance_svc.settlement_recon_batch
    FOR EACH ROW EXECUTE FUNCTION insurance_svc.guard_recon_batch();
