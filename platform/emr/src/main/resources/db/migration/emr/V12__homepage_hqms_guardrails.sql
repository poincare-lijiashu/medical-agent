-- ============================================================================
-- 病历域 V12：病案首页 / HQMS 上报批次的数据库级守门（第六轮 R6-MF-07/08/09）
--
-- 背景（只读评审结论，未改动 V10/V11，全部以新版本增量修正）：
--   MF-07  V11 的 check_hqms_item_once 只在 INSERT 时 EXISTS 检查，READ COMMITTED 下
--          两个并发事务互相看不见未提交明细；advisory lock 又按期间拼键，期间重叠
--          （1..31 与 10..20）时锁不互斥 —— 同一首页可进两个有效批次并重复上报。
--   MF-08  guard_homepage 只守 UPDATE：归档件可被物理删除；可直接 INSERT 一条
--          status='ARCHIVED' 的伪造快照；procedures / discharged_at 在归档后仍可改；
--          患者姓名/首页号/住院天数/入院时间全程可改。
--   MF-09  hqms_export_item 无 UPDATE/DELETE 防护（删一条明细即可"释放"首页）；
--          批次状态在数据库层没有单向约束（ACCEPTED 可改成 CANCELLED 并释放整批）。
--
-- 本迁移落地三件事：
--   1. 首页：INSERT 只允许 DRAFT；UPDATE 强制单向状态机 + 提交后快照只读；DELETE 仅 DRAFT 且未被上报引用。
--   2. 明细：active 标记（由批次状态触发器维护）+ 部分唯一索引 (homepage_id) WHERE active，
--      把"一首页一有效批次"从"应用层查空"升级为**约束级幂等**；有效批次内明细不可改删。
--   3. 批次：UPDATE 只允许 GENERATED → ACCEPTED/REJECTED/CANCELLED，固化列不可改；
--      离开有效状态时自动释放明细（REJECTED/CANCELLED 的首页可重新上报）。
-- ============================================================================

-- ---------------------------------------------------------------- 1. 首页守门

CREATE OR REPLACE FUNCTION emr_svc.guard_homepage_insert() RETURNS trigger AS $$
BEGIN
    IF NEW.status IS DISTINCT FROM 'DRAFT' THEN
        RAISE EXCEPTION '病案首页只能以草稿（DRAFT）状态创建，必须走 生成→提交→归档 流程'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_homepage_insert_guard ON emr_svc.record_homepage;
CREATE TRIGGER trg_homepage_insert_guard
    BEFORE INSERT ON emr_svc.record_homepage
    FOR EACH ROW EXECUTE FUNCTION emr_svc.guard_homepage_insert();

-- 提交/归档后不可变的快照列（生成时固化，事后只能回临床域修正再重新生成）
CREATE OR REPLACE FUNCTION emr_svc.guard_homepage() RETURNS trigger AS $$
BEGIN
    -- 状态机单向：DRAFT → SUBMITTED → ARCHIVED，任何回退都在数据库层拒绝
    IF OLD.status = 'DRAFT' AND NEW.status NOT IN ('DRAFT', 'SUBMITTED') THEN
        RAISE EXCEPTION '病案首页状态不允许从草稿直接变为 %', NEW.status
            USING ERRCODE = 'check_violation';
    END IF;
    IF OLD.status = 'SUBMITTED' AND NEW.status NOT IN ('SUBMITTED', 'ARCHIVED') THEN
        RAISE EXCEPTION '病案首页已提交，不允许退回草稿或重复提交'
            USING ERRCODE = 'check_violation';
    END IF;
    IF OLD.status = 'ARCHIVED' AND NEW.status <> 'ARCHIVED' THEN
        RAISE EXCEPTION '病案首页已归档，不允许退回'
            USING ERRCODE = 'check_violation';
    END IF;

    -- 已提交/已归档：快照列一律只读（含 procedures / discharged_at / 患者与首页号等，
    -- 否则 HQMS 报文与库内当前值会劈叉，归档件的病案法律效力不成立）
    IF OLD.status IN ('SUBMITTED', 'ARCHIVED') THEN
        IF NEW.homepage_no IS DISTINCT FROM OLD.homepage_no
           OR NEW.visit_id IS DISTINCT FROM OLD.visit_id
           OR NEW.patient_id IS DISTINCT FROM OLD.patient_id
           OR NEW.patient_no IS DISTINCT FROM OLD.patient_no
           OR NEW.patient_name IS DISTINCT FROM OLD.patient_name
           OR NEW.admitted_at IS DISTINCT FROM OLD.admitted_at
           OR NEW.discharged_at IS DISTINCT FROM OLD.discharged_at
           OR NEW.stay_days IS DISTINCT FROM OLD.stay_days
           OR NEW.diagnoses IS DISTINCT FROM OLD.diagnoses
           OR NEW.procedures IS DISTINCT FROM OLD.procedures
           OR NEW.fee_summary IS DISTINCT FROM OLD.fee_summary
           OR NEW.total_amount IS DISTINCT FROM OLD.total_amount THEN
            RAISE EXCEPTION '病案首页已提交/已归档，临床快照（诊断/手术/费用/患者信息/出院时间）不可修改'
                USING ERRCODE = 'check_violation';
        END IF;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_homepage_guard ON emr_svc.record_homepage;
