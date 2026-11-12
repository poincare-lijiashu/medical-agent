-- ============================================================================
-- V100：单院备份与 PITR 台账（第 286 批 / ADR-001 P3 延伸）
--
-- 为什么备份要落在"院自己的库"里：
--   * ADR-001 §3 要求"单院 PITR 只恢复该院"，备份坐标（桶、stanza、保留天数）
--     属于这家医院的数据面事实，控制面只做编排与巡检，不替它保管；
--   * 恢复演练与巡检都直连 Cell：台账在 Cell，控制面离线也能由院方自行读取；
--   * 控制面需要在开通时"建桶 + 登记 + 校验 WAL 归档"，登记结果必须可追溯。
--
-- 语义：
--   * status = PENDING  ：已登记计划，但尚未完成首次全备；
--   * status = READY    ：WAL 归档已开启、pgBackRest 可恢复（PITR 生效）；
--   * status = DEGRADED ：备份计划已登记，但 archive_mode/wal_level 不满足 PITR；
--   * status = FAILED   ：最近一次校验/备份失败（issues 里有可读原因）。
--
-- 刻意不做：
--   * 不在控制面保存备份数据本身（只保存坐标与状态）；
--   * 不自动删除过期备份（保留策略由 pgBackRest 的 repo1-retention-* 执行）。
-- ============================================================================

CREATE TABLE IF NOT EXISTS auth_svc.tenant_backup (
    tenant_code     text PRIMARY KEY,
    stanza          text NOT NULL,
    endpoint        text NOT NULL,
    bucket          text NOT NULL,
    region          text,
    pitr_days       integer NOT NULL DEFAULT 7,
    encryption      text NOT NULL DEFAULT 'AES-256-CBC',
    archive_mode    text,
    wal_level       text,
    archived_count  bigint NOT NULL DEFAULT 0,
    last_archived_at timestamptz,
    failed_count    bigint NOT NULL DEFAULT 0,
    last_full_at    timestamptz,
    last_verified_at timestamptz,
    status          text NOT NULL DEFAULT 'PENDING'
                    CHECK (status IN ('PENDING', 'READY', 'DEGRADED', 'FAILED')),
    issues          text[] NOT NULL DEFAULT '{}',
    updated_at      timestamptz NOT NULL DEFAULT now()
);

COMMENT ON TABLE auth_svc.tenant_backup IS
    '单院备份计划与状态：桶/stanza/PITR 保留窗口 + WAL 归档事实；控制面只编排与巡检';
COMMENT ON COLUMN auth_svc.tenant_backup.stanza IS
    'pgBackRest stanza 名（默认等于租户编码），恢复时按 stanza 定位该院仓库';
COMMENT ON COLUMN auth_svc.tenant_backup.pitr_days IS
    'PITR 可回溯天数（与 repo1-retention-full 对齐，按合同配置）';
COMMENT ON COLUMN auth_svc.tenant_backup.issues IS
    '可读问题列表：archive_mode 未开启、wal_level 不足、归档失败次数 > 0 等';
