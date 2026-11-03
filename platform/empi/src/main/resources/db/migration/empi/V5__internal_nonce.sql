-- P0-S5：服务间签名的 nonce 去重表（多副本部署防重放）。
-- 归属本服务 schema（Flyway default-schema），不跨域建表：各服务起飞顺序不保证，
-- 依赖其他服务的迁移会让先启动的副本在迁移完成前 fail-closed 拒绝全部内部流量。
-- 开启方式：medassist.internal-auth.nonce-store=jdbc
CREATE TABLE IF NOT EXISTS internal_nonce (
    nonce_key  text        PRIMARY KEY,
    expires_at timestamptz NOT NULL
);

-- 过期行由 JdbcNonceStore 概率清理，索引保证清理与抢占复用都不退化为全表扫描
CREATE INDEX IF NOT EXISTS idx_internal_nonce_expires_at ON internal_nonce (expires_at);
