package com.medassist.auth.security;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 口令策略（§285）：所有写口令的入口共用同一套规则。
 *
 * <p>这里锁的是"能被现场绕过去"的几种典型口令：默认口令、用户名变形、纯重复字符。
 */
class PasswordPolicyTest {

    private final PasswordPolicy policy = new PasswordPolicy();

    @Test
    @DisplayName("长度不足 / 缺字母数字 均被拒绝")
    void rejectsStructuralWeakness() {
        assertRejected("Ab1", "doctor01", "长度");
        assertRejected("abcdefgh", "doctor01", "字母与数字");
        assertRejected("12345678", "doctor01", "字母与数字");
    }

    @Test
    @DisplayName("不得与用户名相同或包含用户名")
    void rejectsUsernameVariants() {
        assertRejected("doctor01", "doctor01", "用户名");
        assertRejected("Doctor01x", "doctor01", "包含用户名");
        // 用户名很短（<3 位）时不做包含判断：否则几乎每条口令都会误报
        assertThatCode(() -> policy.validate("Qx7mN2pL", "ab")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("高频默认口令被拒绝（医院现场最常见）")
    void rejectsCommonDefaults() {
        assertRejected("Password1", "nurse01", "过于常见");
        assertRejected("admin123", "nurse01", "过于常见");
        assertRejected("hospital1", "nurse01", "过于常见");
    }

    @Test
    @DisplayName("合规口令通过")
    void acceptsStrongPassword() {
        assertThatCode(() -> policy.validate("Ward2026#Round", "doctor01")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("生成的临时口令本身满足策略，且长度 20")
    void generatedPasswordSatisfiesPolicy() {
        String temp = policy.generateTemporary();
        assertThat(temp).hasSize(20).startsWith("Aa1");
        assertThatCode(() -> policy.validate(temp, "admin01")).doesNotThrowAnyException();
        assertThat(policy.generateTemporary()).isNotEqualTo(temp);
    }

    private void assertRejected(String password, String username, String messagePart) {
        assertThatThrownBy(() -> policy.validate(password, username))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(messagePart)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }
}
