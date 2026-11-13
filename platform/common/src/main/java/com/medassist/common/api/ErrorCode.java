package com.medassist.common.api;

/**
 * 平台稳定错误码。
 *
 * <p>编码规则：{@code HHHSS} = HTTP 状态前三位 + 两位业务序号；同码语义永不改变
 * （契约稳定性要求，见计划书 D3 契约冻结约束）。医护端文案由 {@link #defaultMessage()} 提供，
 * 禁止把英文异常信息直接透出。
 *
 * <p>码段分配（G78 起作为新增码的落位依据；<b>已发布的码不重新编号</b>——改号是
 * 客户端可观测的破坏性变更，而重排本身不带来任何运行期收益）：
 * <ul>
 *   <li>40xxx 账号 / 角色 / 机构 / 会话 / 单点登录；41xxx 患者主索引；42xxx 挂号与就诊
 *       （含住院登记、病区、手术记录）；</li>
 *   <li>43xxx 药事与物资——药事占 4340x–4352x，物资占 4345x–4351x，两者交错是历史遗留，
 *       新增物资码请从 4355x 起，避免再插进药事段；</li>
 *   <li>44xxx 医嘱 / 临床路径 / 出院带药 / 急诊 / 体检 / 慢病 / 移动护理 / 输血 / CA；</li>
 *   <li>45xxx 收费与票据；46xxx 医技；47xxx 病案与知情同意书；48xxx 医保；49xxx 知识库；
 *       50xxx 主数据与字典映射。</li>
 * </ul>
 *
 * <p>新增码后必须同步在该服务的 {@code GlobalExceptionHandler#statusOf} 里显式映射，
 * 否则会落到 {@code default -> BAD_REQUEST}，把"不存在"错报成"参数错误"。
 */
public enum ErrorCode {

    OK(0, "成功"),

    // ---- 通用 ----
    BAD_REQUEST(40000, "请求参数有误，请检查后重试"),
    UNAUTHORIZED(40100, "未认证：请先登录"),
    FORBIDDEN(40300, "无权限执行该操作"),
    NOT_FOUND(40400, "请求的资源不存在"),
    NOT_ACCEPTABLE(40600, "请求的响应格式不被支持，请检查 Accept 头"),
    UNSUPPORTED_MEDIA_TYPE(41500, "请求内容类型不被支持，请检查 Content-Type"),
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

    // ---- 统一身份 SSO（40xxx 续，G42）----
    SSO_DISABLED(40110, "未启用统一身份登录，请使用院内账号密码登录"),
    SSO_STATE_INVALID(40111, "统一身份登录状态已失效，请重新发起登录"),
    SSO_TOKEN_INVALID(40112, "统一身份返回的身份令牌校验失败，请重新登录"),
    SSO_IDENTITY_NOT_LINKED(40310, "统一身份未关联本地账号，请联系管理员开通或绑定"),
    SSO_PROVISION_FAILED(40311, "统一身份自动开户失败：缺少有效的账号、角色或科室配置"),
    SSO_PROVIDER_UNAVAILABLE(50310, "统一身份服务暂不可用，请稍后重试或使用院内账号登录"),

    // ---- EMPI 患者主索引（41xxx）----
    PATIENT_NOT_FOUND(41401, "未找到该患者"),
    PATIENT_DUPLICATE(41409, "该患者已存在，请核对证件号后使用已有档案"),
    PATIENT_MERGE_CONFLICT(41410, "患者合并冲突：两条档案均有业务数据，需人工确认"),

    // ---- 挂号（42xxx）----
    REGISTRATION_NO_SLOT(42401, "该时段号源已满"),
    REGISTRATION_DUPLICATED(42409, "该患者本次就诊已挂号"),

    // ---- 就诊（42xxx 续）----
    VISIT_NOT_FOUND(42404, "未找到该就诊记录"),
    /*
     * G78：住院登记 / 病区 / 手术记录原本统一落通用 40400，与就诊的 42404 混用——
     * 同一类"越权或不存在的单据"在不同入口给不同码，日志与排障只能靠中文文案区分。
     * 统一到 42xxx 专属码，HTTP 状态仍是 404（越权与不存在同码的约定不变）。
     */
    ADMISSION_NOT_FOUND(42405, "未找到该住院登记"),
    WARD_NOT_FOUND(42406, "未找到该病区"),
    SURGERY_CASE_NOT_FOUND(42407, "未找到该手术记录"),
    VISIT_STATUS_INVALID(42410, "当前就诊状态不允许该操作"),
    SURGERY_SAFETY_CHECKLIST_REQUIRED(42411, "手术安全核查未完成，不能进入下一节点"),
    SURGERY_SAFETY_CHECKLIST_INVALID(42412, "手术安全核查不合法（核查项/时序/内容有误）"),

    // ---- 医嘱（44xxx）----
    ORDER_ITEM_NOT_FOUND(44401, "未找到该医嘱项目，或该项目已停用"),
    ORDER_STATUS_INVALID(44410, "当前医嘱状态不允许该操作"),
    ORDER_TRANSFER_INCOMPLETE(44411, "医嘱的下游单据未生成（转送失败），请先重试转送或由下游补开后再计费"),

