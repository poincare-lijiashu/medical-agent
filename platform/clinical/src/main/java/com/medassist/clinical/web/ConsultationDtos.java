package com.medassist.clinical.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * 会诊请求/响应体。
 *
 * <p>所有状态迁移都要求 {@code version}：会诊是两个人同时对同一张单子操作
 * （主诊科室撤回、受邀科室受理），没有乐观锁就会出现"刚受理又被撤回"的错乱。
 */
public final class ConsultationDtos {

    private ConsultationDtos() {
    }

    /** 发起会诊：受邀科室不能与主诊科室相同，理由必填。 */
    public record CreateConsultationReq(
            @NotNull UUID toDeptId,
            @NotBlank @Size(max = 500) String reason) {
    }

    /** 拒绝会诊：必须写理由（受理不需要请求体）。 */
    public record RejectConsultationReq(
            @NotBlank @Size(max = 500) String reason) {
    }

    /** 完成会诊：必须给出会诊意见。 */
    public record CompleteConsultationReq(
            @NotBlank @Size(max = 2000) String conclusion,
            @NotNull Long version) {
    }

    /** 撤回会诊：主诊科室终止进行中的会诊。 */
    public record CancelConsultationReq(
            @NotBlank @Size(max = 500) String reason,
            @NotNull Long version) {
    }
}
