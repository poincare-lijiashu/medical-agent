-- ============================================================================
-- 临床域 V59：手术分级目录（术种 → 默认分级，G81）
--
-- 背景：V18 的分级是"一台手术一条"的手工申报，同样的术种每次都要人工再选一遍；
-- 术种与级别的对应关系没有落库，既无法统一口径，也无法解释"为什么这台定为三级"。
--
-- 本表是 **默认值目录**，不是审批结论：
--   * 计划手术时按术种带出默认分级：1~2 级直接免审批，3~4 级自动进入待审批；
--   * 术者仍可在开台前按实际情况调整（调整后按 V18 规则重新走审批）；
--   * 目录项可停用/删除，但历史手术记录里的分级事实不受影响（已落到 surgery_level）。
--
-- 编码外键指向 operation_catalog(code)：目录维护只能针对字典里存在的术种，
-- 避免手工录入的错码在计划手术时静默匹配不上。
-- ============================================================================

CREATE TABLE IF NOT EXISTS clinical_svc.operation_level (
    operation_code text PRIMARY KEY
                   REFERENCES clinical_svc.operation_catalog (code),
    level          int NOT NULL CHECK (level BETWEEN 1 AND 4),
    note           text,
    updated_by     text NOT NULL,
    version        bigint NOT NULL DEFAULT 0,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now()
);

COMMENT ON TABLE clinical_svc.operation_level IS
    '手术分级目录：术种→默认分级（1~4 级）；计划手术时带出，3/4 级自动进入审批';
COMMENT ON COLUMN clinical_svc.operation_level.level IS
    '默认分级：1~2 级免审批，3~4 级需审批（与 V18 surgery_level 口径一致）';
