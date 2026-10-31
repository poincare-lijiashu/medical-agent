package com.medassist.auth.service;

import com.medassist.auth.repo.TokenRepository;
import com.medassist.auth.security.LoginThrottleProperties;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 登录限速测试（M8：此前完全无测试）。
 *
 * <p>关键断言是"两个维度必须分离"：账号维度触发时只锁该账号，
 * IP 维度阈值更高，不能把同出口的其他账号连坐（医院 NAT 场景）。
 */
class LoginThrottleTest {

    private static final LoginThrottleProperties PROPS =
            new LoginThrottleProperties(true, 5, 30, Duration.ofMinutes(5));

    @Test
    void passesWhenBelowThresholds() {
        TokenRepository tokens = mock(TokenRepository.class);
        when(tokens.countRecentFailuresForUser(anyString(), any(Instant.class))).thenReturn(4L);
        when(tokens.countRecentFailuresForIp(anyString(), any(Instant.class))).thenReturn(29L);

        assertThatCode(() -> new LoginThrottle(tokens, PROPS).check("doctor01", "10.0.0.1"))
                .doesNotThrowAnyException();
    }

    @Test
    void accountThresholdReturns429() {
        TokenRepository tokens = mock(TokenRepository.class);
        when(tokens.countRecentFailuresForUser(anyString(), any(Instant.class))).thenReturn(5L);
        when(tokens.countRecentFailuresForIp(anyString(), any(Instant.class))).thenReturn(0L);

        assertThatThrownBy(() -> new LoginThrottle(tokens, PROPS).check("doctor01", "10.0.0.1"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).errorCode())
                        .isEqualTo(ErrorCode.TOO_MANY_REQUESTS))
                .hasMessageContaining("5 分钟");
    }

    @Test
    void ipThresholdIsHigherThanAccountThreshold() {
        TokenRepository tokens = mock(TokenRepository.class);
        // 账号未超限，但同 IP 已达 30 次
        when(tokens.countRecentFailuresForUser(anyString(), any(Instant.class))).thenReturn(0L);
        when(tokens.countRecentFailuresForIp(anyString(), any(Instant.class))).thenReturn(30L);

        assertThatThrownBy(() -> new LoginThrottle(tokens, PROPS).check("nurse99", "10.0.0.1"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("网络");
    }

    @Test
    void disabledSwitchSkipsAllChecks() {
        TokenRepository tokens = mock(TokenRepository.class);
        LoginThrottleProperties disabled =
                new LoginThrottleProperties(false, 5, 30, Duration.ofMinutes(5));

        assertThatCode(() -> new LoginThrottle(tokens, disabled).check("doctor01", "10.0.0.1"))
                .doesNotThrowAnyException();
    }
}
