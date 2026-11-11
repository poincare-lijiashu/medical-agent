-- ============================================================================
-- V60：单据打印留痕
--
-- 背景（第 230 批）：票据/处方/输液卡/费用清单/腕带/同意书都能打印，但系统
-- 不知道"谁在什么时间打印了哪张单"。补打、冒打、打印后丢失都无法追查。
--
-- 口径：
--   * 只追加、不修改（与 change_log 同原则）；不提供删除接口；
--   * doc_type / target_type 由服务侧校验非空与长度，不在库里写死枚举——
--     新增单据类型不应要求一次数据库迁移，但必须由前端常量统一收口；
--   * campus_id 取自操作者可见院区的唯一值；跨院区或未绑定院区的角色写 NULL，
--     查询时按院区过滤仍能收敛（NULL 行只对全院区角色可见）。
-- ============================================================================

CREATE TABLE IF NOT EXISTS clinical_svc.print_event (
    id          uuid PRIMARY KEY,
    doc_type    text NOT NULL,
    doc_no      text NOT NULL,
    target_type text,
    target_id   uuid,
    patient_no  text,
    actor       text NOT NULL,
    campus_id   uuid,
    printed_at  timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS ix_print_event_time
    ON clinical_svc.print_event (printed_at DESC, id DESC);

CREATE INDEX IF NOT EXISTS ix_print_event_target
    ON clinical_svc.print_event (target_type, target_id);

CREATE INDEX IF NOT EXISTS ix_print_event_doc
    ON clinical_svc.print_event (doc_type, doc_no);

COMMENT ON TABLE clinical_svc.print_event IS
    '单据打印留痕：追加式，记录谁在何时打印了哪张单（补打次数以此计数）';
COMMENT ON COLUMN clinical_svc.print_event.doc_type IS
    '单据类型（RECEIPT/CONSENT/PRESCRIPTION/PIVAS_CARD/INPATIENT_FINANCE/WRISTBAND 等，前端常量收口）';
COMMENT ON COLUMN clinical_svc.print_event.doc_no IS
    '单据业务号（票据号/处方号/住院号等），用于按单反查打印历史';
COMMENT ON COLUMN clinical_svc.print_event.target_type IS
    '关联对象类型（RECEIPT/CONSENT/PRESCRIPTION/PIVAS/ADMISSION），与 target_id 成对使用';
COMMENT ON COLUMN clinical_svc.print_event.campus_id IS
    '打印时操作者所属院区（跨院区角色为 NULL，仅全院区角色可见）';
