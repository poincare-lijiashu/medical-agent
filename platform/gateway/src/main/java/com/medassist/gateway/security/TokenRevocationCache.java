package com.medassist.gateway.security;

import com.medassist.gateway.config.GatewayAuthProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 访问令牌撤销名单（内存缓存 + 增量同步）。
 *
 * <p>为什么不在每个请求上回调 auth 做内省：那会把网关变成 auth 的同步依赖，
 * 一次 auth 抖动等于全院登录态抖动。这里采用**增量拉取 + 内存黑名单**：
 * <ul>
 *   <li>auth 暴露 {@code GET /internal/revocations?since=<epochMillis>}（仅内网直连）；</li>
 *   <li>网关每 {@code revocation-sync.interval}（默认 15s）拉一次增量，裁剪已过期项；</li>
 *   <li>登出/停用后最迟一个同步周期内生效；运行期 auth 不可用时保留最后一次名单（宁可少拦，不阻断业务）。</li>
 * </ul>
 *
 * <p><b>启动语义（M4 修正）</b>：旧实现首次同步在启动后 5s 才发生，窗口内名单为空 →
 * 已登出的令牌会被重新放行。现在改为启动时<b>阻塞完成首次同步</b>；
 * 首次失败按 {@code revocation-sync.fail-fast-on-startup}（默认 true）拒绝启动，
 * 让 K8s 重启而不是带着空名单接流量。若显式关闭 fail-fast，则在首次同步成功前
 * {@link #isRevoked} 一律返回 true（fail-closed）。
 *
 * <p>边界：多副本网关各自缓存，同一时刻可能有 ≤1 个周期的差异；不做分布式一致性，
 * 因为撤销是"秒级最终一致"而非强一致需求。
 */
@Component
public class TokenRevocationCache implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(TokenRevocationCache.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Duration SYNC_TIMEOUT = Duration.ofSeconds(5);
    /** 名单容量上限：仅为防御 auth 长期不可用 + 大规模登出造成的无界增长。 */
    private static final int MAX_ENTRIES = 200_000;

    private final GatewayAuthProperties props;
    private final WebClient client;
    private final Map<String, Instant> revoked = new ConcurrentHashMap<>();
    private final AtomicLong lastSyncMillis = new AtomicLong(0L);
    /** 首轮同步是否已完成；未完成时按 fail-closed 处理（见类注释）。 */
    private volatile boolean initialized = false;

    public TokenRevocationCache(GatewayAuthProperties props) {
        this.props = props;
        this.client = WebClient.builder().baseUrl(props.authUri()).build();
        // 同步关闭时不存在"名单未知"的问题，直接视为已初始化
        GatewayAuthProperties.RevocationSync cfg = props.revocationSync();
        this.initialized = (cfg == null || !cfg.enabled());
    }

    /**
     * 启动时同步拉取首轮名单。
     *
     * <p>刻意阻塞启动：网关 Pod 未就绪比"带着空撤销名单对外服务"安全得多。
     */
    @Override
    public void afterPropertiesSet() {
        GatewayAuthProperties.RevocationSync cfg = props.revocationSync();
        if (cfg == null || !cfg.enabled()) {
            log.warn("gateway.revocation.disabled（撤销名单同步已关闭，登出令牌在 TTL 内仍有效）");
            initialized = true;
            return;
        }
        boolean ok = doSync();
        if (!ok && cfg.failFastOnStartup()) {
            throw new IllegalStateException(
                    "网关启动时无法拉取令牌撤销名单（auth 不可达）：拒绝启动以免已登出令牌被放行。"
                            + "确认 auth 可用后重启；临时排障可将 revocation-sync.fail-fast-on-startup 设为 false");
        }
        initialized = ok;
        if (!ok) {
            log.error("gateway.revocation.startup_sync_failed 在首次同步成功前将拒绝全部受保护请求（fail-closed）");
        }
    }

    public boolean isRevoked(String jti) {
        // 首轮同步未完成：撤销状态未知，按"已撤销"处理（fail-closed）
        if (!initialized) {
            return true;
        }
        // 没有 jti 的令牌无法与撤销名单比对：按"已撤销"处理（fail-closed）。
        // 调用方（AuthGlobalFilter）也在入口拒绝缺 jti 的令牌，这里是第二道防线。
        if (jti == null || jti.isBlank()) {
            return true;
        }
        if (revoked.isEmpty()) {
            return false;
        }
        Instant expiresAt = revoked.get(jti);
        return expiresAt != null && expiresAt.isAfter(Instant.now());
    }

    public int size() {
        return revoked.size();
    }

    @Scheduled(fixedDelayString = "${medassist.gateway.auth.revocation-sync.interval:PT15S}",
            initialDelayString = "${medassist.gateway.auth.revocation-sync.interval:PT15S}")
    public void sync() {
        if (props.revocationSync() == null || !props.revocationSync().enabled()) {
            return;
        }
        boolean ok = doSync();
        if (ok) {
            initialized = true;
        }
    }

    /** @return 本轮同步是否成功（HTTP 与解析均正常） */
    private boolean doSync() {
        long since = lastSyncMillis.get();
        try {
            String token = props.internalToken();
            if (token == null || token.isBlank()) {
                // auth 侧内部接口是 fail-closed 的：没有令牌必然 403，直接按失败处理，
                // 让 fail-fast 在启动期就把"漏配令牌"暴露出来，而不是静默空名单运行。
                log.error("gateway.revocation.sync.missing_token 未配置内部令牌，无法同步撤销名单；"
                        + "请注入 INTERNAL_API_TOKEN（或 medassist.gateway.auth.internal-token）");
                return false;
            }
            String body = client.get()
                    .uri(uri -> uri.path("/internal/revocations").queryParam("since", since).build())
                    .header("X-Internal-Token", token)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(SYNC_TIMEOUT);
            if (body == null || body.isBlank()) {
                return false;
            }
            JsonNode root = JSON.readTree(body).path("data");
            JsonNode items = root.path("revocations");
            if (items.isArray()) {
                for (JsonNode item : items) {
                    String jti = item.path("jti").asString("");
                    String exp = item.path("expiresAt").asString("");
                    if (!jti.isBlank() && !exp.isBlank()) {
                        revoked.put(jti, Instant.parse(exp));
                    }
                }
            }
            long serverTime = root.path("serverTime").asLong(System.currentTimeMillis());
            // 回拨 2s：auth 与网关存在时钟偏差时，严格 "> since" 会漏掉同一毫秒写入的撤销项
            lastSyncMillis.set(Math.max(0L, serverTime - 2000L));
            pruneExpired();
            if (!items.isEmpty()) {
                log.info("gateway.revocation.synced new={} total={}", items.size(), revoked.size());
            }
            return true;
        } catch (Exception e) {
            // 运行期 auth 暂不可用：保留旧名单，不阻断流量（fail-open 仅针对撤销名单）
            log.warn("gateway.revocation.sync_failed reason={}", e.toString());
            return false;
        }
    }

    /**
     * 清理过期项；同时兜底容量上限。
     *
     * <p>仅依赖同步成功后才清理会导致"auth 长期不可用 → 名单只增不减"，
     * 因此超过阈值时也主动裁剪（先剪过期，仍超限则丢弃最早期的一条并告警）。
     */
    private void pruneExpired() {
        Instant now = Instant.now();
        revoked.entrySet().removeIf(entry -> !entry.getValue().isAfter(now));
        if (revoked.size() <= MAX_ENTRIES) {
            return;
        }
        log.error("gateway.revocation.capacity_exceeded size={} limit={}，开始按到期时间裁剪",
                revoked.size(), MAX_ENTRIES);
        revoked.entrySet().stream()
                .sorted(Map.Entry.comparingByValue())
                .limit(revoked.size() - MAX_ENTRIES)
                .map(Map.Entry::getKey)
                .toList()
                .forEach(revoked::remove);
    }
}
