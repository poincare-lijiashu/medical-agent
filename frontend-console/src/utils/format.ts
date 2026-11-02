/** 展示层格式化：金额只做展示，禁止在前端做金额运算。 */

const cny = new Intl.NumberFormat('zh-CN', {
  style: 'currency',
  currency: 'CNY',
  minimumFractionDigits: 2,
  maximumFractionDigits: 2,
})

const dateFmt = new Intl.DateTimeFormat('zh-CN', {
  timeZone: 'Asia/Shanghai',
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
})

const dateTimeFmt = new Intl.DateTimeFormat('zh-CN', {
  timeZone: 'Asia/Shanghai',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  hour12: false,
})

export function money(value: number | string | null | undefined): string {
  if (value === null || value === undefined || value === '') return '—'
  const n = typeof value === 'number' ? value : Number(value)
  return Number.isFinite(n) ? cny.format(n) : '—'
}

export function plainMoney(value: number | string | null | undefined): string {
  if (value === null || value === undefined || value === '') return ''
  return String(value)
}

export function date(value: string | null | undefined): string {
  if (!value) return '—'
  const d = new Date(value)
  return Number.isNaN(d.getTime()) ? value : dateFmt.format(d)
}

export function dateTime(value: string | null | undefined): string {
  if (!value) return '—'
  const d = new Date(value)
  return Number.isNaN(d.getTime()) ? value : dateTimeFmt.format(d)
}

export function todayIso(): string {
  const now = new Date()
  const y = now.getFullYear()
  const m = String(now.getMonth() + 1).padStart(2, '0')
  const d = String(now.getDate()).padStart(2, '0')
  return `${y}-${m}-${d}`
}

export function ageOf(birthDate: string | null | undefined): string {
  if (!birthDate) return '—'
  const b = new Date(birthDate)
  if (Number.isNaN(b.getTime())) return '—'
  const now = new Date()
  let age = now.getFullYear() - b.getFullYear()
  const beforeBirthday =
    now.getMonth() < b.getMonth() || (now.getMonth() === b.getMonth() && now.getDate() < b.getDate())
  if (beforeBirthday) age -= 1
  return age >= 0 ? `${age} 岁` : '—'
}

export function genderLabel(g: string | null | undefined): string {
  return g === 'MALE' ? '男' : g === 'FEMALE' ? '女' : '未知'
}

export function maskName(name: string | null | undefined): string {
  if (!name) return '—'
  if (name.length <= 1) return name
  return name[0] + '*'.repeat(Math.max(name.length - 2, 1)) + (name.length > 2 ? name[name.length - 1] : '')
}
