-- ============================================================================
-- V21：实时结算"一单一次 SETTLE"硬约束 + 投递抢占态（G56）
--
-- 背景（评审 §4.1/§4.2）：
--   1) 幂等键 (bill_id, request_id) 中的 request_id 由调用方任意填写，同一 CONFIRMED
--      清单换请求号即可再建一笔 PENDING 并再次向省局出账，库级唯一形同虚设；
--   2) deliver 无边锁/抢占，submit 与 retry 并发时两线程都会外呼省局（重复出账），
--      且结果回写是裸 CAS，"失败的先到"会把成功结果顶掉，本地留 FAILED 而省局已出账。
--
-- 本迁移做三件事：
--   * 收敛历史重复：同一清单多条在途/成功 SETTLE 只保留一条（SETTLED 优先，其次最早），
--     其余置 FAILED 并留原因，避免建唯一索引失败；
--   * 建部分唯一索引：同一清单最多一条 PENDING/DELIVERING/SETTLED 的 SETTLE 交易；
--   * 状态机加入 DELIVERING：投递前先抢占（PENDING/FAILED → DELIVERING）再外呼，
--     保证并发下只有一个线程真正打省局接口；进程崩溃遗留的 DELIVERING 超过
--     10 分钟可由重试重新抢占（服务层按 updated_at 判定）。
-- ============================================================================

-- 1) 历史重复收敛：保留一条有效交易，其余标记失败（不改金额、不改清单）
WITH ranked AS (
    SELECT id,
           row_number() OVER (
               PARTITION BY bill_id
               ORDER BY CASE status WHEN 'SETTLED' THEN 0 ELSE 1 END, created_at, id
           ) AS rn
    FROM insurance_svc.realtime_settlement
    WHERE biz_type = 'SETTLE'
      AND status IN ('PENDING', 'SETTLED')
)
UPDATE insurance_svc.realtime_settlement r
   SET status = 'FAILED',
       last_error = COALESCE(r.last_error,
                             'V21 收敛：同一清单已存在有效实时结算交易，本笔重复交易已置失败'),
       updated_at = now(),
       version = r.version + 1
  FROM ranked
 WHERE r.id = ranked.id
   AND ranked.rn > 1;

-- 2) 状态机补 DELIVERING（原内联 CHECK 约束名为 realtime_settlement_status_check）
ALTER TABLE insurance_svc.realtime_settlement
    DROP CONSTRAINT IF EXISTS realtime_settlement_status_check;
ALTER TABLE insurance_svc.realtime_settlement
    ADD CONSTRAINT realtime_settlement_status_check
    CHECK (status IN ('PENDING', 'DELIVERING', 'SETTLED', 'FAILED'));

-- 3) 一单一次 SETTLE：在途/成功状态下同一清单只允许一条
CREATE UNIQUE INDEX IF NOT EXISTS ux_realtime_settle_once
    ON insurance_svc.realtime_settlement (bill_id)
    WHERE biz_type = 'SETTLE' AND status IN ('PENDING', 'DELIVERING', 'SETTLED');

COMMENT ON INDEX insurance_svc.ux_realtime_settle_once IS
    '同一结算清单最多一笔在途/成功的 SETTLE 交易：换请求号也不能重复出账';
