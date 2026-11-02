package com.medassist.common.security;

import org.springframework.core.env.Environment;

import java.util.Locale;
import java.util.Set;

/**
 * 安全相关的默认值（仅用于本地开发兜底）与环境判定。
 *
 * <p>{@link #DEV_JWT_SECRET} 会出现在 application.yml 的 {@code ${JWT_SECRET:...}} 默认位，
 * 目的是让本地 {@code java -jar} 无需额外设置环境变量即可联调。两个服务启动时会检测该值：
 * <ul>
 *   <li>非生产环境：打印告警，允许启动；</li>
 *   <li>生产环境：<b>直接拒绝启动</b>——仓库内的公开默认密钥一旦进了预发布/生产，
 *       JWT 可被任意伪造，仅靠日志告警是无效防线。</li>
 * </ul>
 */
public final class SecurityDefaults {

    /** 本地开发默认 JWT 密钥（≥32 字节，满足 HS256 强度要求）。 */
    public static final String DEV_JWT_SECRET = "<dev-jwt-secret>";

    /** 令牌签发方标识，网关校验时校验 iss，防止跨系统令牌混用。 */
    public static final String DEFAULT_ISSUER = "medassist-auth";

    /** 显式环境声明（运维唯一权威开关，优先于 profile 探测）。 */
    public static final String ENV_VAR = "MEDASSIST_ENV";

    /** 显式声明为"非生产"的取值；其余任何取值一律按生产处理（含拼写错误）。 */
    private static final Set<String> NON_PRODUCTION_ENVS =
            Set.of("dev", "develop", "local", "test", "ci", "sandbox");

    /** 明确按生产处理的 profile（Helm Chart 的默认运行 profile 是 {@code k8s}）。 */
    private static final Set<String> PRODUCTION_PROFILES =
            Set.of("prod", "production", "staging", "k8s");

    private SecurityDefaults() {
    }

    public static boolean isProduction(Environment env) {
        return isProduction(env, System.getenv(ENV_VAR));
    }

    /**
     * 是否处于会承接真实流量的环境（缺省按生产处理）。
     *
     * <p>判定顺序（先命中先返回）：
     * <ol>
     *   <li><b>显式 {@code MEDASSIST_ENV}</b>：{@code dev/local/test/ci/sandbox} → 非生产；
     *       其它任何取值（prod/staging/k8s/拼写错误）→ 生产。运维不必再猜 profile 命名；</li>
     *   <li>运行在 Kubernetes 集群内（自动注入 {@code KUBERNETES_SERVICE_HOST}）→ 生产；
     *       不依赖 profile 命名，防止"忘开 profile 就绕过守卫"；</li>
     *   <li>profile 为 {@code prod}/{@code production}/{@code staging}/{@code k8s} → 生产；</li>
     *   <li><b>没有任何 active profile → 生产（缺省拒绝）</b>：裸机部署忘设 profile 时，
     *       早期实现会返回"非生产"并放行开发默认密钥/关闭的签名开关（P0-10 ③）；</li>
     *   <li>无法判定（env 为 null）→ 生产。</li>
     * </ol>
     *
     * @param declaredEnv 显式环境声明，生产代码传 {@code System.getenv("MEDASSIST_ENV")}；
     *                    单测可直接注入，避免依赖进程环境变量
     */
    public static boolean isProduction(Environment env, String declaredEnv) {
        if (declaredEnv != null && !declaredEnv.isBlank()) {
            return !NON_PRODUCTION_ENVS.contains(declaredEnv.trim().toLowerCase(Locale.ROOT));
        }
        if (System.getenv("KUBERNETES_SERVICE_HOST") != null) {
            return true;
        }
        if (env == null) {
            return true;
        }
        String[] profiles = env.getActiveProfiles();
        if (profiles.length == 0) {
            // 没有显式声明环境：保守按生产处理（本地开发必须显式 --spring.profiles.active=dev）
            return true;
        }
        for (String profile : profiles) {
            if (PRODUCTION_PROFILES.contains(profile.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
