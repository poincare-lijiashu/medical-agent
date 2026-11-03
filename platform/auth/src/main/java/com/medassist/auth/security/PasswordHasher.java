package com.medassist.auth.security;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.util.HexFormat;

/**
 * 密码哈希：新密码统一 BCrypt；兼容历史 PBKDF2-SHA256 并支持在线升级。
 *
 * <p>背景：v1 的 Python 中台用 {@code hashlib.pbkdf2_hmac} 存储密码
 * （格式 {@code pbkdf2$sha256$<iterations>$<salt-hex>$<dk-hex>}），
 * Flyway {@code V3__import_legacy_users.sql} 原样导入这些哈希。
 * 若 Java 侧只认 BCrypt，导入的老账号将永远无法登录。
 *
 * <p>策略：
 * <ul>
 *   <li>校验：以存储值前缀选择算法（{@code $2a$/$2b$/$2y$} → BCrypt；{@code pbkdf2$} → PBKDF2）；</li>
 *   <li>升级：老哈希校验通过后由 {@code AuthService} 写成 BCrypt（一次性、无感）；</li>
 *   <li>安全：PBKDF2 比较用 {@link MessageDigest#isEqual} 恒定时间；
 *       迭代次数上限防止恶意哈希导致 CPU 耗尽；未知算法一律拒绝。</li>
 * </ul>
 */
@Component
public class PasswordHasher {

    private static final String BCrypt_PREFIX = "$2";
    private static final String PBKDF2_PREFIX = "pbkdf2$";
    /**
     * 迭代次数上限：防止被篡改的超大迭代值拖垮登录线程。
     *
     * <p>取 1,000,000：远超历史 PBKDF2 实际值（60 万以下），
     * 同时把单次校验的 CPU 上限压到毫秒级——否则攻击者可借伪造哈希把登录接口变成 CPU 放大器（S3）。
     */
    private static final int MAX_ITERATIONS = 1_000_000;
    /**
     * BCrypt 强度（P1-S2）：默认 10 在现代 GPU 上已偏弱，提到 12（单次校验约 250ms）。
     *
     * <p>只影响**新编码**；旧哈希按其自身 cost 校验，登录成功后由 {@link #needsUpgrade}
     * 触发在线重写，无需强制全量重置密码。
     */
    private static final int BCRYPT_STRENGTH = 12;

    private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder(BCRYPT_STRENGTH);

    /** 新密码哈希（BCrypt，强度 12，随机盐）。 */
    public String encode(String raw) {
        return bcrypt.encode(raw);
    }

    /** 校验明文密码与存储哈希（自动识别 BCrypt / PBKDF2）。 */
    public boolean verify(String raw, String stored) {
        if (raw == null || stored == null || stored.isBlank()) {
            return false;
        }
        if (stored.startsWith(BCrypt_PREFIX)) {
            return bcrypt.matches(raw, stored);
        }
        if (stored.startsWith(PBKDF2_PREFIX)) {
            return verifyPbkdf2(raw, stored);
        }
        return false;
    }

    /**
     * 是否为需要升级的哈希：非 BCrypt（历史 PBKDF2），或 BCrypt 强度低于当前标准。
     *
     * <p>登录成功后按此重写，旧账号在无感的前提下逐步收敛到强度 12。
     */
    public boolean needsUpgrade(String stored) {
        if (stored == null || stored.isBlank()) {
            return false;
        }
        if (!stored.startsWith(BCrypt_PREFIX)) {
            return true;
        }
        return bcryptCost(stored) < BCRYPT_STRENGTH;
    }

    /** 从 {@code $2a$12$...} 解析强度；无法解析时按 0 处理（视为需要升级）。 */
    private static int bcryptCost(String stored) {
        String[] parts = stored.split("\\$");
        if (parts.length < 3) {
            return 0;
        }
        try {
            return Integer.parseInt(parts[2]);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private boolean verifyPbkdf2(String raw, String stored) {
        String[] parts = stored.split("\\$");
        if (parts.length != 5 || !"pbkdf2".equals(parts[0])) {
            return false;
        }
        String jdkAlgo = switch (parts[1]) {
            case "sha256" -> "PBKDF2WithHmacSHA256";
            case "sha512" -> "PBKDF2WithHmacSHA512";
            case "sha1" -> "PBKDF2WithHmacSHA1";
            default -> null;
        };
        if (jdkAlgo == null) {
            return false;
        }
        int iterations;
        byte[] salt;
        byte[] expected;
        try {
            iterations = Integer.parseInt(parts[2]);
            salt = HexFormat.of().parseHex(parts[3]);
            expected = HexFormat.of().parseHex(parts[4]);
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (iterations <= 0 || iterations > MAX_ITERATIONS || salt.length == 0 || expected.length == 0) {
            return false;
        }
        try {
            byte[] actual = SecretKeyFactory.getInstance(jdkAlgo)
                    .generateSecret(new PBEKeySpec(raw.toCharArray(), salt, iterations, expected.length * 8))
                    .getEncoded();
            return MessageDigest.isEqual(expected, actual);
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            return false;
        }
    }
}
