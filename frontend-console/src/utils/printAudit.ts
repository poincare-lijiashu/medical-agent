/**
 * 打印留痕上报（第 230 批）。
 *
 * 打印窗口成功打开后调用一次，交给 Java 侧 `POST /clinical/print-events` 落库：
 * 谁、何时、打了哪张单。补打次数与冒打追查都以此为准。
 *
 * 关键约束：
 * - **失败必须静默**：纸已经出来了，上报失败不能反过来提示用户"打印失败"；
 * - 不阻塞打印：调用方 `void recordPrint(...)` 即可，不 await；
 * - 类型集中在这里，避免各页面把 `docType` 写成 `receipt` / `RECEIPT` 两种口径。
 */
import { post } from '@/api/http'

export const PRINT_DOC_TYPES = {
  receipt: 'RECEIPT',
  consent: 'CONSENT',
  prescription: 'PRESCRIPTION',
  pivasCard: 'PIVAS_CARD',
  inpatientFinance: 'INPATIENT_FINANCE',
  wristband: 'WRISTBAND',
} as const

export interface PrintAuditInput {
  docType: keyof typeof PRINT_DOC_TYPES
  /** 打印件上的业务号（票据号 / 处方号 / 住院号）。 */
  docNo: string
  targetType?: 'RECEIPT' | 'CONSENT' | 'PRESCRIPTION' | 'PIVAS' | 'ADMISSION'
  targetId?: string
  patientNo?: string
}

export function recordPrint(input: PrintAuditInput): void {
  if (!input.docNo) return
  void post('/clinical/print-events', {
    docType: PRINT_DOC_TYPES[input.docType],
    docNo: input.docNo,
    targetType: input.targetType,
    targetId: input.targetId,
    patientNo: input.patientNo,
  }).catch(() => {
    /* 留痕失败不影响已完成的打印；服务端仍可从网关访问日志追溯 */
  })
}