    // ---- 临床路径（44xxx 续，G29）----
    PATHWAY_TEMPLATE_NOT_FOUND(44420, "未找到该临床路径模板"),
    PATHWAY_TEMPLATE_STATUS_INVALID(44421, "当前路径模板状态不允许该操作"),
    PATHWAY_TEMPLATE_DUPLICATE(44422, "路径编码已存在，请换一个编码"),
    PATHWAY_TEMPLATE_INVALID(44423, "路径模板内容不合法（节点为空、天序号重复或分类错误）"),
    PATHWAY_INSTANCE_NOT_FOUND(44424, "未找到该路径实例"),
    PATHWAY_INSTANCE_STATUS_INVALID(44425, "当前路径状态不允许该操作"),
    PATHWAY_INSTANCE_EXISTS(44426, "该住院就诊已有进行中的临床路径"),
    PATHWAY_TASK_NOT_FOUND(44427, "未找到该路径任务"),
    PATHWAY_TASK_STATUS_INVALID(44428, "当前路径任务已完成或跳过，不能重复处理"),
    PATHWAY_TASK_SKIP_REASON_REQUIRED(44429, "跳过路径任务必须填写原因（变异记录）"),
    PATHWAY_INSTANCE_INCOMPLETE(44430, "仍有未完成的路径任务，不能结束路径"),
    PATHWAY_EXIT_REASON_REQUIRED(44431, "退出临床路径必须填写原因"),
    PATHWAY_VISIT_INVALID(44432, "只有住院且在院就诊才能入径"),

    // ---- 出院带药（44xxx 续，G30）----
    DISCHARGE_MED_NOT_FOUND(44440, "未找到该出院带药单"),
    DISCHARGE_MED_STATUS_INVALID(44441, "当前出院带药状态不允许该操作"),
    DISCHARGE_MED_EXISTS(44442, "该住院登记已有进行中的出院带药单"),
    DISCHARGE_MED_INVALID(44443, "出院带药内容不合法（空明细、药品重复或数量异常）"),
    DISCHARGE_MED_VISIT_INVALID(44444, "只有在院患者才能开立出院带药"),
    DISCHARGE_MED_CANCEL_REASON_REQUIRED(44445, "取消出院带药必须填写原因"),
    DISCHARGE_MED_PRESCRIPTION_MISSING(44446, "出院带药尚未生成药房处方，不能同步或取消已提交单据"),

    // ---- 急诊分诊与留观（44xxx 续，G36）----
    EMERGENCY_TRIAGE_NOT_FOUND(44460, "未找到该急诊分诊记录"),
    EMERGENCY_TRIAGE_STATUS_INVALID(44461, "当前分诊状态不允许该操作"),
    EMERGENCY_TRIAGE_LEVEL_INVALID(44462, "预检分级只能是 1–4 级"),
    EMERGENCY_TRIAGE_VITALS_INVALID(44463, "生命体征超出合理范围，请核对后重试"),
    EMERGENCY_TRIAGE_NOT_REGISTERED(44464, "该分诊记录尚未挂号，不能进入留观"),
    EMERGENCY_BED_NOT_FOUND(44465, "未找到该急诊床位"),
    EMERGENCY_BED_UNAVAILABLE(44466, "没有可用的留观床位，或床位已被他人占用"),
    EMERGENCY_OBSERVATION_NOT_FOUND(44467, "未找到该留观记录"),
    EMERGENCY_OBSERVATION_STATUS_INVALID(44468, "当前留观状态不允许该操作"),
    EMERGENCY_OUTCOME_INVALID(44469, "留观转归只能是 DISCHARGED / ADMITTED / TRANSFERRED / LEFT"),
    EMERGENCY_OUTCOME_NOTE_REQUIRED(44470, "自动离院或转院必须填写转归说明"),
    EMERGENCY_LEAVE_REASON_REQUIRED(44471, "离院必须填写原因"),

    // ---- 体检中心（44xxx 续，G37）----
    HEALTH_PACKAGE_NOT_FOUND(44480, "未找到该体检套餐"),
    HEALTH_PACKAGE_DUPLICATE(44481, "套餐编码已存在，请换一个编码"),
    HEALTH_PACKAGE_STATUS_INVALID(44482, "当前套餐状态不允许该操作"),
    HEALTH_PACKAGE_INVALID(44483, "套餐内容不合法（项目为空、重复或收费项目已停用）"),
    HEALTH_REGISTRATION_NOT_FOUND(44484, "未找到该体检登记"),
    HEALTH_REGISTRATION_STATUS_INVALID(44485, "当前体检登记状态不允许该操作"),
    HEALTH_REGISTRATION_CANCEL_REASON_REQUIRED(44486, "取消体检登记必须填写原因"),
    HEALTH_ITEM_NOT_FOUND(44487, "未找到该体检项目"),
    HEALTH_ITEM_STATUS_INVALID(44488, "当前体检项目状态不允许该操作"),
    HEALTH_ITEM_RESULT_INVALID(44489, "体检结果不合法（完成需填结论，未检需填原因）"),
    HEALTH_REPORT_NOT_FOUND(44490, "未找到该体检报告"),
    HEALTH_REPORT_STATUS_INVALID(44491, "当前体检报告状态不允许该操作"),
    HEALTH_REPORT_INCOMPLETE(44492, "仍有未完成的体检项目，不能出具总检报告"),
    HEALTH_REPORT_INVALID(44493, "总检报告结论不能为空，或内容不合法"),

    // ---- 随访与慢病（44xxx 续，G38）----
    CHRONIC_DISEASE_NOT_FOUND(44500, "未找到该慢病病种，或病种已停用"),
    CHRONIC_RECORD_NOT_FOUND(44501, "未找到该慢病档案"),
    CHRONIC_RECORD_DUPLICATE(44502, "该患者该病种已有进行中的慢病档案"),
    CHRONIC_RECORD_STATUS_INVALID(44503, "当前慢病档案状态不允许该操作"),
    CHRONIC_PLAN_NOT_FOUND(44504, "未找到该随访计划"),
    CHRONIC_PLAN_EXISTS(44505, "该慢病档案已有进行中的随访计划"),
    CHRONIC_PLAN_STATUS_INVALID(44506, "当前随访计划状态不允许该操作"),
    CHRONIC_PLAN_INVALID(44507, "随访计划不合法（间隔 7–365 天，下次日期不能早于今天）"),
    CHRONIC_TASK_NOT_FOUND(44508, "未找到该随访任务"),
    CHRONIC_TASK_STATUS_INVALID(44509, "当前随访任务状态不允许该操作"),
    CHRONIC_FOLLOWUP_INVALID(44510, "随访记录不合法（日期/血压/血糖/体重超出范围或缺少必要信息）"),
    CHRONIC_FOLLOWUP_NOT_FOUND(44511, "未找到该随访记录"),
    CHRONIC_CLOSE_REASON_REQUIRED(44512, "结案必须填写原因"),
    CHRONIC_STOP_REASON_REQUIRED(44513, "停止随访计划必须填写原因"),

