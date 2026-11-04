-- ============================================================================
-- V18：抗菌药物专项（G33）
--
-- antibiotic_approval：特殊使用级（antibiotic_level=3）处方的专项审批。
--   * 一张处方只保留一条审批记录（重新审批覆盖状态并递增 version）；
--   * 审批人不能是开方医师（四眼原则），库级 CHECK 兜底；
--   * 审批通过前，药师审核通过/发药都会被服务层拦截。
-- ============================================================================

CREATE TABLE IF NOT EXISTS pharmacy_svc.antibiotic_approval (
    id              uuid PRIMARY KEY,
    prescription_id uuid NOT NULL REFERENCES pharmacy_svc.prescription (id),
    max_level       integer NOT NULL CHECK (max_level >= 1 AND max_level <= 3),
    status          text NOT NULL CHECK (status IN ('APPROVED', 'REJECTED')),
    note            text,
    requested_by    text NOT NULL,
    requested_at    timestamptz NOT NULL DEFAULT now(),
    reviewed_by     text NOT NULL,
    reviewed_at     timestamptz NOT NULL DEFAULT now(),
    version         bigint NOT NULL DEFAULT 0,
    CONSTRAINT ux_antibiotic_approval_rx UNIQUE (prescription_id),
    CONSTRAINT ck_antibiotic_approval_self CHECK (lower(reviewed_by) <> lower(requested_by))
);

CREATE INDEX IF NOT EXISTS ix_antibiotic_approval_status
    ON pharmacy_svc.antibiotic_approval (status, reviewed_at DESC);

COMMENT ON TABLE pharmacy_svc.antibiotic_approval IS
    '特殊使用级抗菌药物审批：审批人≠开方医师，批准前不得审核通过或发药';
