package com.medassist.common.security;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;

import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 从网关注入的身份头解析 {@link DataScope}。
 *
 * <p>输入只允许来自网关重写后的请求头：客户端同名头已在网关被剥离，
 * 下游不再自行解析 Cookie / X-Forwarded-* / 自定义身份头。
 *
 * <p>失败方向统一为拒绝（403）：
 * <ul>
 *   <li>角色为单科室类，但 {@code X-Org} 缺失或不合法 → 拒绝；</li>
 *   <li>角色为空（旧令牌）→ 拒绝，并提示重新登录；</li>
 * </ul>
 * 宁可让配置缺陷暴露成一次 403，也不能让它退化成"全院可见"。
 */
public final class DataScopeResolver {

    private static final String SEPARATOR = ",";

    private DataScopeResolver() {
    }

    /**
     * 解析数据范围。
     *
     * @param actorHeader        {@code X-Actor}
     * @param rolesHeader        {@code X-Roles}（英文逗号分隔）
     * @param permissionsHeader  {@code X-Permissions}（英文逗号分隔）
     * @param orgHeader          {@code X-Org}（科室 UUID；网关未绑定时不下发）
     */
    public static DataScope resolve(String actorHeader, String rolesHeader,
                                    String permissionsHeader, String orgHeader) {
        Set<String> roles = splitToUpperSet(rolesHeader);
        if (roles.isEmpty()) {
            throw new BusinessException(ErrorCode.FORBIDDEN,
                    "当前登录凭证缺少角色信息，请重新登录后再试");
        }
        boolean phiReadable = Permissions.has(permissionsHeader, DataScope.PHI_READ);
        boolean allDepartments = roles.stream().anyMatch(DataScope::isAllDepartmentRole);

        if (allDepartments) {
            return DataScope.all(actorHeader, phiReadable);
        }
        UUID deptId = parseDepartment(orgHeader);
        return DataScope.ofDepartment(actorHeader, deptId, phiReadable);
    }

    /**
     * 科室头解析。
     *
     * <p>只接受标准 UUID：科室编码是历史遗留（如 {@code D013}），
     * 拿编码去比 UUID 列永远匹配不上，会静默返回空列表——比报错更难排查。
     */
    private static UUID parseDepartment(String orgHeader) {
        if (orgHeader == null || orgHeader.isBlank()) {
            throw new BusinessException(ErrorCode.FORBIDDEN,
                    "当前账号未绑定科室，无法确定数据范围，请联系管理员配置后重新登录");
        }
        try {
            return UUID.fromString(orgHeader.trim());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.FORBIDDEN,
                    "科室标识格式不合法，无法确定数据范围，请联系管理员");
        }
    }

    private static Set<String> splitToUpperSet(String header) {
        if (header == null || header.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(header.split(SEPARATOR))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(String::toUpperCase)
                .collect(Collectors.toUnmodifiableSet());
    }
}
