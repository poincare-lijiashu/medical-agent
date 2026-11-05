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

    /** {@code X-Campus} 的显式"全部院区"标记（仅网关对管理员/质控下发）。 */
    public static final String CAMPUS_ALL = "*";

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

        // G51：院区口径改为 fail-closed，与科室口径对齐。
        //
        // 旧实现"缺 X-Campus 即不限制院区"是 6 个业务域唯一的兜底路径：旧令牌（升级前
        // 签发，TTL 窗口内）、以及任何不经网关的服务直连（内网可达即带 X-Org/X-Roles）
        // 都会落进 allCampuses=true，一条请求同时穿透临床/药房/医保/医技/物资/病案。
        //
        // 现在三种输入：
        //   * 缺失/空白 → 仅主院区（DEFAULT_CAMPUS_ID），绝不放开；
        //   * "*"      → 显式全院区（网关对管理员/质控下发，见 AuthGlobalFilter）；
        //   * UUID 列表 → 按列表收敛，非法值整体拒绝。
        boolean allCampuses = CAMPUS_ALL.equals(campusHeader == null ? null : campusHeader.trim());
        Set<UUID> campuses;
        if (allCampuses) {
            campuses = Set.of();
        } else if (campusHeader == null || campusHeader.isBlank()) {
            campuses = Set.of(DataScope.DEFAULT_CAMPUS_ID);
        } else {
            campuses = parseIds(campusHeader, "院区");
        }

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
