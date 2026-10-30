package com.medassist.auth.security;

import com.medassist.auth.domain.UserAccount;
import com.medassist.common.security.SecurityDefaults;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * JWT 签发与本地校验（HS256）。
 *
 * <p>安全约束：
 * <ul>
 *   <li>密钥只从配置（环境变量 {@code JWT_SECRET}）读取，代码内无真实密钥；</li>
 *   <li>密钥长度 &lt; 32 字节直接拒绝启动（HS256 要求 256 位，短密钥可被离线爆破）；</li>
 *   <li>校验侧固定算法 + 校验 iss/exp，拒绝 alg=none 与算法混淆攻击；</li>
 *   <li>令牌只携带身份与授权快照（sub/roles/org/exp），<b>不携带任何患者敏感信息</b>。</li>
 * </ul>
 *
 * <p>已知边界：JWT 无状态，账号被停用后旧令牌在 TTL 内仍有效。P0 以 8 小时 TTL + 全部
 * 管理操作审计兜底；P1 引入短 TTL + 刷新令牌 / Redis 撤销列表后收敛（见 README 待办）。
 */
@Service
public class JwtTokenService {

    private static final Logger log = LoggerFactory.getLogger(JwtTokenService.class);

    /** HS256 密钥最小字节数（256 位）。 */
    private static final int MIN_SECRET_BYTES = 32;

    private final JwtProperties props;
    private final JwtEncoder encoder;
    private final JwtDecoder decoder;

    public JwtTokenService(JwtProperties props) {
        this.props = props;

        String secret = props.secret();
        byte[] secretBytes = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "JWT 密钥未配置或长度不足 " + MIN_SECRET_BYTES + " 字节，拒绝启动（请设置环境变量 JWT_SECRET）");
        }
        if (SecurityDefaults.DEV_JWT_SECRET.equals(secret)) {
            log.warn("正在使用本地开发默认 JWT 密钥，生产环境必须通过环境变量 JWT_SECRET 覆盖真实密钥");
        }

        SecretKey key = new SecretKeySpec(secretBytes, "HmacSHA256");
        this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));

        NimbusJwtDecoder nimbus = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        // 固定校验 iss 与 exp（createDefault 已包含时间类声明校验）
        nimbus.setJwtValidator(JwtValidators.createDefaultWithIssuer(props.issuer()));
        this.decoder = nimbus;
    }

    /** 为账号签发访问令牌（roles 为签发时刻的角色快照）。 */
    public IssuedToken issue(UserAccount user, List<String> roles) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant expiresAt = now.plus(props.ttl());
        String tokenId = UUID.randomUUID().toString();

        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(props.issuer())
                .subject(user.id().toString())
                .id(tokenId)
                .issuedAt(now)
                .expiresAt(expiresAt)
                .claim("username", user.username())
                .claim("name", user.displayName())
                .claim("roles", roles);
        if (user.orgCode() != null && !user.orgCode().isBlank()) {
            claims.claim("org", user.orgCode());
        }

        Jwt jwt = encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims.build()));
        long expiresIn = Math.max(1, ChronoUnit.SECONDS.between(now, expiresAt));
        return new IssuedToken(jwt.getTokenValue(), now, expiresAt, expiresIn, tokenId);
    }

    /**
     * 校验令牌并提取声明。
     *
     * @return 校验通过返回声明；签名错误、结构非法、已过期或 iss 不匹配返回 {@link Optional#empty()}
     */
    public Optional<TokenClaims> verify(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            Jwt jwt = decoder.decode(token);
            List<String> roles = jwt.getClaimAsStringList("roles");
            return Optional.of(new TokenClaims(
                    jwt.getSubject(),
                    jwt.getClaimAsString("username"),
                    jwt.getClaimAsString("name"),
                    roles == null ? List.of() : roles,
                    jwt.getClaimAsString("org"),
                    jwt.getIssuedAt(),
                    jwt.getExpiresAt(),
                    jwt.getId()));
        } catch (JwtException e) {
            log.debug("jwt.verify.rejected reason={}", e.getMessage());
            return Optional.empty();
        }
    }

    /** 签发结果。 */
    public record IssuedToken(String token, Instant issuedAt, Instant expiresAt,
                              long expiresInSeconds, String tokenId) {
    }

    /** 校验结果（令牌声明快照）。 */
    public record TokenClaims(String userId, String username, String displayName, List<String> roles,
                              String orgCode, Instant issuedAt, Instant expiresAt, String tokenId) {
    }
}
