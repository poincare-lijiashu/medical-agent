// 审计流人话化（轮3）：逐字迁移 legacy frontend/js/util.js 的 humanizeAudit——
// event_type+action → 中文描述模板：payload 有什么展示什么，缺省只显示动作；
// 未映射的兜底显示 event_type+payload 摘要（原始 JSON 仍在折叠区，排障用）。
// 覆盖回归锁：tests/test_audit_humanize_coverage.py 提取 backend 全部审计 action
// 字面量与本表键集比对——新增审计事件必须同步在此补人话，否则测试红。
export function humanizeAudit(e) {
  const p = (e && e.payload) || {}
  const ev = String((e && e.event) || '')
  let act = String((e && e.action) || '')
  const rid = p.rid ? ('#' + p.rid) : ''
  if (!act && ev.indexOf('.') >= 0) act = ev.slice(ev.indexOf('.') + 1) // PG 镜像点分事件（admin/data 数据源）
  const cf = (p.conf !== undefined && p.conf !== null && Number.isFinite(Number(p.conf)))
    ? Number(p.conf).toFixed(2) : '—'
  const T = p.user || p.name || p.tag || p.file || p.filename || p.path
    || (p.kind ? (p.kind + (p.pid ? ('/' + p.pid) : '')) : '') || ''
  const MAP = {
    /* —— 审核中心 / 复核流 —— */
    list_pending: () => p.n !== undefined ? `查看了待核对列表（${p.n} 项待处理）` : '查看了待核对列表',
    history: () => p.n !== undefined ? `查看了签发历史（${p.n} 条）` : '查看了签发历史',
    list: () => p.n !== undefined ? `查看了列表（${p.n} 条）` : '查看了列表',
    status: () => p.rid ? `查看了复核单状态（${p.rid}）` : '查看了复核队列状态',
    purge_pending: () => p.n !== undefined ? `清空了待核对列表（${p.n} 条）` : '清空了待核对列表',
    review_enqueued: () => `答案进入待核对队列${rid ? ('（' + rid + '）') : ''}`,
    auto_sign_full: () => `留痕模式自动签发（${rid}）`.trim(),
    auto_sign: () => `中危规则库提示自动签发（${rid}）`.trim(),
    self_confirmed: () => `完成了知情确认（${rid}）`.trim(),
    resolved_approved: () => rid ? `对 ${rid} 执行了签发` : '执行了签发',
    resolved_rejected: () => rid ? `对 ${rid} 执行了驳回` : '执行了驳回',
    approved: () => rid ? `对 ${rid} 执行了签发` : '执行了签发',
    rejected: () => rid ? `对 ${rid} 执行了驳回` : '执行了驳回',
    reopen: () => rid ? `对 ${rid} 执行了转人工` : '执行了转人工',
    reopened: () => rid ? `对 ${rid} 执行了转人工` : '执行了转人工',
    /* —— 查询/问答类（event_type 细分：qc 质控 / literature 文献 / mdt 会诊 / consult 发起）—— */
    query: () => {
      if (ev === 'qc') return p.fields !== undefined ? `提交了病历质控（字段 ${p.fields} 项 · 检验 ${p.labs ?? 0} 项）` : '提交了病历质控'
      if (ev === 'literature') return '查询了医学文献'
      if (ev === 'mdt') return '发起了 AI 多智能体会诊'
      if (ev === 'consult') return '准备发起跨科室会诊'
      return '执行了查询'
    },
    prefilter: () => '命中系统快捷应答（未使用检索）',
    offtopic_reject: () => '拒答了技术领域外问题',
    degraded: () => {
      if (ev === 'literature') return '文献问答服务降级（已标记复核）'
      if (ev === 'mdt') return '会诊服务降级（已标记复核）'
      if (ev === 'consult') return '会诊组队服务降级（已留痕）'
      return '服务降级（已留痕）'
    },
    fallback_no_enqueue: () => '常识兜底回答（不入审核队列）',
    stream: () => '发起了流式问答',
    stream_error: () => '流式生成异常（已记录审计）',
    agentic_refine: () => `检索改写（第 ${p.attempt ?? '?'} 轮）`,
    answered: () => `药物问答（置信 ${cf}${p.review ? '；需药剂科复核' : ''}）`,
    answer: () => { // PG 镜像 qc.answer/literature.answer/drug.answer…（event 为点分名）
      if (ev.indexOf('qc') === 0) return `提交了质控结论（置信 ${cf}${p.review ? ' · 需复核' : ''}）`
      if (ev.indexOf('drug') === 0) return `药物问答（置信 ${cf}${p.review ? '；需药剂科复核' : ''}）`
      return `AI 问答留痕（置信 ${cf}${p.review ? ' · 需复核' : ''}）`
    },
    /* —— 智能开药 —— */
    suggested: () => `AI 开药建议（推荐 ${p.n ?? 0} 种${p.blocked ? ' · 高危联用已阻断' : ''}${(p.dropped || []).length ? ` · 拦截幻影药 ${p.dropped.length} 个` : ''}）`,
    /* 阶段2.3 处方流转：提交/改写重提/高危强制开立（approved/rejected 复用上方既有键）；
       阶段3 查漏结论：prescription 全部审计动作均已映射；问题3② 补 list_reviewed_by_me；
       整改轮 B 任务2（方案A）：submitted 人话带字典外药名单（out_of_dict_drugs） */
    submitted: () => `提交了处方送药师审核（${p.n ?? '?'} 种药${p.forced_high_risk ? ' · 高危强制开立' : ''}${(p.out_of_dict_drugs || []).length ? ' · 含字典外药 ' + p.out_of_dict_drugs.join('、') : ''}）`,
    rewritten: () => `改写了被驳回处方并重新送审（${p.n ?? '?'} 种药）`,
    forced_with_reason: () => `高危联用强制开立并附理由（${rid}）`.trim(),
    list_reviewed_by_me: () => p.n !== undefined ? `查看了本人审核过的处方（${p.n} 条）` : '查看了本人审核过的处方',
    /* —— 影像/病例（VL 链路）—— */
    describe_failed: () => '影像描述失败（已留痕）',
    vl_failed: () => '多模态解析失败（已留痕）',
    vl_empty: () => 'AI 未生成影像所见（已提示重试）',
    vl_truncated: () => '影像输出因长度截断（按最大长度返回）',
    vl_described: () => `生成了影像所见（${p.len ?? '?'} 字${p.risky ? ' · 含高危征象' : ''}）`,
    need_image: () => '提示需上传影像',
    scaffold: () => '提示粘贴病例要点',
    /* —— 跨科室会诊 —— */
    prefilter_reject: () => '会诊发起被预检拦截（请补充病例信息）',
    team_failed: () => '会诊组队失败（请补充病例信息）',
    consult_created: () => (p.depts && p.depts.length) ? `发起了跨科室会诊（召集：${p.depts.join('、')}）` : '发起了跨科室会诊',
    consult_dispatch: () => `会诊分发给 ${(p.departments || []).length} 个科室`,
    consult_opinion: () => `提交了会诊意见（${p.dept || '科室未设置'}）`,
    consult_closed: () => `结束了会诊 ${p.cid || rid || ''}`.trim(),
    list_mine: () => `查看了我的会诊（发起 ${p.initiated ?? 0} · 参与 ${p.participated ?? 0}）`,
    list_inbox: () => `查看了会诊收件箱（${p.n ?? 0} 条待意见）`,
    created: () => ev.indexOf('case_archive') === 0 // 阶段4：case_archive.created（PG 点分镜像）→ 归档人话；consult.created → 会诊单
      ? `质控病历已归档病例库（${p.arch_id || rid || ''}）`.trim()
      : `创建了会诊单 ${p.cid || rid || ''}`.trim(),
    /* 阶段4：case_archive.removed（移出病例库软删除，payload 带 arch_id+reason）*/
    removed: () => ev.indexOf('case_archive') === 0
      ? `将 ${p.arch_id || rid || ''} 移出病例库（原因：${p.reason || '—'}）`.trim()
      : `移除了 ${p.arch_id || rid || ''}`.trim(),
    /* 阶段4：case_archive.failed（质控 approve 归档失败仅审计，不影响质控主流程）*/
    failed: () => ev.indexOf('case_archive') === 0
      ? '病例归档失败（已留痕，不影响质控主流程）'
      : '操作失败（已留痕）',
    opinion: () => `提交了会诊意见（${p.dept || '科室未设置'}）`,
    closed: () => `结束了会诊 ${p.cid || rid || ''}`.trim(),
    /* —— 病历质控 —— */
    resubmit: () => `重新提交了质控病历（第 ${p.attempt ?? '?'} 次）`,
    qc_escalated: () => `质控重提自动升级病案科复核（第 ${p.attempt ?? '?'} 次）`,
    auto_reject: () => `AI 自动驳回质控病历（硬伤 ${(p.hard || []).length} 项）`,
    auto_pass: () => `AI 预审通过并归档（置信 ${cf}）`,
    my_rejections: () => p.n !== undefined ? `查看了我的质控驳回（${p.n} 条）` : '查看了我的质控驳回',
    parse: () => `粘贴病历让 AI 拆分（${p.len ?? '?'} 字）`,
    /* —— 知识库 —— */
    search: () => `检索了知识库（top ${p.top_k ?? '—'}）`,
    search_failed: () => '知识库检索失败（已降级提示）',
    upload: () => T ? `上传了知识库 ${T}` : '上传了知识库文件',
    upload_rejected: () => `知识库上传被拒绝（${T || '文件'}：${String(p.reason || '类型不支持').slice(0, 60)}）`,
    upload_async: () => `上传了大文件，后台向量化中（${T || '文件'}）`,
    upload_failed: () => `知识库文件摄取失败（${T || '文件'}）`,
    delete: () => T ? `删除了 ${T}` : '执行了删除',
    /* 轮 A1：内置库删除 / 删除失败兜底（kb.builtin_removed / kb.delete_failed） */
    builtin_removed: () => T ? `删除了内置知识库 ${T}（重跑种子脚本可恢复）` : '删除了内置知识库',
    delete_failed: () => `知识库删除失败（${T || '文档'}，已原样返回错误）`,
    /* —— 管理端 —— */
    config_toggle: () => `切换了运行时开关 ${p.key || '—'}（${p.from ? '开' : '关'} → ${p.to ? '开' : '关'}）`,
    user_created: () => `创建了用户 ${p.user || '—'}（${p.role || '—'}）`,
    user_deleted: () => `删除了用户 ${p.user || '—'}`,
    department_added: () => `新增了科室 ${p.name || '—'}`,
    department_removed: () => `删除了科室 ${p.name || '—'}`,
    /* 阶段1.5：药品字典/规则管理变更（payload 带 target=dict|rules；dict 带 name，rules 带药对） */
    drug_dict_changed: () => p.target === 'rules'
      ? `${p.action === 'add' ? '新增了' : p.action === 'update' ? '更新了' : '删除了'}相互作用规则（${p.drug_a || '—'} × ${p.drug_b || '—'}）`
      : `${p.action === 'add' ? '新增了' : p.action === 'update' ? '更新了' : '删除了'}药品字典条目 ${p.name || '—'}`,
    /* 评测行动项4：规则审校批量标记（drug_rules.reviewed，payload 带 count/decision） */
    reviewed: () => `标记了 ${p.count ?? 0} 条相互作用规则审校状态（${p.decision === 'unreviewed' ? '撤销为未审校' : '药师核对通过'}）`,
    llm_add: () => `新增了模型配置（${p.kind || '—'}/${p.pid || '—'}）`,
    llm_remove: () => `删除了模型配置（${p.kind || '—'}/${p.pid || '—'}）`,
    llm_activate: () => `激活了模型配置（${p.kind || '—'}/${p.pid || '—'}）`,
    llm_deactivate: () => `停用了${p.kind === 'vision' ? '视觉' : '对话'}模型配置（回落内置）`,
    llm_test: () => `测试了模型连通性（${p.model_id || '—'}：${p.ok ? '成功' : '失败'}）`,
    /* —— 认证 —— */
    password_changed: () => '修改了自己的登录密码',
    password_reset: () => `重置了用户密码（${p.user || p.username || '—'}）`,
    /* —— HIS 外推 —— */
    push_failed: () => '质控结论推送 HIS 失败（已留痕）',
    pushed: () => `质控结论已推送 HIS（${p.status || '—'}）`,
    push_skipped: () => 'HIS 适配器跳过推送',
  }
  const tpl = MAP[act]
  if (tpl) return tpl()
  /* —— 关键词兜底（动态/未知动作族的安全网，命中顺序：签发/驳回/转人工 优先于 增删改）—— */
  const has = (s) => act.includes(s)
  if (has('approved')) return rid ? `对 ${rid} 执行了签发` : '执行了签发'
  if (has('rejected')) return rid ? `对 ${rid} 执行了驳回` : '执行了驳回'
  if (has('reopen')) return rid ? `对 ${rid} 执行了转人工` : '执行了转人工'
  if (has('upload') && T) return `上传了知识库 ${T}`
  if ((has('create') || has('_created') || has('add') || has('activate')) && T) return `创建了 ${T}`
  if ((has('delete') || has('remov') || has('deactivate')) && T) return `删除了 ${T}`
  if (has('update') && T) return `修改了 ${T}`
  // —— 兜底：event_type + action + payload 摘要 ——
  const sum = Object.entries(p).filter(([k]) => k !== 'action').slice(0, 4)
    .map(([k, v]) => `${k}=${typeof v === 'string' ? v.slice(0, 40) : JSON.stringify(v)}`).join('；')
  const base = ((ev || '') + (act ? ' · ' + act : '')).trim()
  return sum ? `${base || '系统事件'}（${sum}）` : (base || '系统事件')
}

// 字节数 → KB/MB 人话（legacy fmtLogSize 同实现：<1KB 显示 B）
export function fmtLogSize(b) {
  b = Number(b) || 0
  if (b < 1024) return b + ' B'
  if (b < 1024 * 1024) return (b / 1024).toFixed(1) + ' KB'
  return (b / 1024 / 1024).toFixed(2) + ' MB'
}

// 概览/数据面板运行时长（legacy fmtUp 同实现）
export function fmtUp(s) {
  if (s < 60) return s + 's'
  const m = Math.floor(s / 60)
  if (m < 60) return m + 'm'
  return Math.floor(m / 60) + 'h' + (m % 60) + 'm'
}