    // ---- 移动护理与条码（44xxx 续，G39）----
    MOBILE_TASK_NOT_FOUND(44520, "未找到该移动护理任务"),
    MOBILE_TASK_STATUS_INVALID(44521, "当前移动护理任务状态不允许该操作"),
    MOBILE_TASK_INVALID(44522, "移动护理任务内容不合法（类型/项目/条码缺失）"),
    MOBILE_ADMISSION_INVALID(44523, "住院登记不存在、已出院或未分床，不能生成移动护理任务"),
    MOBILE_EXCEPTION_REASON_REQUIRED(44524, "登记扫码异常必须填写原因"),
    MOBILE_SCAN_INVALID(44525, "扫码内容为空，请重新扫描腕带/药品/标本条码"),

    // ---- 输血闭环（44xxx 续，G40）----
    TRANSFUSION_REQUEST_NOT_FOUND(44530, "未找到该输血申请"),
    TRANSFUSION_STATUS_INVALID(44531, "当前输血状态不允许该操作"),
    TRANSFUSION_REQUEST_INVALID(44532, "输血申请内容不合法（血型/成分/数量/适应症）"),
    TRANSFUSION_CROSSMATCH_NOT_FOUND(44533, "未找到该配血记录"),
    TRANSFUSION_CROSSMATCH_INVALID(44534, "配血记录不合法（血袋号/血型/结果/有效期）"),
    TRANSFUSION_CROSSMATCH_INCOMPATIBLE(44535, "配血不合，不能发血"),
    TRANSFUSION_NOT_CROSSMATCHED(44536, "该申请尚无配血相合的记录，不能发血或输注"),
    TRANSFUSION_DOUBLE_CHECK_REQUIRED(44537, "输注前双人核对必须由两名不同人员完成"),
    TRANSFUSION_RECORD_NOT_FOUND(44538, "未找到该输注记录"),
    TRANSFUSION_REACTION_INVALID(44539, "输血不良反应内容不合法（分级/症状）"),
    TRANSFUSION_CANCEL_REASON_REQUIRED(44540, "取消输血申请必须填写原因"),
    TRANSFUSION_REACTION_EXISTS(44541, "该输注记录已上报不良反应，请勿重复上报"),
    TRANSFUSION_PRECHECK_REQUIRED(44542, "发血前必须完成输血前评估与知情同意"),
    TRANSFUSION_PRECHECK_EXISTS(44543, "该申请已存在输血前评估记录，不允许覆盖"),
    TRANSFUSION_ISSUE_CHECK_INVALID(44544, "发血核对不合法（双人核对/取血人/血液外观/血袋完整性）"),
    TRANSFUSION_OBSERVATION_REQUIRED(44545, "完成输注前至少需要一条输注观察记录（15 分钟观察）"),
    TRANSFUSION_REACTION_STATUS_INVALID(44546, "当前不良反应状态不允许该操作"),
    TRANSFUSION_REACTION_NOT_FOUND(44547, "未找到该输血不良反应记录"),

    // ---- CA 电子签名（44xxx 续，G41）----
    CA_SIGNATURE_NOT_FOUND(44550, "未找到该 CA 电子签名"),
    CA_SIGNATURE_INVALID(44551, "CA 电子签名内容不合法"),
    CA_SIGNATURE_STATUS_INVALID(44552, "当前 CA 签名状态不允许该操作"),
    CA_PROVIDER_UNAVAILABLE(44553, "CA 签名服务暂不可用，请稍后重试"),
    CA_VERIFY_FAILED(44554, "CA 签名验签失败，签名值或内容可能已被篡改"),
    CA_SIGNATURE_EXISTS(44555, "该病历签名行已有 CA 签名，请勿重复签署"),
    CA_REVOKE_REASON_REQUIRED(44556, "撤销 CA 签名必须填写原因"),

    // ---- 麻醉复苏 PACU（44xxx 续，G80）----
    PACU_STAY_NOT_FOUND(44560, "未找到该手术的复苏记录"),
    PACU_STAY_EXISTS(44561, "该手术已有复苏记录，请勿重复入室"),
    PACU_STAY_STATUS_INVALID(44562, "当前复苏状态不允许该操作（已出室或已签名）"),
    PACU_CASE_NOT_ELIGIBLE(44563, "只有已完成的手术可以进入复苏室"),
    PACU_OBSERVATION_TIME_INVALID(44564, "复苏时间不合法（入室早于手术结束、或观察/出室时间早于入室）"),
    PACU_OBSERVATION_DUPLICATED(44565, "该时间点已有复苏观察记录，请调整时间"),
    PACU_DISCHARGE_NOT_READY(44566, "最近一次 Aldrete 评分未达 9 分，不能出复苏室"),
    PACU_DESTINATION_INVALID(44567, "出室去向只能是 WARD / ICU / DISCHARGE"),
    PACU_SELF_SIGN(44568, "复苏记录不能由记录人自己签名，请由另一名医护人员复核"),

    // ---- 手术分级目录（44xxx 续，G81）----
    OPERATION_LEVEL_NOT_FOUND(44570, "该手术编码尚未配置默认分级"),
    OPERATION_LEVEL_INVALID(44571, "默认分级只能是 1~4 级，且手术编码必须存在并处于启用状态"),
    OPERATION_LEVEL_CONFLICT(44572, "默认分级已被他人修改，请刷新后重试"),

