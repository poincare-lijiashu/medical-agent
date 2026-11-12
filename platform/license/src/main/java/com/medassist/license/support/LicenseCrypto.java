package com.medassist.license.support;

import com.medassist.license.config.LicenseProperties;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 授权书签名与验签（Ed25519）。
 *
 * <p>密钥纪律：
 * <ul>
 *   <li>院内只需要**公钥**（{@code LICENSE_VERIFY_KEY}）即可离线验签，私钥不应进院；</li>
 *   <li>私钥只在厂商构建中通过 {@code LICENSE_SIGNING_KEY} 注入（KMS/HSM 更好）；
 *       开发环境没有密钥时在 {@code data/license/} 下自动生成，方便本地走通全链路；</li>
 *   <li>自动生成的私钥目录权限收窄到当前用户，日志只打印 keyId，不打印密钥本体。</li>
 * </ul>
 */
public final class LicenseCrypto {

    private static final String ALG = "Ed25519";
    private static final String PRIVATE_FILE = "ed25519-private.b64";
    private static final String PUBLIC_FILE = "ed25519-public.b64";

    private LicenseCrypto() {
    }

    /** 验签公钥：优先生成配置里的 verifyKey，否则由私钥推导（开发环境）。 */
    public static PublicKey publicKey(LicenseProperties props) {
        try {
            if (props.verifyKey() != null && !props.verifyKey().isBlank()) {
                byte[] der = Base64.getDecoder().decode(props.verifyKey().trim());
                return KeyFactory.getInstance(ALG).generatePublic(new X509EncodedKeySpec(der));
            }
            return keyPair(props).getPublic();
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("授权验签公钥不可用：" + e.getMessage(), e);
        }
    }

    /** 签发私钥：配置 > 落盘文件 > 自动生成（仅开发）。 */
    public static PrivateKey privateKey(LicenseProperties props) {
        try {
            if (props.signingKey() != null && !props.signingKey().isBlank()) {
                byte[] der = Base64.getDecoder().decode(props.signingKey().trim());
                return KeyFactory.getInstance(ALG).generatePrivate(new PKCS8EncodedKeySpec(der));
            }
            return keyPair(props).getPrivate();
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("授权签发私钥不可用：" + e.getMessage(), e);
        }
    }

    /** 密钥对：优先配置，其次落盘，最后生成并持久化（开发环境幂等）。 */
    public static KeyPair keyPair(LicenseProperties props) {
        if (props.signingKey() != null && !props.signingKey().isBlank()) {
            try {
                PrivateKey priv = KeyFactory.getInstance(ALG)
                        .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(props.signingKey().trim())));
                PublicKey pub = props.verifyKey() != null && !props.verifyKey().isBlank()
                        ? KeyFactory.getInstance(ALG)
                        .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(props.verifyKey().trim())))
                        : derivePublic(priv);
                return new KeyPair(pub, priv);
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException("授权密钥解析失败：" + e.getMessage(), e);
            }
        }
        Path dir = Path.of(props.keyDir());
        Path privFile = dir.resolve(PRIVATE_FILE);
        Path pubFile = dir.resolve(PUBLIC_FILE);
        try {
            if (Files.isRegularFile(privFile) && Files.isRegularFile(pubFile)) {
                PrivateKey priv = KeyFactory.getInstance(ALG).generatePrivate(
                        new PKCS8EncodedKeySpec(Base64.getDecoder().decode(Files.readString(privFile, StandardCharsets.UTF_8).trim())));
                PublicKey pub = KeyFactory.getInstance(ALG).generatePublic(
                        new X509EncodedKeySpec(Base64.getDecoder().decode(Files.readString(pubFile, StandardCharsets.UTF_8).trim())));
                return new KeyPair(pub, priv);
            }
            KeyPair generated = KeyPairGenerator.getInstance(ALG).generateKeyPair();
            Files.createDirectories(dir);
            Files.writeString(privFile, Base64.getEncoder().encodeToString(generated.getPrivate().getEncoded()),
                    StandardCharsets.UTF_8);
            Files.writeString(pubFile, Base64.getEncoder().encodeToString(generated.getPublic().getEncoded()),
                    StandardCharsets.UTF_8);
            restrict(privFile);
            return generated;
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalStateException("授权密钥初始化失败：" + e.getMessage(), e);
        }
    }

    /** Ed25519 签名（Base64）。 */
    public static String sign(byte[] data, PrivateKey key) {
        try {
            Signature signer = Signature.getInstance(ALG);
            signer.initSign(key);
            signer.update(data);
            return Base64.getEncoder().encodeToString(signer.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("授权签名失败：" + e.getMessage(), e);
        }
    }

    /** 验签：任何异常都按"不通过"处理（不把异常类型泄露给调用方）。 */
    public static boolean verify(byte[] data, String signatureBase64, PublicKey key) {
        if (signatureBase64 == null || signatureBase64.isBlank()) {
            return false;
        }
        try {
            Signature verifier = Signature.getInstance(ALG);
            verifier.initVerify(key);
            verifier.update(data);
            return verifier.verify(Base64.getDecoder().decode(signatureBase64.trim()));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return false;
        }
    }

    /** 密钥标识：公钥 SHA-256 的前 16 个 hex 字符，用于授权书与运维排查（不泄露密钥）。 */
    public static String keyId(PublicKey key) {
        return sha256Hex(key.getEncoded()).substring(0, 16);
    }

    public static String sha256Hex(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static PublicKey derivePublic(PrivateKey priv) throws GeneralSecurityException {
        // Ed25519 私钥（PKCS#8）尾部即为 32 字节公钥：从编码里取出，避免依赖 KeyFactory 的推导能力
        byte[] der = priv.getEncoded();
        if (der.length < 32) {
            throw new GeneralSecurityException("Ed25519 私钥长度异常");
        }
        byte[] raw = new byte[32];
        System.arraycopy(der, der.length - 32, raw, 0, 32);
        // 重新构造 SPKI：302a300506032b6570032100 || raw
        byte[] prefix = HexFormat.of().parseHex("302a300506032b6570032100");
        byte[] spki = new byte[prefix.length + raw.length];
        System.arraycopy(prefix, 0, spki, 0, prefix.length);
        System.arraycopy(raw, 0, spki, prefix.length, raw.length);
        return KeyFactory.getInstance(ALG).generatePublic(new X509EncodedKeySpec(spki));
    }

    private static void restrict(Path file) {
        try {
            file.toFile().setReadable(false, false);
            file.toFile().setReadable(true, true);
            file.toFile().setWritable(false, false);
            file.toFile().setWritable(true, true);
        } catch (RuntimeException ignored) {
            // 某些文件系统（容器只读层/WSL 挂载）不支持细粒度权限，忽略即可
        }
    }
}
