export interface NavItem {
  name: string
  path: string
  title: string
  /** 一句话说明（悬停提示 + 空态文案复用） */
  hint: string
  icon: string
  group: string
  /** 任一权限点命中即可见；为空表示登录即可见 */
  permissions?: string[]
}

export const NAV_ITEMS: NavItem[] = [
  {
    name: 'orders',
    path: '/orders',
    title: '医嘱工作台',
    hint: '患者就诊上下文 · 开立/停撤医嘱 · 费用与结算',
    icon: 'orders',
    group: '临床诊疗',
    permissions: ['clinical:visit:read'],
  },
  {
    name: 'consultation',
    path: '/consultation',
    title: '会诊工作台',
    hint: '会诊申请与意见 · 受邀科室只读授权随会诊结束收回',
    icon: 'users',
    group: '临床诊疗',
    permissions: ['clinical:consultation:read'],
  },
  {
    name: 'preop',
    path: '/preop',
    title: '术前访视',
    hint: '麻醉评估、气道与禁食核查、访视签署',
    icon: 'preop',
    group: '临床诊疗',
    permissions: ['clinical:surgery:read'],
  },
  {
    name: 'surgery',
    path: '/surgery',
    title: '手术排台',
    hint: '手术间日程 · 分级审批待办 · 台次状态',
    icon: 'surgery',
    group: '手术与收费',
    permissions: ['clinical:surgery:read'],
  },
  {
    name: 'surgery-charges',
    path: '/surgery-charges',
    title: '术中收费核对',
    hint: '按手术病例核对收费打包，差异逐项可追溯',
    icon: 'charges',
    group: '手术与收费',
    permissions: ['clinical:charge:read'],
  },
  {
    name: 'narcotic',
    path: '/narcotic',
    title: '麻精专册',
    hint: '麻醉药品处方专册与空安瓿回收闭环',
    icon: 'narcotic',
    group: '手术与收费',
    permissions: ['pharmacy:read'],
  },
  {
    name: 'homepage-qc',
    path: '/homepage-qc',
    title: '病案首页质控',
    hint: '首页完整性、费用勾稽与归档',
    icon: 'qc',
    group: '质量与知识',
    permissions: ['emr:homepage:read'],
  },
  {
    name: 'knowledge',
    path: '/knowledge',
    title: '知识库',
    hint: '带引用的检索与问答，拒答即拒答',
    icon: 'kb',
    group: '质量与知识',
    permissions: ['kb:space:read'],
  },
]

export const NAV_GROUPS = ['临床诊疗', '手术与收费', '质量与知识']

export function findNav(name: string): NavItem | undefined {
  return NAV_ITEMS.find((n) => n.name === name)
}
