-- ============================================================================
-- V61：补打原因登记（第 238 批）
--
-- 背景：V60 只记录"谁在何时打了哪张单"。质控能看见同一单号打了 4 次，
-- 却分不清是"打印机卡纸重打"（合理）还是"重复出单"（风险）。
--
-- 口径：
--   * reprint_seq 由服务端在入库时计算（同 doc_type + doc_no 的第几次），
--     不在库里用自增/序列——单号是业务键，不是主键，序列会被无关单号穿插；
--   * reason 只有第 2 次及以后才强制（服务端拦截，见 PrintEventService），
--     首次打印留空：首次不需要解释，强制填写只会训练出"随便选一个"的脏数据；
--   * reason 仍允许空——历史数据（V61 之前）没有原因；
--     质控报表按"未说明"计数，把无原因补打单独暴露出来，而不是当成已解释。
-- ============================================================================

ALTER TABLE clinical_svc.print_event
    ADD COLUMN IF NOT EXISTS reprint_seq integer NOT NULL DEFAULT 1;

ALTER TABLE clinical_svc.print_event
    ADD COLUMN IF NOT EXISTS reason text;

-- 历史行回填次序：V61 之前的补打在表里没有次序标记，
-- 若一律留 1 会让“打印 4 次”的异常行显示“未说明 0 次”，
-- 等于把没有原因的历史补打伪装成已解释。按 printed_at 排队更接近事实。
WITH numbered AS (
    SELECT id,
           row_number() OVER (PARTITION BY doc_type, doc_no ORDER BY printed_at, id) AS seq
      FROM clinical_svc.print_event
)
UPDATE clinical_svc.print_event e
   SET reprint_seq = n.seq
  FROM numbered n
 WHERE e.id = n.id
   AND e.reprint_seq <> n.seq;

COMMENT ON COLUMN clinical_svc.print_event.reprint_seq IS
    '该单号第几次打印（1 = 首次；>=2 为补打，服务端强制要求 reason）';
COMMENT ON COLUMN clinical_svc.print_event.reason IS
    '补打原因（卡纸重打/患者遗失/信息变更等），首次打印为空；空值按“未说明”统计';

-- 补打次数与“未说明”统计都按 (doc_type, doc_no) 聚合；
-- V60 已有 ix_print_event_doc，这里不重复建索引。
