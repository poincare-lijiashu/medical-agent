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
    /** 网关 response-timeout 到期，或下游在超时预算内未返回。 */
    GATEWAY_TIMEOUT(50400, "服务响应超时，请稍后重试；若持续出现请联系信息科"),

    // ---- 账号与权限（40xxx）----
    // P1-D6：此前账号/角色/科室/会话不存在统一落到通用 40400，客户端无法按码分支；
    // 与各域 41xxx–49xxx 的专属码惯例对齐后，前端可稳定区分"账号不存在"与"会话过期"。
    USER_NOT_FOUND(40401, "未找到该账号"),
    ROLE_NOT_FOUND(40402, "未找到该角色，或该角色已停用"),
    ORG_NOT_FOUND(40403, "未找到该科室，或该科室已停用"),
    SESSION_NOT_FOUND(40404, "会话不存在或已失效"),
    USER_DUPLICATE(40409, "该用户名已存在"),
    LAST_ADMIN_PROTECTED(40410, "不能停用或降级系统中最后一个启用的管理员账号"),

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
    ORDER_TRANSFER_INCOMPLETE(44411, "医嘱的下游单据未生成（转送失败），请先重试转送或由下游补开后再计费"),

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

    // ---- 药品采购（43xxx 续，G2）----
    SUPPLIER_NOT_FOUND(43430, "未找到该供应商"),
    SUPPLIER_INACTIVE(43431, "该供应商已停用，不能新开采购单"),
    SUPPLIER_LICENSE_EXPIRED(43432, "该供应商的药品经营许可证已过期，不能新开采购单"),
    PURCHASE_ORDER_NOT_FOUND(43433, "未找到该采购单"),
    PURCHASE_ORDER_STATUS_INVALID(43434, "当前采购单状态不允许该操作"),
    PURCHASE_ITEM_NOT_FOUND(43435, "采购明细不属于该采购单"),
    PURCHASE_RECEIPT_EXCEEDS(43436, "验收数量超过采购未到数量"),
    PURCHASE_RECEIPT_INVALID(43437, "验收内容不合法（重复明细或数量超限）"),
    GOODS_RECEIPT_NOT_FOUND(43438, "未找到该到货验收单"),

    // ---- 药品盘点（43xxx 续，G6）----
    STOCK_TAKE_NOT_FOUND(43460, "未找到该盘点单"),
    STOCK_TAKE_ITEM_NOT_FOUND(43461, "未找到该盘点明细"),
    STOCK_TAKE_STATUS_INVALID(43462, "当前盘点单状态不允许该操作"),
    STOCK_TAKE_ACTIVE_EXISTS(43463, "已有一张进行中的盘点单，请先提交并审批或取消"),
    STOCK_TAKE_INCOMPLETE(43464, "仍有批次未录入实盘数量，不能提交"),
    STOCK_TAKE_STOCK_DRIFT(43465, "盘点期间库存已发生变化，请取消后重新盘点"),
    STOCK_TAKE_SELF_APPROVAL(43466, "不能审批自己创建的盘点单"),
    STOCK_TAKE_EMPTY(43467, "当前没有可盘点的库存批次"),
    STOCK_TAKE_VARIANCE_REASON_REQUIRED(43468, "实盘数量与账面不一致时必须填写差异原因"),

    // ---- 药品报损（43xxx 续，G7）----
    STOCK_LOSS_NOT_FOUND(43470, "未找到该报损单"),
    STOCK_LOSS_ITEM_INVALID(43471, "报损明细不合法（空明细或同一批次重复）"),
    STOCK_LOSS_STATUS_INVALID(43472, "当前报损单状态不允许该操作"),
    STOCK_LOSS_EXCEEDS(43473, "报损数量超过该批次当前余量"),
    STOCK_LOSS_STOCK_INSUFFICIENT(43474, "审批时批次余量不足，请核对后重新提交"),
    STOCK_LOSS_SELF_REVIEW(43475, "不能审批自己提交的报损单"),
    STOCK_LOSS_BATCH_NOT_FOUND(43476, "报损明细中的批次不存在"),
    STOCK_LOSS_REJECT_REASON_REQUIRED(43477, "驳回报损必须填写原因"),

    // ---- 药品科室调拨（43xxx 续，G8）----
    DRUG_TRANSFER_NOT_FOUND(43480, "未找到该调拨单"),
    DRUG_TRANSFER_ITEM_INVALID(43481, "调拨明细不合法（空明细或同一批次重复）"),
    DRUG_TRANSFER_STATUS_INVALID(43482, "当前调拨单状态不允许该操作"),
    DRUG_TRANSFER_STOCK_INSUFFICIENT(43483, "中心药房批次余量不足，请核对后重新提交"),
    DRUG_TRANSFER_REJECT_REASON_REQUIRED(43484, "拒收调拨必须填写原因"),
    DRUG_TRANSFER_CANCEL_REASON_REQUIRED(43485, "撤回调拨必须填写原因"),
    DRUG_TRANSFER_BATCH_NOT_FOUND(43486, "调拨明细中的批次不存在"),
    DRUG_TRANSFER_EXCEEDS(43487, "调拨数量超过该批次当前余量"),

    // ---- 物资耗材（43xxx 续，G3；与药械同属物资供应，编码段相邻）----
    CONSUMABLE_NOT_FOUND(43450, "未找到该耗材，或该耗材已停用"),
    CONSUMABLE_BATCH_NOT_FOUND(43451, "未找到该耗材批次"),
    CONSUMABLE_ISSUE_NOT_FOUND(43452, "未找到该出库单"),
    CONSUMABLE_STOCK_INSUFFICIENT(43453, "耗材库存不足"),
    CONSUMABLE_BATCH_EXPIRED(43454, "所选批次已过期，禁止出库或用于患者"),
    CONSUMABLE_TRACE_EXCEEDS(43455, "追溯数量超过已出库未追溯数量"),
    CONSUMABLE_ISSUE_INVALID(43456, "出库内容不合法（重复明细或数量异常）"),
    CONSUMABLE_ISSUE_ITEM_NOT_FOUND(43457, "未找到该出库明细"),

    // ---- 耗材未使用退回（43xxx 续，G9）----
    CONSUMABLE_RETURN_NOT_FOUND(43500, "未找到该耗材退回单"),
    CONSUMABLE_RETURN_ITEM_INVALID(43501, "退回明细不合法（同一批次重复）"),
    CONSUMABLE_RETURN_STATUS_INVALID(43502, "当前退回单状态不允许该操作"),
    CONSUMABLE_DEPT_STOCK_INSUFFICIENT(43503, "科室二级库余量不足，无法退回"),
    CONSUMABLE_RETURN_REJECT_REASON_REQUIRED(43504, "驳回退回必须填写原因"),
    CONSUMABLE_RETURN_CANCEL_REASON_REQUIRED(43505, "撤回退回必须填写原因"),
    CONSUMABLE_RETURN_BATCH_NOT_FOUND(43506, "退回明细中的批次不存在"),
    CONSUMABLE_RETURN_SELF_REVIEW(43507, "不能审批自己提交的退回单"),

    // ---- 麻醉药品（43xxx 续）----
    NARCOTIC_REGISTER_NOT_FOUND(43407, "未找到该麻醉药品专册登记"),
    NARCOTIC_PRESCRIBER_NOT_FOUND(43408, "未找到该医师的麻醉药品处方权记录"),
    NARCOTIC_PRESCRIBER_REQUIRED(43420, "该医师未取得麻醉药品处方权，不能开具麻醉药品"),
    NARCOTIC_AMPOULE_OUTSTANDING(43421, "该患者上次麻醉药品的空安瓿尚未回收，暂不能再次开具"),
    NARCOTIC_AMPOULE_INVALID(43422, "空安瓿回收数量必须等于发药数量"),
    NARCOTIC_PRESCRIBER_STATE_INVALID(43423, "该医师的麻醉药品处方权状态不允许该操作"),

    // ---- 医技（46xxx）----
    TECH_EXAM_ITEM_NOT_FOUND(46401, "未找到该医技项目，或该项目已停用"),
    TECH_APPLICATION_NOT_FOUND(46402, "未找到该检查/检验申请"),
    TECH_SPECIMEN_NOT_FOUND(46403, "未找到该标本"),
    TECH_REPORT_NOT_FOUND(46404, "未找到该报告"),
    TECH_CRITICAL_NOT_FOUND(46405, "未找到该危急值"),
    TECH_PACS_STUDY_NOT_FOUND(46406, "未找到该影像检查登记"),
    TECH_APPLICATION_STATUS_INVALID(46410, "当前申请状态不允许该操作"),
    TECH_REPORT_STATUS_INVALID(46411, "当前报告状态不允许该操作"),
    TECH_CRITICAL_STATUS_INVALID(46412, "当前危急值状态不允许该操作"),
    TECH_PACS_STATUS_INVALID(46413, "当前影像检查状态不允许该操作"),

    // ---- 影像调阅（46xxx 续，G5）----
    TECH_PACS_NOT_CONFIGURED(46407, "影像服务（PACS）未配置，暂不能调阅影像"),
    TECH_PACS_UPSTREAM_FAILED(46408, "影像服务（PACS）响应异常，请稍后重试"),
    TECH_PACS_REMOTE_NOT_FOUND(46409, "影像服务未返回该序列或影像"),
    TECH_READING_TASK_NOT_FOUND(46420, "未找到该阅片任务"),
    TECH_READING_STATUS_INVALID(46421, "当前阅片任务状态不允许该操作"),
    TECH_READING_NOT_ASSIGNEE(46422, "该阅片任务不属于当前医师，不能释放或完成"),
    TECH_READING_STUDY_NOT_READY(46423, "影像检查尚未完成，不能领取阅片"),
    TECH_READING_REPORT_NOT_FINAL(46424, "报告尚未签发，不能完成阅片"),

    // ---- 病历（47xxx）----
    EMR_TEMPLATE_NOT_FOUND(47401, "未找到该病历模板，或模板已停用"),
    EMR_RECORD_NOT_FOUND(47402, "未找到该病历文书"),
    EMR_CHANGE_REQUEST_NOT_FOUND(47403, "未找到该病案修改申请"),
    EMR_HOMEPAGE_NOT_FOUND(47404, "未找到该病案首页"),
    EMR_HQMS_NOT_FOUND(47405, "未找到该上报批次"),
    EMR_RECORD_STATUS_INVALID(47410, "当前病历状态不允许该操作"),
    EMR_QC_BLOCKED(47411, "病历质控未通过，存在必须整改的项目"),
    EMR_CHANGE_REQUEST_INVALID(47412, "病案修改申请状态不允许该操作"),
    EMR_SIGN_LEVEL_INVALID(47413, "签名级别不正确：必须按 书写医师→主治→主任 顺序签署"),
    EMR_SIGN_POLICY_INVALID(47414, "签名策略不存在、配置不合法或已被他人修改"),
    EMR_HOMEPAGE_STATUS_INVALID(47415, "当前病案首页状态不允许该操作"),
    EMR_HOMEPAGE_INCOMPLETE(47416, "病案首页必填项未完成，不能提交"),
    EMR_HOMEPAGE_SOURCE_INVALID(47417, "该就诊不满足病案首页生成条件（须为已出院住院就诊）"),
    EMR_HQMS_EXPORT_INVALID(47418, "当前上报批次状态不允许该操作"),
    EMR_HQMS_EXPORT_EMPTY(47419, "该期间没有待上报的已归档病案首页"),

    // ---- 病案编码（47xxx 续，G4）----
    ICD_ITEM_NOT_FOUND(47430, "未找到该 ICD 编码，或该编码已停用"),
    CODING_NOT_FOUND(47431, "未找到该病案编码记录"),
    CODING_INVALID(47432, "编码内容不合法（主诊断必须且只能有一个、编码须存在于字典）"),
    HOMEPAGE_NOT_CODED(47433, "病案首页尚未完成编码，不能归档"),
    CODING_STATUS_INVALID(47434, "当前编码状态不允许该操作"),

    // ---- 病案编码更正（47xxx 续，G10）----
    CODING_CORRECTION_NOT_FOUND(47440, "未找到该编码更正申请"),
    CODING_CORRECTION_INVALID(47441, "编码更正内容不合法，或该病案已有待审批的更正申请"),
    CODING_CORRECTION_STATUS_INVALID(47442, "当前更正申请状态不允许该操作"),
    CODING_CORRECTION_SELF_REVIEW(47443, "不能审批自己提交的编码更正申请"),
    CODING_CORRECTION_REASON_REQUIRED(47444, "驳回编码更正必须填写原因"),
    CODING_CORRECTION_CANCEL_REASON_REQUIRED(47445, "撤回编码更正必须填写原因"),
    CODING_CORRECTION_NOT_CODED(47446, "病案尚未完成编码，不能申请更正"),

    // ---- 医保（48xxx）----
    INSURANCE_ITEM_NOT_FOUND(48401, "未找到该医保目录项目"),
    INSURANCE_MEMBER_NOT_FOUND(48402, "未找到该患者的参保登记"),
    INSURANCE_BILL_NOT_FOUND(48403, "未找到该医保结算清单"),
    INSURANCE_EXPORT_NOT_FOUND(48404, "未找到该医保导出批次"),
    INSURANCE_SCHEME_NOT_FOUND(48405, "未找到该医保支付方案"),
    INSURANCE_GROUP_NOT_FOUND(48406, "未找到该医保分组"),
    INSURANCE_NOT_ENROLLED(48410, "该患者无有效参保信息，无法生成医保结算清单"),
    INSURANCE_BILL_STATUS_INVALID(48411, "当前结算清单状态不允许该操作"),
    INSURANCE_NO_CHARGE(48412, "该就诊单暂无收费明细，无法生成结算清单"),
    INSURANCE_EXPORT_EMPTY(48413, "该期间没有已确认的医保结算清单，无法导出"),
    INSURANCE_EXPORT_INVALID(48414, "当前导出批次状态不允许该操作"),
    INSURANCE_SCHEME_INVALID(48415, "支付方案配置不合法或已被他人修改"),

    // ---- 知识库（49xxx）----
    KB_SPACE_NOT_FOUND(49401, "未找到该知识空间"),
    KB_DOCUMENT_NOT_FOUND(49402, "未找到该知识文档"),
    KB_SPACE_DUPLICATE(49409, "知识空间编码已存在"),
    KB_DOCUMENT_DUPLICATE(49408, "该知识空间已存在内容完全相同的文档"),
    KB_SPACE_ACCESS_DENIED(49410, "你不是该知识空间的成员，无权访问"),
    KB_SPACE_STATUS_INVALID(49411, "当前知识空间状态不允许该操作"),
    KB_DOCUMENT_STATUS_INVALID(49412, "当前文档状态不允许该操作"),
    KB_QUERY_INVALID(49413, "检索词为空或不合法，请调整后重试"),
    KB_PARSE_UNSUPPORTED(49414, "该文件格式暂不支持解析，请转为纯文本或 Markdown 后入库"),
    KB_EMBEDDING_UNAVAILABLE(49420, "向量模型未配置，当前仅支持关键词检索");

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