    // ---- 护理敏感质量指标（44xxx 续，§266）----
    NURSING_INDICATOR_NOT_FOUND(44580, "未找到该护理敏感指标，或指标已停用"),
    NURSING_INDICATOR_EVENT_INVALID(44581, "护理不良事件内容不合法（指标/时间/科室/患者归属）"),
    NURSING_INDICATOR_SHIFT_INVALID(44583, "发生时间无法归属到有效班次，请选择班次或核对时间"),
    NURSING_INDICATOR_DENOMINATOR_INVALID(44584, "设备日数据不合法（类型/日期/数量）"),
    NURSING_INDICATOR_REPORT_NOT_FOUND(44585, "未找到该护理指标上报记录"),
    NURSING_INDICATOR_REPORT_STATUS_INVALID(44586, "当前上报状态不允许该操作"),
    NURSING_INDICATOR_REPORT_RETURN_REASON_REQUIRED(44587, "退回上报必须填写原因"),
    NURSING_INDICATOR_SELF_REVIEW(44588, "上报审核不能由提交人本人完成，请由其他人复核"),
    NURSING_INDICATOR_PERIOD_INVALID(44589, "上报周期不合法（格式/未来周期/超出可追溯范围）"),

    // ---- 知情同意书（47xxx 续，G44）----
    CONSENT_NOT_FOUND(47420, "未找到该知情同意书"),
    CONSENT_TEMPLATE_NOT_FOUND(47421, "未找到该同意书模板，或模板未发布"),
    CONSENT_TEMPLATE_STATUS_INVALID(47422, "当前模板状态不允许该操作"),
    CONSENT_STATUS_INVALID(47423, "当前同意书状态不允许该操作"),
    CONSENT_SIGNER_REQUIRED(47424, "签署同意书必须填写患者/家属签署人及与患者关系"),
    CONSENT_REVOKE_REASON_REQUIRED(47425, "撤销知情同意书必须填写原因"),
    CONSENT_SIGNATURE_INVALID(47426, "知情同意书签名或内容校验失败"),

    // ---- 收费（45xxx）----
    CHARGE_NOT_FOUND(45401, "未找到该就诊的收费单"),
    CHARGE_STATUS_INVALID(45410, "当前收费单状态不允许该操作"),

    // ---- 收费票据（45xxx 续，G14）----
    CHARGE_RECEIPT_NOT_FOUND(45420, "未找到该收费票据"),
    CHARGE_RECEIPT_EXISTS(45421, "该收费单已有有效票据，请勿重复开票"),
    CHARGE_RECEIPT_STATUS_INVALID(45422, "当前票据状态不允许该操作（作废后不可重打）"),
    CHARGE_RECEIPT_VOID_REASON_REQUIRED(45423, "作废票据必须填写原因"),
    CHARGE_RECEIPT_CHARGE_NOT_PAID(45424, "只有已结算的收费单才能开票"),

    // ---- 单据打印留痕（45xxx 续，第 238 批）----
    /** 补打拦截：同一单号第 2 次及以后打印必须登记原因，前端据此弹出原因选择。 */
    PRINT_REASON_REQUIRED(45440, "该单据已打印过，补打必须登记原因"),
    /** 异常处置：判定“需整改”时必须写清楚要改什么，否则等于把问题推给下一个人。 */
    PRINT_ANOMALY_NOTE_REQUIRED(45441, "判定为需整改时必须填写处置说明"),

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

    // ---- 抗菌药物专项（43xxx 续，G33）----
    ANTIBIOTIC_APPROVAL_REQUIRED(43440, "该处方含特殊使用级抗菌药物，须先完成专项审批"),
    ANTIBIOTIC_APPROVAL_INVALID(43441, "该处方不含特殊使用级抗菌药物，无需专项审批"),
    ANTIBIOTIC_SELF_APPROVAL(43442, "不能审批自己开具的抗菌药物处方"),

    // ---- 静配中心（43xxx 续，G34）----
    PIVAS_ORDER_NOT_FOUND(43520, "未找到该静配单或成品输液标签"),
    PIVAS_ORDER_EXISTS(43521, "该处方已生成静配单，不能重复建单"),
    PIVAS_STATUS_INVALID(43522, "当前静配单状态不允许该操作"),
    PIVAS_PRESCRIPTION_INVALID(43523, "只有已审核通过的处方可以进入静配中心"),
    PIVAS_SELF_CHECK(43524, "复核人不能与配置人相同"),
    PIVAS_REJECT_REASON_REQUIRED(43525, "驳回静配单必须填写原因"),
    PIVAS_STOCK_INSUFFICIENT(43526, "静配药品批次余量不足，无法完成配置"),

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

    // ---- 耗材跨科室调拨（43xxx 续，G12）----
    DEPT_TRANSFER_NOT_FOUND(43510, "未找到该耗材调拨单"),
    DEPT_TRANSFER_ITEM_INVALID(43511, "调拨明细不合法（同一批次重复）"),
    DEPT_TRANSFER_STATUS_INVALID(43512, "当前调拨单状态不允许该操作"),
    DEPT_TRANSFER_STOCK_INSUFFICIENT(43513, "调出科室二级库余量不足，无法调拨"),
    DEPT_TRANSFER_REJECT_REASON_REQUIRED(43514, "拒收调拨必须填写原因"),
    DEPT_TRANSFER_CANCEL_REASON_REQUIRED(43515, "撤回调拨必须填写原因"),
    DEPT_TRANSFER_SAME_DEPT(43516, "调出科室与调入科室不能相同"),
    DEPT_TRANSFER_BATCH_NOT_FOUND(43517, "调拨明细中的批次不存在"),
    DEPT_TRANSFER_SELF_ACCEPT(43518, "不能接收或拒收自己提交的调拨单"),

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

