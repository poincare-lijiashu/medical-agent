package com.medassist.emr.web;

import com.medassist.emr.domain.EmrModel.Amendment;
import com.medassist.emr.domain.EmrModel.MedicalRecord;
import com.medassist.emr.domain.EmrModel.QcResult;
import com.medassist.emr.domain.EmrModel.RecordSignature;
import com.medassist.emr.domain.EmrModel.Template;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

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

    /** 病历详情：正文 + 签名 + 修订历史 + 当前版本质控结果。 */
    public record RecordView(MedicalRecord record, List<RecordSignature> signatures,
                             List<Amendment> amendments, List<QcResult> qcResults) {
    }

    /** 质控结果（提交前预览）。 */
    public record QcReport(boolean passed, int fatalCount, int majorCount, int minorCount,
                           List<QcResult> results) {
    }

    /** 哈希校验结果。 */
    public record HashVerify(boolean valid, String storedHash, String recomputedHash) {
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
}
