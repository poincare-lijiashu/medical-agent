package com.medassist.common.web;

/**
 * nonce 去重存储（P0-S5）。
 *
 * <p>为什么需要抽象：进程内去重只对**单副本**严格有效；多副本部署时同一 nonce 打到另一个副本
 * 仍会被判为"新鲜"，签名重放窗口内可被利用。生产多副本必须换成共享存储。
 *
 * <p>两种实现：
 * <ul>
 *   <li>进程内（{@code InternalAuthFilter.NonceCache}）：默认，单副本/开发环境；</li>
 *   <li>{@link JdbcNonceStore}：共享 PostgreSQL（多副本），靠唯一键 + 过期时间实现
 *       "首次插入成功=新鲜、已存在且未过期=重放"。开启方式：
 *       {@code medassist.internal-auth.nonce-store=jdbc}。</li>
 * </ul>
 */
public interface NonceStore {

    enum Outcome {
        /** 首次出现，接受。 */
        FRESH,
        /** 窗口内已出现过，按重放拒绝。 */
        REPLAY,
        /** 存储饱和且无法清理，fail-closed 拒绝（绝不整体清空，否则等于放回全部已用 nonce）。 */
        SATURATED
    }

    Outcome register(String key);
}
