/**
 * 状态展示元数据（P1-F4 / P1-F6）。
 *
 * <p>为什么从 StatusPill 里抽出来：同一个状态码在不同业务域可能是完全不同的意思
 * （{@code ACCEPTED} 在会诊是「会诊中」，在医保报盘是「已受理」）。此前所有域共用一张
 * 全局映射表，医保报盘只能在自己的组件里另建一套映射，两边文案迟早漂移。
 *
 * <p>这里按域分层：{@link BASE} 放全平台语义一致的状态，{@link DOMAIN_OVERRIDES}
 * 只放"同名不同义"的例外。新增域时优先找 BASE 是否已有，避免再造一套。
 */
export type Tone = 'ok' | 'warn' | 'danger' | 'accent' | 'neutral'

export type StatusDomain =
  | 'clinical'
  | 'pharmacy'
  | 'tech'
  | 'emr'
  | 'insurance'
  | 'insurance-export'
  | 'insurance-realtime'

export interface StatusMeta {
  label: string
  tone: Tone
}

/** 全平台语义一致的状态（同一状态码在各域含义相同）。 */
const BASE: Record<string, StatusMeta> = {
  // 就诊
  REGISTERED: { label: '待接诊', tone: 'warn' },
  IN_TREATMENT: { label: '接诊中', tone: 'accent' },
  FINISHED: { label: '已结束', tone: 'ok' },
  CANCELLED: { label: '已取消', tone: 'neutral' },
  // 住院
  ADMITTED: { label: '待入科', tone: 'warn' },
  IN_HOSPITAL: { label: '在院', tone: 'accent' },
  DISCHARGED: { label: '已出院', tone: 'neutral' },
  PATIENT_ACTIVE: { label: '有效档案', tone: 'ok' },
  ACTIVE: { label: '启用', tone: 'ok' },
  MAINTENANCE: { label: '维护中', tone: 'warn' },
  INACTIVE: { label: '停用', tone: 'neutral' },
  // 医嘱
  PLACED: { label: '已下达', tone: 'accent' },
  CHARGED: { label: '已计费', tone: 'ok' },
  STOPPED: { label: '已停嘱', tone: 'neutral' },
  REJECTED: { label: '已驳回', tone: 'danger' },
  // 收费
  UNPAID: { label: '未结算', tone: 'warn' },
  PAID: { label: '已结算', tone: 'ok' },
  VOID: { label: '已作废', tone: 'neutral' },
  // 病案首页
  DRAFT: { label: '草稿', tone: 'warn' },
  SUBMITTED: { label: '已提交', tone: 'accent' },
  ARCHIVED: { label: '已归档', tone: 'ok' },
  // 手术
  SCHEDULED: { label: '已排台', tone: 'accent' },
  PLANNED: { label: '已计划', tone: 'warn' },
  IN_PROGRESS: { label: '进行中', tone: 'accent' },
  COMPLETED: { label: '已完成', tone: 'ok' },
  NOT_REQUIRED: { label: '无需审批', tone: 'neutral' },
  PENDING: { label: '待审批', tone: 'warn' },
  APPROVED: { label: '已放行', tone: 'ok' },
  // 麻精专册
  PENDING_RETURN: { label: '待回收空安瓿', tone: 'warn' },
  RETURNED: { label: '已回收', tone: 'ok' },
  // 护理执行
  EXECUTED: { label: '已执行', tone: 'ok' },
  REFUSED: { label: '患者拒绝', tone: 'warn' },
  OMITTED: { label: '未执行', tone: 'danger' },
  // 会诊
  REQUESTED: { label: '待受理', tone: 'warn' },
  ACCEPTED: { label: '会诊中', tone: 'accent' },
  // 知识库
  READY: { label: '已索引', tone: 'ok' },
  INDEXING: { label: '索引中', tone: 'warn' },
  FAILED: { label: '索引失败', tone: 'danger' },
  // 药房
  PENDING_REVIEW: { label: '待审核', tone: 'warn' },
  DISPENSED: { label: '已发药', tone: 'ok' },
  PARTIAL_RETURNED: { label: '部分退回', tone: 'warn' },
  EXPIRED: { label: '已过期', tone: 'danger' },
  NEAR_EXPIRY: { label: '近效期', tone: 'warn' },
  // 医技
  APPLIED: { label: '已开立', tone: 'warn' },
  COLLECTED: { label: '已采集', tone: 'accent' },
  SPECIMEN_COLLECTED: { label: '已采集', tone: 'accent' },
  RECEIVED: { label: '已接收', tone: 'accent' },
  REPORTED: { label: '已出报告', tone: 'ok' },
  CLOSED: { label: '已关闭', tone: 'neutral' },
  ACKNOWLEDGED: { label: '已确认', tone: 'ok' },
  FINAL: { label: '已签发', tone: 'ok' },
  AMENDED: { label: '已修订', tone: 'accent' },
  // 影像阅片
  CLAIMED: { label: '阅片中', tone: 'accent' },
  RELEASED: { label: '已释放', tone: 'neutral' },
  // 医保
  CONFIRMED: { label: '已确认', tone: 'ok' },
  VOIDED: { label: '已作废', tone: 'neutral' },
  DISABLED: { label: '已停用', tone: 'neutral' },
  REFUNDED: { label: '已退费', tone: 'neutral' },
  // 通用
  SIGNED: { label: '已签署', tone: 'ok' },
  FIT: { label: '可耐受', tone: 'ok' },
  FIT_WITH_CAUTION: { label: '谨慎评估', tone: 'warn' },
  UNFIT: { label: '不宜手术', tone: 'danger' },
}

/** 只列"同名不同义"的例外，避免整张表复制。 */
const DOMAIN_OVERRIDES: Partial<Record<StatusDomain, Record<string, StatusMeta>>> = {
  // 医保报盘批次：ACCEPTED 是省局回执「已受理」，不是会诊「会诊中」
  'insurance-export': {
    GENERATED: { label: '待回执', tone: 'warn' },
    ACCEPTED: { label: '已受理', tone: 'ok' },
    REJECTED: { label: '已退回', tone: 'danger' },
    CANCELLED: { label: '已撤销', tone: 'neutral' },
  },
  // 医保实时结算：PENDING 是「平台处理中」，不是审批待办
  'insurance-realtime': {
    PENDING: { label: '待受理', tone: 'warn' },
    SETTLED: { label: '已结算', tone: 'ok' },
    FAILED: { label: '结算失败', tone: 'danger' },
  },
  // 药房采购：RECEIVED 指"采购明细全部到货"，不是"标本已接收"
  pharmacy: {
    PARTIAL: { label: '部分到货', tone: 'warn' },
    RECEIVED: { label: '已到齐', tone: 'ok' },
    SUSPENDED: { label: '已停用', tone: 'danger' },
  },
}

const FALLBACK: StatusMeta = { label: '—', tone: 'neutral' }

/** 取状态展示元数据；域内例外优先于全局语义。 */
export function statusMeta(domain: StatusDomain, value?: string | null): StatusMeta {
  const key = (value ?? '').toUpperCase()
  const override = DOMAIN_OVERRIDES[domain]?.[key]
  if (override) {
    return override
  }
  return BASE[key] ?? (value ? { label: value, tone: 'neutral' } : FALLBACK)
}

/** 只要文案（Toast / 导出文件名等非徽标场景）。 */
export function statusLabel(domain: StatusDomain, value?: string | null): string {
  return statusMeta(domain, value).label
}
