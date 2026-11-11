-- 病区交班报告（第 254 批）
--
-- 为什么加：护士站交班长期靠纸质白板与口头描述，"谁需要重点看、卡在哪一项"
-- 无法留痕，也无法证明交接过。本表把一次交班固化为：
--   病区 + 交班日 + 班次（唯一）→ 备注 → 逐患者备注与风险快照 → 签字。
--
-- 规则：
--   * 同一病区同一天同一班次只有一份交班报告（唯一索引），重复保存即编辑草稿；
--   * 签字后不可再改（服务层 409），快照保留签字当时看到的患者与风险项；
--   * 患者快照不引用床位/状态实时值，避免出院后回看交班记录被"改写历史"。
CREATE TABLE clinical_svc.handover (
    id          uuid PRIMARY KEY,
    ward_id     uuid NOT NULL REFERENCES clinical_svc.ward (id),
    shift_date  date NOT NULL,
    shift_code  text NOT NULL CHECK (shift_code IN ('DAY', 'EVENING', 'NIGHT')),
    note        text,
    status      text NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'SIGNED')),
    created_by  text NOT NULL,
    signed_by   text,
    signed_at   timestamptz,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    version     bigint NOT NULL DEFAULT 0
);

COMMENT ON TABLE clinical_svc.handover IS
    '病区交班报告：病区+交班日+班次唯一；签字后冻结（DRAFT → SIGNED）';

CREATE UNIQUE INDEX ux_handover_shift
    ON clinical_svc.handover (ward_id, shift_date, shift_code);

CREATE INDEX ix_handover_ward_date
    ON clinical_svc.handover (ward_id, shift_date DESC);

CREATE TABLE clinical_svc.handover_item (
    id            uuid PRIMARY KEY,
    handover_id   uuid NOT NULL REFERENCES clinical_svc.handover (id) ON DELETE CASCADE,
    admission_id  uuid NOT NULL REFERENCES clinical_svc.admission (id),
    bed_no        text,
    patient_no    text NOT NULL,
    flags         text NOT NULL DEFAULT '',
    note          text,
    created_at    timestamptz NOT NULL DEFAULT now(),
    UNIQUE (handover_id, admission_id)
);

COMMENT ON TABLE clinical_svc.handover_item IS
    '交班逐患者快照：床位/病案号/风险项为签字当时的值，备注为护士填写';

CREATE INDEX ix_handover_item_handover
    ON clinical_svc.handover_item (handover_id);
