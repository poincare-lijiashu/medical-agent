package com.medassist.emr.web;

import com.medassist.emr.domain.EmrModel.Amendment;
import com.medassist.emr.domain.EmrModel.MedicalRecord;
import com.medassist.emr.domain.EmrModel.QcResult;
import com.medassist.emr.domain.EmrModel.RecordSignature;
import com.medassist.emr.domain.EmrModel.Template;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 病历请求/响应契约。
 *
 * <p>请求体不接受状态、签名人、哈希——状态由服务端状态机推进，
 * 签名人与哈希由网关注入的身份与服务端计算决定（客户端无法伪造签名）。
 */
public final class EmrDtos {

    private EmrDtos() {
    }

    /** 新建模板。schemaJson 为字段定义数组（[{key,label,type,required,options}]）。 */
    public record CreateTemplateReq(
            @NotBlank @Size(max = 64) String templateCode,
            @NotBlank @Size(max = 128) String name,
            @NotBlank @Pattern(regexp = "ADMISSION_NOTE|FIRST_COURSE|PROGRESS_NOTE|DISCHARGE_SUMMARY"
                    + "|OPERATION_NOTE|OUTPATIENT_NOTE|CONSULT_NOTE", message = "文书类型不合法") String docType,
            @Pattern(regexp = "OUTPATIENT|INPATIENT", message = "适用范围不合法") String scope,
            @NotBlank String schemaJson) {
    }

    /** 启停用模板。 */
    public record ToggleTemplateReq(@NotNull Boolean enabled, @NotNull Long version) {
    }

    /**
     * 签名策略修改。
     *
     * @param requiredLevel 归档所需签名级别（1 书写医师 / 2 主治 / 3 主任）
     * @param version       已存在的策略必须携带当前版本号（首次创建不填）；用于防止并发静默降级
     */
    public record SignPolicyReq(
            @NotNull @Min(1) @Max(3) Integer requiredLevel,
            Long version) {
    }

    /** 新建病历草稿。 */
    public record CreateRecordReq(
            @NotNull UUID patientId,
            UUID visitId,
            @Size(max = 64) String visitNo,
            UUID deptId,
            @NotBlank @Size(max = 64) String templateCode,
            @Size(max = 128) String title,
            Map<String, String> content) {
    }

    /** 保存草稿内容（必须带版本号）。 */
    public record SaveContentReq(
            @NotNull Map<String, String> content,
            @NotNull Long version) {
    }

    /** 修订（签名后）。 */
    public record AmendReq(
            @NotNull Map<String, String> content,
            @NotBlank @Size(max = 256) String reason) {
    }

    /** 签名请求：level 缺省为书写医师；上级签名按 AUTHOR→ATTENDING→CHIEF 顺序。 */
    public record SignReq(
            @Pattern(regexp = "AUTHOR|ATTENDING|CHIEF", message = "签名级别不合法") String level) {
    }

    /** 病案修改申请（{@code resubmitOf} 仅在重新申请时指向被驳回/撤回的上一条）。 */
    public record ChangeRequestReq(
            @NotNull Map<String, String> content,
            @NotBlank @Size(max = 512) String reason,
            UUID resubmitOf) {
    }

    /** 修改申请审批意见（通过/驳回共用）。 */
    public record ReviewReq(@Size(max = 512) String comment) {
    }

    /** 病历详情：正文 + 签名 + 修订历史 + 当前版本质控结果。 */
    public record RecordView(MedicalRecord record, List<RecordSignature> signatures,
                             List<Amendment> amendments, List<QcResult> qcResults) {
    }

    /** 质控结果（提交前预览）。 */
    public record QcReport(boolean passed, int fatalCount, int majorCount, int minorCount,
                           List<QcResult> results) {
    }

    /**
     * 内容完整性校验结果。
     *
     * @param valid               正文当前哈希是否等于落库哈希
     * @param signaturesValid     当前版本各级签名保存的哈希是否与正文一致（签名行被篡改时为 false）
     * @param signatureMismatches 不一致的签名（形如 {@code CHIEF:chief01}），便于定位
     */
    public record HashVerify(boolean valid, String storedHash, String recomputedHash,
                             boolean signaturesValid, List<String> signatureMismatches) {
    }

    /** 模板视图。 */
    public record TemplateView(Template template) {
    }

    /** 提交结果（含质控明细，便于前端提示）。 */
    public record SubmitResult(RecordView record, QcReport qc) {
    }

    /** 分页/列表条目（不带正文，避免列表接口返回大量 JSON）。 */
    public record RecordSummary(UUID id, String recordNo, UUID patientId, String patientNo,
                                String docType, String title, String author, String status,
                                int revision, java.time.Instant createdAt) {
    }