    // ---- 设备质控与结构化报告（46xxx 续，G35）----
    TECH_DEVICE_NOT_FOUND(46430, "未找到该设备"),
    TECH_DEVICE_DUPLICATE(46431, "设备编号已存在，不能重复建档"),
    TECH_DEVICE_STATUS_INVALID(46432, "当前设备状态不允许该操作"),
    TECH_DEVICE_QC_INVALID(46433, "质控记录内容不合法（类型/结论/指标）"),
    TECH_REPORT_TEMPLATE_NOT_FOUND(46440, "未找到该结构化报告模板"),
    TECH_REPORT_TEMPLATE_DUPLICATE(46441, "模板编码已存在，不能重复创建"),
    TECH_REPORT_TEMPLATE_STATUS_INVALID(46442, "当前模板状态不允许该操作"),
    TECH_REPORT_TEMPLATE_INVALID(46443, "模板字段定义不合法（必须至少一个字段且 key 唯一）"),
    TECH_REPORT_STRUCTURED_INVALID(46444, "结构化报告内容不合法（模板未发布或必填字段缺失）"),
    TECH_LIS_MESSAGE_NOT_FOUND(46450, "未找到该 LIS 入站消息"),

    // ---- 设备维保工单（46xxx 续，G82）----
    TECH_WORK_ORDER_NOT_FOUND(46460, "未找到该维保工单"),
    TECH_WORK_ORDER_STATUS_INVALID(46461, "当前维保工单状态不允许该操作"),
    TECH_WORK_ORDER_INVALID(46462, "维保工单内容不合法（类型/优先级/标题/完工时间/费用）"),
    TECH_WORK_ORDER_CANCEL_REASON_REQUIRED(46463, "取消维保工单必须填写原因"),
    TECH_WORK_ORDER_SELF_ACCEPT(46464, "维保工单不能由建单人自己验收，请由设备科或质控复核"),

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
    /*
     * G79：首页快照里的诊断/手术/费用是 JSON。解析失败原先静默返回空集合，
     * 打印出来就是"这份首页没有诊断"，与真实数据损坏无法区分（同一份数据在
     * HQMS 上报路径是会显式报错的）。改为明确报错，提示核对来源数据。
     */
    EMR_HOMEPAGE_SNAPSHOT_INVALID(47406, "病案首页快照损坏，无法读取诊断/手术/费用明细，请核对来源数据"),
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
    INSURANCE_REALTIME_NOT_FOUND(48416, "未找到该实时结算交易"),
    INSURANCE_SCHEME_RATE_DUPLICATE(48417, "该生效日期已有费率记录，请直接修改方案或换一个日期"),
    INSURANCE_SCHEME_RATE_MISSING(48418, "所选日期没有生效的费率，请先配置该日期的费率"),
    INSURANCE_TEMPLATE_NOT_FOUND(48419, "未找到该报盘报文模板"),
    INSURANCE_TEMPLATE_DUPLICATE(48420, "报文模板编码已存在"),
    INSURANCE_TEMPLATE_PUBLISH_CONFLICT(48421, "已有生效中的报文模板，请先停用后再发布"),
    INSURANCE_TEMPLATE_INVALID(48422, "报文模板配置不合法（字段映射或必填口径有误）"),
    INSURANCE_TEMPLATE_REQUIRED_MISSING(48423, "报文模板必填字段在清单中取不到值，无法生成报盘"),
    INSURANCE_TEMPLATE_STATUS_INVALID(48424, "当前报文模板状态不允许该操作"),
    INSURANCE_GROUPER_RULE_SET_NOT_FOUND(48425, "未找到可用的分组规则包，请先导入并启用"),
    INSURANCE_GROUPER_RULE_INVALID(48426, "分组规则包内容不合法（层级/编码/优先级有误）"),
    INSURANCE_GROUPER_UNAVAILABLE(48427, "分组服务暂不可用，请稍后重试"),
    INSURANCE_GROUPER_RESULT_NOT_FOUND(48428, "未找到该就诊的分组结果"),

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
    KB_EMBEDDING_UNAVAILABLE(49420, "向量模型未配置，当前仅支持关键词检索"),

    // ---- 主数据与字典映射（50xxx，§269）----
    MDM_DOMAIN_NOT_FOUND(50401, "未找到该主数据域，或该域已停用"),
    MDM_ITEM_NOT_FOUND(50402, "未找到该主数据条目"),
    MDM_MAPPING_NOT_FOUND(50403, "未找到该外部编码映射"),
    MDM_CHANGE_REQUEST_NOT_FOUND(50404, "未找到该主数据变更申请"),
    MDM_IMPORT_NOT_FOUND(50405, "未找到该导入批次"),
    MDM_EXTERNAL_SYSTEM_NOT_FOUND(50406, "未找到该外部系统，或该系统已停用"),
    MDM_ITEM_DUPLICATE(50409, "该域下已存在相同本地编码的主数据条目"),
    MDM_MAPPING_DUPLICATE(50410, "该本地编码在此系统下已存在相同外部编码的映射"),
    MDM_CHANGE_REQUEST_STATUS_INVALID(50411, "当前变更申请状态不允许该操作"),
    MDM_CHANGE_REQUEST_SELF_REVIEW(50412, "不能审批自己提交的变更申请，请由其他数据治理员复核"),
    MDM_IMPORT_CHECKSUM_MISMATCH(50413, "导入内容与预检批次不一致，请重新预检后再应用"),
    MDM_CHANGE_REQUEST_INVALID(50414, "变更申请内容不合法（编码/字段/状态/目标条目有误）"),
    MDM_MAPPING_INVALID(50415, "映射内容不合法（外部系统、外部编码、映射类型或主映射冲突）"),
    MDM_IMPORT_INVALID(50416, "导入内容不合法（行数超限、域未知、编码重复或字段缺失）"),
    MDM_ITEM_STATUS_INVALID(50417, "当前条目状态不允许该操作（源同步条目由同步维护）"),
    MDM_SYNC_FAILED(50418, "源字典同步失败，请检查源服务与数据库权限后重试"),
    MDM_DOMAIN_NOT_SYNCABLE(50419, "该主数据域不支持从源表同步"),
    MDM_REASON_REQUIRED(50420, "变更或驳回必须填写原因（至少 2 个字符）"),
    MDM_ACTOR_REQUIRED(50421, "主数据变更必须带真实操作人，禁止匿名写入"),

