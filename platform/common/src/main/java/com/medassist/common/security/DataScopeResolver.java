package com.medassist.common.security;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;

import java.util.Arrays;
import java.util.LinkedHashSet;
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
 *   <li>角色为单科室类，但 {@code X-Org} 缺失、为空或含非法 UUID → 拒绝；</li>
 *   <li>角色为空（旧令牌）→ 拒绝，并提示重新登录；</li>
 * </ul>
 * 宁可让配置缺陷暴露成一次 403，也不能让它退化成"全院可见"。
 */
public final class DataScopeResolver {

    private static final String SEPARATOR = ",";

    private DataScopeResolver() {
    }

    /**
     * 解析数据范围（兼容旧调用：无院区头时视为不限制院区）。
     */
    public static DataScope resolve(String actorHeader, String rolesHeader,
                                    String permissionsHeader, String orgHeader) {
        return resolve(actorHeader, rolesHeader, permissionsHeader, orgHeader, null);
    }

    /**
     * 解析数据范围。
     *
     * @param actorHeader        {@code X-Actor}
     * @param rolesHeader        {@code X-Roles}（英文逗号分隔）
     * @param permissionsHeader  {@code X-Permissions}（英文逗号分隔）
     * @param orgHeader          {@code X-Org}（科室 UUID，多科室执业时英文逗号分隔）
     * @param campusHeader       {@code X-Campus}（院区 UUID，多院区执业时英文逗号分隔）
     */
    public static DataScope resolve(String actorHeader, String rolesHeader,
                                    String permissionsHeader, String orgHeader,
                                    String campusHeader) {
        Set<String> roles = splitToUpperSet(rolesHeader);
        if (roles.isEmpty()) {
            throw new BusinessException(ErrorCode.FORBIDDEN,
                    "当前登录凭证缺少角色信息，请重新登录后再试");
        }
        boolean phiReadable = Permissions.has(permissionsHeader, DataScope.PHI_READ);
        boolean allDepartments = roles.stream().anyMatch(DataScope::isAllDepartmentRole);

        Set<UUID> campuses = parseIds(campusHeader, "院区");
        // 没有院区头（旧令牌/直连调用）时不限制院区，保持既有行为；
        // 网关在新令牌上始终下发 X-Campus，生产路径自然启用隔离。
        //
        // G47：全院科室角色不再自动等于"跨院区"。药师/麻醉/手术室/质控可以看所有科室，
        // 但只能在本人绑定院区内可见——否则东院区处方会被主院区药师看到。
        boolean allCampuses = campusHeader == null || campusHeader.isBlank();

        if (allDepartments) {
            return DataScope.of(actorHeader, Set.of(), true, campuses, allCampuses, phiReadable);
        }
        return DataScope.of(actorHeader, parseIds(orgHeader, "科室"), false,
                campuses, allCampuses, phiReadable);
    }

    /**
     * 科室/院区头解析（支持多值）。
     *
     * <p>只接受标准 UUID：科室编码是历史遗留（如 {@code D013}），
     * 拿编码去比 UUID 列永远匹配不上，会静默返回空列表——比报错更难排查。
     *
     * <p>列表里任意一个值非法就整体拒绝：部分放行等于猜测调用者意图，
     * 而"猜错"的代价是看到不该看的患者。
     */
    private static Set<UUID> parseIds(String header, String label) {
        if (header == null || header.isBlank()) {
            return Set.of();
        }
        Set<UUID> ids = new LinkedHashSet<>();
        for (String raw : header.split(SEPARATOR)) {
            String value = raw.trim();
            if (value.isEmpty()) {
                continue;
            }
            try {
                ids.add(UUID.fromString(value));
            } catch (IllegalArgumentException e) {
                throw new BusinessException(ErrorCode.FORBIDDEN,
                        label + "标识格式不合法，无法确定数据范围，请联系管理员");
            }
        }
        if (ids.isEmpty()) {
            throw new BusinessException(ErrorCode.FORBIDDEN,
                    "当前账号未绑定" + label + "，无法确定数据范围，请联系管理员配置后重新登录");
        }
        return Set.copyOf(ids);
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
