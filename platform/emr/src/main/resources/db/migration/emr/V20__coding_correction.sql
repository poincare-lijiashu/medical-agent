-- ============================================================================
-- EMR V20：病案编码更正申请（G10）
--
-- 背景：V19 把 CODED 编码锁定为不可变，这是对的；但编码员发现编码错误时，
-- 过去只能"改首页"或"直接改库"，两条路都没有申请人与审批人留痕。
--
-- 口径：
--   * 编码完成后需要改诊断/手术，必须新建"更正申请"，写明原因与拟更正内容；
--   * SUBMITTED → APPROVED / REJECTED；SUBMITTED 可撤回；
--   * 审批通过才把拟更正内容回写病案首页快照（首页仍须为 SUBMITTED，已归档不可改）；
--   * 原 homepage_coding 保持不可变（V19 触发器），更正历史全部留在本表，
--     "谁在什么时候以什么理由改了什么"可逐条追溯；
--   * 同一首页同时最多一张待审批申请（部分唯一索引兜底并发）。
-- ============================================================================

CREATE SEQUENCE IF NOT EXISTS emr_svc.coding_correction_no_seq START 1;

CREATE TABLE IF NOT EXISTS emr_svc.coding_correction (
    id           uuid PRIMARY KEY,
    correction_no text NOT NULL UNIQUE,                        -- CCYYYYMMDD######
    homepage_id  uuid NOT NULL REFERENCES emr_svc.record_homepage (id),
    status       text NOT NULL DEFAULT 'SUBMITTED'
                 CHECK (status IN ('SUBMITTED', 'APPROVED', 'REJECTED', 'CANCELLED')),
    reason       text NOT NULL,
    diagnoses    jsonb NOT NULL DEFAULT '[]'::jsonb,
    procedures   jsonb NOT NULL DEFAULT '[]'::jsonb,
    note         text,
    created_by   text NOT NULL,
    reviewed_by  text,
    reviewed_at  timestamptz,
    review_note  text,
    cancelled_by text,
    cancelled_at timestamptz,
    cancel_reason text,
    version      bigint NOT NULL DEFAULT 0,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_coding_correction_reviewed CHECK (
        (status IN ('APPROVED', 'REJECTED')
            AND reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL)
        OR status IN ('SUBMITTED', 'CANCELLED')
    ),
    CONSTRAINT ck_coding_correction_reject_note CHECK (
        (status = 'REJECTED' AND review_note IS NOT NULL)
        OR status <> 'REJECTED'
    ),
    CONSTRAINT ck_coding_correction_cancelled CHECK (
        (status = 'CANCELLED' AND cancelled_by IS NOT NULL AND cancelled_at IS NOT NULL
            AND cancel_reason IS NOT NULL)
        OR status <> 'CANCELLED'
    )
);

-- 同一首页同时只允许一张待审批申请：双击/并发提交不会生成两张
CREATE UNIQUE INDEX IF NOT EXISTS ux_coding_correction_pending
    ON emr_svc.coding_correction (homepage_id) WHERE status = 'SUBMITTED';
CREATE INDEX IF NOT EXISTS ix_coding_correction_status
    ON emr_svc.coding_correction (status, created_at DESC);
CREATE INDEX IF NOT EXISTS ix_coding_correction_homepage
    ON emr_svc.coding_correction (homepage_id, created_at DESC);

COMMENT ON TABLE emr_svc.coding_correction IS
    '病案编码更正申请：四眼审批通过后回写首页快照，原编码记录保持不可变';

-- 已审批的更正内容是证据：禁止删除；非 SUBMITTED 状态不得再改拟更正内容/原因/申请人
CREATE OR REPLACE FUNCTION emr_svc.guard_coding_correction() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION '病案编码更正申请不允许删除（审批留痕是归档依据）'
            USING ERRCODE = 'insufficient_privilege';
    END IF;

    IF OLD.status <> 'SUBMITTED'
       AND (NEW.status IS DISTINCT FROM OLD.status
            OR NEW.homepage_id IS DISTINCT FROM OLD.homepage_id
            OR NEW.reason IS DISTINCT FROM OLD.reason
            OR NEW.diagnoses IS DISTINCT FROM OLD.diagnoses
            OR NEW.procedures IS DISTINCT FROM OLD.procedures
            OR NEW.created_by IS DISTINCT FROM OLD.created_by
            OR NEW.cancelled_by IS DISTINCT FROM OLD.cancelled_by
            OR NEW.cancelled_at IS DISTINCT FROM OLD.cancelled_at
            OR NEW.cancel_reason IS DISTINCT FROM OLD.cancel_reason
            OR NEW.reviewed_by IS DISTINCT FROM OLD.reviewed_by
            OR NEW.reviewed_at IS DISTINCT FROM OLD.reviewed_at
            OR NEW.review_note IS DISTINCT FROM OLD.review_note) THEN
        RAISE EXCEPTION '编码更正申请已关闭（%），不允许修改', OLD.status
            USING ERRCODE = 'check_violation';
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_coding_correction_guard ON emr_svc.coding_correction;
CREATE TRIGGER trg_coding_correction_guard
    BEFORE UPDATE OR DELETE ON emr_svc.coding_correction
    FOR EACH ROW EXECUTE FUNCTION emr_svc.guard_coding_correction();
