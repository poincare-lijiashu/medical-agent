/**
 * 打印留痕上报（第 230 批，第 238 批补打原因）。
 *
 * 打印窗口成功打开后调用一次，交给 Java 侧 `POST /clinical/print-events` 落库：
 * 谁、何时、打了哪张单。补打次数与冒打追查都以此为准。
 *
 * 关键约束：
 * - **失败必须静默**：纸已经出来了，上报失败不能反过来提示用户"打印失败"；
 * - 不阻塞打印：调用方 `void recordPrint(...)` 即可，不 await；
 * - 类型集中在这里，避免各页面把 `docType` 写成 `receipt` / `RECEIPT` 两种口径。
 *
 * 补打原因（第 238 批）：同一单号第 2 次及以后，服务端会以 422 拒绝并要求原因。
 * 这里捕获该错误码、弹一次全局原因弹窗，然后**重发一条**（不改原记录），
 * 所以 7 个打印入口一行都不用改——原因登记属于留痕通道的职责，不是业务页的职责。
 */
import { ApiError, post } from '@/api/http'
import type { PrintEvent } from '@/api/types'
import { askReprintReason } from '@/utils/reprintReason'

export const PRINT_DOC_TYPES = {
  receipt: 'RECEIPT',
  consent: 'CONSENT',
  prescription: 'PRESCRIPTION',
  pivasCard: 'PIVAS_CARD',
  inpatientFinance: 'INPATIENT_FINANCE',
  wristband: 'WRISTBAND',
  nursingSheet: 'NURSING_SHEET',
} as const

/** 与留痕页、原因弹窗同一套中文口径（弹窗标题用）。 */
export const PRINT_DOC_LABELS: Record<(typeof PRINT_DOC_TYPES)[keyof typeof PRINT_DOC_TYPES], string> = {
  RECEIPT: '收费票据',
  CONSENT: '知情同意书',
  PRESCRIPTION: '处方笺',
  PIVAS_CARD: '静配输液卡',
  INPATIENT_FINANCE: '住院费用汇总单',
  WRISTBAND: '住院腕带',
  NURSING_SHEET: '护理执行单',
}

/** 服务端错误码：补打未登记原因（ErrorCode.PRINT_REASON_REQUIRED）。 */
const REPRINT_REASON_REQUIRED = 45440

/**
 * 同类型补打的原因复用窗口。
 *
 * <p>整区批量打印一次会给每张护理执行单各上报一条；第 2 次整区打印时
 * 若逐单弹窗，护士要连点同一原因几十次。同一人、同一单据类型、60 秒内
 * 视为同一次补打操作，复用刚选的原因；换类型或超窗口必须重新选择。
 */
const REASON_REUSE_MS = 60_000
let recentReason: { docType: string; reason: string; at: number } | null = null

export interface PrintAuditInput {
  docType: keyof typeof PRINT_DOC_TYPES
  /** 打印件上的业务号（票据号 / 处方号 / 住院号）。 */
  docNo: string
  targetType?: 'RECEIPT' | 'CONSENT' | 'PRESCRIPTION' | 'PIVAS' | 'ADMISSION'
  targetId?: string
  patientNo?: string
  /** 补打原因；首次打印不传，补打时由通道弹窗补齐。 */
  reason?: string
}

export function recordPrint(input: PrintAuditInput): void {
  if (!input.docNo) return
  void send(input, true).catch(() => {
    /* 兜底：任何异常都不得冒泡到业务页 */
  })
}

/**
 * 上报一次；`mayAskReason` 只对首次上报为 true，防止服务端持续拒绝时无限弹窗。
 * 补打原因重发仍失败时静默放弃：这一单会以“未说明/未留痕”暴露在质控端，
 * 但绝不能让已经出纸的操作在界面上变成“打印失败”。
 */
async function send(input: PrintAuditInput, mayAskReason: boolean): Promise<void> {
  try {
    await post<PrintEvent>('/clinical/print-events', {
      docType: PRINT_DOC_TYPES[input.docType],
      docNo: input.docNo,
      targetType: input.targetType,
      targetId: input.targetId,
      patientNo: input.patientNo,
      reason: input.reason,
    })
  } catch (e) {
    if (mayAskReason && e instanceof ApiError && e.code === REPRINT_REASON_REQUIRED) {
      const code = PRINT_DOC_TYPES[input.docType]
      const reused =
        recentReason && recentReason.docType === code && Date.now() - recentReason.at < REASON_REUSE_MS
          ? recentReason.reason
          : null
      if (reused) {
        await send({ ...input, reason: reused }, false)
        return
      }
      const reason = await askReprintReason({
        docType: code,
        docNo: input.docNo,
        message: e.message,
      })
      recentReason = { docType: code, reason, at: Date.now() }
      await send({ ...input, reason }, false)
      return
    }
    /* 留痕失败不影响已完成的打印；服务端仍可从网关访问日志追溯 */
  }
}
