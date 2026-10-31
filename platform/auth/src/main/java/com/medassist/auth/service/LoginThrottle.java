package com.medassist.auth.service;

import com.medassist.auth.repo.TokenRepository;
import com.medassist.auth.security.LoginThrottleProperties;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * 登录限速（暴力破解防护）。
 *
 * <p>统计口径：最近 {@code window} 内、同一 username <b>或</b>同一来源 IP 的失败次数，
 * 任一维度达到 {@code maxFailures} 即拒绝登录并返回 429。计数直接落 PG，
 * 多副本共享同一窗口（无需 Redis / 粘性会话）。
 *
 * <p>来源 IP 口径（2026-10-31 已修）：只读取网关按 trusted-proxies 规则重写的
 * {@code X-Client-Ip}，auth 不再自行解析 {@code X-Forwarded-For}（SCG 对 XFF 是追加语义，
 * 客户端自带首段即可自选来源 IP）。网关未注入该头时退化为"未知来源"，username 维度仍然有效。
 * 如需更强的 IP 约束，应在 Ingress 层做真实源地址限速。
 */
@Service
public class LoginThrottle {

    private static final Logger log = LoggerFactory.getLogger(LoginThrottle.class);

    private final TokenRepository tokens;
    private final LoginThrottleProperties props;

    public LoginThrottle(TokenRepository tokens, LoginThrottleProperties props) {
        this.tokens = tokens;
        this.props = props;
    }

    /** 检查是否已触发限速；触发则抛 429（文案包含等待分钟数）。 */
    public void check(String username, String clientIp) {
        if (!props.enabled()) {
            return;
        }
        Instant since = Instant.now().minus(props.window());
        long userFailures = tokens.countRecentFailuresForUser(username, since);
        if (userFailures >= props.maxFailures()) {
            long minutes = Math.max(1, props.window().toMinutes());
            log.warn("login.throttled.reason=ACCOUNT username={} ip={} failures={}",
                    username, clientIp, userFailures);
            throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS,
                    "登录失败次数过多，请 " + minutes + " 分钟后再试");
        }
        // IP 维度阈值显著更高：医院内网共享 NAT 出口，过低会连坐整层楼（实测踩坑）
        long ipFailures = tokens.countRecentFailuresForIp(clientIp, since);
        if (ipFailures >= props.maxIpFailures()) {
            long minutes = Math.max(1, props.window().toMinutes());
            log.warn("login.throttled.reason=IP ip={} failures={}", clientIp, ipFailures);
            throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS,
                    "当前网络登录失败次数过多，请 " + minutes + " 分钟后再试");
        }
    }

    /** 记录一次登录尝试（成功/失败都记，供统计与审计分析）。 */
    public void record(String username, String clientIp, boolean success) {
        tokens.recordAttempt(username, clientIp, success);
    }
}
