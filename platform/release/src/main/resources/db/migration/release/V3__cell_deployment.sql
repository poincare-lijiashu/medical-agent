-- ============================================================================
-- V3：Cell 维度的部署记录（第 283 批 / ADR-001 P2「隔离验收」）
--
-- 背景：ADR-001 之后"发布"不再是一个全局动作，而是"按 Cell 分批推进"。
-- 如果部署台账没有 Cell 维度，就回答不了两个必须回答的问题：
--   1. 某家医院当前跑的是哪个版本（是否落后于已发布列车）；
--   2. 某家医院回滚时，应该回到**它自己**的上一版，而不是别家的上一版。
--
-- 语义：
--   * tenant_code 为空 = 平台级/历史记录（本批之前的 5 条记录保持原样，不臆造归属）；
--   * 回滚匹配必须用 IS NOT DISTINCT FROM，使"空 = 空"也成立，
--     避免历史记录回滚时错误命中某家医院的部署；
--   * batch_label 只做演练与审计追溯（PILOT / CANARY_10 / ALL），不参与业务判断。
-- ============================================================================

ALTER TABLE release_svc.rel_deployment
    ADD COLUMN IF NOT EXISTS tenant_code text,
    ADD COLUMN IF NOT EXISTS batch_label text;

CREATE INDEX IF NOT EXISTS ix_rel_deploy_tenant
    ON release_svc.rel_deployment (tenant_code, component_code, started_at DESC);

COMMENT ON COLUMN release_svc.rel_deployment.tenant_code IS
    '目标 Cell（医院租户编码）；空表示平台级或 ADR-001 之前的历史记录';
COMMENT ON COLUMN release_svc.rel_deployment.batch_label IS
    '发布批次标签（PILOT/CANARY_10/ALL 等），仅用于灰度演练与审计追溯';
