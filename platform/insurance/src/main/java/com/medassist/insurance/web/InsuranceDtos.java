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

    /** 结算清单视图：汇总 + 明细行。 */
    public record BillView(SettlementBill bill, List<BillLine> lines) {
    }

    /** 导入结果。 */
    public record ImportResult(int imported, List<String> codes) {
    }
}
