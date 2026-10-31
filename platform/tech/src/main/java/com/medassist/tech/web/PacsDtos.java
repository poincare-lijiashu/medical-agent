package com.medassist.tech.web;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * 影像（PACS）集成契约。
 *
 * <p>请求体只接受"登记"与"状态推进"两类意图，不接收申请号/患者号等派生字段：
 * AccessionNumber 由服务端复用申请号，患者信息以申请单为准，避免 PACS 侧写错主体。
 */
public final class PacsDtos {

    private PacsDtos() {
    }

    /**
     * 登记影像检查（PACS 在患者到达/开始扫描时调用）。
     *
     * <p>同一申请重复登记同一 StudyInstanceUID 为幂等；不同 UID 视为冲突。
     */
    public record RegisterStudyReq(
            @NotNull UUID applicationId,
            @NotBlank
            @Pattern(regexp = "[0-9]+(\\.[0-9]+)+", message = "StudyInstanceUID 只能由数字与点组成")
            @Size(max = 64) String studyInstanceUid,
            @Size(max = 16) String modality,
            @Size(max = 64) String bodyPart,
            @Size(max = 512) String viewerUrl) {
    }

    /** 回传检查进度（MPPS / PACS 侧状态）。数量与查看链接缺省时保持原值。 */
    public record UpdateStudyStatusReq(
            @NotBlank
            @Pattern(regexp = "ARRIVED|IN_PROGRESS|COMPLETED|CANCELLED",
                    message = "状态只能是 ARRIVED/IN_PROGRESS/COMPLETED/CANCELLED")
            String status,
            @Min(0) @Max(100000) Integer seriesCount,
            @Min(0) @Max(1000000) Integer imageCount,
            @Size(max = 512) String viewerUrl) {
    }
}
