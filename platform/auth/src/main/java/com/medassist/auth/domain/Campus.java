package com.medassist.auth.domain;

import java.util.UUID;

/**
 * 院区字典（G46）。
 *
 * <p>院区是数据归属的第一层边界：账号通过 {@code user_campus} 绑定可访问院区，
 * 登录令牌下发 {@code campuses} 声明，业务域按 {@code campus_id} 收敛数据。
 * 停用只是不允许再分配/新建业务数据，不追溯收回历史数据归属。
 */
public record Campus(
        UUID id,
        String code,
        String name,
        String status,
        int sortNo,
        long version
) {

    public static final String ACTIVE = "ACTIVE";
    public static final String DISABLED = "DISABLED";

    public boolean active() {
        return ACTIVE.equals(status);
    }
}
