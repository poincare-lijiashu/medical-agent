package com.medassist.auth.domain;

/** 组织架构节点（医院 / 科室 / 病区）。 */
public record Org(
        String code,
        String name,
        String parentCode,
        String type,
        String status,
        int sortNo
) {
}
