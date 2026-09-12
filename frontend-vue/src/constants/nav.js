// 菜单定义常量：语义复制自 legacy frontend/js/state.js 的 ROLE_NAV 与
// frontend/js/views_core.js 的 rail 分组/文案（轮1 先复制语义，不改角色可见性）；
// 轮2/3 逐步把各 key 的占位视图替换为真实实现并补角标轮询。
export const NAV_ITEMS = {
  overview: { t: '概览' },
  literature: { t: '医学文献' },
  rx: { t: '开药工作台' },
  imaging: { t: '影像辅助' },
  case: { t: '病例总结' },
  mdt: { t: '多智能体会诊' },
  myconsults: { t: '我的会诊' },
  qc: { t: '病历质控（病案科）' },
  review: { t: '审核中心' },
  kb: { t: '知识库管理' },
  data: { t: '数据面板' },
  arch: { t: '架构 · 合规' },
}

// 分组（legacy rail 同款分组标题与顺序）；items 为该组导航 key
export const NAV_GROUPS = [
  { g: '总览', items: ['overview'] },
  { g: '临床助手', items: ['literature', 'rx', 'imaging', 'case'] },
  { g: '协作', items: ['mdt', 'myconsults'] },
  { g: '质控', items: ['qc', 'review'] },
  { g: '系统', items: ['kb', 'data', 'arch'] },
]

// 与 legacy state.js ROLE_NAV 逐字同源（含阶段3 语义：rx 取代 drug；qc 对 doctor/pharmacist 开放）
export const ROLE_NAV = {
  doctor: ['overview', 'literature', 'rx', 'imaging', 'case', 'mdt', 'myconsults', 'qc', 'review'],
  pharmacist: ['overview', 'literature', 'rx', 'qc', 'review'],
  admin: ['overview', 'kb', 'qc', 'review', 'data', 'arch'],
  qc: ['overview', 'literature', 'rx', 'qc', 'review'],
}

// 角色中文名（legacy 顶栏显示原始 role 码；新壳转中文便于阅读，权限判断始终用 role 码）
export const ROLE_LABEL = {
  doctor: '医师',
  pharmacist: '药师',
  qc: '质控员',
  admin: '管理员',
}
