package com.medassist.empi.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 跨系统标识登记请求（HIS/LIS/PACS/INSURANCE/REGIONAL）。 */
public record CrosswalkReq(
        @NotBlank(message = "系统编码不能为空")
        @Pattern(regexp = "HIS|LIS|PACS|INSURANCE|REGIONAL", message = "系统编码不合法")
        String systemCode,

        @NotBlank(message = "外部标识不能为空")
        @Size(max = 64, message = "外部标识长度不能超过 64")
        String externalId
) {
}
