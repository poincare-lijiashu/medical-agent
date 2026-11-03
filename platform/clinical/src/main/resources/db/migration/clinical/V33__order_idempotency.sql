-- 医嘱开立的幂等键（V33）
--
-- 背景：弱网/超时下用户会重复点击「开立」。前端每次提交带同一个 Idempotency-Key，
-- 后端据此去重，保证「同一次提交只落一次医嘱」。
--
-- 设计取舍：
--   1) 落库而不是放缓存：重试可能跨越数分钟甚至网关重启，缓存会丢键导致重复开单；
--   2) 键按 (visit_id, actor, idempotency_key) 唯一：不同医生用同一个键互不影响；
--   3) 保存结果快照（order_ids）：重放时直接返回首次结果，而不是再查一遍（查也可能被后续撤销影响）；
--   4) 保留 7 天由清理任务处理，不在这里做 TTL。

CREATE TABLE IF NOT EXISTS clinical_svc.order_idempotency (
    id              uuid PRIMARY KEY,
    visit_id        uuid NOT NULL REFERENCES clinical_svc.visit (id),
    actor           text NOT NULL,
    idempotency_key text NOT NULL,
    order_ids       uuid[] NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ux_order_idempotency UNIQUE (visit_id, actor, idempotency_key),
    CONSTRAINT ck_order_idempotency_key CHECK (length(idempotency_key) BETWEEN 8 AND 128)
);

-- 清理任务按时间扫描
CREATE INDEX IF NOT EXISTS ix_order_idempotency_created
    ON clinical_svc.order_idempotency (created_at);