    // ---------------------------------------------------------------- 病案首页

    /** 生成病案首页：只需就诊号，患者/诊断/费用由服务端从临床域取回。 */
    public record CreateHomepageReq(@NotNull UUID visitId) {
    }

    /**
     * 手术操作（首页录入；当前没有手术域）。
     */
    public record HomepageProcedureReq(
            @NotBlank @Size(max = 32) String code,
            @NotBlank @Size(max = 128) String name,
            java.time.LocalDate date,
            @Size(max = 64) String surgeon,
            @Size(max = 64) String anesthesia,
            @Size(max = 16) String incisionLevel) {
    }

    /**
     * 补录首页专有信息（PUT 语义）。
     *
     * <p>科室/病区/主治医师**不传则保持原值**（来自临床快照，防止漏传被清空）；
     * 其余表单字段不传即按清空或默认值处理（离院方式空、再住院计划 NONE、抢救 0 次）。
     * 诊断、费用、出院时间不在本契约内——它们只能由临床域修正后重新生成。
     *
     * @param version 必填，草稿乐观锁版本号
     */
    public record UpdateHomepageReq(
            @NotNull Long version,
            @Size(max = 64) String deptName,
            @Size(max = 64) String wardName,
            @Size(max = 32) String bedNo,
            @Size(max = 64) String attendingDoctor,
            @Pattern(regexp = "OUTPATIENT|EMERGENCY|TRANSFER", message = "入院途径不合法")
            String admissionWay,
            @Pattern(regexp = "BY_DOCTOR|TRANSFER|TRANSFER_HOME|NON_DOCTOR|DEATH|OTHER",
                    message = "离院方式不合法") String dischargeMode,
            @Pattern(regexp = "NONE|WITHIN_31_DAYS", message = "再住院计划不合法") String readmitPlan,
            @Pattern(regexp = "MARRIED|SINGLE|DIVORCED|WIDOWED|UNKNOWN", message = "婚姻状态不合法")
            String maritalStatus,
            @Size(max = 64) String occupation,
            @Size(max = 64) String nation,
            @Pattern(regexp = "A|B|O|AB|UNKNOWN", message = "血型不合法") String bloodType,
            @Pattern(regexp = "POSITIVE|NEGATIVE|UNKNOWN", message = "RH 血型不合法") String rhFactor,
            @Min(0) @Max(99) Integer rescueCount,
            @Min(0) @Max(99) Integer rescueSuccessCount,
            Boolean criticalCase,
            Boolean hospitalInfection,
            @Size(max = 64) String chiefPhysician,
            @Size(max = 64) String residentPhysician,
            @Size(max = 64) String coder,
            @Size(max = 20, message = "手术操作不得超过 20 条")
            List<@Valid HomepageProcedureReq> procedures,
            /** 死亡时间：离院方式=DEATH 时提交前必填（第六轮 MF-06）。 */
            Instant deathTime,
            @Size(max = 256) String deathCause) {
    }

    /**
     * 病案首页视图：实体 + 反序列化后的诊断/手术/费用，前端无需二次解析 JSON 字符串。
     */
    public record HomepageView(com.medassist.emr.domain.EmrModel.Homepage homepage,
                               List<Map<String, Object>> diagnoses,
                               List<Map<String, Object>> procedures,
                               Map<String, java.math.BigDecimal> feeSummary) {
    }

    // ---------------------------------------------------------------- HQMS 上报

    /** HQMS 回执：ACCEPTED / REJECTED（退回必须写原因）。 */
    public record HqmsReceiptReq(
            @NotBlank @Pattern(regexp = "ACCEPTED|REJECTED", message = "回执状态不合法") String status,
            @NotBlank @Size(max = 64) String receiptNo,
            @Size(max = 512) String message) {
    }

    /** 撤销未回执的上报批次（必须写原因）。 */
    public record CancelHqmsExportReq(
            @NotBlank @Size(max = 512) String reason) {
    }

    /**
     * 上报批次摘要（不含报文本身，避免列表接口返回大字段）。
     *
     * @param excludedAlreadyExported 期间内已上报、本次被排除的首页数
     * @param remainingPending        超过单批上限、本期间仍需再报的首页数
     */
    public record HqmsExportBatchSummary(UUID id, String batchNo, java.time.LocalDate periodFrom,
                                         java.time.LocalDate periodTo, int homepageCount,
                                         java.math.BigDecimal totalAmount, String payloadHash,
                                         String status, String receiptNo, String receiptMessage,
                                         java.time.Instant receiptAt, String cancelledReason,
                                         java.time.Instant cancelledAt, String createdBy,
                                         long version, java.time.Instant createdAt,
                                         int excludedAlreadyExported, int remainingPending) {
    }
}
