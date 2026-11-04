package com.medassist.clinical.web;

import com.medassist.clinical.domain.ClinicalModel.DischargeMedication;
import com.medassist.clinical.domain.ClinicalModel.DischargeMedicationItem;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * 出院带药契约（G30）。
 *
 * <p>药品编码由药房字典校验（临床只存快照）；提交后由药房生成「发药时计价」处方，
 * 发药结果经同步接口/定时任务回写，临床不重复计费。
 */
public final class DischargeMedicationDtos {

    private DischargeMedicationDtos() {
    }

    /** 住院号与就诊 ID 二选一；明细同一药品编码只能出现一次。 */
    public record CreateDischargeMedicationReq(
            UUID visitId,
            @Size(max = 32) String visitNo,
            @Size(max = 200) String note,
            @NotEmpty @Size(max = 50) List<@Valid MedicationLine> items) {
    }

    public record MedicationLine(
            @NotBlank @Size(max = 64) String drugCode,
            @NotBlank @Size(max = 100) String drugName,
            @Size(max = 100) String spec,
            @Size(max = 16) String unit,
            @NotNull @Min(1) @Max(100000) Integer quantity,
            @Size(max = 64) String dosage,
            @Size(max = 32) String frequency,
            @Min(1) @Max(365) Integer days) {
    }

    public record DischargeMedicationView(DischargeMedication medication,
                                          List<DischargeMedicationItem> items) {
    }

    public record CancelDischargeMedicationReq(@NotBlank @Size(max = 200) String reason) {
    }
}
