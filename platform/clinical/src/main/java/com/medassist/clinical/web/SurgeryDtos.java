package com.medassist.clinical.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/** 手术麻醉域请求体（第一批：计划 / 开始 / 完成 / 取消）。 */
public final class SurgeryDtos {

    private SurgeryDtos() {
    }

    /**
     * 计划手术。
     *
     * <p>只接受字典内的编码：手术名称取字典快照，避免"同一手术十个名字"。
     */
    public record PlanSurgeryReq(
            @NotBlank @Size(max = 32) String operationCode,
            Instant plannedAt,
            @Size(max = 64) String surgeon,
            @Size(max = 64) String assistant,
            @Pattern(regexp = "GENERAL|REGIONAL|LOCAL|SEDATION|NONE", message = "麻醉方式不合法")
            String anesthesiaType,
            @Pattern(regexp = "I|II|III|IV|V|VI", message = "ASA 分级不合法") String asaGrade,
            @Pattern(regexp = "I|II|III|IV", message = "切口等级不合法") String incisionLevel) {
    }

    /** 状态迁移必须带版本号（CAS）。 */
    public record SurgeryVersionReq(@NotNull Long version) {
    }

    /** 取消手术：必须写原因。 */
    public record CancelSurgeryReq(
            @NotNull Long version,
            @NotBlank @Size(max = 256) String reason) {
    }
}
