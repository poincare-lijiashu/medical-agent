package com.medassist.tech.web;

import com.medassist.tech.domain.TechModel.Device;
import com.medassist.tech.domain.TechModel.DeviceQc;
import com.medassist.tech.domain.TechModel.ReportTemplate;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 设备质控与结构化报告模板契约（G35）。
 */
public final class DeviceDtos {

    private DeviceDtos() {
    }

    public record CreateDeviceReq(
            @NotBlank @Size(max = 128) String name,
            @NotBlank @Pattern(regexp = "CT|MR|US|DR|CR|LIS|ECG|OTHER",
                    message = "设备类型只能是 CT/MR/US/DR/CR/LIS/ECG/OTHER") String modality,
            UUID deptId,
            @Size(max = 128) String location,
            @Size(max = 128) String manufacturer,
            @Size(max = 128) String model,
            @Size(max = 128) String serialNo,
            LocalDate installedAt) {
    }

    /** 设备状态变更（CAS：必须带当前 version）。 */
    public record UpdateDeviceStatusReq(
            @NotBlank @Pattern(regexp = "ACTIVE|MAINTENANCE|RETIRED",
                    message = "设备状态只能是 ACTIVE/MAINTENANCE/RETIRED") String status,
            long version) {
    }

    /** 质控登记：result=FAIL 时 note 建议填写原因，但只做提示不强制。 */
    public record RecordQcReq(
            @NotBlank @Pattern(regexp = "DAILY|WEEKLY|MONTHLY|CALIBRATION",
                    message = "质控类型只能是 DAILY/WEEKLY/MONTHLY/CALIBRATION") String qcType,
            @NotBlank @Pattern(regexp = "PASS|FAIL", message = "质控结论只能是 PASS/FAIL")
            String result,
            @NotBlank @Size(max = 128) String itemName,
            @Size(max = 64) String expectedValue,
            @Size(max = 64) String actualValue,
            @Size(max = 32) String unit,
            Instant performedAt,
            @Size(max = 256) String note) {
    }

    /** 设备详情：最近一次质控结果 + 质控历史。 */
    public record DeviceView(Device device, String latestQcResult, List<DeviceQc> qcHistory) {
    }

    public record DeviceSummary(int total, int active, int maintenance, int retired,
                                int qcFail30d) {
    }

    public record FieldSpec(
            @NotBlank @Size(max = 64) String key,
            @NotBlank @Size(max = 128) String label,
            @NotBlank @Pattern(regexp = "TEXT|NUMBER|SELECT",
                    message = "字段类型只能是 TEXT/NUMBER/SELECT") String type,
            boolean required,
            List<@Size(max = 64) String> options,
            @Size(max = 32) String unit) {
    }

    public record CreateReportTemplateReq(
            @NotBlank @Size(max = 64) String code,
            @NotBlank @Size(max = 128) String name,
            @NotBlank @Pattern(regexp = "CT|MR|US|DR|CR|LIS|ECG|OTHER",
                    message = "设备类型只能是 CT/MR/US/DR/CR/LIS/ECG/OTHER") String modality,
            @NotEmpty @Size(max = 50) List<@Valid FieldSpec> fields) {
    }

    public record TemplateView(ReportTemplate template) {
    }
}
