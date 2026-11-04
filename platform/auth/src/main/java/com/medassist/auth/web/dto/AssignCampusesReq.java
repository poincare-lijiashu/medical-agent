package com.medassist.auth.web.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/** 分配账号可见院区（G45）。 */
public record AssignCampusesReq(
        @NotEmpty(message = "至少绑定一个院区")
        @Size(max = 20, message = "一个账号最多绑定 20 个院区")
        List<UUID> campusIds) {
}