    // ---- 历史数据迁移（51xxx，§270）----
    MIG_TARGET_NOT_FOUND(51401, "未找到该迁移目标，或该目标已停用"),
    MIG_BATCH_NOT_FOUND(51402, "未找到该迁移批次"),
    MIG_PROFILE_NOT_FOUND(51403, "未找到该列映射档案"),
    MIG_PROFILE_DUPLICATE(51404, "映射档案编码已存在"),
    MIG_BATCH_STATUS_INVALID(51405, "当前批次状态不允许该操作"),
    MIG_BATCH_HAS_BLOCKING_ROWS(51406, "批次存在错误或冲突行，整批拒绝应用；请修正源数据后重新预检"),
    MIG_TEMPLATE_INVALID(51407, "迁移模板不合法（缺少必填列或存在未知列）"),
    MIG_ROW_INVALID(51408, "迁移行内容不合法（类型/枚举/长度/引用/业务校验未通过）"),
    MIG_ROLLBACK_CONFLICT(51409, "批次应用后目标数据已被修改，拒绝回滚；请先核对差异再人工处理"),
    MIG_DEPENDENCY_ORDER_INVALID(51410, "批次顺序不符合目标依赖关系（必须先主数据、后患者、再业务历史）"),
    MIG_ACTOR_REQUIRED(51411, "迁移操作必须带真实操作人，禁止匿名写入"),
    MIG_PROFILE_INVALID(51412, "映射档案内容不合法（源列/目标字段/转换规则有误）"),
    MIG_PAYLOAD_UNREADABLE(51413, "迁移行加密载荷无法解密，请确认 EMPI_FIELD_KEY 未被更换"),
    MIG_RECONCILIATION_FAILED(51414, "迁移对账未通过：目标行数或金额与批次台账不一致"),
    MIG_REASON_REQUIRED(51415, "回滚必须填写原因（至少 2 个字符）"),

    // ---- 运营支撑（52xxx，§271）----
    OPS_SERVICE_NOT_FOUND(52401, "未找到该服务目录项，或该服务已停用"),
    OPS_TICKET_NOT_FOUND(52402, "未找到该工单"),
    OPS_TICKET_STATUS_INVALID(52403, "当前工单状态不允许该操作"),
    OPS_TICKET_TRANSITION_INVALID(52404, "工单状态流转不合法（例如未解决不能关闭）"),
    OPS_TICKET_ASSIGNEE_REQUIRED(52405, "派单或接单必须指定处理人"),
    OPS_TICKET_RESOLUTION_REQUIRED(52406, "解决工单必须填写处理结论（至少 2 个字符）"),
    OPS_TICKET_VERSION_CONFLICT(52407, "工单已被他人更新，请刷新后重试"),
    OPS_SLA_POLICY_NOT_FOUND(52408, "该服务与优先级没有可用的 SLA 策略"),
    OPS_CALENDAR_NOT_FOUND(52409, "未找到营业日历"),
    OPS_SLA_INVALID(52410, "SLA 策略不合法（时限必须为正，营业时间模式必须绑定日历）"),
    OPS_CALENDAR_INVALID(52411, "营业日历不合法（工作日不能为空，结束时间必须晚于开始时间）"),
    OPS_ACTOR_REQUIRED(52412, "运营操作必须带真实操作人，禁止匿名写入"),
    OPS_REASON_REQUIRED(52413, "挂起、重开或取消必须填写原因（至少 2 个字符）"),
    OPS_TICKET_PAUSED(52414, "工单处于挂起状态，请先恢复后再处理"),
    OPS_SLA_SCAN_FAILED(52415, "SLA 超时扫描失败，请检查数据库后重试"),
    OPS_ROTATION_NOT_FOUND(52416, "未找到该值班轮换组"),
    OPS_ROTATION_INVALID(52417, "值班轮换配置不合法（成员/周期/起始日期有误，或组编码已存在）"),
    OPS_SHIFT_NOT_FOUND(52418, "未找到该值班班次"),
    OPS_INCIDENT_NOT_FOUND(52419, "未找到该重大事件"),
    OPS_INCIDENT_STATUS_INVALID(52420, "当前事件状态不允许该操作"),
    OPS_INCIDENT_TRANSITION_INVALID(52421, "事件状态流转不合法"),
    OPS_POSTMORTEM_NOT_FOUND(52422, "未找到该事件复盘"),
    OPS_POSTMORTEM_SELF_REVIEW(52423, "复盘作者不能审批自己的复盘，请由其他负责人复核"),
    OPS_POSTMORTEM_STATUS_INVALID(52424, "当前复盘状态不允许该操作"),
    OPS_POSTMORTEM_INCOMPLETE(52425, "复盘提交前必须填写根因与至少一条纠正措施"),
    OPS_INCIDENT_INVALID(52426, "事件内容不合法（影响服务为空或关联工单不存在）"),
    OPS_INTEGRATION_SOURCE_NOT_FOUND(52427, "未找到该告警接入源，或该接入源已停用"),
    OPS_INTEGRATION_PAYLOAD_INVALID(52428, "告警格式不合法（缺少 alerts/fingerprint/status）"),
    /** 接入令牌错误：401 段，网关白名单放行后由服务自身校验。 */
    OPS_INTEGRATION_TOKEN_INVALID(40129, "接入令牌无效或已轮换，请更新告警平台配置"),

