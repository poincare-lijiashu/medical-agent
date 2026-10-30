package com.medassist.common.security;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;

import java.util.Arrays;

/**
 * 权限点校验（P1 授权矩阵的最小实现）。
 *
 * <p>授权模型：网关从 JWT 的 {@code permissions} 声明注入 {@link AuthHeaders#PERMISSIONS}，
 * 业务接口只声明"需要哪个权限点"，不关心角色名。新增角色只需在 auth 域配置
 * role_permission，业务代码零改动。
 *
 * <p>为什么不在网关做接口级鉴权：网关只做认证与粗粒度边界（受保护前缀），
 * 细粒度授权属于业务语义（谁可以合并患者、谁可以看证件号），必须与业务代码同仓演进，
 * 否则每次授权调整都要改网关并重启流量入口。
 *
 * <p>边界：权限是**签发时刻快照**。撤销角色后，旧令牌在其 TTL 内仍携带旧权限
 * （P1 通过缩短 TTL + 刷新令牌 + 登出撤销收敛；即时逐权限撤销需要令牌内省，见 README）。
 */
public final class Permissions {

    private static final String SEPARATOR = ",";

    private Permissions() {
    }

    /** 权限头中是否包含指定权限点（大小写敏感，与 auth 域权限码一致）。 */
    public static boolean has(String permissionsHeader, String permission) {
        if (permissionsHeader == null || permission == null || permissionsHeader.isBlank()) {
            return false;
        }
        return Arrays.stream(permissionsHeader.split(SEPARATOR))
                .map(String::trim)
                .anyMatch(permission::equals);
    }

    /**
     * 要求具备指定权限点，否则抛 {@link ErrorCode#FORBIDDEN}。
     *
     * <p>权限头为空通常是旧版本令牌（签发时尚未包含 permissions 声明），
     * 提示重新登录即可获得新令牌；不抛 401，避免把"已认证但无权限"误报成"未登录"。
     */
    public static void require(String permissionsHeader, String permission) {
        if (permissionsHeader == null || permissionsHeader.isBlank()) {
            throw new BusinessException(ErrorCode.FORBIDDEN,
                    "当前登录凭证缺少权限信息，请重新登录后再试");
        }
        if (!has(permissionsHeader, permission)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权限执行该操作：" + permission);
        }
    }
}
