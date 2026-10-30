package com.medassist.auth.domain;

/** 角色。 */
public record Role(
        String code,
        String name,
        String description,
        boolean builtin
) {
}
