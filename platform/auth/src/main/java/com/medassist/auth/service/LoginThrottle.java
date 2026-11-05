package com.medassist.auth.service;

import com.medassist.auth.repo.TokenRepository;
import com.medassist.auth.security.LoginThrottleProperties;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Locale;

/**
 * 登录限速（暴力破解防护）。
 *
 * <p>统计口径（P1-S3）：
 * <ol>
 *   <li><b>同一账号 + 同一来源 IP</b> 的失败次数达到 {@code maxFailures} → 429；</li>
 *   <li>同一来源 IP 的失败次数达到 {@code maxIpFailures}（显著更高）→ 429。</li>
 * </ol>
 *
 * <p>账号维度必须与 IP 组合：只按 username 全局计数时，攻击者从任意地址
 * 连错 5 次即可把目标账号锁 5 分钟——低成本拒绝服务，且受害者自己也无法登录。
 *
 * <p><b>原子占位（G62）</b>：旧实现是"先 check 计数 → bcrypt 校验（~100ms）→ 后 record"
 * 两步，并发请求在任何 record 落库前都能通过 check，实际可尝试次数远超阈值。
 * 现改为登录开始时先 {@link #reserve}（单语句 {@code ON CONFLICT DO UPDATE} 原子自增，
 * 行锁串行化），超限即 429；登录成功再 {@link #refund} 退还这次占位。
 *
 * <p>来源 IP 口径（2026-10-31 已修）：只读取网关按 trusted-proxies 规则重写的
 * {@code X-Client-Ip}，auth 不再自行解析 {@code X-Forwarded-For}（SCG 对 XFF 是追加语义，
 * 客户端自带首段即可自选来源 IP）。网关未注入该头时退化为"未知来源"，账号+IP 维度仍然有效。
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

    /**
     * 占位并判定是否超限：未超限返回本次占位（登录成功后需 {@link #refund}）。
     *
     * @throws BusinessException 429，已超过窗口阈值
     */
    public Reservation reserve(String username, String clientIp) {
        if (!props.enabled()) {
            return Reservation.DISABLED;
        }
        String userKey = userKey(username, clientIp);
        int userFailures = tokens.claimThrottle(userKey, props.window());
        if (userFailures > props.maxFailures()) {
            long minutes = Math.max(1, props.window().toMinutes());
            log.warn("login.throttled.reason=ACCOUNT_IP username={} ip={} failures={}",
                    username, clientIp, userFailures);
            throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS,
                    "登录失败次数过多，请 " + minutes + " 分钟后再试");
        }
        // IP 维度阈值显著更高：医院内网共享 NAT 出口，过低会连坐整层楼（实测踩坑）
        String ipKey = ipKey(clientIp);
        int ipFailures = tokens.claimThrottle(ipKey, props.window());
        if (ipFailures > props.maxIpFailures()) {
            long minutes = Math.max(1, props.window().toMinutes());
            log.warn("login.throttled.reason=IP ip={} failures={}", clientIp, ipFailures);
            throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS,
                    "当前网络登录失败次数过多，请 " + minutes + " 分钟后再试");
        }
        return new Reservation(userKey, ipKey);
    }

    /** 登录成功：退还本次占位（成功不应计入失败次数）。 */
    public void refund(Reservation reservation) {
        if (!props.enabled() || reservation == null || !reservation.active()) {
            return;
        }
        tokens.refundThrottle(reservation.userKey());
        tokens.refundThrottle(reservation.ipKey());
    }

    /**
     * 记录一次登录尝试明细（审计口径，不参与限速判定）。
     *
     * <p>限速判定只看 {@link TokenRepository#claimThrottle} 的原子桶，
     * 这里落 {@code login_attempt} 仅供事后分析与告警。
     */
    public void record(String username, String clientIp, boolean success) {
        tokens.recordAttempt(username, clientIp, success);
    }

    private static String userKey(String username, String clientIp) {
        return "u:" + (username == null ? "" : username.trim().toLowerCase(Locale.ROOT))
                + "|" + clientIp;
    }

    private static String ipKey(String clientIp) {
        return "i:" + clientIp;
    }

    /** 一次登录的限速占位；{@link #DISABLED} 表示限速关闭（无需退还）。 */
    public record Reservation(String userKey, String ipKey) {

        static final Reservation DISABLED = new Reservation(null, null);

        boolean active() {
            return userKey != null;
        }
    }
}