CREATE TRIGGER trg_homepage_guard
    BEFORE UPDATE ON emr_svc.record_homepage
    FOR EACH ROW EXECUTE FUNCTION emr_svc.guard_homepage();

-- 归档件不可物理删除：仅草稿且未被任何上报批次引用时可删
CREATE OR REPLACE FUNCTION emr_svc.guard_homepage_delete() RETURNS trigger AS $$
BEGIN
    IF OLD.status <> 'DRAFT' THEN
        RAISE EXCEPTION '病案首页已提交/已归档，不允许删除（只能通过病案修改申请留痕更正）'
            USING ERRCODE = 'check_violation';
    END IF;
    IF EXISTS (SELECT 1 FROM emr_svc.hqms_export_item i WHERE i.homepage_id = OLD.id) THEN
        RAISE EXCEPTION '病案首页 % 已被 HQMS 上报批次引用，不允许删除', OLD.homepage_no
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN OLD;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_homepage_delete_guard ON emr_svc.record_homepage;
CREATE TRIGGER trg_homepage_delete_guard
    BEFORE DELETE ON emr_svc.record_homepage
    FOR EACH ROW EXECUTE FUNCTION emr_svc.guard_homepage_delete();

COMMENT ON FUNCTION emr_svc.guard_homepage_insert() IS
    '病案首页创建守门：只允许 DRAFT，禁止直插伪造的 SUBMITTED/ARCHIVED 快照';
COMMENT ON FUNCTION emr_svc.guard_homepage_delete() IS
    '病案首页删除守门：仅草稿且未被上报批次引用时可删，归档件不可物理删除';

-- ---------------------------------------------------------------- 2. 上报明细

ALTER TABLE emr_svc.hqms_export_item
    ADD COLUMN IF NOT EXISTS active boolean NOT NULL DEFAULT true;

COMMENT ON COLUMN emr_svc.hqms_export_item.active IS
    '是否占用该首页：批次处于 GENERATED/ACCEPTED 时为 true；退回/撤销后由触发器置 false 释放首页';

-- 回填：历史数据按批次状态推导
UPDATE emr_svc.hqms_export_item i
   SET active = (b.status IN ('GENERATED', 'ACCEPTED'))
  FROM emr_svc.hqms_export_batch b
 WHERE b.id = i.batch_id
   AND i.active IS DISTINCT FROM (b.status IN ('GENERATED', 'ACCEPTED'));

-- 历史并发漏洞可能已产生"同一首页处于两个有效批次"的脏数据：
-- 保留最近创建的批次占用，其余降级释放（不删除历史明细，保持批次自洽）。
WITH ranked AS (
    SELECT i.batch_id, i.homepage_id,
           row_number() OVER (PARTITION BY i.homepage_id
                              ORDER BY b.created_at DESC, b.batch_no DESC) AS rn
      FROM emr_svc.hqms_export_item i
      JOIN emr_svc.hqms_export_batch b ON b.id = i.batch_id
     WHERE i.active
)
UPDATE emr_svc.hqms_export_item i
   SET active = false
  FROM ranked r
 WHERE i.batch_id = r.batch_id
   AND i.homepage_id = r.homepage_id
   AND r.rn > 1;

-- 约束级幂等：一个首页同时只能被一个有效批次占用（并发下由唯一索引兜底，而不是 EXISTS 查空）
CREATE UNIQUE INDEX IF NOT EXISTS ux_hqms_item_active_homepage
    ON emr_svc.hqms_export_item (homepage_id)
    WHERE active;

-- 明细的 active 由批次状态推导，应用层不写该列
CREATE OR REPLACE FUNCTION emr_svc.set_hqms_item_active() RETURNS trigger AS $$
DECLARE
    batch_status text;
