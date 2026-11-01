-- ============================================================================
-- 临床域 V18：手术分级与审批（手术麻醉域第四批）
--
-- 为什么"分级"必须是硬约束而不是一张装饰表：
--   * 《医疗技术临床应用管理办法》与手术分级管理制度要求：三级、四级手术必须
--     经科主任/医务部门审批后方可实施，一、二级手术由具备相应资质的医师决定；
--   * 只把分级写在手术记录备注里，等于把"能不能开台"交给人工记忆；
--     本表让开台校验有唯一事实来源（SurgeryService 开台前强制校验）。
--
-- 状态模型（与"分级"强绑定，由 CHECK 在数据库层兜底）：
--   level 1~2 → approval_status 只能是 NOT_REQUIRED（免审批）
--   level 3~4 → approval_status ∈ PENDING / APPROVED / REJECTED
--
-- 审批字段用 reviewed_by/reviewed_at 而不是 approved_by/approved_at：
-- 通过（APPROVED）与驳回（REJECTED）都需要记录"是谁做的决定"，
-- 若用 approved_* 承载驳回，字段名会与语义相反。
--
-- 四眼原则（申请人与审批人不得为同一人）在服务层校验；
-- 本表只保证"审批结果 + 审批人 + 时间"这些事实可追溯。
-- ============================================================================

CREATE TABLE IF NOT EXISTS clinical_svc.surgery_level (
    id              uuid PRIMARY KEY,
    surgery_case_id uuid NOT NULL UNIQUE REFERENCES clinical_svc.surgery_case (id),
    level           int NOT NULL,
    approval_status text NOT NULL,
    applied_by      text NOT NULL,
    applied_at      timestamptz NOT NULL DEFAULT now(),
    reviewed_by     text,
    reviewed_at     timestamptz,
    reject_reason   text,
    created_by      text NOT NULL,
    version         bigint NOT NULL DEFAULT 0,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_surgery_level_range CHECK (level BETWEEN 1 AND 4),
    -- 一、二级手术免审批；三、四级必须走审批流程
    CONSTRAINT ck_surgery_level_flow CHECK (
        (level <= 2 AND approval_status = 'NOT_REQUIRED')
        OR (level >= 3 AND approval_status IN ('PENDING', 'APPROVED', 'REJECTED'))
    ),
    -- APPROVED 必须留下审批人与审批时间
    CONSTRAINT ck_surgery_level_approved CHECK (
        approval_status <> 'APPROVED'
        OR (reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL)
    ),
    -- REJECTED 必须留下审批人与驳回原因
    CONSTRAINT ck_surgery_level_rejected CHECK (
        approval_status <> 'REJECTED'
        OR (reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL AND reject_reason IS NOT NULL)
    )
);

CREATE INDEX IF NOT EXISTS ix_surgery_level_status
    ON clinical_svc.surgery_level (approval_status, applied_at DESC);

COMMENT ON TABLE clinical_svc.surgery_level IS
    '手术分级：一台手术一条；三级/四级必须审批通过才能开台（服务层强制）';

-- 分级变更后不得沿用原审批：直接改库把 4 级改成 3 级时，APPROVED 必须一并失效
CREATE OR REPLACE FUNCTION clinical_svc.guard_surgery_level() RETURNS trigger AS $$
BEGIN
    IF NEW.level <> OLD.level AND NEW.approval_status = 'APPROVED' THEN
        RAISE EXCEPTION '手术分级变更后必须重新审批，不能沿用原审批结论'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_guard_surgery_level ON clinical_svc.surgery_level;
CREATE TRIGGER trg_guard_surgery_level
    BEFORE UPDATE ON clinical_svc.surgery_level
    FOR EACH ROW EXECUTE FUNCTION clinical_svc.guard_surgery_level();

-- 报表口径：按手术间 + 时间窗统计排台/完成/取消/占用分钟，需要 (room_id, planned_start) 索引。
-- V16 已建 ix_schedule_room_time，这里只补"按状态过滤"的部分索引（占用量只统计未取消）。
CREATE INDEX IF NOT EXISTS ix_schedule_room_active
    ON clinical_svc.surgery_schedule (room_id, planned_start, planned_end)
    WHERE status <> 'CANCELLED';
