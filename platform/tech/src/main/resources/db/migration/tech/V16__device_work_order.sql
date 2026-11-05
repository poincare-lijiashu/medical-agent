-- ============================================================================
-- 医技域 V16：设备维保工单（G82，评审缺口"设备维保工单"）
--
-- 背景：V12 已有设备档案与质控记录，但"设备坏了怎么报修、谁来修、修完谁验收"
-- 没有系统留痕：报修靠电话，维修费用与停机时间无法统计，验收也没有责任人。
--
-- 状态机：
--   OPEN → IN_PROGRESS → DONE → ACCEPTED
--   OPEN / IN_PROGRESS / DONE → CANCELLED（必须写原因）
--
-- 关键约束（数据库兜底，应用层校验只是提前失败）：
--   * DONE / ACCEPTED 必须有完工时间与处理措施——"修好了"不能只改状态；
--   * ACCEPTED 必须有验收人与验收时间；
--   * ACCEPTED / CANCELLED 是终态，触发器禁止再改（工单是维修追溯凭证）；
--   * 维修费用非负；完工时间不得早于开始时间。
--
-- 与设备状态的联动由服务层负责：
--   * 故障维修（CORRECTIVE）开始 → 设备置 MAINTENANCE（仅当原状态 ACTIVE）；
--   * 工单验收/取消 → 若该设备已无未完结工单，置回 ACTIVE。
-- ============================================================================

CREATE SEQUENCE IF NOT EXISTS tech_svc.device_work_order_no_seq START 1;

CREATE TABLE IF NOT EXISTS tech_svc.device_work_order (
    id            uuid PRIMARY KEY,
    order_no      text NOT NULL UNIQUE,               -- WOYYYYMMDD######
    device_id     uuid NOT NULL REFERENCES tech_svc.device (id),
    order_type    text NOT NULL
                  CHECK (order_type IN ('PREVENTIVE', 'CORRECTIVE', 'INSPECTION')),
    priority      text NOT NULL DEFAULT 'NORMAL'
                  CHECK (priority IN ('LOW', 'NORMAL', 'HIGH', 'URGENT')),
    title         text NOT NULL,
    description   text,
    reported_by   text NOT NULL,
    reported_at   timestamptz NOT NULL DEFAULT now(),
    vendor        text,
    status        text NOT NULL DEFAULT 'OPEN'
                  CHECK (status IN ('OPEN', 'IN_PROGRESS', 'DONE', 'ACCEPTED', 'CANCELLED')),
    started_at    timestamptz,
    finished_at   timestamptz,
    resolution    text,
    cost_amount   numeric(12, 2) CHECK (cost_amount IS NULL OR cost_amount >= 0),
    accepted_by   text,
    accepted_at   timestamptz,
    accept_note   text,
    cancel_reason text,
    created_by    text NOT NULL,
    version       bigint NOT NULL DEFAULT 0,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_wo_time CHECK (
        (started_at IS NULL OR started_at >= reported_at - interval '1 day')
        AND (finished_at IS NULL OR started_at IS NULL OR finished_at >= started_at)
    ),
    CONSTRAINT ck_wo_done CHECK (
        status NOT IN ('DONE', 'ACCEPTED')
        OR (finished_at IS NOT NULL AND resolution IS NOT NULL)
    ),
    CONSTRAINT ck_wo_accepted CHECK (
        (status = 'ACCEPTED' AND accepted_by IS NOT NULL AND accepted_at IS NOT NULL)
        OR (status <> 'ACCEPTED' AND accepted_by IS NULL AND accepted_at IS NULL)
    ),
    CONSTRAINT ck_wo_cancel CHECK (
        (status = 'CANCELLED' AND cancel_reason IS NOT NULL) OR status <> 'CANCELLED'
    )
);

CREATE INDEX IF NOT EXISTS ix_wo_device_time
    ON tech_svc.device_work_order (device_id, reported_at DESC);
CREATE INDEX IF NOT EXISTS ix_wo_status_time
    ON tech_svc.device_work_order (status, reported_at DESC);

COMMENT ON TABLE tech_svc.device_work_order IS
    '设备维保工单：报修→维修→完工→验收；ACCEPTED/CANCELLED 为终态并冻结';

-- 终态冻结：验收/取消后不再允许修改（工单是维修与费用追溯凭证）
CREATE OR REPLACE FUNCTION tech_svc.guard_device_work_order() RETURNS trigger AS $$
BEGIN
    IF OLD.status IN ('ACCEPTED', 'CANCELLED') THEN
        RAISE EXCEPTION '维保工单已验收或已取消，不能修改' USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_guard_device_work_order ON tech_svc.device_work_order;
CREATE TRIGGER trg_guard_device_work_order
    BEFORE UPDATE ON tech_svc.device_work_order
    FOR EACH ROW EXECUTE FUNCTION tech_svc.guard_device_work_order();
