-- ============================================================================
-- 医技域 V17：设备档案院区归属（G83）
--
-- 背景：V12 的设备档案只有 dept_id、没有 campus_id，设备列表/看板一直是全院口径——
-- 主院区设备科能看到东院区设备与维保工单（G49 已把申请/报告/影像按院区收敛，
-- 设备是那次收口遗漏的一张表）。本迁移补列并回溯归属。
--
-- 回填口径（不做跨 schema JOIN，避免迁移顺序耦合；G69 的教训）：
--   * 设备是资产而非就诊数据，无法从 visit 回溯；
--   * 存量设备一律落主院区，并用 campus_source='MAIN_FALLBACK' 标记，
--     之后可由设备科按实际库房订正（订正后置 RUNTIME）；
--   * 新增设备由服务层按提交账号写入院区（campus_source='RUNTIME'）。
--
-- 锁风险：ADD COLUMN + 常量 DEFAULT 在 PG11+ 是元数据操作，不重写全表，
-- 因此无需分批回填（迁移安全门禁也允许带常量 DEFAULT 的新增列）。
-- ============================================================================

ALTER TABLE tech_svc.device ADD COLUMN IF NOT EXISTS campus_id uuid NOT NULL DEFAULT 'a0000000-0000-0000-0000-000000000001';

ALTER TABLE tech_svc.device ADD COLUMN IF NOT EXISTS campus_source text NOT NULL DEFAULT 'MAIN_FALLBACK';

CREATE INDEX IF NOT EXISTS ix_device_campus
    ON tech_svc.device (campus_id, status);

COMMENT ON COLUMN tech_svc.device.campus_id IS
    '院区归属（G83）：建档时按提交账号固化，列表/看板/工单按院区收敛';
COMMENT ON COLUMN tech_svc.device.campus_source IS
    '院区来源：RUNTIME 建档写入 / MAIN_FALLBACK 存量回填（可订正）';
