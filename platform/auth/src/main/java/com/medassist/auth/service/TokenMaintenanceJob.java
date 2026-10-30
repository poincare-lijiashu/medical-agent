package com.medassist.auth.service;

import com.medassist.auth.repo.TokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * 令牌与登录尝试的定期清理（防止表无限增长）。
 *
 * <p>频率 conservative（每小时）：清理工作本身是幂等 DELETE，多副本重复执行也无副作用。
 * 保留窗口：刷新令牌过期后再留 7 天（便于事后审计盗用），登录尝试保留 30 天。
 */
@Component
public class TokenMaintenanceJob {

    private static final Logger log = LoggerFactory.getLogger(TokenMaintenanceJob.class);

    private final TokenRepository tokens;

    public TokenMaintenanceJob(TokenRepository tokens) {
        this.tokens = tokens;
    }

    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT2M")
    public void purge() {
        try {
            int refresh = tokens.purgeExpiredRefreshTokens();
            int attempts = tokens.purgeOldAttempts(Instant.now().minus(30, ChronoUnit.DAYS));
            if (refresh > 0 || attempts > 0) {
                log.info("token.maintenance.purged refreshTokens={} loginAttempts={}", refresh, attempts);
            }
        } catch (Exception e) {
            // 清理失败不影响业务（下次重试）
            log.warn("token.maintenance.failed reason={}", e.getMessage());
        }
    }
}
