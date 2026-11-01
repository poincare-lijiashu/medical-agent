package com.medassist.pharmacy.web;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 麻醉药品管理（处方权 / 专册 / 空安瓿回收）请求体。 */
public final class NarcoticDtos {

    private NarcoticDtos() {
    }

    /** 授予麻醉药品处方权（已存在则重新启用并刷新证书号）。 */
    public record GrantPrescriberReq(
            @NotBlank @Size(max = 64) String prescriber,
            @Size(max = 64) String licenseNo) {
    }

    /** 撤销处方权：必须写原因（版本号来自查询结果，防覆盖他人操作）。 */
    public record RevokePrescriberReq(
            @NotNull Long version,
            @NotBlank @Size(max = 256) String reason) {
    }

    /** 空安瓿回收登记：数量必须等于发药数量，接收人为药房经办人。 */
    public record AmpouleReturnReq(
            @Min(1) int quantity,
            @NotBlank @Size(max = 64) String receiver) {
    }
}
