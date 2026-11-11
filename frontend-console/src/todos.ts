/**
 * 全局任务待办（第 220 批；第 239 批新增补打异常待核查）。
 *
 * 只聚合“本人有权限、且有明确处理入口”的事项，计数全部走服务端 summary 接口：
 * - AI 待核对：`review/pending` 的 total；
 * - 会诊待我填：`consults/inbox` 的 pendingForMe（silent 轮询不写读审计）；
 * - 我的处方被驳回：`prescriptions/mine` 的 counts.rejected；
 * - 补打异常待核查：`print-events/anomalies/summary` 的 pending（默认近 30 天）。
 *
 * 失败用 `failed=true` 表示，**不能**退化成 0（0 是“没有待办”的业务结论）。
 */
import { clinicalApi } from '@/api'
import { aiConsultsInboxSummary, aiReviewPending, aiRxMineSummary } from '@/api/ai'
import { t } from '@/i18n'
import { hasRole } from '@/stores/session'

export interface TodoItem {
  key: string
  label: string
  /** null = 本次取数失败，界面显示“—”并提示刷新 */
  count: number | null
  failed: boolean
  to: string
}

async function tally(
  key: string,
  label: string,
  to: string,
  fetchCount: () => Promise<number>,
): Promise<TodoItem> {
  try {
    const count = await fetchCount()
    return { key, label, to, count: Math.max(0, count), failed: false }
  } catch {
    return { key, label, to, count: null, failed: true }
  }
}

export async function loadTodoItems(): Promise<TodoItem[]> {
  const jobs: Promise<TodoItem>[] = []

  if (hasRole(['ADMIN', 'DOCTOR', 'PHARMACIST', 'QC'])) {
    jobs.push(
      tally('review', t('todo.review'), '/ai/review?tab=pending', async () => {
        const page = await aiReviewPending({ limit: 1, compact: 1 })
        return page.total ?? 0
      }),
    )
  }

  if (hasRole(['ADMIN', 'QC'])) {
    // 第 239 批：补打异常只有质控/管理员能核查，其他人不显示这条待办（避免“有角标点进去 403”）
    jobs.push(
      tally('printAnomaly', t('todo.printAnomaly'), '/print-events?anomaly=1', async () => {
        const data = await clinicalApi.printAnomalySummary({})
        return data.pending ?? 0
      }),
    )
  }

  if (hasRole('DOCTOR')) {
    jobs.push(
      tally('consults', t('todo.consults'), '/ai/review?tab=consults', async () => {
        const data = await aiConsultsInboxSummary()
        return data.pendingForMe ?? 0
      }),
      tally('rxRejected', t('todo.rxRejected'), '/rx', async () => {
        const data = await aiRxMineSummary()
        return (data.counts ?? {}).rejected ?? 0
      }),
    )
  }

  return Promise.all(jobs)
}
