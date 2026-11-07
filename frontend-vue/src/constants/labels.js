/** 后端枚举 → 中文（评审 04-S3：同一个概念在两个页面只能有一种说法）。
 *
 * 此前 `AskView` 与 `AgentView` 各自维护一份映射，管理员页甚至直接输出英文枚举
 * （`single` / `supervisor`），同一个 `PUBLISHED` 出现「已发布 / 已生效 / 非已发布」
 * 三种中文说法。这里收敛为唯一来源，两个视图共用。
 */

export const STRATEGY_TEXT = {
  single: '单智能体直达',
  supervisor: '主路由 + 候选',
  explicit: '手动指定',
  prefilter: '快捷应答',
}

export const STATUS_TEXT = {
  DRAFT: '草稿',
  IN_REVIEW: '待审核',
  PUBLISHED: '已生效',
  REJECTED: '已驳回',
  DISABLED: '已停用',
}

export function strategyText(strategy) {
  return STRATEGY_TEXT[strategy] || strategy || ''
}

export function statusText(status) {
  return STATUS_TEXT[status] || status || ''
}

export function statusType(status) {
  if (status === 'PUBLISHED') return 'success'
  if (status === 'IN_REVIEW') return 'warning'
  if (status === 'REJECTED') return 'danger'
  return 'info'
}
