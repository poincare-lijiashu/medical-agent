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
    TOO_MANY_REQUESTS(42900, "操作过于频繁，请稍后再试"),
    INTERNAL_ERROR(50000, "系统处理异常，已记录日志，请联系管理员"),
    UPSTREAM_UNAVAILABLE(50300, "依赖服务暂不可用，请稍后重试"),

    // ---- EMPI 患者主索引（41xxx）----
    PATIENT_NOT_FOUND(41401, "未找到该患者"),
    PATIENT_DUPLICATE(41409, "该患者已存在，请核对证件号后使用已有档案"),
    PATIENT_MERGE_CONFLICT(41410, "患者合并冲突：两条档案均有业务数据，需人工确认"),

    // ---- 挂号（42xxx）----
    REGISTRATION_NO_SLOT(42401, "该时段号源已满"),
    REGISTRATION_DUPLICATED(42409, "该患者本次就诊已挂号"),

    // ---- 就诊（42xxx 续）----
    VISIT_NOT_FOUND(42404, "未找到该就诊记录"),
    VISIT_STATUS_INVALID(42410, "当前就诊状态不允许该操作"),

    // ---- 医嘱（44xxx）----
    ORDER_ITEM_NOT_FOUND(44401, "未找到该医嘱项目，或该项目已停用"),
    ORDER_STATUS_INVALID(44410, "当前医嘱状态不允许该操作"),

    // ---- 收费（45xxx）----
    CHARGE_NOT_FOUND(45401, "未找到该就诊的收费单"),
    CHARGE_STATUS_INVALID(45410, "当前收费单状态不允许该操作"),

    // ---- 药房（43xxx）----
    DRUG_STOCK_INSUFFICIENT(43401, "药品库存不足"),
    DRUG_BATCH_EXPIRED(43402, "所选批次已过期，禁止发药"),
    DRUG_NOT_FOUND(43403, "未找到该药品，或该药品已停用"),
    PRESCRIPTION_NOT_FOUND(43404, "未找到该处方"),
    DISPENSE_NOT_FOUND(43405, "未找到该发药单"),
    PRESCRIPTION_STATUS_INVALID(43410, "当前处方状态不允许该操作"),
    DRUG_RETURN_EXCEEDS(43411, "退药数量超过已发数量"),

    // ---- 医技（46xxx）----
    TECH_EXAM_ITEM_NOT_FOUND(46401, "未找到该医技项目，或该项目已停用"),
    TECH_APPLICATION_NOT_FOUND(46402, "未找到该检查/检验申请"),
    TECH_SPECIMEN_NOT_FOUND(46403, "未找到该标本"),
    TECH_REPORT_NOT_FOUND(46404, "未找到该报告"),
    TECH_CRITICAL_NOT_FOUND(46405, "未找到该危急值"),
    TECH_APPLICATION_STATUS_INVALID(46410, "当前申请状态不允许该操作"),
    TECH_REPORT_STATUS_INVALID(46411, "当前报告状态不允许该操作"),
    TECH_CRITICAL_STATUS_INVALID(46412, "当前危急值状态不允许该操作"),

    // ---- 病历（47xxx）----
    EMR_TEMPLATE_NOT_FOUND(47401, "未找到该病历模板，或模板已停用"),
    EMR_RECORD_NOT_FOUND(47402, "未找到该病历文书"),
    EMR_RECORD_STATUS_INVALID(47410, "当前病历状态不允许该操作"),
    EMR_QC_BLOCKED(47411, "病历质控未通过，存在必须整改的项目");

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
