/**
 * CSV 导出（第 236 批）。
 *
 * 为什么不用第三方库：只需要“二维表 → 文本”，但有两个容易被忽略的细节必须处理：
 * - **Excel 兼容**：UTF-8 CSV 不加 BOM，Excel 打开会中文乱码；
 * - **公式注入**：以 `= + - @` 开头的单元格会被 Excel/Sheets 当公式执行，
 *   患者姓名或备注里出现 `=cmd|...` 时就会变成钓鱼载体，必须强制成文本。
 */
type Cell = string | number | null | undefined

function escapeCell(value: Cell): string {
  const raw = String(value ?? '')
  // 前置单引号让表格软件按文本处理；换行/制表符同样属于危险前缀
  const safe = /^[=+\-@\t\r]/.test(raw) ? `'${raw}` : raw
  return /[",\n\r]/.test(safe) ? `"${safe.replace(/"/g, '""')}"` : safe
}

export function toCsv(rows: Cell[][]): string {
  return rows.map((row) => row.map(escapeCell).join(',')).join('\r\n')
}

/** 下载 CSV：`\uFEFF` 是 UTF-8 BOM，缺了它 Excel 打开就是乱码。 */
export function downloadCsv(filename: string, rows: Cell[][]): void {
  const blob = new Blob(['\uFEFF' + toCsv(rows)], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = filename
  document.body.appendChild(link)
  link.click()
  link.remove()
  URL.revokeObjectURL(url)
}
