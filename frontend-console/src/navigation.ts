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
    name: 'inpatient',
    path: '/inpatient',
    title: '住院工作台',
    hint: '病区床位 · 入出院 · 分床转床 · 预交金与欠费预警',
    icon: 'bed',
    group: '住院管理',
    permissions: ['inpatient:read'],
  },
  {
    name: 'nursing',
    path: '/nursing',
    title: '护理执行',
    hint: '长嘱逐日执行 · 拒绝/未执行当日不计费 · 更正联动红冲',
    icon: 'clipboard',
    group: '住院管理',
    permissions: ['inpatient:nursing:read'],
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
    name: 'tech',
    path: '/tech',
    title: '医技工作台',
    hint: '检查检验申请 · 标本采集接收 · 报告签发 · 危急值闭环',
    icon: 'monitor',
    group: '医技检查',
    permissions: ['tech:read'],
  },
  {
    name: 'narcotic',
    path: '/narcotic',
    title: '麻精专册',
    hint: '麻醉药品处方专册与空安瓿回收闭环',
    icon: 'narcotic',
    group: '药事管理',
    permissions: ['pharmacy:read'],
  },
  {
    name: 'pharmacy',
    path: '/pharmacy',
    title: '药房工作台',
    hint: '处方审核 · 调配发药 · 退药回补 · 药品目录与库存预警',
    icon: 'pill',
    group: '药事管理',
    permissions: ['pharmacy:read'],
  },
  {
    name: 'insurance',
    path: '/insurance',
    title: '医保结算',
    hint: '参保登记 · 结算清单生成/确认/作废 · 按就诊对账',
    icon: 'wallet',
    group: '医保结算',
    permissions: ['insurance:settlement:read'],
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

export const NAV_GROUPS = [
  '临床诊疗',
  '住院管理',
  '医技检查',
  '药事管理',
  '手术与收费',
  '医保结算',
  '质量与知识',
]

export function findNav(name: string): NavItem | undefined {
  return NAV_ITEMS.find((n) => n.name === name)
}
