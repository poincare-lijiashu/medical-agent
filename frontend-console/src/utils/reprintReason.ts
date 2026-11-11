import { reactive } from 'vue'

/**
 * 补打原因弹窗（第 238 批）。
 *
 * 为什么由「打印动作」异步触发，而不是打印前先问：
 * 打印窗口已经打开、纸已经出来之后，才知道这是第几次打印（判定在服务端）。
 * 因此上报被服务端以 422 拒绝时，这里补问原因并重发一条——**不修改已写入的记录**，
 * 留痕表仍是纯追加。
 *
 * 预设原因是给忙碌场景用的：护士在护士站不会手打一整句话；
 * 但同时保留「未说明（仅留痕）」，让不想/不便说明的人有出口，
 * 质控端按“未说明”单独计数——有出口才不会逼出假原因。
 */
export const REPRINT_REASONS = [
  '打印质量问题（卡纸/缺字/墨迹不清）',
  '患者或家属要求重新打印',
  '单据遗失补打',
  '信息变更后重新出具',
  '科室留存或归档',
  '未说明（仅留痕）',
] as const

export interface ReprintAsk {
  docType: string
  docNo: string
  /** 服务端拒绝时的原文（含“已打印 N 次”），直接展示，避免前端另算一遍。 */
  message: string
}

interface Pending extends ReprintAsk {
  resolve: (reason: string) => void
}

/** 同单号连续打印、整区批量打印会并发触发：排队逐条问，弹窗不叠层。 */
const queue: Pending[] = []
let active: ((reason: string) => void) | null = null

/** 弹窗展示态：组件只读，写入只发生在 ask/answer 内部。 */
export const reprintPrompt = reactive({
  open: false,
  docType: '',
  docNo: '',
  message: '',
})

function showNext(): void {
  const item = queue.shift()
  if (!item) {
    reprintPrompt.open = false
    active = null
    return
  }
  active = item.resolve
  reprintPrompt.docType = item.docType
  reprintPrompt.docNo = item.docNo
  reprintPrompt.message = item.message
  reprintPrompt.open = true
}

/** 请求一次补打原因；resolve 的结果必为非空字符串（弹窗不给“什么都不填”的出口）。 */
export function askReprintReason(ask: ReprintAsk): Promise<string> {
  return new Promise<string>((resolve) => {
    queue.push({ ...ask, resolve })
    if (!active) showNext()
  })
}

/** 弹窗确认：交回原因并继续问下一条（若有）。 */
export function answerReprintReason(reason: string): void {
  const done = active
  active = null
  reprintPrompt.open = false
  done?.(reason)
  showNext()
}
