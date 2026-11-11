/**
 * 出院/交班风险机器码的中文口径（第 254 批从 InpatientView 抽出）。
 *
 * <p>为什么不各自维护一份：预检、交班、CSV、交接单都展示同一批机器码，
 * 分散在多处时新增一个码必然漏掉某个界面，最后屏幕上出现英文码。
 */
export const CHECK_CODE_LABELS: Record<string, string> = {
  ADMISSION_STATUS: '状态异常',
  UNSETTLED_CHARGE: '未结费用',
  UNBILLED_TEMP_ORDER: '临时医嘱未计费',
  UNBILLED_DAILY_ORDER: '长嘱未计费',
  UNSTOPPED_LONG_ORDER: '长嘱未停',
  DISCHARGE_MED_PENDING: '带药未完成',
  EMR_ARCHIVE: '病历未归档',
  OVERDUE_DISCHARGE: '超期未出院',
  PLANNED_DISCHARGE_TODAY: '今日预出院',
}

/** 超期在列表行内已有“超期 N 天”徽章，避免重复显示“超期未出院”。 */
export const OVERDUE_CODE = 'OVERDUE_DISCHARGE'

export function checkCodeLabel(code: string): string {
  return CHECK_CODE_LABELS[code] ?? code
}

export function visibleWarnings(codes: string[]): string[] {
  return codes.filter((c) => c !== OVERDUE_CODE)
}
