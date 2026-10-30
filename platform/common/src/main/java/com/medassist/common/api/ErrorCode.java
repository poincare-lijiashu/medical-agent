package com.medassist.common.api;

/**
 * 平台稳定错误码。
 *
 * <p>编码规则：{@code HHHSS} = HTTP 状态前三位 + 两位业务序号；同码语义永不改变
 * （契约稳定性要求，见计划书 D3 契约冻结约束）。医护端文案由 {@link #defaultMessage()} 提供，
 * 禁止把英文异常信息直接透出。
 */
public enum ErrorCode {

    OK(0, "成功"),

    // ---- 通用 ----
    BAD_REQUEST(40000, "请求参数有误，请检查后重试"),
    UNAUTHORIZED(40100, "未认证：请先登录"),
    FORBIDDEN(40300, "无权限执行该操作"),
    NOT_FOUND(40400, "请求的资源不存在"),
    CONFLICT(40900, "数据已被他人修改，请刷新后重试"),
    VALIDATION_FAILED(42200, "提交内容不符合校验规则"),
    INTERNAL_ERROR(50000, "系统处理异常，已记录日志，请联系管理员"),
    UPSTREAM_UNAVAILABLE(50300, "依赖服务暂不可用，请稍后重试"),

    // ---- EMPI 患者主索引（41xxx）----
    PATIENT_NOT_FOUND(41401, "未找到该患者"),
    PATIENT_DUPLICATE(41409, "该患者已存在，请核对证件号后使用已有档案"),
    PATIENT_MERGE_CONFLICT(41410, "患者合并冲突：两条档案均有业务数据，需人工确认"),

    // ---- 挂号（42xxx）----
    REGISTRATION_NO_SLOT(42401, "该时段号源已满"),
    REGISTRATION_DUPLICATED(42409, "该患者本次就诊已挂号"),

    // ---- 药房（43xxx）----
    DRUG_STOCK_INSUFFICIENT(43401, "药品库存不足"),
    DRUG_BATCH_EXPIRED(43402, "所选批次已过期，禁止发药");

    private final int code;
    private final String defaultMessage;

    ErrorCode(int code, String defaultMessage) {
        this.code = code;
        this.defaultMessage = defaultMessage;
    }

    public int code() {
        return code;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
