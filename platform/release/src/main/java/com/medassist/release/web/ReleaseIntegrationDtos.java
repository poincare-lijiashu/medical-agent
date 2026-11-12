package com.medassist.release.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * CI/CD 接入 DTO（§276）。
 *
 * <p>入站事件字段保持宽松：GitHub/GitLab/Jenkins 的原始 payload 差异很大，
 * 由流水线脚本归一化成这里的结构；服务层再做业务校验。
 */
public final class ReleaseIntegrationDtos {

    private ReleaseIntegrationDtos() {
    }

    public record CiSourceReq(
            @NotBlank @Size(max = 64)
            @Pattern(regexp = "[a-z0-9][a-z0-9-]{1,63}",
                    message = "编码只能是小写字母、数字与短横线")
            String code,
            @NotBlank @Size(max = 128) String name,
            @Pattern(regexp = "GITHUB|GITLAB|JENKINS|GENERIC") String kind,
            List<@Size(max = 64) String> allowedComponents) {
    }

    public record CiEventReq(
            String kind,
            String componentCode,
            String version,
            UUID versionId,
            String environment,
            String action,
            String result,
            Map<String, Object> checklist,
            UUID rollbackOf,
            String reason,
            String startedAt,
            String finishedAt,
            String actor,
            String gitCommit,
            String imageDigest,
            String schemaVersion,
            Boolean breakingChanges,
            String changelog,
            String eventId,
            /** 目标 Cell（医院租户）；为空表示平台级部署。 */
            String tenantCode,
            /** 灰度批次标签（PILOT/CANARY_10/ALL），仅审计追溯。 */
            String batchLabel) {
    }
}
