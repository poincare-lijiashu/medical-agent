package com.medassist.empi.crypto;

import com.medassist.common.security.SecurityDefaults;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 敏感字段加解密（AES-256-GCM）。
 *
 * <p>设计（计划书 D7.4 数据合规）：
 * <ul>
 *   <li>加密：随机 12 字节 IV + AES/GCM/NoPadding，输出 Base64(iv || ciphertext+tag)；</li>
 *   <li>查重不依赖明文：另存 {@link #sha256Hex} 不可逆哈希，明文永不建索引；</li>
 *   <li>密钥经环境变量 {@code EMPI_FIELD_KEY}（Base64 32 字节）注入，禁止硬编码进库；</li>
 *   <li>生产切换国密 SM4 时仅替换本类实现，调用方签名不变。</li>
 * </ul>
 */
@Component
public class FieldCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;
    /** 查重盐最小长度：过短的盐可被彩虹表/字典攻击抵消加盐收益（身份证、手机号空间有限）。 */
    private static final int MIN_SALT_LEN = 16;
    /**
     * 仓库内 {@code application-dev.yml} 中两个开发专用值的 SHA-256 指纹。
     *
     * <p>生产环境若照抄 dev 值启动，等于用"公开密钥 + 公开盐"加密全院 PHI，
     * 与 JWT 的 {@code DEV_JWT_SECRET} 守卫同规格：直接拒绝启动。
     * 这里存指纹而不是明文，避免把 dev 密钥再抄一份进生产制品。
     */
    private static final String DEV_FIELD_KEY_SHA256 =
            "cb59252045f6df063ca6ee2e547e70d0a0fb7c0b05b34815e66e28a60d80e3dc";
    private static final String DEV_HASH_SALT_SHA256 =
            "7545b13b52c6b7cea02e87ad25de34aea94ce8b8aef7bf9d8e310fbb2b6983ca";

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();
    /** 证件号/手机号哈希加盐（防止彩虹表反查）；与密钥分离配置。 */
    private final String hashSalt;

    public FieldCipher(@Value("${empi.field-key:}") String base64Key,
                       @Value("${empi.hash-salt:}") String hashSalt,
                       Environment environment) {
        // fail-fast：密钥缺失时绝不"用默认值继续跑"——那等于用公开密钥加密全院 PHI
        if (base64Key == null || base64Key.isBlank()) {
            throw new IllegalStateException(
                    "EMPI_FIELD_KEY 未配置：患者敏感字段无法安全加密，服务拒绝启动。"
                            + "生产请由密钥管理系统注入；本地开发请加 --spring.profiles.active=dev");
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64Key.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("EMPI_FIELD_KEY 不是合法的 Base64 字符串", e);
        }
        if (raw.length != 32) {
            throw new IllegalStateException("EMPI_FIELD_KEY 必须为 Base64 编码的 32 字节密钥（AES-256）");
        }
        if (hashSalt == null || hashSalt.isBlank()) {
            throw new IllegalStateException(
                    "EMPI_HASH_SALT 未配置：查重哈希将失去抗彩虹表能力，服务拒绝启动。"
                            + "生产请由密钥管理系统注入；本地开发请加 --spring.profiles.active=dev");
        }
        if (hashSalt.trim().length() < MIN_SALT_LEN) {
            throw new IllegalStateException("EMPI_HASH_SALT 长度不得少于 " + MIN_SALT_LEN + " 个字符");
        }
        // 生产禁用仓库内 dev 默认密钥/盐（与 JWT DEV_JWT_SECRET 守卫同规格，P0-10 ①）
        if (SecurityDefaults.isProduction(environment)) {
            String keyDigest = sha256Hex(base64Key.trim());
            String saltDigest = sha256Hex(hashSalt.trim());
            if (DEV_FIELD_KEY_SHA256.equals(keyDigest) || DEV_HASH_SALT_SHA256.equals(saltDigest)) {
                throw new IllegalStateException(
                        "检测到生产/预发布环境仍在使用 application-dev.yml 的开发默认 EMPI 密钥或盐，"
                                + "服务拒绝启动（请通过 Secret 注入 EMPI_FIELD_KEY / EMPI_HASH_SALT）");
            }
        }
        this.key = new SecretKeySpec(raw, "AES");
        this.hashSalt = hashSalt.trim();
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** 加密；入参为空返回 null（空值不加密，避免无意义密文）。 */
    public String encrypt(String plain) {
        if (plain == null || plain.isBlank()) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_LEN];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("字段加密失败", e);
        }
    }

    /** 解密；密文为空或非法返回 null（历史脏数据不阻断查询）。 */
    public String decrypt(String encrypted) {
        if (encrypted == null || encrypted.isBlank()) {
            return null;
        }
        try {
            byte[] all = Base64.getDecoder().decode(encrypted);
            if (all.length <= IV_LEN) {
                return null;
            }
            byte[] iv = new byte[IV_LEN];
            System.arraycopy(all, 0, iv, 0, IV_LEN);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] pt = cipher.doFinal(all, IV_LEN, all.length - IV_LEN);
            return new String(pt, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    /** 查重哈希（加盐 SHA-256，十六进制小写）。 */
    public String hash(String plain) {
        if (plain == null || plain.isBlank()) {
            return null;
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            String salted = hashSalt + "|" + plain.trim().toUpperCase();
            return HexFormat.of().formatHex(md.digest(salted.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("哈希计算失败", e);
        }
    }
}
