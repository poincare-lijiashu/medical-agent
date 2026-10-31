package com.medassist.common.security;

import java.util.List;

/**
 * 身份头契约（网关 → 下游微服务）。
 *
 * <p>信任边界（计划书 D2.2 接入层）：下游服务<b>只信任网关注入</b>的身份头。
 * 客户端同名请求头必须在网关被剥离后由网关按 JWT 声明重新写入，
 * 否则任意调用方可伪造 {@code X-Actor} 冒充他人（P0-3 遗留风险）。
 *
 * <p>部署约束：auth / empi 等业务服务不得对公网暴露监听地址，
 * 只允许网关与集群内探针访问（K8s NetworkPolicy / 主机防火墙）。
 */
public final class AuthHeaders {

    /** 操作者标识（登录名，用于审计落库）。 */
    public static final String ACTOR = "X-Actor";

    /** 操作者账号主键（UUID）。 */
    public static final String USER_ID = "X-User-Id";

    /** 角色码列表，英文逗号分隔（如 {@code DOCTOR,NURSE}）。 */
    public static final String ROLES = "X-Roles";

    /**
     * 权限点列表（英文逗号分隔），由令牌中的 {@code permissions} 声明注入。
     *
     * <p>与角色不同：业务接口授权只看权限点，不看角色名，新增角色无需改业务代码。
     * 权限为**签发时刻快照**，角色调整后需重新登录才生效（与角色 claim 同一边界）。
     */
    public static final String PERMISSIONS = "X-Permissions";

    /** 当前令牌 ID（JWT {@code jti}），用于登出撤销与审计串联。 */
    public static final String TOKEN_ID = "X-Token-Id";

    /** 所属科室编码（未绑定科室时网关不下发该头）。 */
    public static final String ORG = "X-Org";

    /**
     * 可信客户端 IP（网关按 {@code trusted-proxies} 规则解析后重写，客户端提交值一律丢弃）。
     *
     * <p>下游（auth）只读该头，不再自行解析 {@code X-Forwarded-For}——
     * SCG 对 XFF 是**追加**语义，客户端自带首段即可自选来源 IP（M3）。
     */
    public static final String CLIENT_IP = "X-Client-Ip";

    /** 链路追踪 ID（网关生成，用于日志与审计串联）。 */
    public static final String TRACE_ID = "X-Trace-Id";

    /**
     * 客户端可能伪造、网关必须一律剥离的请求头。
     *
     * <p>包含 MF-07 的签名头：客户端自带 {@code X-Internal-Sig} 等不会有任何作用，
     * 网关在入口先全部删除，再由签名过滤器按最终身份头重新签发。
     */
    public static final List<String> CLIENT_SUPPLIED = List.of(
            ACTOR, USER_ID, ROLES, PERMISSIONS, TOKEN_ID, ORG, CLIENT_IP, TRACE_ID,
            InternalSignature.HEADER_SIG, InternalSignature.HEADER_TS,
            InternalSignature.HEADER_NONCE, InternalSignature.HEADER_KEY_ID,
            InternalSignature.HEADER_SIG_VER);

    private AuthHeaders() {
    }
}
