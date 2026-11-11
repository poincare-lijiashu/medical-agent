/**
 * 跨域审计的动作/对象中文口径（第 242 批）。
 *
 * 设计取舍：
 * - **不穷举整串**：各域动作是 `名词_动词`（`RECORD_SIGN` / `USER_DISABLE` / `PACS_READING_CLAIM`），
 *   整串映射表很快会漏；这里按「动词表 + 名词表」拼装，新增普通动作天然可读；
 * - **不猜**：名词或动词任一未识别就返回原始 action，界面上同时保留原文，
 *   宁可露出 `FOO_BAR` 也不要把“停用账号”错写成“删除账号”；
 * - 少数动宾组合有固定说法（如 `PRINT_ANOMALY_REVIEW`）放整串特例。
 */

/** 动词表：取 action 最后一个下划线之后的部分。 */
const VERBS: Record<string, string> = {
  CREATE: '新建',
  UPSERT: '维护',
  UPDATE: '修改',
  DELETE: '删除',
  REMOVE: '移除',
  VIEW: '查看',
  SEARCH: '查询',
  LIST: '查看列表',
  EXPORT: '导出',
  IMPORT: '导入',
  PRINT: '打印',
  SIGN: '签署',
  ARCHIVE: '归档',
  WITHDRAW: '撤回',
  AMEND: '修改',
  CANCEL: '取消',
  VOID: '作废',
  CONFIRM: '确认',
  SUBMIT: '提交',
  APPROVE: '通过审核',
  REJECT: '驳回',
  ENABLE: '启用',
  DISABLE: '停用',
  RESET: '重置',
  ASSIGN: '分配',
  REGISTER: '登记',
  ACK: '确认',
  CLAIM: '认领',
  RELEASE: '释放',
  COMPLETE: '完成',
  REPLAY: '重放',
  MERGE: '合并',
  PUBLISH: '发布',
  GENERATE: '生成',
  CAPTURE: '采集',
  PUSH: '推送',
  ADOPT: '采纳',
  DISTILL: '提炼',
  REVIEW: '核查',
  DOWNLOAD: '下载',
  UPLOAD: '上传',
}

/** 名词表：取 action 动词之前的部分。 */
const NOUNS: Record<string, string> = {
  PRINT_ANOMALY: '补打异常',
  PRINT_EVENT: '打印留痕',
  USER: '账号',
  ROLE: '角色',
  ORG: '科室',
  CAMPUS: '院区',
  SESSION: '会话',
  PATIENT: '患者',
  VISIT: '就诊',
  ADMISSION: '住院登记',
  WARD: '病区',
  ORDER: '医嘱',
  PRESCRIPTION: '处方',
  DISPENSE: '发药',
  DRUG: '药品',
  CHARGE: '收费单',
  RECEIPT: '票据',
  SETTLEMENT: '结算',
  BILL: '结算单',
  REPORT: '报告',
  RECORD: '病历',
  CONSENT: '知情同意书',
  DEVICE: '设备',
  APPLICATION: '申请单',
  SPECIMEN: '标本',
  PACS_STUDY: '影像检查',
  PACS_READING: '影像报告',
  TRANSFUSION: '输血',
  OPERATION: '手术',
  PATHWAY: '临床路径',
  KB_SPACE: '知识库',
  TEMPLATE: '模板',
  EXPORT_BATCH: '报盘批次',
  PAYMENT_GROUPING: '结算分组',
  INSURANCE_ITEM: '医保项目',
  MEMBER: '参保人',
  SIGN_POLICY: '签名策略',
  CRITICAL: '危急值',
  DEVICE_WORK_ORDER: '设备工单',
  BOT: 'Bot',
  AGENT: '智能体',
  SKILL: '技能卡',
}

/** 整串特例：动宾组合有专门说法，逐词拼会别扭。 */
const EXACT: Record<string, string> = {
  PRINT_ANOMALY_REVIEW: '核查补打异常',
  PATIENT_VIEW_PHI: '查看患者隐私信息',
  PATIENT_SEARCH: '检索患者',
  RECORD_SEARCH: '检索病历',
  RECORD_PRINT: '打印病历',
  RECORD_SIGN: '签署病历',
  USER_PASSWORD_RESET: '重置账号密码',
  CRITICAL_ACK: '确认危急值',
  VOLUME_DISABLE: '停用批量业务',
}

/** 审计来源（schema 标签）→ 域中文名。 */
const SOURCES: Record<string, string> = {
  auth: '账号与权限',
  empi: '患者主索引',
  clinical: '临床',
  pharmacy: '药事',
  tech: '医技',
  emr: '病案',
  insurance: '医保',
}

/** 动作中文名；未识别返回原文（调用方应同时展示原文）。 */
export function actionLabel(action?: string | null): string {
  const key = (action || '').trim()
  if (!key) return '—'
  const exact = EXACT[key]
  if (exact) return exact
  const idx = key.lastIndexOf('_')
  if (idx <= 0) return key
  const verb = VERBS[key.slice(idx + 1)]
  const nounKey = key.slice(0, idx)
  // 名词可能是多词（PACS_READING_CLAIM）：先整段命中，再退到最后两词、首词
  const parts = nounKey.split('_')
  const noun =
    NOUNS[nounKey] ??
    NOUNS[parts.slice(-2).join('_')] ??
    NOUNS[parts[0]]
  return verb && noun ? `${verb}${noun}` : key
}

/** 对象类型中文名；未识别返回原文。 */
export function targetTypeLabel(targetType?: string | null): string {
  const key = (targetType || '').trim()
  if (!key) return '—'
  return NOUNS[key] ?? key
}

/** 审计来源域中文名（来自哪个服务的 change_log）。 */
export function auditSourceLabel(source?: string | null): string {
  const key = (source || '').trim()
  if (!key) return '—'
  return SOURCES[key] ?? key
}
