package com.medassist.common.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * 共享数据库 nonce 存储（P0-S5）：多副本部署时防止跨副本重放。
 *
 * <p>实现要点：
 * <ul>
 *   <li>唯一键 + 过期时间：{@code INSERT ... ON CONFLICT DO UPDATE ... WHERE expires_at <= now()}
 *       插入成功（影响行数 1）= 新鲜；已存在且未过期（影响 0 行）= 重放；</li>
 *   <li>过期行可直接被"抢占"复用，无需先 DELETE，避免清理与校验竞争；</li>
 *   <li>概率清理（每 {@link #PURGE_EVERY} 次）删除过期行，控制表体积；</li>
 *   <li>数据库异常按 fail-closed 处理：抛 {@link IllegalStateException} 由过滤器转 403，
 *       绝不"查不到就当新鲜"（那等于关闭防重放）。</li>
 * </ul>
 *
 * <p>表归属：每个服务在**自己的 schema** 内建 {@code internal_nonce}（Flyway 迁移随各服务发布），
 * 因为各服务 Flyway 只管理自己的 schema，且起飞顺序不保证——依赖其他服务建表会让
 * 先启动的副本在迁移完成前 fail-closed 拒绝全部内部流量。
 */
public final class JdbcNonceStore implements NonceStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcNonceStore.class);
    private static final int PURGE_EVERY = 100;
    /** schema 名只允许标识符字符：它来自配置，仍按白名单校验，避免拼接注入。 */
    private static final Pattern SCHEMA_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private final JdbcClient jdbc;
    private final String schema;
    private final long ttlSeconds;
    private final AtomicLong calls = new AtomicLong();

    public JdbcNonceStore(JdbcClient jdbc, String schema, long ttlSeconds) {
        if (schema == null || !SCHEMA_NAME.matcher(schema).matches()) {
            throw new IllegalArgumentException("nonce 表所属 schema 非法：" + schema);
        }
        this.jdbc = jdbc;
        this.schema = schema;
        this.ttlSeconds = ttlSeconds;
    }

    @Override
    public Outcome register(String key) {
        if (calls.incrementAndGet() % PURGE_EVERY == 0) {
            purge();
        }
        int affected = jdbc.sql("""
                        INSERT INTO %s.internal_nonce (nonce_key, expires_at)
                        VALUES (:key, now() + make_interval(secs => :ttl))
                        ON CONFLICT (nonce_key) DO UPDATE
                           SET expires_at = EXCLUDED.expires_at
                         WHERE %s.internal_nonce.expires_at <= now()
                        """.formatted(schema, schema))
                .param("key", key)
                .param("ttl", ttlSeconds)
                .update();
        return affected > 0 ? Outcome.FRESH : Outcome.REPLAY;
    }

    private void purge() {
        try {
            jdbc.sql("DELETE FROM " + schema + ".internal_nonce WHERE expires_at <= now()").update();
        } catch (RuntimeException e) {
            // 清理失败不影响防重放语义（过期行会被下一次 register 抢占复用）
            log.warn("internal-auth.nonce_purge_failed err={}", e.getClass().getSimpleName());
        }
    }
}
