-- ============================================================================
-- V20：医保结算清单院区归属（G48，延续 G45–G47 的多院区边界）
--
-- 清单院区在生成时固化（来自临床收费汇总里的就诊院区，见 InternalChargeController）。
-- 存量清单按就诊院区回填；找不到就诊的回退主院区，随后收紧 NOT NULL。
-- 实时结算交易不单独存院区：它一定挂在清单上，查询时 JOIN 清单院区即可。
--
-- G69（评审 §6.8）：insurance 可能先于 clinical 完成迁移，旧脚本的
-- `FROM clinical_svc.visit` 会因缺列阻断启动。这里按列存在性守卫：
-- clinical 未就绪时跳过按就诊回填，存量落主院区并标 campus_source='MAIN_FALLBACK'。
-- ============================================================================

ALTER TABLE insurance_svc.settlement_bill ADD COLUMN IF NOT EXISTS campus_id uuid;
ALTER TABLE insurance_svc.settlement_bill ADD COLUMN IF NOT EXISTS campus_source text;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
                WHERE table_schema = 'clinical_svc' AND table_name = 'visit'
                  AND column_name = 'campus_id') THEN
        UPDATE insurance_svc.settlement_bill b
           SET campus_id = v.campus_id, campus_source = 'VISIT'
          FROM clinical_svc.visit v
         WHERE v.id = b.visit_id
           AND b.campus_id IS NULL;
    END IF;
END $$;

UPDATE insurance_svc.settlement_bill
   SET campus_id = 'a0000000-0000-0000-0000-000000000001'::uuid,
       campus_source = COALESCE(campus_source, 'MAIN_FALLBACK')
 WHERE campus_id IS NULL;

ALTER TABLE insurance_svc.settlement_bill ALTER COLUMN campus_id SET NOT NULL;
ALTER TABLE insurance_svc.settlement_bill ALTER COLUMN campus_source SET DEFAULT 'RUNTIME';
ALTER TABLE insurance_svc.settlement_bill ALTER COLUMN campus_source SET NOT NULL;

CREATE INDEX IF NOT EXISTS ix_settlement_bill_campus_created
    ON insurance_svc.settlement_bill (campus_id, created_at DESC);

COMMENT ON COLUMN insurance_svc.settlement_bill.campus_id IS '结算清单所属院区（生成时固化；列表/详情/对账/实时结算按此收敛）';
COMMENT ON COLUMN insurance_svc.settlement_bill.campus_source IS
    '院区来源：RUNTIME=运行时固化，VISIT=迁移时按就诊回填，MAIN_FALLBACK=迁移时无法判定落主院区';
