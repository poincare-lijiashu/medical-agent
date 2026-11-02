import type { SurgerySchedule } from '@/api/types'

/** 后端排台实体的病例外键是 surgeryCaseId；兼容旧字段 caseId。 */
export function caseIdOf(s: SurgerySchedule): string {
  return s.surgeryCaseId ?? s.caseId ?? ''
}

/** 排台接口只返回病例外键时，用台次作为可读名称，不编造术式。 */
export function scheduleName(s: SurgerySchedule): string {
  if (s.operationName) return s.operationName
  if (s.sequence) return `第${s.sequence}台`
  return '排台记录'
}

export function shortId(v: string | null | undefined): string {
  return v ? v.slice(0, 8) : '—'
}

export function hhmm(v: string | null | undefined): string {
  if (!v) return '--:--'
  const d = new Date(v)
  return Number.isNaN(d.getTime()) ? String(v).slice(0, 5) : d.toTimeString().slice(0, 5)
}

export const DAY_START = 7 * 60
export const DAY_END = 20 * 60

export function minuteOf(v: string): number {
  const d = new Date(v)
  if (Number.isNaN(d.getTime())) return DAY_START
  return d.getHours() * 60 + d.getMinutes() + d.getSeconds() / 60
}

export function todayIso(): string {
  const now = new Date()
  const p = (n: number) => String(n).padStart(2, '0')
  return `${now.getFullYear()}-${p(now.getMonth() + 1)}-${p(now.getDate())}`
}

export function isoShift(day: string, delta: number): string {
  const d = new Date(`${day}T12:00:00`)
  d.setDate(d.getDate() + delta)
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`
}
