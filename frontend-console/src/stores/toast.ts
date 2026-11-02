import { ref } from 'vue'

export type ToastKind = 'ok' | 'warn' | 'error' | 'info'

export interface ToastItem {
  id: number
  kind: ToastKind
  title: string
  desc?: string
}

let seq = 0
const items = ref<ToastItem[]>([])

function push(kind: ToastKind, title: string, desc?: string) {
  const id = ++seq
  items.value = [...items.value, { id, kind, title, desc }].slice(-4)
  window.setTimeout(() => dismiss(id), kind === 'error' ? 7000 : 4200)
  return id
}

export function dismiss(id: number) {
  items.value = items.value.filter((t) => t.id !== id)
}

export const toast = {
  /** 用 getter 暴露，模板读取时仍会建立响应式依赖 */
  get list(): ToastItem[] {
    return items.value
  },
  ok: (title: string, desc?: string) => push('ok', title, desc),
  warn: (title: string, desc?: string) => push('warn', title, desc),
  error: (title: string, desc?: string) => push('error', title, desc),
  info: (title: string, desc?: string) => push('info', title, desc),
  dismiss,
}
