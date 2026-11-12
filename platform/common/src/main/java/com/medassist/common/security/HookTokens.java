package com.medassist.common.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * 外部接入源令牌（告警平台 / CI 流水线）。
 *
 * <p>为什么只存哈希：接入令牌等同于"机器身份"，一旦数据库泄露，明文令牌可直接伪造
 * 工单或部署记录。这里与密码同规格：明文只在创建/轮换时返回一次，库内仅存 SHA-256。
 *
 * <p>比较必须常量时间：普通 {@code equals} 会在首个不同字节提前返回，
 * 攻击者可通过响应时间逐字节猜出哈希值。
 */
public final class HookTokens {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final HexFormat HEX = HexFormat.of();
    private static final int RANDOM_BYTES = 24;
    private static final int HINT_LENGTH = 12;

    private HookTokens() {
    }

    /** 生成 {@code <prefix>_<6 位标识>_<48 位随机>}，便于在日志中区分来源而不暴露主体。 */
    public static String generate(String prefix) {
        byte[] id = new byte[3];
        byte[] body = new byte[RANDOM_BYTES];
        RANDOM.nextBytes(id);
        RANDOM.nextBytes(body);
        return prefix + "_" + HEX.formatHex(id) + "_" + HEX.formatHex(body);
    }

    public static String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HEX.formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 缺少 SHA-256", e);
        }
    }

    public static boolean matches(String token, String storedHash) {
        if (token == null || token.isBlank() || storedHash == null || storedHash.isBlank()) {
            return false;
        }
        byte[] actual = hash(token).getBytes(StandardCharsets.UTF_8);
        byte[] expected = storedHash.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(actual, expected);
    }

    /** 仅展示前缀，便于管理员辨认"是哪一把钥匙"，不足以还原令牌。 */
    public static String hint(String token) {
        if (token == null) {
            return "";
        }
        return token.length() <= HINT_LENGTH ? token : token.substring(0, HINT_LENGTH);
    }

    /** 从 {@code Authorization: Bearer xxx} 提取令牌；格式不符返回 null。 */
    public static String bearer(String authorization) {
        if (authorization == null) {
            return null;
        }
        String value = authorization.trim();
        if (!value.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        String token = value.substring(7).trim();
        return token.isEmpty() ? null : token;
    }
}
