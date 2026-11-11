-- 交班待办事项（第 256 批）
--
-- 为什么加：第 254/255 批解决了“交接记录”和“完成率”，但交班最核心的一句话
-- “这件事还没做完，下一班接着办”仍然只能写在自由文本里，接班者无法逐条确认闭环。
--
-- 规则：
--   * 事项归属某一份交班报告（来源班次），可关联在院患者（也可只写病区级事项）；
--   * 状态只有 OPEN / DONE：DONE 记录闭环人与时间，不物理删除；
--   * 同一病区的 OPEN 事项跨班次可见（结转）；草稿保存只替换本报告的 OPEN 事项，
--     已被接班者闭环的事项永远保留，不能被后来的编辑抹掉；
--   * 事项不改变医嘱、床位与费用，只是交接责任的清单化。
CREATE TABLE clinical_svc.handover_task (
    id            uuid PRIMARY KEY,
    handover_id   uuid NOT NULL REFERENCES clinical_svc.handover (id) ON DELETE CASCADE,
    admission_id  uuid REFERENCES clinical_svc.admission (id),
    bed_no        text,
    patient_no    text,
    content       text NOT NULL,
    status        text NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'DONE')),
    created_by    text NOT NULL,
    closed_by     text,
    closed_at     timestamptz,
    close_note    text,
    created_at    timestamptz NOT NULL DEFAULT now()
);

COMMENT ON TABLE clinical_svc.handover_task IS
    '交班待办事项：归属来源班次，OPEN 跨班次结转，DONE 保留闭环人与时间';

CREATE INDEX ix_handover_task_open
    ON clinical_svc.handover_task (admission_id)
    WHERE status = 'OPEN';

CREATE INDEX ix_handover_task_handover
    ON clinical_svc.handover_task (handover_id, status);
