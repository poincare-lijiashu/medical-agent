package com.medassist.empi.crypto;

import org.springframework.beans.factory.annotation.Value;
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

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();
    /** 证件号/手机号哈希加盐（防止彩虹表反查）；与密钥分离配置。 */
    private final String hashSalt;

    public FieldCipher(@Value("${empi.field-key}") String base64Key,
                       @Value("${empi.hash-salt:medassist-empi-salt}") String hashSalt) {
        byte[] raw = Base64.getDecoder().decode(base64Key);
        if (raw.length != 32) {
            throw new IllegalStateException("EMPI_FIELD_KEY 必须为 Base64 编码的 32 字节密钥");
        }
        this.key = new SecretKeySpec(raw, "AES");
        this.hashSalt = hashSalt;
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
