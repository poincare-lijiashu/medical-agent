-- ============================================================================
-- V62：补打异常核查处置（第 239 批）
--
-- 背景：§237 能发现异常、§238 能登记原因，但发现之后没有人“签字”——
-- 质控看完就关页面，下一个人再来看同一批单子，等于异常永远处于未处理状态。
--
-- 口径：
--   * 每条处置独立成行（追加式），最新一条即当前结论；不覆盖历史，
--     便于回答“上次是谁认定合理的、依据是什么”；
--   * 不建外键到 print_event：异常是按 (doc_type, doc_no) 聚合出来的口径，
--     不是某一行流水；单据被补打后仍沿用同一条处置链；
--   * 处置后同一单号再次被补打（last_at 晚于 reviewed_at）会重新计入待核查，
--     否则“认定合理”会变成永久免责。
-- ============================================================================

CREATE TABLE IF NOT EXISTS clinical_svc.print_anomaly_review (
    id          uuid PRIMARY KEY,
    doc_type    text NOT NULL,
    doc_no      text NOT NULL,
    verdict     text NOT NULL,
    note        text,
    actor       text NOT NULL,
    reviewed_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS ix_print_anomaly_review_doc
    ON clinical_svc.print_anomaly_review (doc_type, doc_no, reviewed_at DESC);

COMMENT ON TABLE clinical_svc.print_anomaly_review IS
    '补打异常核查处置：追加式，最新一条为该单号当前结论';
COMMENT ON COLUMN clinical_svc.print_anomaly_review.verdict IS
    '处置结论：REASONABLE（核实为合理补打）/ NEEDS_ACTION（需整改）';
COMMENT ON COLUMN clinical_svc.print_anomaly_review.note IS
    '处置说明；NEEDS_ACTION 时必填（服务端校验），REASONABLE 可空';