BEGIN
    SELECT status INTO batch_status
      FROM emr_svc.hqms_export_batch
     WHERE id = NEW.batch_id;
    IF batch_status IS NULL THEN
        RAISE EXCEPTION '上报批次 % 不存在', NEW.batch_id
            USING ERRCODE = 'foreign_key_violation';
    END IF;
    NEW.active := batch_status IN ('GENERATED', 'ACCEPTED');
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_hqms_item_active ON emr_svc.hqms_export_item;
CREATE TRIGGER trg_hqms_item_active
    BEFORE INSERT OR UPDATE ON emr_svc.hqms_export_item
    FOR EACH ROW EXECUTE FUNCTION emr_svc.set_hqms_item_active();

-- 有效批次内的明细不可改不可删（否则删一条即可"释放"首页并重报，批次自身也自相矛盾）
CREATE OR REPLACE FUNCTION emr_svc.guard_hqms_item() RETURNS trigger AS $$
DECLARE
    batch_status text;
BEGIN
    SELECT status INTO batch_status
      FROM emr_svc.hqms_export_batch
     WHERE id = OLD.batch_id;
    IF batch_status IN ('GENERATED', 'ACCEPTED') THEN
        RAISE EXCEPTION '批次 % 仍为有效状态（%），其上报明细不可修改或删除',
            OLD.batch_id, batch_status
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN COALESCE(NEW, OLD);
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_hqms_item_guard ON emr_svc.hqms_export_item;
CREATE TRIGGER trg_hqms_item_guard
    BEFORE UPDATE OR DELETE ON emr_svc.hqms_export_item
    FOR EACH ROW EXECUTE FUNCTION emr_svc.guard_hqms_item();

COMMENT ON FUNCTION emr_svc.guard_hqms_item() IS
    '上报明细守门：批次仍为 GENERATED/ACCEPTED 时明细不可改删，避免"删明细释放首页"重报';

-- ---------------------------------------------------------------- 3. 上报批次

CREATE OR REPLACE FUNCTION emr_svc.guard_hqms_batch() RETURNS trigger AS $$
BEGIN
    IF OLD.status <> 'GENERATED' THEN
        RAISE EXCEPTION '上报批次 % 已回执/撤销（%），不允许再修改', OLD.batch_no, OLD.status
            USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.status NOT IN ('GENERATED', 'ACCEPTED', 'REJECTED', 'CANCELLED') THEN
        RAISE EXCEPTION '上报批次状态 % 不合法', NEW.status
            USING ERRCODE = 'check_violation';
    END IF;
    -- 固化列一律不可改：事后核对只认生成时的报文与哈希
    IF NEW.batch_no IS DISTINCT FROM OLD.batch_no
       OR NEW.period_from IS DISTINCT FROM OLD.period_from
       OR NEW.period_to IS DISTINCT FROM OLD.period_to
       OR NEW.homepage_count IS DISTINCT FROM OLD.homepage_count
       OR NEW.total_amount IS DISTINCT FROM OLD.total_amount
       OR NEW.payload IS DISTINCT FROM OLD.payload
       OR NEW.payload_hash IS DISTINCT FROM OLD.payload_hash
       OR NEW.created_by IS DISTINCT FROM OLD.created_by
       OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION '上报批次 % 的期间/份数/金额/报文/哈希等固化字段不可修改', OLD.batch_no
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_hqms_batch_guard ON emr_svc.hqms_export_batch;
CREATE TRIGGER trg_hqms_batch_guard
    BEFORE UPDATE ON emr_svc.hqms_export_batch
    FOR EACH ROW EXECUTE FUNCTION emr_svc.guard_hqms_batch();

-- 批次离开有效状态（REJECTED/CANCELLED）时释放首页，允许重新上报
CREATE OR REPLACE FUNCTION emr_svc.sync_hqms_items_active() RETURNS trigger AS $$
BEGIN
    IF NEW.status IN ('REJECTED', 'CANCELLED')
       AND OLD.status IN ('GENERATED', 'ACCEPTED') THEN
        UPDATE emr_svc.hqms_export_item
           SET active = false
         WHERE batch_id = NEW.id
           AND active;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_hqms_batch_sync_items ON emr_svc.hqms_export_batch;
CREATE TRIGGER trg_hqms_batch_sync_items
    AFTER UPDATE ON emr_svc.hqms_export_batch
    FOR EACH ROW EXECUTE FUNCTION emr_svc.sync_hqms_items_active();

COMMENT ON FUNCTION emr_svc.guard_hqms_batch() IS
    '上报批次守门：仅 GENERATED 可迁移，且只能到 ACCEPTED/REJECTED/CANCELLED；固化字段不可改';
COMMENT ON FUNCTION emr_svc.sync_hqms_items_active() IS
    '退回/撤销批次时释放首页（明细 active=false），使首页可重新上报';
