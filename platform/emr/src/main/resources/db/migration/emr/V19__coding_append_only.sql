-- ============================================================================
-- EMR V19：病案编码证据链防护（G4 收口）
--
-- 背景：V18 建了 homepage_coding，但"编码完成后不可改"只写在服务层。
-- 直连库仍可 UPDATE 已 CODED 的诊断/手术，或整行 DELETE 抹掉编码痕迹，
-- 与"编码是归档与 DRG 入组的依据"这一合规定位不符。
--
-- 口径：
--   * CODING（草稿）允许更新，方便反复保存；
--   * CODED 之后任何字段变更一律拒绝（要改只能走病案修改申请，另起流程）；
--   * 任何状态都不允许 DELETE（编码记录一旦产生即为证据）。
-- ============================================================================

CREATE OR REPLACE FUNCTION emr_svc.guard_coding_mutation() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION '病案编码记录不允许删除（编码是归档与 DRG 入组的依据）'
            USING ERRCODE = 'insufficient_privilege';
    END IF;

    IF OLD.status = 'CODED'
       AND (NEW.status IS DISTINCT FROM OLD.status
            OR NEW.diagnoses IS DISTINCT FROM OLD.diagnoses
            OR NEW.procedures IS DISTINCT FROM OLD.procedures
            OR NEW.coder IS DISTINCT FROM OLD.coder
            OR NEW.coded_at IS DISTINCT FROM OLD.coded_at) THEN
        RAISE EXCEPTION '病案编码已完成，不允许修改（如需更正请走病案修改申请）'
            USING ERRCODE = 'check_violation';
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_homepage_coding_guard ON emr_svc.homepage_coding;
CREATE TRIGGER trg_homepage_coding_guard
    BEFORE UPDATE OR DELETE ON emr_svc.homepage_coding
    FOR EACH ROW EXECUTE FUNCTION emr_svc.guard_coding_mutation();

COMMENT ON FUNCTION emr_svc.guard_coding_mutation() IS
    '病案编码附加防护：CODED 后不可改；任何状态不可删（服务层校验之外的数据库兜底）';
