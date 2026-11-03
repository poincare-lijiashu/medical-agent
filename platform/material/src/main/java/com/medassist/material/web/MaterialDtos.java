package com.medassist.material.web;

import com.medassist.material.domain.MaterialModel.Consumable;
import com.medassist.material.domain.MaterialModel.ConsumableIssue;
import com.medassist.material.domain.MaterialModel.ConsumableIssueItem;
import com.medassist.material.domain.MaterialModel.ConsumableTrace;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 物资耗材域出入参。
 *
 * <p>金额一律由服务端按耗材目录价/批次价计算，客户端不传金额；
 * 追溯只接受 {@code issueItemId}，耗材与批次由服务端从出库明细回填，避免"追溯记录与出库批次对不上"。
 */
public final class MaterialDtos {

    private MaterialDtos() {
    }

    // ---------------------------------------------------------------- 目录

    public record CreateConsumableReq(
            @NotBlank @Size(max = 64) String code,
            @NotBlank @Size(max = 128) String name,
            @NotBlank @Size(max = 128) String spec,
            @NotBlank @Size(max = 16) String unit,
            @NotBlank @Pattern(regexp = "GENERAL|HIGH_VALUE|IMPLANT", message = "类别只能是 GENERAL/HIGH_VALUE/IMPLANT")
            String category,
            @NotNull @DecimalMin(value = "0.00", message = "价格不能为负") BigDecimal price,
            @Size(max = 128) String manufacturer,
            @Size(max = 64) String registrationNo) {
    }

    public record UpdatePriceReq(
            @NotNull @DecimalMin(value = "0.00", message = "价格不能为负") BigDecimal price,
            @NotNull @Min(0) Long version) {
    }

    public record ToggleConsumableReq(
            @NotNull Boolean enabled,
            @NotNull @Min(0) Long version) {
    }

    /** 目录视图：{@code totalStock} 为全部批次合计，供窗口判断可出库量。 */
    public record ConsumableView(Consumable consumable, int totalStock) {
    }

    // ---------------------------------------------------------------- 库存

    /** 入库；{@code expireDate} 对高值/植入类必填。{@code unitPrice} 为空时取目录价。 */
    public record ReceiveBatchReq(
            @NotBlank @Size(max = 64) String batchNo,
            LocalDate expireDate,
            @Min(value = 1, message = "入库数量必须大于 0") int quantity,
            @Size(max = 128) String supplier,
            @DecimalMin(value = "0.00", message = "单价不能为负") BigDecimal unitPrice) {
    }

    /** 近效期/已过期批次预警。 */
    public record StockWarning(String consumableCode, String consumableName, String batchNo,
                               LocalDate expireDate, int quantity, boolean expired) {
    }

    // ---------------------------------------------------------------- 出库

    public record CreateIssueReq(
            @NotNull UUID deptId,
            @Size(max = 128) String deptName,
            @NotBlank @Pattern(regexp = "DEPT_STOCK|SURGERY|CLINIC", message = "用途只能是 DEPT_STOCK/SURGERY/CLINIC")
            String purpose,
            @NotBlank @Size(max = 64) String requestId,
            @Size(max = 256) String remark,
            @NotEmpty(message = "出库明细不能为空") @Size(max = 200) List<@Valid IssueLine> items) {
    }

    public record IssueLine(
            @NotNull UUID consumableId,
            @Min(value = 1, message = "出库数量必须大于 0") int quantity) {
    }

    public record IssueView(ConsumableIssue issue, List<ConsumableIssueItem> items) {
    }

    // ---------------------------------------------------------------- 追溯

    public record CreateTraceReq(
            @NotNull UUID issueItemId,
            @Min(value = 1, message = "使用数量必须大于 0") int quantity,
            @NotNull UUID patientId,
            @NotBlank @Size(max = 64) String patientNo,
            UUID visitId,
            UUID surgeryId,
            Instant usedAt,
            @Size(max = 256) String remark) {
    }

    public record TraceView(ConsumableTrace trace) {
    }

    /** 召回影响面：先看涉及多少患者/手术，再决定是否启动召回。 */
    public record RecallView(String batchNo, long traceCount, int totalQuantity,
                             long patientCount, long surgeryCount,
                             List<ConsumableTrace> traces) {
    }

    // ---------------------------------------------------------------- 汇总

    public record MaterialSummary(long consumableCount, int totalStock, BigDecimal stockValue,
                                  long nearExpiryBatches, long expiredBatches, long issuesToday,
                                  long tracesToday) {
    }
}
