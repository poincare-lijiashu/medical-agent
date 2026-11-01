-- ============================================================================
-- 病历域 V11：病案首页 HQMS 上报批次与回执
--
-- 与医保报盘（insurance V6/V8）同一套思路：
--   * 只导出**已归档**首页，按出院日期落在期间内筛选；
--   * 批次固化 XML 与 SHA-256，事后核对不依赖首页/费用的当前值；
--   * 一个首页同时只能存在于一个**有效**批次（GENERATED / ACCEPTED），
--     触发器在数据库层兜底，避免重复上报；
--   * 回执与撤销都是单向迁移，用"期望状态 + 版本号"CAS。
-- ============================================================================

CREATE SEQUENCE IF NOT EXISTS emr_svc.hqms_batch_no_seq START 1;

CREATE TABLE IF NOT EXISTS emr_svc.hqms_export_batch (
    id               uuid PRIMARY KEY,
    batch_no         text NOT NULL UNIQUE,               -- HQYYYYMMDD######
    period_from      date NOT NULL,
    period_to        date NOT NULL,
    homepage_count   int NOT NULL CHECK (homepage_count > 0),
    total_amount     numeric(14, 2) NOT NULL CHECK (total_amount >= 0),
    payload          text NOT NULL,                      -- 固化报文（XML）
    payload_hash     text NOT NULL,                      -- SHA-256(payload)
    status           text NOT NULL DEFAULT 'GENERATED'
                     CHECK (status IN ('GENERATED', 'ACCEPTED', 'REJECTED', 'CANCELLED')),
    receipt_no       text,
    receipt_message  text,
    receipt_at       timestamptz,
    cancelled_reason text,
    cancelled_at     timestamptz,
    created_by       text NOT NULL,
    version          bigint NOT NULL DEFAULT 0,
    created_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_hqms_period CHECK (period_to >= period_from),
    CONSTRAINT ck_hqms_batch_receipt CHECK (
        (status IN ('ACCEPTED', 'REJECTED')
            AND receipt_no IS NOT NULL AND receipt_at IS NOT NULL)
        OR status IN ('GENERATED', 'CANCELLED')
    ),
    CONSTRAINT ck_hqms_batch_cancel CHECK (
        (status = 'CANCELLED' AND cancelled_reason IS NOT NULL AND cancelled_at IS NOT NULL)
        OR status <> 'CANCELLED'
    )
);

CREATE INDEX IF NOT EXISTS ix_hqms_batch_period
    ON emr_svc.hqms_export_batch (period_from, period_to);
CREATE INDEX IF NOT EXISTS ix_hqms_batch_status
    ON emr_svc.hqms_export_batch (status, created_at DESC);

CREATE TABLE IF NOT EXISTS emr_svc.hqms_export_item (
    batch_id      uuid NOT NULL REFERENCES emr_svc.hqms_export_batch (id),
    homepage_id   uuid NOT NULL REFERENCES emr_svc.record_homepage (id),
    homepage_no   text NOT NULL,
    patient_no    text NOT NULL,
    discharged_at timestamptz,
    total_amount  numeric(14, 2) NOT NULL,
    PRIMARY KEY (batch_id, homepage_id)
);

CREATE INDEX IF NOT EXISTS ix_hqms_item_homepage
    ON emr_svc.hqms_export_item (homepage_id);

COMMENT ON TABLE emr_svc.hqms_export_batch IS
    '病案首页 HQMS 上报批次：固化报文与 SHA-256；回执/撤销单向迁移';
COMMENT ON TABLE emr_svc.hqms_export_item IS
    '上报批次明细：一个首页同时只能出现在一个有效批次（触发器兜底）';

-- ---- 一个首页同时只能在一个有效批次（GENERATED / ACCEPTED）----
CREATE OR REPLACE FUNCTION emr_svc.check_hqms_item_once() RETURNS trigger AS $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM emr_svc.hqms_export_item i
          JOIN emr_svc.hqms_export_batch b ON b.id = i.batch_id
         WHERE i.homepage_id = NEW.homepage_id
           AND b.status IN ('GENERATED', 'ACCEPTED')
    ) THEN
        RAISE EXCEPTION '病案首页 % 已纳入有效上报批次，不能重复上报', NEW.homepage_no
            USING ERRCODE = 'unique_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_hqms_item_once ON emr_svc.hqms_export_item;
CREATE TRIGGER trg_hqms_item_once
    BEFORE INSERT ON emr_svc.hqms_export_item
    FOR EACH ROW EXECUTE FUNCTION emr_svc.check_hqms_item_once();

COMMENT ON FUNCTION emr_svc.check_hqms_item_once() IS
    '首页重复上报兜底：同一首页不得进入两个有效批次（撤销/退回后释放）';
