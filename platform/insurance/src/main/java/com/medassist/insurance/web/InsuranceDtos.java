package com.medassist.insurance.web;

import com.medassist.insurance.domain.InsuranceModel.BillLine;
import com.medassist.insurance.domain.InsuranceModel.SettlementBill;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** 医保请求/响应契约。金额一律服务端计算，请求体不接受任何金额。 */
public final class InsuranceDtos {

    private InsuranceDtos() {
    }

    /** 新增/更新医保目录项目。 */
    public record ItemReq(
            @NotBlank @Size(max = 64) String insuranceCode,
            @NotBlank @Size(max = 128) String name,
            @NotBlank @Pattern(regexp = "DRUG|SERVICE|MATERIAL|DIAGNOSIS", message = "目录类别不合法")
            String category,
            @NotBlank @Pattern(regexp = "A|B|C", message = "支付类别不合法") String payCategory,
            @DecimalMin("0") @DecimalMax("1") BigDecimal selfPayRatio,
            @DecimalMin("0") BigDecimal priceLimit,
            Boolean enabled) {
    }

    /** 目录批量导入（按医保编码 UPSERT）。 */
    public record ImportItemsReq(@NotEmpty @Valid List<ItemReq> items) {
    }

    /** 启停用目录项目。 */
    public record ToggleReq(@NotNull Boolean enabled, @NotNull Long version) {
    }

    /** 院内收费项目映射。 */
    public record MappingReq(@NotBlank @Size(max = 64) String insuranceCode, Boolean enabled) {
    }

    /** 参保登记（按 patientId UPSERT）。 */
    public record MemberReq(
            @NotNull UUID patientId,
            @NotBlank @Pattern(regexp = "EMPLOYEE|RESIDENT|RETIRED|OTHER", message = "参保类型不合法")
            String insuranceType,
            @NotBlank @Size(max = 64) String region,
            @Size(max = 64) String personCategory,
            @DecimalMin("0") BigDecimal deductible,
            @DecimalMin("0") @DecimalMax("1") BigDecimal reimbursementRatio,
            @DecimalMin("0") BigDecimal cap,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            @Pattern(regexp = "ACTIVE|DISABLED", message = "参保状态不合法") String status) {
    }

    /** 生成/重算结算清单（只接受 visitId）。 */
    public record GenerateBillReq(@NotNull UUID visitId) {
    }

    /**
     * 结算清单视图：汇总 + 明细行 + 年度封顶口径。
     *
     * <p>{@code capUsed} 为本自然年同参保人已确认清单的统筹合计，{@code capRemaining} 为本次测算前的
     * 剩余额度（封顶未配置时为 null）；读历史清单不重算，两者均为 null。
     */
    public record BillView(SettlementBill bill, List<BillLine> lines,
                           BigDecimal capUsed, BigDecimal capRemaining) {
    }

    /** 作废（红冲）请求：必须写明原因，留痕到清单与审计。 */
    public record VoidReq(@NotBlank @Size(max = 512) String reason) {
    }

    /**
     * 按就诊对账结果：清单金额 vs 当前收费重算。
     *
     * <p>{@code matched=false} 表示清单与当前费用/待遇不一致，需要作废后重新生成；
     * {@code chargeTotal} 为空表示参保信息已不存在、无法重算。
     */
    public record ReconcileView(UUID visitId, String billNo, String billStatus, boolean matched,
                                BigDecimal billTotal, BigDecimal chargeTotal,
                                BigDecimal billInsurance, BigDecimal chargeInsurance,
                                BigDecimal billSelfPay, BigDecimal chargeSelfPay,
                                int billLineCount, int chargeLineCount) {
    }

    /** 导入结果。 */
    public record ImportResult(int imported, List<String> codes) {
    }
}
