package com.medassist.masterdata.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 主数据 API 请求体。
 *
 * <p>校验分两层：Bean Validation 负责"字段形状"（长度/必填/枚举），
 * 服务层负责"跨字段与跨表"的规则（日期先后、条目是否存在、主映射冲突、审批职责分离）。
 * 只靠注解无法表达这些规则，只靠服务层又会让错误信息变成通用 400。
 */
public final class MasterDataDtos {

    private MasterDataDtos() {
    }

    /** 新建条目（提交变更申请）；本地编码由路径提供。 */
    public record ItemCreateReq(
            @NotBlank @Size(max = 200) String name,
            @Size(max = 64) String alias,
            @Size(max = 64) String category,
            @Size(max = 200) String spec,
            @Size(max = 32) String unit,
            @Pattern(regexp = "ACTIVE|INACTIVE", message = "状态只能是 ACTIVE 或 INACTIVE")
            String status,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            Map<String, Object> attributes,
            @NotBlank @Size(min = 2, max = 500, message = "变更原因至少 2 个字符") String reason) {
    }

    /** 启停条目。 */
    public record ItemStatusReq(
            @NotBlank
            @Pattern(regexp = "ACTIVE|INACTIVE", message = "状态只能是 ACTIVE 或 INACTIVE")
            String status,
            @NotBlank @Size(min = 2, max = 500) String reason) {
    }

    /** 新增/修订映射。 */
    public record MappingReq(
            @NotBlank @Size(max = 64) String systemCode,
            @NotBlank @Size(max = 128) String externalCode,
            @Size(max = 200) String externalName,
            @Pattern(regexp = "EXACT|EQUIVALENT|GROUP", message = "映射类型只能是 EXACT / EQUIVALENT / GROUP")
            String mapType,
            Boolean primary,
            @Pattern(regexp = "ACTIVE|INACTIVE") String status,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            @Size(max = 500) String remark,
            @NotBlank @Size(min = 2, max = 500) String reason) {
    }

    /** 停用映射。 */
    public record MappingDeleteReq(
            @NotBlank @Size(max = 64) String systemCode,
            @NotBlank @Size(max = 128) String externalCode,
            @NotBlank @Size(min = 2, max = 500) String reason) {
    }

    /** 审批意见（可空；驳回时必填由服务层校验）。 */
    public record ApproveReq(@Size(max = 500) String comment) {
    }

    /** 驳回原因。 */
    public record RejectReq(
            @NotBlank @Size(min = 2, max = 500, message = "驳回原因至少 2 个字符")
            String reviewComment) {
    }

    /** JSON 导入中的一条主数据。 */
    public record ImportMappingReq(
            @NotBlank @Size(max = 64) String systemCode,
            @NotBlank @Size(max = 128) String externalCode,
            @Size(max = 200) String externalName,
            @Pattern(regexp = "EXACT|EQUIVALENT|GROUP") String mapType,
            Boolean primary,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            @Size(max = 500) String remark) {
    }

    /** JSON 导入中的一条主数据（可带映射）。 */
    public record ImportItemReq(
            @NotBlank @Size(max = 64) String localCode,
            @NotBlank @Size(max = 200) String name,
            @Size(max = 64) String alias,
            @Size(max = 64) String category,
            @Size(max = 200) String spec,
            @Size(max = 32) String unit,
            @Pattern(regexp = "ACTIVE|INACTIVE") String status,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            Map<String, Object> attributes,
            @Valid List<ImportMappingReq> mappings) {
    }

    /** JSON 批量导入请求（预检与应用共用同一报文）。 */
    public record JsonImportReq(
            @NotBlank @Size(max = 64) String domainCode,
            @Size(max = 200) String fileName,
            @NotEmpty @Valid List<ImportItemReq> items) {
    }
}