    // ---- 备份与灾备（52xxx 续，§297）----
    /*
     * 备份与演练是发布门禁的证据链：缺记录、状态不对、时限不达标都要给稳定码，
     * 否则发布页只能靠中文文案判断"为什么被拦"。
     */
    OPS_BACKUP_POLICY_NOT_FOUND(52429, "未找到该备份策略，或该策略已停用"),
    OPS_BACKUP_POLICY_INVALID(52430, "备份策略不合法（范围/计划/保留/加密/RPO/负责人有误）"),
    OPS_BACKUP_RUN_NOT_FOUND(52431, "未找到该备份运行记录"),
    OPS_BACKUP_RUN_INVALID(52432, "备份运行内容不合法（类型/时间/大小/校验和/位置有误）"),
    OPS_BACKUP_RUN_CONFLICT(52433, "同一幂等键的备份运行已结束，结果不可改写"),
    OPS_DR_DRILL_NOT_FOUND(52434, "未找到该灾备演练"),
    OPS_DR_STATUS_INVALID(52435, "当前演练状态不允许该操作"),
    OPS_DR_TRANSITION_INVALID(52436, "演练状态流转不合法（PLANNED → RUNNING → PASSED/FAILED/ABORTED）"),
    OPS_DR_STEPS_INCOMPLETE(52437, "仍有必填演练步骤未通过，不能判定演练通过"),
    OPS_DR_TARGET_EXCEEDED(52438, "实测 RTO/RPO 超过目标值，不能判定演练通过"),
    OPS_DR_SELF_VERIFY(52439, "演练执行人不能自行复核通过，请由另一名负责人复核"),
    OPS_DR_EVIDENCE_REQUIRED(52440, "演练通过或步骤通过必须填写证据（日志/截图/还原结果）"),
    OPS_DR_INVALID(52441, "演练内容不合法（范围/环境/目标/关联备份/步骤有误）"),

    // ---- 实施交付与培训考核（52xxx 续，§298）----
    IMPL_TEMPLATE_NOT_FOUND(52442, "未找到该实施模板，或模板已停用"),
    IMPL_TEMPLATE_INVALID(52443, "实施模板不合法（阶段权重之和必须为 100、任务为空或编号重复）"),
    IMPL_PROJECT_NOT_FOUND(52444, "未找到该实施项目"),
    IMPL_PROJECT_STATUS_INVALID(52445, "当前实施项目状态不允许该操作"),
    IMPL_PROJECT_TRANSITION_INVALID(52446, "实施项目状态流转不合法（计划 → 实施 → 上线 → 验收，可取消）"),
    IMPL_TASK_NOT_FOUND(52447, "未找到该实施任务"),
    IMPL_TASK_STATUS_INVALID(52448, "当前实施任务状态不允许该操作"),
    IMPL_TASK_EVIDENCE_REQUIRED(52449, "任务完成必须填写证据（签字件/截图/报告地址）"),
    IMPL_TASK_BLOCKED_REASON_REQUIRED(52450, "标记任务阻塞必须填写原因"),
    IMPL_TASK_WAIVER_REASON_REQUIRED(52451, "任务豁免必须填写原因"),
    IMPL_NOT_READY(52452, "实施项目尚未达到上线就绪条件（必填任务或培训通过率未达标）"),
    IMPL_SELF_APPROVAL(52453, "项目负责人不能自己批准上线或验收，请由另一名负责人复核"),
    IMPL_COURSE_NOT_FOUND(52454, "未找到该培训课程，或课程已停用"),
    IMPL_COURSE_INVALID(52455, "培训课程或题库不合法（选项/答案/分值有误）"),
    IMPL_EXAM_NOT_FOUND(52456, "未找到该考试"),
    IMPL_EXAM_INVALID(52457, "考试不合法（课程未启用、重复开考或及格分有误）"),
    IMPL_ATTEMPT_INVALID(52458, "考试成绩不合法（答题不完整或选项越界）"),
    IMPL_ATTEMPT_CLOSED(52459, "考试已关闭，不能再录入成绩"),

    // ---- 版本与补丁（53xxx，§273）----
    REL_COMPONENT_NOT_FOUND(53401, "未找到该发布组件"),
    REL_VERSION_NOT_FOUND(53402, "未找到该组件版本"),
    REL_VERSION_DUPLICATE(53403, "该组件已存在相同版本号"),
    REL_TRAIN_NOT_FOUND(53404, "未找到该发布列车"),
    REL_TRAIN_STATUS_INVALID(53405, "当前列车状态不允许该操作"),
    REL_TRAIN_ITEM_INVALID(53406, "列车车厢不合法（组件/版本不匹配、版本未发布或已作废）"),
    REL_COMPATIBILITY_CONFLICT(53407, "发布列车存在兼容性冲突，不能冻结或发布"),
    REL_PATCH_NOT_FOUND(53408, "未找到该补丁"),
    REL_PATCH_STATUS_INVALID(53409, "当前补丁状态不允许该操作"),
    REL_PATCH_SELF_REVIEW(53410, "补丁提交人不能审批自己的补丁，请由其他负责人复核"),
    REL_FREEZE_ACTIVE(53411, "当前处于变更冻结窗口，禁止发布或部署"),
    REL_CHECKLIST_INCOMPLETE(53412, "生产部署检查单不完整，请完成备份/回滚方案/冒烟/值班/变更单"),
    REL_DEPLOYMENT_NOT_FOUND(53413, "未找到该部署记录"),
    REL_ROLLBACK_NO_TARGET(53414, "没有可回滚的上一次成功部署"),
    REL_VERSION_STATUS_INVALID(53415, "当前版本状态不允许该操作"),
    REL_ACTOR_REQUIRED(53416, "发布操作必须带真实操作人，禁止匿名写入"),
    REL_RELEASE_INVALID(53417, "发布内容不合法（版本号/时间/组件/检查单有误）"),
    REL_TRAIN_DUPLICATE(53418, "发布列车编码已存在"),
    REL_PATCH_INVALID(53419, "补丁内容不合法（影响版本为空或修复版本不属于该组件）"),
    REL_WINDOW_NOT_FOUND(53420, "未找到该变更窗口"),
    REL_WINDOW_INVALID(53421, "变更窗口不合法（结束时间必须晚于开始时间）"),
    REL_CI_SOURCE_NOT_FOUND(53422, "未找到该 CI 接入源，或该接入源已停用"),
    REL_CI_PAYLOAD_INVALID(53424, "CI 事件格式不合法（kind/component/version/result 有误）"),
    REL_CI_TOKEN_INVALID(40131, "CI 接入令牌无效或已轮换，请更新流水线密钥"),
    /** 生产部署门禁：没有有效期内的已复核灾备演练，不允许上线。 */
    REL_DR_DRILL_REQUIRED(53425, "缺少有效期内的灾备演练通过记录（需已复核），生产部署被拒绝"),

