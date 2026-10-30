package com.medassist.gateway.security;

import com.medassist.gateway.config.GatewayAuthProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

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
 *   <li>登出/停用后最迟一个同步周期内生效；auth 不可用时保留最后一次名单（宁可少拦，不阻断业务）。</li>
 * </ul>
 *
 * <p>边界：多副本网关各自缓存，同一时刻可能有 ≤1 个周期的差异；不做分布式一致性，
 * 因为撤销是"秒级最终一致"而非强一致需求。
 */
@Component
public class TokenRevocationCache {

    private static final Logger log = LoggerFactory.getLogger(TokenRevocationCache.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final GatewayAuthProperties props;
    private final WebClient client;
    private final Map<String, Instant> revoked = new ConcurrentHashMap<>();
    private final AtomicLong lastSyncMillis = new AtomicLong(0L);

    public TokenRevocationCache(GatewayAuthProperties props) {
        this.props = props;
        this.client = WebClient.builder().baseUrl(props.authUri()).build();
    }

    public boolean isRevoked(String jti) {
        if (jti == null || revoked.isEmpty()) {
            return false;
        }
        Instant expiresAt = revoked.get(jti);
        return expiresAt != null && expiresAt.isAfter(Instant.now());
    }

    public int size() {
        return revoked.size();
    }

    @Scheduled(fixedDelayString = "${medassist.gateway.auth.revocation-sync.interval:PT15S}",
            initialDelayString = "PT5S")
    public void sync() {
        if (!props.revocationSync().enabled()) {
            return;
        }
        long since = lastSyncMillis.get();
        try {
            String body = client.get()
                    .uri(uri -> uri.path("/internal/revocations").queryParam("since", since).build())
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(java.time.Duration.ofSeconds(5));
            if (body == null || body.isBlank()) {
                return;
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
            lastSyncMillis.set(serverTime);
            pruneExpired();
            if (!items.isEmpty()) {
                log.info("gateway.revocation.synced new={} total={}", items.size(), revoked.size());
            }
        } catch (Exception e) {
            // auth 暂不可用：保留旧名单，不阻断流量（fail-open 仅针对撤销名单）
            log.warn("gateway.revocation.sync_failed reason={}", e.getMessage());
        }
    }

    private void pruneExpired() {
        Instant now = Instant.now();
        revoked.entrySet().removeIf(entry -> !entry.getValue().isAfter(now));
    }
}
