package com.medassist.common.security;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 院区集合 → SQL 过滤串。
 *
 * <p>空集合表示"跨院区角色"（{@link DataScope#campusFilter()} 对全院区角色返回空集），
 * 调用方据此不加过滤；因此这里返回空串而不是 {@code NULL}，避免 SQL 里出现
 * {@code IN ()} 语法错误或三元判断分叉。
 */
public final class CampusFilters {

    private CampusFilters() {
    }

    public static String csv(Set<UUID> campusIds) {
        if (campusIds == null || campusIds.isEmpty()) {
            return "";
        }
        return campusIds.stream().map(UUID::toString).collect(Collectors.joining(","));
    }
}
