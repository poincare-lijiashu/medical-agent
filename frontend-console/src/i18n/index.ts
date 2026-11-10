/**
 * 轻量 i18n 运行时（第 178 批起步）。
 *
 * 设计取舍：当前只需要「文案外置 + 中英切换」，不引入 vue-i18n 这类运行时依赖——
 * 一个 ref + 一个查表函数就够，包体和心智负担都更小。
 * 模板里直接调用 `t('key')` 即可获得响应式：`t` 内部读了 `locale.value`，
 * 切换语言会让所有用到它的模板与 computed 自动重算。
 *
 * 约定：新增文案先登记到 `zh-CN.ts`（唯一真源），再补 `en.ts`；
 * 两边 key 由 `Record<MessageKey, string>` 约束，漏翻会在 typecheck 阶段直接失败。
 */
import { ref } from 'vue'
import en from './en'
import zhCN, { type MessageKey } from './zh-CN'

export type Locale = 'zh-CN' | 'en'
export type { MessageKey }

export const SUPPORTED_LOCALES: { value: Locale; label: string }[] = [
  { value: 'zh-CN', label: '简体中文' },
  { value: 'en', label: 'English' },
]

const STORAGE_KEY = 'medassist.console.locale'
const DICTS: Record<Locale, Record<string, string>> = { 'zh-CN': zhCN, en }

function readInitialLocale(): Locale {
  try {
    const saved = localStorage.getItem(STORAGE_KEY)
    if (saved === 'zh-CN' || saved === 'en') return saved
  } catch {
    // 隐私模式下 localStorage 不可用：退回浏览器语言判断
  }
  // 院内默认中文；浏览器语言为英文的机器（外籍专家/英文环境）默认英文
  return navigator.language?.toLowerCase().startsWith('en') ? 'en' : 'zh-CN'
}

export const locale = ref<Locale>(readInitialLocale())

/**
 * 取文案。`{name}` 占位符用 `vars` 替换；目标语言缺该 key 时回落中文，再回落 `fallback`，最后回 key 本身。
 */
export function t(key: string, vars?: Record<string, string | number>, fallback?: string): string {
  let text = DICTS[locale.value][key] ?? zhCN[key as MessageKey] ?? fallback ?? key
  if (vars) {
    for (const [name, value] of Object.entries(vars)) {
      text = text.split(`{${name}}`).join(String(value))
    }
  }
  return text
}

/** 导航标题：以 `navigation.ts` 的中文为准的兜底文案，避免字典未覆盖时出现裸 key。 */
export function navTitle(name: string, fallback: string): string {
  return t(`nav.${name}.title`, undefined, fallback)
}

/** 导航一句话说明（命令面板的副标题 / 悬停提示）。 */
export function navHint(name: string, fallback: string): string {
  return t(`nav.${name}.hint`, undefined, fallback)
}

/** 导航分组名（以中文分组名作为 key，避免再维护一套分组标识）。 */
export function navGroupLabel(name: string): string {
  return t(`nav.group.${name}`, undefined, name)
}

export function setLocale(next: Locale): void {
  locale.value = next
  try {
    localStorage.setItem(STORAGE_KEY, next)
  } catch {
    // 存不下就只在本次会话生效
  }
  applyDocumentLang()
}

/** 在中英文之间切换（用户菜单里的语言项）。 */
export function toggleLocale(): void {
  setLocale(locale.value === 'zh-CN' ? 'en' : 'zh-CN')
}

export function applyDocumentLang(): void {
  document.documentElement.lang = locale.value === 'en' ? 'en' : 'zh-CN'
}

applyDocumentLang()
