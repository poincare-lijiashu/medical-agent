-- 手术分级与开台的并发护栏（V34）
--
-- 背景：开台（SurgeryService.start）会在同一事务里读分级并落手术状态。
-- 若"改分级"的事务不加锁，它可以在"读分级"与"落状态"之间把分级改回待审批并提交，
-- 开台仍然成功——审批门槛被静默绕过，而审计里两条记录都看起来合规。
--
-- 防线分三层：
--   1) 服务层：SurgeryLevelService 的写路径（setLevel/approve/reject）改用
--      lockSurgeryCase（SELECT ... FOR UPDATE），与开台持同一把行锁，串行化两条路径；
--   2) 服务层：setLevel 要求手术仍为 PLANNED，手术一开始就不允许改分级；
--   3) 数据库层（本文件）：即使有人绕过服务层直接写库，也不允许把已开始手术的分级
--      改回 PENDING/REJECTED。
--
-- 这一层是"最后一道"，不是主防线；它存在的意义是让绕过服务层的写入也失败，
-- 而不是靠"大家都走服务层"的口头约定。

CREATE OR REPLACE FUNCTION clinical_svc.guard_surgery_level_after_start()
RETURNS trigger AS $$
DECLARE
    case_status text;
BEGIN
    -- 只拦"改回未放行状态"这一种危险变更；改成 APPROVED / NOT_REQUIRED 是安全的
    IF NEW.approval_status NOT IN ('PENDING', 'REJECTED') THEN
        RETURN NEW;
    END IF;

    SELECT status INTO case_status
      FROM clinical_svc.surgery_case
     WHERE id = NEW.surgery_case_id;

    IF case_status IN ('IN_PROGRESS', 'COMPLETED') THEN
        RAISE EXCEPTION
            '手术已开始（%），分级不得改回 % 状态', case_status, NEW.approval_status
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_surgery_level_guard ON clinical_svc.surgery_level;
CREATE TRIGGER trg_surgery_level_guard
    BEFORE UPDATE ON clinical_svc.surgery_level
    FOR EACH ROW
    EXECUTE FUNCTION clinical_svc.guard_surgery_level_after_start();
