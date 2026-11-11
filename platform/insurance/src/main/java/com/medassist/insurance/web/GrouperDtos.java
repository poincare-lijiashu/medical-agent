package com.medassist.insurance.web;

import com.medassist.insurance.domain.InsuranceModel.GrouperCodeCount;
import com.medassist.insurance.domain.InsuranceModel.GrouperEngineStat;
import com.medassist.insurance.domain.InsuranceModel.GrouperReasonCount;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * DRG/DIP 可配置分组引擎契约（§263）。
 *
 * <p>导入请求一次携带整包规则：规则包必须自洽（ADRG 的 MDC 存在、DRG 的 ADRG 存在），
 * 否则拒绝整个导入，不做"导入一半、跑起来才发现缺项"。
 */
public final class GrouperDtos {

    private GrouperDtos() {
    }

    public record MdcRuleReq(int priority, @NotBlank String mdcCode, @NotBlank String mdcName,
                             @NotBlank String diagFrom, @NotBlank String diagTo, Boolean enabled) {
    }

    public record AdrgRuleReq(int priority, @NotBlank String adrgCode, @NotBlank String adrgName,
                              @NotBlank String mdcCode, @NotBlank String matchType,
                              String diagPrefix, String procPrefix, Boolean enabled) {
    }

    public record DrgDefReq(@NotBlank String drgCode, @NotBlank String drgName,
                            @NotBlank String adrgCode, @NotBlank String severity,
                            @NotNull BigDecimal weight) {
    }

    public record CcRuleReq(@NotBlank String severity, @NotBlank String diagPrefix) {
    }

    public record CcExclusionReq(@NotBlank String primaryDiagPrefix,
                                 @NotBlank String secondaryDiagPrefix) {
    }

    public record DipCatalogReq(@NotBlank String dipCode, @NotBlank String dipName,
                                @NotBlank String diagCode, String procCode,
                                @NotBlank String type, @NotNull BigDecimal score) {
    }

    public record ImportRuleSetReq(@NotBlank String code, @NotBlank String version,
                                   @NotBlank String name, @NotBlank String type,
                                   @NotNull LocalDate effectiveFrom, @NotBlank String source,
                                   Boolean activate,
                                   List<MdcRuleReq> mdcRules, List<AdrgRuleReq> adrgRules,
                                   List<DrgDefReq> drgDefs, List<CcRuleReq> ccRules,
                                   List<CcExclusionReq> ccExclusions,
                                   List<DipCatalogReq> dipCatalog) {
    }

    public record ImportRuleSetResult(UUID id, String code, String version, String status,
                                      int mdcRules, int adrgRules, int drgDefs, int ccRules,
                                      int ccExclusions, int dipCatalog) {
    }

    /**
     * 预分组请求：诊断/手术缺省时回源（临床诊断、病案首页手术），显式传入则以请求为准。
     *
     * @param schemeCode 可选支付方案，配置了就同时算"支付标准 = 权重/分值 × 费率/点值"
     */
    public record PreviewReq(@NotNull UUID visitId, String type, String ruleSetCode,
                             String ruleSetVersion, String principalDiagnosis,
                             List<String> diagnoses, List<String> procedures, String schemeCode,
                             LocalDate serviceDate) {
    }

    /** 省局正式分组结果登记：用于与院内预分组做差异分析。 */
    public record OfficialReq(@NotNull UUID visitId, @NotBlank String type, String ruleSetCode,
                              String ruleSetVersion, String mdcCode, String adrgCode,
                              String drgCode, String dipCode, String severity, BigDecimal weight,
                              BigDecimal score, BigDecimal paymentStandard, String reason,
                              String principalDiagnosis, List<String> diagnoses,
                              List<String> procedures) {
    }

    public record ResultRef(String engine, String ruleSetCode, String ruleSetVersion,
                            String mdcCode, String adrgCode, String drgCode, String dipCode,
                            String severity, BigDecimal weight, BigDecimal score,
                            BigDecimal paymentStandard, String reason, Instant createdAt) {
    }

    /** 预分组 vs 正式分组差异：consistent=false 时逐项列出差异字段。 */
    public record VarianceView(UUID visitId, String type, ResultRef pre, ResultRef official,
                               boolean consistent, List<String> differences) {
    }

    /** 分组报表：引擎/类型统计 + 入组 Top 病组 + 未入组原因 Top。 */
    public record SummaryView(Instant from, Instant to, List<GrouperEngineStat> engines,
                              List<GrouperCodeCount> topGroups,
                              List<GrouperReasonCount> topReasons) {
    }
}