    // ---- 授权与许可（54xxx，§279）----
    LICENSE_NOT_FOUND(54401, "未找到该授权书"),
    LICENSE_SIGNATURE_INVALID(54402, "授权书签名校验失败：内容可能被篡改或签发密钥不匹配"),
    LICENSE_INSTANCE_MISMATCH(54403, "授权书与当前部署实例不匹配，请在目标院区重新签发"),
    LICENSE_PAYLOAD_INVALID(54404, "授权书内容不合法（版本号/模块/期限/配额有误）"),
    LICENSE_DUPLICATE(54409, "该授权编号已导入过，请核对后使用新编号签发"),
    LICENSE_STATUS_INVALID(54405, "当前授权状态不允许该操作"),
    LICENSE_ISSUANCE_DISABLED(54406, "本部署未开启签发能力，请由厂商签发后在目标院区导入"),
    LICENSE_ACTOR_REQUIRED(54410, "授权变更必须带真实操作人，禁止匿名写入"),
    LICENSE_KEY_UNAVAILABLE(54408, "授权验签密钥不可用，请检查 LICENSE_VERIFY_KEY 配置"),
    LICENSE_USAGE_INVALID(54411, "用量快照不合法（日期/计数不能为负）"),

    // ---- 开放平台客户端（55xxx，§280）----
    OAUTH_CLIENT_NOT_FOUND(55401, "未找到该开放平台客户端"),
    OAUTH_CLIENT_INVALID(55402, "客户端参数不合法（名称/范围/有效期有误）"),
    OAUTH_SCOPE_NOT_ALLOWED(55403, "申请的范围不在开放平台可授予目录内（第三方只允许只读范围）"),
    OAUTH_CLIENT_STATUS_INVALID(55404, "当前客户端状态不允许该操作"),
    OAUTH_SECRET_INVALID(40132, "客户端密钥不正确"),
    OAUTH_CLIENT_DISABLED(40133, "客户端已停用或已过期，请联系平台管理员"),

    // ---- IHE XDS 文档共享（57xxx，§295 / 计划书 W3.5）----
    /*
     * 文档共享是"对外协议"而不是业务写入：唯一标识重复、内容哈希不符、检索过宽
     * 都必须给出可机器判断的稳定码，区域平台才能按码补偿，而不是读中文文案猜。
     */
    XDS_DOCUMENT_NOT_FOUND(57401, "未找到该共享文档"),
    XDS_METADATA_INVALID(57402, "共享文档元数据不合法（唯一标识/类型/格式/时间/保密级别有误）"),
    XDS_CONTENT_INVALID(57403, "共享文档内容不合法（为空、超限或格式不支持）"),
    XDS_HASH_MISMATCH(57404, "共享文档哈希校验失败，内容与登记信息不一致"),
    XDS_SIZE_MISMATCH(57405, "共享文档大小与登记信息不一致"),
    XDS_STATUS_INVALID(57406, "当前共享文档状态不允许该操作"),
    XDS_QUERY_TOO_BROAD(57407, "检索条件过宽：至少提供患者、唯一标识或时间范围之一"),
    XDS_SOURCE_NOT_FOUND(57408, "未找到生成共享文档所需的来源数据"),
    XDS_DOCUMENT_DUPLICATE(57409, "该文档唯一标识已注册，请勿重复提交"),
    XDS_SOURCE_INVALID(57410, "来源数据不满足生成条件（如报告未签发、就诊未住院）"),
    XDS_ACTOR_REQUIRED(57411, "共享文档操作必须带真实操作人，禁止匿名写入"),

    // ---- 租户与实例隔离（56xxx，§281 / ADR-001 全院级全栈隔离）----
    /*
     * 租户 = 一家医院（独立 Cell），院区 = 同一医院内的多院区。
     * 台账只存元数据，不存患者数据；临床数据面按 Cell 物理隔离，不存在跨租户查询路径。
     */
    TENANT_NOT_FOUND(56401, "未找到该租户，或该租户尚未开通"),
    TENANT_INVALID(56402, "租户参数不合法（编码/实例码/端点/配额有误）"),
    TENANT_STATUS_INVALID(56403, "当前租户状态不允许该操作"),
    TENANT_DUPLICATE(56409, "租户编码或实例码已存在，请核对后重试"),
    /** 403 段：租户被停用后，全院禁止登录；已签发的令牌由网关按入口拦截。 */
    TENANT_SUSPENDED(40320, "该院区已停用，请联系平台运营"),
    TENANT_MISMATCH(40321, "访问入口与登录租户不一致，请从本院入口登录"),
    TENANT_INSTANCE_MISMATCH(40322, "登录凭证不属于当前部署实例，请从本院入口重新登录");

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
