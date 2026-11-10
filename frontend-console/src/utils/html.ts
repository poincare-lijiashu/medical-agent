/**
 * HTML 转义（唯一实现）。
 *
 * 所有把数据库文本拼进 `innerHTML` / `v-html` / 打印窗口的地方都必须经过这里：
 * 患者姓名、诊断、药品名、签名备注都来自用户输入或外部接口，不能直接拼字符串。
 */

const MAP: Record<string, string> = {
  '<': '&lt;',
  '>': '&gt;',
  '&': '&amp;',
  '"': '&quot;',
  "'": '&#39;',
}

export function escapeHtml(input: unknown): string {
  return String(input ?? '').replace(/[<>&"']/g, (c) => MAP[c] ?? c)
}
