package com.medassist.auth.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 密码哈希兼容性测试：BCrypt 新密码 + 历史 PBKDF2 校验与升级判定。
 *
 * <p>PBKDF2 用例的期望值由 v1 Python 中台同一算法生成
 * （{@code hashlib.pbkdf2_hmac('sha256', pw, salt, iterations)}），确保跨语言一致。
 */
class PasswordHasherTest {

    /** 由 Python 生成的固定向量：pbkdf2$sha256$10000$0011...$6712... */
    private static final String LEGACY_HASH =
            "pbkdf2$sha256$10000$00112233445566778899aabbccddeeff"
                    + "$6712a37ba504691c79a4621a89c5344f8d22da78d3afd9947841349d8064ed57";

    private final PasswordHasher hasher = new PasswordHasher();

    @Test
    void bcryptRoundTrip() {
        String hash = hasher.encode("Med@2026");

        assertThat(hash).startsWith("$2");
        assertThat(hasher.verify("Med@2026", hash)).isTrue();
        assertThat(hasher.verify("wrong-password", hash)).isFalse();
        assertThat(hasher.needsUpgrade(hash)).isFalse();
    }

    @Test
    void verifiesLegacyPbkdf2Hash() {
        assertThat(hasher.verify("Med@2026", LEGACY_HASH)).isTrue();
        assertThat(hasher.verify("Med@2025", LEGACY_HASH)).isFalse();
        assertThat(hasher.needsUpgrade(LEGACY_HASH)).isTrue();
    }

    @Test
    void rejectsMalformedOrUnknownHashes() {
        assertThat(hasher.verify("Med@2026", "")).isFalse();
        assertThat(hasher.verify("Med@2026", null)).isFalse();
        assertThat(hasher.verify(null, LEGACY_HASH)).isFalse();
        assertThat(hasher.verify("Med@2026", "pbkdf2$md5$1000$00$00")).isFalse();
        assertThat(hasher.verify("Med@2026", "pbkdf2$sha256$0$00$00")).isFalse();
        assertThat(hasher.verify("Med@2026", "pbkdf2$sha256$99999999$00$00")).isFalse();
        assertThat(hasher.verify("Med@2026", "pbkdf2$sha256$1000$zz$00")).isFalse();
        assertThat(hasher.verify("Med@2026", "plain-text")).isFalse();
    }
}
