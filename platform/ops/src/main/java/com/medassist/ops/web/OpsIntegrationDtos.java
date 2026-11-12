package com.medassist.ops.web;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

/**
 * 告警接入 DTO（§276）。
 *
 * <p>入站 payload 只做"结构性"校验：Alertmanager 的通知字段随版本演进，
 * 未知字段直接忽略；业务校验（状态、fingerprint、服务映射）在服务层完成。
 */
public final class OpsIntegrationDtos {

    private OpsIntegrationDtos() {
    }

    public record AlertSourceReq(
            @NotBlank @Size(max = 64)
            @Pattern(regexp = "[a-z0-9][a-z0-9-]{1,63}",
                    message = "编码只能是小写字母、数字与短横线")
            String code,
            @NotBlank @Size(max = 128) String name,
            @Pattern(regexp = "ALERTMANAGER|GENERIC") String kind,
            @Size(max = 64) String defaultService,
            Map<String, String> severityMap,
            Boolean autoResolve,
            @Min(0) @Max(1440) Integer repeatCommentMinutes) {
    }

    public record AlertItem(
            String status,
            Map<String, String> labels,
            Map<String, String> annotations,
            String startsAt,
            String endsAt,
            String generatorURL,
            String fingerprint) {
    }

    public record AlertmanagerPayload(
            String version,
            String status,
            String groupKey,
            String receiver,
            Map<String, String> commonLabels,
            Map<String, String> commonAnnotations,
            List<AlertItem> alerts) {
    }
}
