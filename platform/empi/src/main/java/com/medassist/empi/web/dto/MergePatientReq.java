package com.medassist.empi.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/** 患者档案合并请求：把 fromId 的档案并入 toId（保留原始记录与追溯链）。 */
public record MergePatientReq(
        @NotNull(message = "源档案 ID 不能为空")
        UUID fromId,

        @NotNull(message = "目标档案 ID 不能为空")
        UUID toId,

        @NotBlank(message = "合并原因不能为空")
        @Size(max = 256, message = "合并原因长度不能超过 256")
        String reason
) {
}
