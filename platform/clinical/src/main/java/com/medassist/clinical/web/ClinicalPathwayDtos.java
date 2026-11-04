package com.medassist.clinical.web;

import com.medassist.clinical.domain.ClinicalModel.PathwayInstance;
import com.medassist.clinical.domain.ClinicalModel.PathwayNode;
import com.medassist.clinical.domain.ClinicalModel.PathwayTask;
import com.medassist.clinical.domain.ClinicalModel.PathwayTemplate;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * 临床路径契约（G29）。
 *
 * <p>模板节点只接受「第几天 + 顺序 + 名称 + 分类」：入径后按节点生成任务快照，
 * 已入径患者不会因模板后续停用而丢失待办。
 */
public final class ClinicalPathwayDtos {

    private ClinicalPathwayDtos() {
    }

    /** 新建模板：节点不能为空，天序号与顺序在公司内唯一。 */
    public record CreateTemplateReq(
            @NotBlank @Size(max = 32)
            @Pattern(regexp = "[A-Za-z0-9_-]+", message = "路径编码只能包含字母、数字、下划线与短横线")
            String code,
            @NotBlank @Size(max = 100) String name,
            @Size(max = 32) String diagnosisCode,
            UUID deptId,
            @NotEmpty @Size(max = 200) List<@Valid NodeReq> nodes) {
    }

    public record NodeReq(
            @NotNull @Min(1) @Max(60) Integer dayNo,
            @NotNull @Min(1) @Max(99) Integer seq,
            @NotBlank @Size(max = 100) String name,
            @NotBlank
            @Pattern(regexp = "ASSESSMENT|ORDER|NURSING|EXAM|SURGERY|OTHER",
                    message = "节点分类只能是 ASSESSMENT/ORDER/NURSING/EXAM/SURGERY/OTHER")
            String category,
            Boolean required,
            @Size(max = 500) String description) {
    }

    public record TemplateView(PathwayTemplate template, List<PathwayNode> nodes) {
    }

    /** 入径：住院号与就诊 ID 二选一（住院号更贴近临床习惯）。 */
    public record EnrollReq(UUID visitId, @Size(max = 32) String visitNo,
                            @NotNull UUID templateId) {
    }

    public record ProgressView(int total, int done, int skipped, int pending) {
    }

    public record InstanceView(PathwayInstance instance, List<PathwayTask> tasks,
                               ProgressView progress) {
    }

    /** 任务流转：DONE=完成 / SKIPPED=跳过（变异，必须写原因）。 */
    public record UpdateTaskReq(
            @NotBlank
            @Pattern(regexp = "DONE|SKIPPED", message = "任务状态只能是 DONE 或 SKIPPED")
            String status,
            @Size(max = 200) String note) {
    }

    public record ExitReq(@NotBlank @Size(max = 200) String reason) {
    }
}
