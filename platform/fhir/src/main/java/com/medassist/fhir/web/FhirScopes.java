package com.medassist.fhir.web;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.security.DataScope;
import com.medassist.common.security.DataScopeResolver;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * FHIR 权限点与院区收敛口径。
 *
 * <p>FHIR 是<b>院级</b>对外接口，不按科室切分（区域平台/集成引擎需要跨科室取数），
 * 但仍按院区收敛：多院区医院不能让东院区的集成账号读到西院区数据。
 *
 * <p>授权矩阵（见 auth V82）：
 * <ul>
 *   <li>{@code fhir:read}：读取全部 FHIR 资源；</li>
 *   <li>{@code fhir:read_phi}：返回患者姓名/出生日期/患者号明文与跨系统标识；</li>
 *   <li>院区来自网关注入的 {@code X-Campus}，缺失按主院区收敛，非法值整体拒绝。</li>
 * </ul>
 */
public final class FhirScopes {

    private FhirScopes() {
    }

    /**
     * 解析可见院区；空集合表示"不限制"（仅 {@code X-Campus: *}）。
     *
     * <p>与 {@link DataScopeResolver} 同口径：缺失 → 主院区，非法 → 拒绝，
     * 绝不让"没配好"退化成"全院可见"。
     */
    public static Set<UUID> campusFilter(String campusHeader) {
        if (campusHeader == null || campusHeader.isBlank()) {
            return Set.of(DataScope.DEFAULT_CAMPUS_ID);
        }
        String value = campusHeader.trim();
        if (DataScopeResolver.CAMPUS_ALL.equals(value)) {
            return Set.of();
        }
        Set<UUID> ids = new LinkedHashSet<>();
        for (String raw : value.split(",")) {
            String item = raw.trim();
            if (item.isEmpty()) {
                continue;
            }
            try {
                ids.add(UUID.fromString(item));
            } catch (IllegalArgumentException e) {
                throw new BusinessException(ErrorCode.FORBIDDEN,
                        "院区标识格式不合法，无法确定数据范围，请联系管理员");
            }
        }
        if (ids.isEmpty()) {
            throw new BusinessException(ErrorCode.FORBIDDEN,
                    "当前账号未绑定院区，无法确定数据范围，请联系管理员配置后重新登录");
        }
        return Set.copyOf(ids);
    }
}
