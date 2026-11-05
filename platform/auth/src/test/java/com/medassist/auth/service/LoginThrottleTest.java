package com.medassist.auth.service;

import com.medassist.auth.repo.TokenRepository;
import com.medassist.auth.security.LoginThrottleProperties;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 登录限速测试（M8；G62 改为原子占位）。
 *
 * <p>关键断言：
 * <ol>
 *   <li>两个维度必须分离：账号维度触发时只锁该账号，IP 维度阈值更高，
 *       不能把同出口的其他账号连坐（医院 NAT 场景）；</li>
 *   <li>判定发生在占位返回的计数上（不是先查后写），并发请求由行锁串行化；</li>
 *   <li>成功登录退还占位，成功不计入失败次数。</li>
 * </ol>
 */
class LoginThrottleTest {

    private static final LoginThrottleProperties PROPS =
            new LoginThrottleProperties(true, 5, 30, Duration.ofMinutes(5));

    @Test
    void passesWhenBelowThresholds() {
        TokenRepository tokens = mock(TokenRepository.class);
        when(tokens.claimThrottle(anyString(), any(Duration.class))).thenReturn(4, 29);

        var reservation = new LoginThrottle(tokens, PROPS).reserve("doctor01", "10.0.0.1");

        assertThat(reservation.active()).isTrue();
        verify(tokens).claimThrottle(eq("u:doctor01|10.0.0.1"), eq(PROPS.window()));
        verify(tokens).claimThrottle(eq("i:10.0.0.1"), eq(PROPS.window()));
    }

    @Test
    void blocksWhenAccountIpBucketExceeded() {
        TokenRepository tokens = mock(TokenRepository.class);
        when(tokens.claimThrottle(anyString(), any(Duration.class))).thenReturn(6);

        assertThatThrownBy(() -> new LoginThrottle(tokens, PROPS).reserve("doctor01", "10.0.0.1"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.TOO_MANY_REQUESTS));
        // 账号维度已超限：不再占用 IP 桶
        verify(tokens, never()).claimThrottle(eq("i:10.0.0.1"), any(Duration.class));
    }

    @Test
    void blocksWhenIpBucketExceeded() {
        TokenRepository tokens = mock(TokenRepository.class);
        when(tokens.claimThrottle(eq("u:nurse99|10.0.0.1"), any(Duration.class))).thenReturn(1);
        when(tokens.claimThrottle(eq("i:10.0.0.1"), any(Duration.class))).thenReturn(31);

        assertThatThrownBy(() -> new LoginThrottle(tokens, PROPS).reserve("nurse99", "10.0.0.1"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.TOO_MANY_REQUESTS));
    }

    @Test
    void usernameBucketIsCaseInsensitive() {
        TokenRepository tokens = mock(TokenRepository.class);
        when(tokens.claimThrottle(anyString(), any(Duration.class))).thenReturn(1, 1);

        new LoginThrottle(tokens, PROPS).reserve("Doctor01", "10.9.9.9");

        verify(tokens).claimThrottle(eq("u:doctor01|10.9.9.9"), any(Duration.class));
    }

    @Test
    void refundOnlyForActiveReservation() {
        TokenRepository tokens = mock(TokenRepository.class);
        when(tokens.claimThrottle(anyString(), any(Duration.class))).thenReturn(1, 1);
        LoginThrottle throttle = new LoginThrottle(tokens, PROPS);

        var reservation = throttle.reserve("doctor01", "10.0.0.1");
        throttle.refund(reservation);

        verify(tokens).refundThrottle("u:doctor01|10.0.0.1");
        verify(tokens).refundThrottle("i:10.0.0.1");
    }

    @Test
    void disabledThrottleSkipsStorage() {
        TokenRepository tokens = mock(TokenRepository.class);
        LoginThrottleProperties disabled =
                new LoginThrottleProperties(false, 5, 30, Duration.ofMinutes(5));
        LoginThrottle throttle = new LoginThrottle(tokens, disabled);

        assertThatCode(() -> throttle.reserve("doctor01", "10.0.0.1"))
                .doesNotThrowAnyException();
        throttle.refund(LoginThrottle.Reservation.DISABLED);
        verifyNoInteractions(tokens);
    }
}
