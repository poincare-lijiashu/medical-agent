package com.medassist.migration.domain;

import com.medassist.migration.domain.MigrationModel.MappingRule;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

/**
 * 迁移 API 请求体。
 *
 * <p>Bean Validation 只保证"形状"（目标/行列数/长度），跨表与跨字段规则由服务层判定：
 * 例如必填字段是否都有映射、模板列是否未知、目标是否依赖尚未迁移的数据。
 */
public final class MigrationRequests {

    private MigrationRequests() {
    }

    /** 上传并预检。CSV 与 rows 二选一；同时给出时以 csv 为准。 */
    public record PreviewReq(
            @NotBlank @Size(max = 64) String targetCode,
            @Size(max = 64) String sourceSystem,
            @Size(max = 255) String fileName,
            @Size(max = 64) String profileCode,
            List<Map<String, String>> rows,
            String csv) {
    }

    /** 新建映射档案。 */
    public record ProfileReq(
            @NotBlank @Size(min = 3, max = 64) String profileCode,
            @NotBlank @Size(max = 128) String name,
            @NotBlank @Size(max = 64) String sourceSystem,
            @NotBlank @Size(max = 64) String targetCode,
            @NotEmpty List<MappingRule> mappings) {
    }

    /** 回滚（可一次回滚多个批次，服务层自动按依赖逆序执行）。 */
    public record RollbackReq(
            @NotEmpty List<String> batchNos,
            @NotBlank @Size(min = 2, max = 500) String reason) {
    }
}
