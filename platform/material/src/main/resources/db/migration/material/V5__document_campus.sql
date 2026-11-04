-- ============================================================================
-- V5：物资单据院区归属（G50，延续 G45-G49 的多院区边界）
--
-- 三张业务单据在创建时固化院区：
--   * 出库单：取开展出库操作的账号写入院区（物资科按院区独立发料）；
--   * 退回单：取提交科室账号的写入院区；
--   * 调拨单：取发起科室账号的写入院区（跨科室调拨不改变中心批次，院区随发起方）。
--
-- 存量数据没有可靠的院区来源（科室是全院字典，不携带院区），统一回填主院区，
-- 随后收紧 NOT NULL；上线后新单据一律由 DataScope.campusForWrite() 写入。
-- ============================================================================

ALTER TABLE material_svc.consumable_issue ADD COLUMN IF NOT EXISTS campus_id uuid;
ALTER TABLE material_svc.consumable_return ADD COLUMN IF NOT EXISTS campus_id uuid;
ALTER TABLE material_svc.dept_transfer ADD COLUMN IF NOT EXISTS campus_id uuid;

UPDATE material_svc.consumable_issue
   SET campus_id = 'a0000000-0000-0000-0000-000000000001'::uuid
 WHERE campus_id IS NULL;
UPDATE material_svc.consumable_return
   SET campus_id = 'a0000000-0000-0000-0000-000000000001'::uuid
 WHERE campus_id IS NULL;
UPDATE material_svc.dept_transfer
   SET campus_id = 'a0000000-0000-0000-0000-000000000001'::uuid
 WHERE campus_id IS NULL;

ALTER TABLE material_svc.consumable_issue ALTER COLUMN campus_id SET NOT NULL;
ALTER TABLE material_svc.consumable_return ALTER COLUMN campus_id SET NOT NULL;
ALTER TABLE material_svc.dept_transfer ALTER COLUMN campus_id SET NOT NULL;

CREATE INDEX IF NOT EXISTS ix_consumable_issue_campus_created
    ON material_svc.consumable_issue (campus_id, created_at DESC);
CREATE INDEX IF NOT EXISTS ix_consumable_return_campus_created
    ON material_svc.consumable_return (campus_id, created_at DESC);
CREATE INDEX IF NOT EXISTS ix_dept_transfer_campus_created
    ON material_svc.dept_transfer (campus_id, created_at DESC);

COMMENT ON COLUMN material_svc.consumable_issue.campus_id IS '出库单所属院区（创建时固化；列表/详情按此收敛）';
COMMENT ON COLUMN material_svc.consumable_return.campus_id IS '退回单所属院区（创建时固化；列表/详情/审批按此收敛）';
COMMENT ON COLUMN material_svc.dept_transfer.campus_id IS '调拨单所属院区（发起时固化；列表/详情/接收按此收敛）';
