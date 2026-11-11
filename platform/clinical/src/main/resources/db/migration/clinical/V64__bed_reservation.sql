-- 床位预留（第 253 批）
--
-- 为什么加：床位状态里已有 RESERVED，但没有任何代码路径会写入它——
-- 择期入院/转科/急诊留床只能靠口头约定，护士站交接必然打架。
--
-- 约束：
--   * 一张床同时最多一条 ACTIVE 预留（部分唯一索引），否则“谁的预留算数”无法判定；
--   * 患者可空（机动/急诊预留），有患者时必须与入院患者一致才能消费；
--   * 预留到期由后台任务置 EXPIRED 并释放床位；预留不改变入院/出院门禁。
CREATE TABLE clinical_svc.bed_reservation (
    id              uuid PRIMARY KEY,
    bed_id          uuid NOT NULL REFERENCES clinical_svc.bed (id),
    ward_id         uuid NOT NULL REFERENCES clinical_svc.ward (id),
    patient_id      uuid,
    patient_no      text,
    reason          text,
    reserved_by     text NOT NULL,
    reserved_until  timestamptz,
    status          text NOT NULL DEFAULT 'ACTIVE'
                    CHECK (status IN ('ACTIVE', 'CONSUMED', 'RELEASED', 'EXPIRED')),
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    version         bigint NOT NULL DEFAULT 0
);

COMMENT ON TABLE clinical_svc.bed_reservation IS
    '床位预留：择期入院/转科/急诊留床；同一床位同时只有一条 ACTIVE 预留';

-- 一张床只能有一条有效预留；释放/消费/过期后自动让位。
CREATE UNIQUE INDEX ux_bed_reservation_active
    ON clinical_svc.bed_reservation (bed_id)
    WHERE status = 'ACTIVE';

-- 病区列表与到期扫描
CREATE INDEX ix_bed_reservation_ward
    ON clinical_svc.bed_reservation (ward_id, status, reserved_until);

CREATE INDEX ix_bed_reservation_expiry
    ON clinical_svc.bed_reservation (reserved_until)
    WHERE status = 'ACTIVE' AND reserved_until IS NOT NULL;
