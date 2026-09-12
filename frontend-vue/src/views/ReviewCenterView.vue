<!-- 审核中心（轮3）：语义迁移 legacy frontend/js/views_admin.js 审核中心/开药待审/病例库/
     清空待核对全部逻辑（legacy 零改动）。角色分支逐字对齐：
     - qc/admin = 高风险双人核对工作台：待处理（签发/驳回/转人工复核/详情）+ 历史记录 +
       病例库 tab（qc/admin）+ admin 开药待审区 + 清空待核对（确认词「清空」）；
     - pharmacist = 药剂科：开药待审（处方卡通过/驳回意见弹条）+ 我的待确认 + 我的历史
       （合并本人审核过的处方）+ 药品字典/相互作用规则管理 tab；
     - doctor = 我的待确认（AI 自动签发知情确认）+ 我的历史；图片可见性按角色由后端
       _review_image_view 收窄，前端仅消费 images 中 data:image/ 白名单（缩略条+详情大图）。
     drug 项已清且全角色过滤（含 pharmacist/admin）；qc 无开药审核权（403 不拉取）。
     加载请求序号守卫防旧响应覆盖（legacy reviewLoadSeq 同语义）。 -->
<template>
  <div>
    <!-- 头部：角色分支文案 + admin 清空待核对 + 刷新 -->
    <div class="shead">
      <div>
        <h2 data-testid="review-title">{{ headTitle }}</h2>
        <p data-testid="review-sub">{{ headSub }}</p>
      </div>
      <div class="spacer"></div>
      <el-button v-if="role === 'admin'" size="small" type="danger" plain @click="purgePending">清空待核对</el-button>
      <el-button size="small" @click="load">刷新</el-button>
    </div>

    <!-- tab 条：待处理/历史记录（+字典/规则 pharmacist +病例库 qc/admin）+ 档案筛选（qc/admin） -->
    <div class="shead tabbar">
      <el-button size="small" :type="tab === 'pending' ? 'primary' : ''" plain data-testid="review-tab-pending" @click="switchTab('pending')">
        {{ isMine ? (isPh ? '待处理' : '我的待确认') : '待处理' }}
      </el-button>
      <el-button size="small" :type="tab === 'history' ? 'primary' : ''" plain data-testid="review-tab-history" @click="switchTab('history')">
        {{ isMine ? '我的历史' : '历史记录' }}
      </el-button>
      <el-button v-if="isPh" size="small" :type="tab === 'drugdict' ? 'primary' : ''" plain data-testid="review-tab-drugdict" @click="switchTab('drugdict')">药品字典</el-button>
      <el-button v-if="isPh" size="small" :type="tab === 'drugrules' ? 'primary' : ''" plain data-testid="review-tab-drugrules" @click="switchTab('drugrules')">相互作用规则</el-button>
      <!-- 轮 A：第三栏「其它科室会诊协助」=会诊收件箱（doctor/pharmacist 专属，legacy tabConsults） -->
      <el-button v-if="isMine" size="small" :type="tab === 'consults' ? 'primary' : ''" plain data-testid="review-tab-consults" @click="switchTab('consults')">
        其它科室会诊协助{{ badges.consultPendingOps ? ' ' + badges.consultPendingOps : '' }}
      </el-button>
      <el-button v-if="!isMine" size="small" :type="tab === 'casearchive' ? 'primary' : ''" plain data-testid="review-tab-casearchive" @click="switchTab('casearchive')">病例库</el-button>
      <input
        v-if="!isMine" v-model="filterQ" class="inp" style="max-width: 220px; margin-left: auto"
        placeholder="筛选：助手/问题/提交人" spellcheck="false" aria-label="档案筛选"
      />
    </div>

    <!-- F1：加载失败错误条与列表区并存（不再 v-if/v-else 顶掉列表——429/网络失败时
         列表区整体消失=「假空态」根因）；错误详情在此，列表/空态照常渲染 -->
    <el-card v-if="loadErr" shadow="never" class="page-body" style="margin-bottom: 10px" data-testid="review-load-error">
      <el-alert type="error" :closable="false" show-icon :title="loadErr" />
    </el-card>

    <!-- ============ 药品字典 / 相互作用规则 tab（pharmacist） ============ -->
    <div v-if="tab === 'drugdict'" class="page-body"><DrugDictEditor /></div>
    <div v-else-if="tab === 'drugrules'" class="page-body"><DrugRulesEditor /></div>

    <!-- ============ 病例库 tab（qc/admin） ============ -->
    <div v-else-if="tab === 'casearchive'" class="page-body" data-testid="case-archive">
      <el-card shadow="never" style="border-left: 3px solid var(--accent)">
        <h3 style="margin: 0 0 8px">病例库（质控通过自动归档）</h3>
        <div style="display: flex; gap: 8px; align-items: center; flex-wrap: wrap">
          <el-tag type="success" effect="light" round>{{ caStats.active ?? 0 }} 例在库</el-tag>
          <el-tag effect="light" round>{{ caStats.removed ?? 0 }} 已移除</el-tag>
          <span class="src num">共 {{ caStats.total ?? 0 }} 条 · 移除为软删除留痕（可审计）</span>
        </div>
      </el-card>
      <el-card shadow="never">
        <div style="display: flex; gap: 8px; flex-wrap: wrap; align-items: center">
          <el-select v-model="caDept" placeholder="全部科室" clearable style="max-width: 150px" aria-label="科室筛选">
            <el-option v-for="d in caDepts" :key="d" :label="d" :value="d" />
          </el-select>
          <el-select v-model="caStatus" placeholder="全部状态" clearable style="max-width: 130px" aria-label="状态筛选">
            <el-option label="在库" value="active" />
            <el-option label="已移除" value="removed" />
          </el-select>
          <el-date-picker v-model="caFrom" type="date" placeholder="归档起始日期" value-format="YYYY-MM-DD" style="max-width: 160px" />
          <el-date-picker v-model="caTo" type="date" placeholder="归档截止日期" value-format="YYYY-MM-DD" style="max-width: 160px" />
          <span class="src num">{{ caList.length }} 条</span>
        </div>
        <ul class="feed" style="margin-top: 8px">
          <template v-if="caList.length">
            <li v-for="i in caList" :key="i.id" style="border-top: 1px solid var(--line); padding: 10px 0; display: block">
              <div style="display: flex; gap: 8px; align-items: center; flex-wrap: wrap">
                <b class="num">{{ i.id }}</b>
                <el-tag :type="i.status === 'active' ? 'success' : 'info'" effect="light" size="small" round>{{ i.status === 'active' ? '在库' : '已移除' }}</el-tag>
                <el-tag type="primary" effect="plain" size="small" round>{{ i.dept || '科室—' }}</el-tag>
                <span class="src">患者标识：{{ i.patient_ref || '—' }}</span>
                <span class="t num">{{ fmtTs(i.archived_at) }}</span>
                <span class="src">质控签字：{{ i.reviewed_by || '—' }}</span>
                <el-button v-if="i.status === 'active'" size="small" type="danger" plain style="margin-left: auto" @click="caRemove(i.id)">移除</el-button>
              </div>
              <details style="margin-top: 6px">
                <summary class="src" style="cursor: pointer">质控结论摘要与病历详情 ▾</summary>
                <div class="txt" style="white-space: pre-wrap; margin-top: 4px">{{ i.qc_conclusion || '（无结论摘要）' }}</div>
                <div class="src num" style="margin-top: 4px">质控置信：{{ i.qc_confidence ?? '—' }}</div>
                <pre class="record-pre">{{ JSON.stringify(i.record || {}, null, 2) }}</pre>
                <div v-if="i.labs && Object.keys(i.labs).length" class="src" style="margin-top: 4px">检验值：{{ Object.entries(i.labs).map(([k, v]) => k + '=' + v).join('；') }}</div>
              </details>
              <div v-if="i.status !== 'active'" class="src" style="margin-top: 4px">移除人：{{ i.removed_by || '—' }} · 移除原因：{{ i.removed_reason || '—' }}</div>
            </li>
          </template>
          <li v-else><span class="a">暂无归档病例（质控 approve 后自动入库）。</span></li>
        </ul>
      </el-card>
    </div>

    <!-- ============ 会诊协助 tab（轮 A：legacy loadConsultInbox/renderConsultInbox 语义迁移，
         数据源 GET /consults/inbox；doctor/pharmacist 专属） ============ -->
    <div v-else-if="tab === 'consults'" class="page-body" v-loading="consultLoading" data-testid="consult-inbox">
      <el-card v-if="consultErr" shadow="never"><el-alert type="error" :closable="false" show-icon :title="consultErr" /></el-card>
      <!-- 空态（legacy 逐字：含当前账号科室提示与一票语义） -->
      <el-card v-else-if="!consultItems.length" shadow="never">
        <div class="empty-box">
          <div>暂无其它科室的会诊协助请求。</div>
          <div class="lead" style="margin: 8px auto 0">当其它科室发起跨科室会诊并邀请您所在科室（{{ consultDept || '未设置' }}）时，会出现在这里；意见仅有建议权，每人一票。</div>
        </div>
      </el-card>
      <el-card v-for="c in consultItems" :key="c.id" shadow="never" style="border-left: 3px solid var(--accent)" :data-cid="c.id">
        <div style="display: flex; gap: 8px; align-items: center; flex-wrap: wrap">
          <h3 style="margin: 0">跨科室会诊协助</h3>
          <el-tag type="warning" effect="light" size="small" round>进行中</el-tag>
          <span class="src num" style="margin-left: auto">发起人 {{ c.initiator || '—' }}（{{ c.initiator_dept || '—' }}） · {{ fmtTs(c.created_at) }}</span>
        </div>
        <div style="margin-top: 6px; display: flex; gap: 6px; flex-wrap: wrap; align-items: center">
          <span class="src">受邀科室：</span>
          <el-tag v-for="(dpt, i) in (c.target_depts || [])" :key="i" size="small" effect="plain" round>{{ dpt }}</el-tag>
        </div>
        <!-- 三段式详情（ops:false：不出医生意见段——意见由下方表单呈现，legacy consultDetailBlocks 同语义） -->
        <div style="margin-top: 8px">
          <h4 style="margin: 0 0 4px">摘要（病例/会诊问题）</h4>
          <div class="txt" style="white-space: pre-wrap">{{ splitConsultQuestion(c.question).case || '（无）' }}</div>
          <template v-if="splitConsultQuestion(c.question).draft">
            <h4 style="margin: 10px 0 4px">AI 会诊小结（共识 · 分歧 · 建议）</h4>
            <div class="md-body" v-html="md(splitConsultQuestion(c.question).draft)"></div>
          </template>
        </div>
        <el-collapse v-if="splitAiByDept(c.ai_analysis).length" style="margin-top: 10px">
          <el-collapse-item :title="`各专科 AI 意见（${splitAiByDept(c.ai_analysis).length} 科）`" name="ai">
            <el-collapse v-for="(a, i) in splitAiByDept(c.ai_analysis)" :key="i" style="margin-bottom: 8px">
              <el-collapse-item :title="a.dept || 'AI 意见'" :name="String(i)">
                <div class="md-body" v-html="md(a.text)"></div>
              </el-collapse-item>
            </el-collapse>
          </el-collapse-item>
        </el-collapse>
        <!-- 随单影像缩略（data:image/ 白名单过滤，点击放大查看） -->
        <div v-if="consultThumbs(c).length" style="margin-top: 8px; display: flex; flex-wrap: wrap; gap: 6px">
          <el-image
            v-for="(u, i) in consultThumbs(c)" :key="i" :src="u" fit="cover"
            class="cthumb" :preview-src-list="consultThumbs(c)" :initial-index="i" preview-teleported
          />
        </div>
        <!-- 意见表单：本人已提交 → 已提交 chip（医生粒度 mine 判定，legacy o.doctor===consultMe） -->
        <div v-if="consultMine(c)" style="margin-top: 8px">
          <el-tag type="success" effect="light" size="small" round>已提交本科室意见（每人一票）</el-tag>
        </div>
        <div v-else style="margin-top: 8px">
          <textarea
            v-model="opDrafts[c.id]" class="inp" rows="2" :aria-label="'会诊意见 ' + c.id"
            placeholder="填写您所在科室的会诊意见（仅建议权，无驳回权）"
          ></textarea>
          <div style="margin-top: 6px">
            <el-button size="small" type="primary" @click="submitOpinion(c.id)">提交科室意见</el-button>
          </div>
        </div>
      </el-card>
    </div>

    <!-- ============ 待处理 / 历史记录 ============ -->
    <div v-else class="page-body" v-loading="loading">
      <!-- F1：待核对计数头（与侧边栏角标同源对账，服务端真值在页面上可见） -->
      <el-card v-if="tab === 'pending'" shadow="never" style="border-left: 3px solid var(--accent)" data-testid="pending-count">
        <b>共 <span class="num">{{ pendItems.length }}</span> 条{{ isMine ? (isPh ? '待办（待审处方）' : '待确认') : '待核对' }}</b>
        <span class="src" style="margin-left: 8px">{{ isMine ? '与侧边栏角标同源。' : (badges.cfgFull ? '与侧边栏角标同源；留痕模式下 AI 已自动签发项在此只读留档。' : '与侧边栏角标同源；需第二名人员签发/驳回。') }}</span>
      </el-card>
      <!-- 开药待审区（pharmacist/admin 专属数据源；qc/doctor 403 不拉取不渲染） -->
      <template v-if="tab === 'pending' && (role === 'pharmacist' || role === 'admin')">
        <el-card shadow="never" style="border-left: 3px solid var(--accent)" data-testid="rx-pending-section">
          <h3 style="margin: 0">开药待审（处方队列）</h3>
          <div class="src" style="margin-top: 4px">开药工作台直送的处方：核对药单与相互作用；驳回必须填写意见（双控：不能审核本人提交的处方）。</div>
        </el-card>
        <el-card v-if="!rxPendingItems.length" shadow="never"><div class="src">暂无开药待审处方。</div></el-card>
        <el-card v-for="it in rxPendingItems" :key="it.id" shadow="never" :data-rxid="it.id">
          <div style="display: flex; gap: 8px; align-items: center; flex-wrap: wrap">
            <b class="num">{{ it.id }}</b>
            <el-tag v-if="it.forced_high_risk" type="danger" effect="light" size="small" round>高危强制开立</el-tag>
            <!-- 整改轮 B 任务2（方案A）：字典外药处方显眼红徽——药剂科重点审核入口 -->
            <el-tag v-if="it.out_of_dict" type="danger" effect="dark" size="small" round data-testid="rx-ood-badge">含字典外药品·重点审核</el-tag>
            <span class="src">医生 {{ it.doctor || '—' }} · 科室 {{ it.dept || '—' }} · {{ fmtTs(it.created_at) }}</span>
            <span style="margin-left: auto; display: inline-flex; gap: 6px">
              <el-button size="small" type="primary" @click="rxReview(it.id, 'approve')">通过</el-button>
              <el-button size="small" @click="rxReview(it.id, 'reject')">驳回</el-button>
            </span>
          </div>
          <div class="txt" style="margin-top: 6px">病例摘要：{{ (it.case_text || '').slice(0, 120) }}{{ (it.case_text || '').length > 120 ? '…' : '' }}</div>
          <ul class="feed" style="margin-top: 4px">
            <li v-for="(d, di) in (it.drugs || [])" :key="di">
              <!-- 方案A：外典药红字 + 字典外徽标；理由（note）随行展示 -->
              <span class="e" :style="d.out_of_dict ? 'color: var(--danger, #c0392b)' : ''">{{ d.name }}</span>
              <el-tag v-if="d.out_of_dict" type="danger" effect="light" size="small" round>字典外</el-tag>
              <span class="src">{{ d.dose || '剂量待定' }} · {{ d.freq || '频次待定' }}{{ d.note ? ' · 理由：' + d.note : '' }}</span>
            </li>
          </ul>
          <div v-if="it.contraindication_reason || it.forced_high_risk" class="src" style="margin-top: 4px; border-left: 3px solid #c0392b; padding-left: 8px">
            联用理由：{{ it.contraindication_reason || '（未填写）' }}
          </div>
        </el-card>
      </template>

      <!-- 我的待确认 / 待核对表（空态文案按角色/模式逐字） -->
      <template v-if="tab === 'pending'">
        <el-card v-if="!pendItems.length" shadow="never">
          <div class="empty-box">
            <!-- F1：加载失败时空态主文案不再谎报「没有待核对项」（假空误导根因之一） -->
            <div>{{ loadErr ? '列表加载失败（详见上方错误提示），请点右上角「刷新」重试。' : (isMine ? (isPh ? '暂无待办（开药待审）。' : '没有您的待确认项。') : '没有待核对项。') }}</div>
            <div class="lead" style="margin: 8px auto 0">{{ isMine
              ? (filterQ ? '无匹配结果，可调整筛选词。' : (isPh
                ? '开药工作台直送的处方会出现在这里，由您签发/驳回；药品字典与相互作用规则在上方标签页维护。'
                : (badges.cfgFull ? '留痕模式下 AI 自动签发的高危结论会出现在这里，等待您知情确认。' : '留痕模式未开启（QC_AUTO_SIGN_FULL=false），高危结论走人工双控')))
              : (filterQ ? '无匹配结果，可调整筛选词。' : '当出现药物禁忌、影像高危征象或证据不足的低置信回答时，会自动进入这里，等待质控员/管理员签发。') }}</div>
          </div>
        </el-card>
        <el-card v-else body-style="padding:0" style="overflow: hidden">
          <table class="rv-table">
            <thead><tr><th>项目{{ isMine ? '（点击展开全文）' : '' }}</th><th>置信度</th><th>风险</th><th>提交人</th><th>{{ isMine ? '操作' : '操作' }}</th></tr></thead>
            <tbody>
              <tr v-for="p in pendSlice" :key="p.id">
                <td>
                  <details>
                    <summary class="qtext" style="cursor: pointer; list-style: none">{{ p.agent }}：{{ (p.question || '').slice(0, 60) }} <span class="src">▾ 展开全文</span></summary>
                    <div class="txt" style="margin-top: 8px; white-space: pre-wrap">{{ p.question }}</div>
                    <div class="txt md-body" style="margin-top: 6px" v-html="md(p.answer || '')"></div>
                  </details>
                  <!-- 缩略图条：仅渲染 data:image/ 白名单影像（后端 _review_image_view 按角色收窄后的前端消费） -->
                  <div v-if="imgOf(p).length" class="review-thumbs">
                    <img v-for="(u, ui) in imgOf(p)" :key="ui" class="thumb" alt="留痕影像缩略图" loading="lazy" :src="u" style="cursor: zoom-in" @click="showImg(u)" />
                  </div>
                </td>
                <td class="num">{{ (p.confidence || 0).toFixed(2) }}</td>
                <td><el-tag type="warning" effect="light" size="small" round>{{ p.risk_reason || '高风险' }}</el-tag></td>
                <td class="num">{{ p.submitted_by }}<div class="src">{{ fmtTs(p.ts) }}</div></td>
                <td>
                  <!-- doctor「我的待确认」行：AI 自动签发待本人知情确认（仅确认，无签发权） -->
                  <template v-if="isMine">
                    <el-tag v-if="p.status === 'approved' && /AI·/.test(String(p.reviewed_by || ''))" type="primary" effect="light" size="small" round>AI 自动签发 · 待您知情确认</el-tag>
                    <div style="margin-top: 6px; display: flex; gap: 6px; flex-wrap: wrap">
                      <el-button size="small" type="primary" @click="selfConfirm(p.id)">知情确认</el-button>
                      <el-button size="small" @click="openDetail(p)">详情</el-button>
                    </div>
                  </template>
                  <!-- qc/admin 待核对行：双控分流（drug 例外说明逐字） -->
                  <template v-else>
                    <template v-if="p.submitted_by === reviewMe"><span class="src">双控：你不能核对本人提交</span></template>
                    <template v-else-if="canSignItem(p)">
                      <div style="display: inline-flex; gap: 6px; vertical-align: middle">
                        <el-button size="small" type="primary" @click="resolve(p.id, 'approved')">通过签发</el-button>
                        <el-button size="small" @click="resolve(p.id, 'rejected')">驳回</el-button>
                      </div>
                    </template>
                    <span v-else class="src">{{ p.agent === 'drug'
                      ? (role === 'pharmacist' ? '药物核对项：药师仅可处理本人之外提交的条目（双控）' : '药物核对项：签发/驳回由药师（pharmacist）/管理员执行')
                      : (badges.cfgFull ? '留痕模式：AI 已自动签发（只读档案）' : '签发/驳回需质控员或管理员') }}</span>
                    <div style="margin-top: 6px"><el-button size="small" @click="openDetail(p)">详情</el-button></div>
                  </template>
                </td>
              </tr>
            </tbody>
          </table>
          <div class="pager">
            <el-button size="small" :disabled="pendPage === 0" @click="pendPage--; scrollTop()">‹ 上一页</el-button>
            <span class="src num">第 {{ pendPage + 1 }} / {{ pendPages }} 页 · 共 {{ pendItems.length }} 项</span>
            <el-button size="small" :disabled="pendPage >= pendPages - 1" @click="pendPage++; scrollTop()">下一页 ›</el-button>
          </div>
        </el-card>
      </template>

      <!-- 历史记录 / 我的历史（pharmacist 合并本人审核过的处方，双轨统一分页） -->
      <template v-if="tab === 'history'">
        <el-card v-if="!histItems.length" shadow="never">
          <div class="empty-box">
            <div>{{ isMine ? '暂无您的历史记录。' : '暂无已处理的历史记录。' }}</div>
            <div class="lead" style="margin: 8px auto 0">{{ filterQ ? '无匹配结果，可调整筛选词。' : '签发/驳回过的项会出现在这里。' }}</div>
          </div>
        </el-card>
        <el-card v-else body-style="padding:0" style="overflow: hidden">
          <table class="rv-table">
            <thead><tr><th>项目（点击展开全文）</th><th>置信度</th><th>风险</th><th>提交人</th><th>处理结果</th></tr></thead>
            <tbody>
              <!-- 处方审核记录专用行（rid/状态/意见/时间双轨取自处方字段） -->
              <tr v-for="p in histSlice.filter((x) => x._rx)" :key="'rx' + p.id">
                <td>
                  <details>
                    <summary class="qtext" style="cursor: pointer; list-style: none">处方审核：{{ p.id }}（医生 {{ p.doctor || '—' }}）<span class="src">▾ 展开详情</span></summary>
                    <div class="txt" style="margin-top: 8px; white-space: pre-wrap">{{ p.case_text }}</div>
                    <ul class="feed" style="margin-top: 6px">
                      <li v-for="(d, di) in (p.drugs || [])" :key="di">
                        <span class="e">{{ d.name }}</span>
                        <span class="src">{{ d.dose || '剂量待定' }} · {{ d.freq || '频次待定' }}</span>
                      </li>
                    </ul>
                  </details>
                </td>
                <td class="num">—</td>
                <td><el-tag :type="p.forced_high_risk ? 'danger' : 'primary'" effect="light" size="small" round>{{ p.forced_high_risk ? '高危强制开立' : '处方审核' }}</el-tag></td>
                <td class="num">{{ p.doctor || '—' }}<div class="src">{{ fmtTs(p.created_at) }}</div></td>
                <td>
                  <el-tag :type="p.status === 'approved' ? 'success' : 'warning'" effect="light" size="small" round>{{ p.status === 'approved' ? '已签发通过' : '已驳回' }}</el-tag>
                  <div class="src">{{ p.pharm_reviewer || '' }}{{ p.pharm_reviewed_at ? ' · ' + fmtTs(p.pharm_reviewed_at) : '' }} {{ p.pharm_opinion || '' }}</div>
                </td>
              </tr>
              <!-- review 队列历史行（状态徽章：自动签发 mid / 已签发 ok / 已驳回 warn） -->
              <tr v-for="p in histSlice.filter((x) => !x._rx)" :key="p.id">
                <td>
                  <details>
                    <summary class="qtext" style="cursor: pointer; list-style: none">{{ p.agent }}：{{ (p.question || '').slice(0, 60) }} <span class="src">▾ 展开全文</span></summary>
                    <div class="txt" style="margin-top: 8px; white-space: pre-wrap">{{ p.question }}</div>
                    <div class="txt md-body" style="margin-top: 6px" v-html="md(p.answer || '')"></div>
                  </details>
                  <div v-if="imgOf(p).length" class="review-thumbs">
                    <img v-for="(u, ui) in imgOf(p)" :key="ui" class="thumb" alt="留痕影像缩略图" loading="lazy" :src="u" style="cursor: zoom-in" @click="showImg(u)" />
                  </div>
                </td>
                <td class="num">{{ (p.confidence || 0).toFixed(2) }}</td>
                <td><el-tag type="warning" effect="light" size="small" round>{{ p.risk_reason || '高风险' }}</el-tag></td>
                <td class="num">{{ p.submitted_by }}<div class="src">{{ fmtTs(p.ts) }}</div></td>
                <td>
                  <el-tag :type="p.status === 'approved' ? (isAuto(p) ? 'primary' : 'success') : 'warning'" effect="light" size="small" round>
                    {{ p.status === 'approved' ? (isAuto(p) ? '自动签发' : '已签发') : '已驳回' }}
                  </el-tag>
                  <div class="src">{{ p.reviewed_by || '' }}{{ p.resolved_at ? ' · ' + fmtTs(p.resolved_at) : '' }} {{ p.review_note || '' }}</div>
                  <!-- 双控自动签发翻案（qc/admin 且非留痕模式；提交人不可翻案） -->
                  <div v-if="isAuto(p) && canSign && p.submitted_by !== reviewMe" style="margin-top: 6px">
                    <el-button size="small" @click="reopen(p.id)">转人工复核</el-button>
                  </div>
                </td>
              </tr>
            </tbody>
          </table>
          <div class="pager">
            <el-button size="small" :disabled="histPage === 0" @click="histPage--; scrollTop()">‹ 上一页</el-button>
            <span class="src num">第 {{ histPage + 1 }} / {{ histPages }} 页 · 共 {{ histItems.length }} 条已处理</span>
            <el-button size="small" :disabled="histPage >= histPages - 1" @click="histPage++; scrollTop()">下一页 ›</el-button>
          </div>
        </el-card>
      </template>
    </div>

    <!-- ============ 核对详情对话框（openReviewDetail 语义） ============ -->
    <el-dialog v-model="detailOn" :title="detail ? '核对详情 · ' + detail.agent : '核对详情'" width="640px">
      <template v-if="detail">
        <div class="src" style="margin-bottom: 12px">
          <el-tag :type="confType(detail.confidence)" effect="light" size="small" round>{{ confLabel(detail.confidence) }} {{ (detail.confidence || 0).toFixed(2) }}</el-tag>
          <el-tag type="warning" effect="light" size="small" round>{{ detail.risk_reason || '高风险' }}</el-tag>
          <el-tag v-if="isAuto(detail)" type="primary" effect="light" size="small" round>自动签发</el-tag>
          <el-tag v-if="detail.confirmed_by_self" type="success" effect="light" size="small" round>本人已确认</el-tag>
        </div>
        <h4>问题</h4><div class="txt md-body" v-html="md(detail.question || '')"></div>
        <h4 style="margin-top: 14px">AI 回答</h4><div class="txt md-body" v-html="md(detail.answer || '')"></div>
        <template v-if="imgOf(detail).length">
          <h4 style="margin-top: 14px">影像（点击放大）</h4>
          <div style="margin-top: 6px; display: flex; flex-wrap: wrap; gap: 6px">
            <img v-for="(u, ui) in imgOf(detail)" :key="ui" class="thumb" style="width: 38px; height: 38px; object-fit: cover; border-radius: 6px; cursor: zoom-in" :src="u" alt="待核对影像" @click="showImg(u)" />
          </div>
        </template>
        <h4 style="margin-top: 14px">提交人</h4><div>{{ detail.submitted_by || '' }}</div>
        <template v-if="detail.reviewed_by && detail.status !== 'pending'">
          <h4 style="margin-top: 14px">审核</h4><div>{{ detail.reviewed_by }}{{ detail.resolved_at ? ' · ' + fmtTs(detail.resolved_at) : '' }}{{ detail.review_note ? ' · ' + detail.review_note : '' }}</div>
        </template>
        <!-- 操作区按角色/模式渲染（canSignItem 分流 + 本人知情确认 + 翻案） -->
        <div style="margin-top: 16px; display: flex; gap: 8px; flex-wrap: wrap">
          <el-button
            v-if="detailMine && detail.self_confirm_required && !detail.confirmed_by_self"
            size="small" type="primary" @click="selfConfirm(detail.id); detailOn = false"
          >知情确认</el-button>
          <template v-if="canSignItem(detail) && !detailMine">
            <el-button size="small" type="primary" @click="resolve(detail.id, 'approved'); detailOn = false">通过签发</el-button>
            <el-button size="small" @click="resolve(detail.id, 'rejected'); detailOn = false">驳回</el-button>
          </template>
          <el-button v-if="canSign && !detailMine && isAuto(detail)" size="small" @click="reopen(detail.id); detailOn = false">转人工复核</el-button>
          <span v-if="!canSignItem(detail) && !detailMine" class="src">{{ detail.agent === 'drug'
            ? '签发/驳回由药师（pharmacist）/管理员执行' : '签发/驳回由质控员（qc）/管理员执行' }}</span>
        </div>
      </template>
    </el-dialog>

    <!-- ============ 影像放大查看（data:image/ 白名单外一律拒绝渲染） ============ -->
    <el-dialog v-model="imgOn" title="影像查看" width="560px">
      <img v-if="imgSrc" style="max-width: 100%" :src="imgSrc" alt="待核对影像" />
    </el-dialog>

    <!-- ============ 驳回 · 填写原因（review 队列；备注可选，先备注再签发确认） ============ -->
    <el-dialog v-model="rejectOn" title="驳回 · 填写原因" width="440px">
      <textarea v-model="rejectNote" class="inp" rows="3" aria-label="驳回原因" placeholder="驳回原因（可选，将记入审计留痕）"></textarea>
      <template #footer>
        <el-button size="small" @click="rejectOn = false">取消</el-button>
        <el-button size="small" type="danger" @click="confirmReject">确认驳回</el-button>
      </template>
    </el-dialog>

    <!-- ============ 签发确认（F4：轻量确认，Escape/取消均不产生 API 调用） ============ -->
    <el-dialog v-model="signOn" title="签发确认" width="380px">
      <div class="txt">确认【{{ signDec === 'approved' ? '通过签发' : '驳回' }}】该核对项？此操作记入审计留痕。</div>
      <template #footer>
        <el-button size="small" @click="signOn = false">取消</el-button>
        <el-button size="small" :type="signDec === 'approved' ? 'primary' : 'danger'" :loading="resolving" @click="doResolve">确认</el-button>
      </template>
    </el-dialog>

    <!-- ============ 开药驳回 · 审核意见（必填，服务端 422 双保险） ============ -->
    <el-dialog v-model="rxRejectOn" title="驳回处方 · 填写审核意见" width="440px">
      <textarea v-model="rxOpinion" class="inp" rows="3" aria-label="驳回意见" placeholder="驳回意见（必填，将反馈给开药医生用于重写）"></textarea>
      <template #footer>
        <el-button size="small" @click="rxRejectOn = false">取消</el-button>
        <el-button size="small" type="danger" @click="rxRejectGo">确认驳回</el-button>
      </template>
    </el-dialog>

    <!-- ============ 移出病例库 · 原因必填（软删除留痕可审计） ============ -->
    <el-dialog v-model="caRemoveOn" :title="'移出病例库 · ' + caRemoveId" width="440px">
      <div class="src">移除为软删除（status=removed 留痕，可审计不可静默抹除），移除原因必填并记入审计。</div>
      <textarea v-model="caRemoveReason" class="inp" rows="3" aria-label="移除原因（必填）" placeholder="移除原因（必填，如：患者身份信息录入有误需重新归档）" style="margin-top: 8px"></textarea>
      <template #footer>
        <el-button size="small" @click="caRemoveOn = false">取消</el-button>
        <el-button size="small" type="danger" @click="caRemoveGo">确认移除</el-button>
      </template>
    </el-dialog>

    <!-- ============ 清空待核对（admin；确认词「清空」双保险） ============ -->
    <el-dialog v-model="purgeOn" title="清空待核对 · 高危操作" width="440px">
      <div class="txt">将删除全部 <b class="num">{{ purgeN }}</b> 条「待核对」项；已签发/驳回的历史保留。<br />
        <span class="src">此操作记入审计。为防误触，请输入「清空」二字后确认。</span></div>
      <input v-model="purgeWord" class="inp" aria-label="输入清空确认" spellcheck="false" style="margin-top: 10px" placeholder="输入：清空" autocomplete="off" />
      <template #footer>
        <el-button size="small" @click="purgeOn = false">取消</el-button>
        <el-button size="small" type="danger" :disabled="purgeWord.trim() !== '清空'" @click="purgeGo">确认清空</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
// 状态语义对齐 legacy：reviewItems/myConfirmItems/historyItems/rxPendingItems/rxHistoryItems/
// reviewMe/reviewFilterQ/分页（12/页）/seq 守卫；canSign=(qc||admin)&&!cfgFull；
// canSignItem: drug→pharmacist/admin（问题4 收权），其它 agent→canSign。
import { computed, defineOptions, onActivated, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import http from '../api'
import { md, fmtTs, detailText, splitConsultQuestion, splitAiByDept } from '../utils/assist'
import { useAuthStore } from '../stores/auth'
import { useBadgeStore } from '../stores/badges'
import DrugDictEditor from '../components/DrugDictEditor.vue'
import DrugRulesEditor from '../components/DrugRulesEditor.vue'

defineOptions({ name: 'ReviewCenterView' })

const auth = useAuthStore()
const badges = useBadgeStore()
const role = computed(() => auth.role)
const isMine = computed(() => role.value !== 'qc' && role.value !== 'admin') // doctor/pharmacist = 我的记录
const isPh = computed(() => role.value === 'pharmacist')
const canSign = computed(() => (role.value === 'qc' || role.value === 'admin') && !badges.cfgFull)
function canSignItem(p) {
  if (!p) return false
  if (p.agent === 'drug') return role.value === 'admin' || role.value === 'pharmacist'
  return canSign.value
}

// 头部文案（legacy renderReviewHeader 逐字）
const headTitle = computed(() => isMine.value ? (isPh.value ? '审核中心 · 药剂科' : '审核中心 · 我的记录') : '审核中心 · 高风险双人核对')
const headSub = computed(() => isMine.value
  ? (isPh.value
    ? '开药处方由您签发/驳回；药品字典与相互作用规则在下方标签页维护（药学专业数据药剂科维护）'
    : '仅显示您提交的待确认项与历史记录；签发/驳回由质控员（qc）/管理员执行')
  : '高风险结论需第二名医师/药师签发')

const tab = ref('pending')
const loading = ref(false)
const loadErr = ref('')
const reviewItems = ref([])
const myConfirmItems = ref([])
const historyItems = ref([])
const rxPendingItems = ref([])
const rxHistoryItems = ref([])
const reviewMe = ref('')
const filterQ = ref('')
const pendPage = ref(0)
const histPage = ref(0)
const REVIEW_PER = 12
let loadSeq = 0 // 加载请求序号守卫：并发加载只应用最新一次响应（legacy reviewLoadSeq 同语义）

function switchTab(t) {
  tab.value = t
  if (t === 'casearchive') loadCaseArchive()
  if (t === 'consults') loadConsultInbox() // 轮 A：会诊收件箱独立数据源（/consults/inbox），不走 renderReview 语义
}

// ---- 影像消费（_review_image_view 前端侧）：data:image/ 前缀白名单过滤 ----
function imgOf(p) {
  return ((p && p.images) || []).filter((u) => String(u).startsWith('data:image/'))
}
const imgOn = ref(false)
const imgSrc = ref('')
function showImg(src) {
  if (!src || !src.startsWith('data:image/')) return
  imgSrc.value = src
  imgOn.value = true
}

// 双控自动签发判定（reviewed_by 含「AI·阈值自动/留痕模式」）
function isAuto(p) {
  return p.status === 'approved' && /AI·(阈值自动|留痕模式)/.test(String(p.reviewed_by || ''))
}
function confType(c) { return c >= 0.75 ? 'success' : (c >= 0.6 ? 'primary' : 'warning') }
function confLabel(c) { return c >= 0.75 ? '高置信' : (c >= 0.6 ? '中等' : '偏低') }

// ---- 数据加载（qc/admin 与 mine 双分支；角标随数据刷新） ----
async function load() {
  const seq = ++loadSeq
  loading.value = true
  loadErr.value = ''
  try {
    if (!isMine.value) {
      const { data } = await http.get('/medical/review/pending')
      if (seq !== loadSeq) return
      reviewItems.value = data.pending || []
      reviewMe.value = data.me || ''
      // 历史拉取失败静默不阻塞（legacy try-catch 同语义）；drug 类记录所有角色都过滤
      try {
        const h = await http.get('/medical/review/history')
        if (seq !== loadSeq) return
        historyItems.value = (h.data.items || []).filter((i) => i.status !== 'pending' && i.agent !== 'drug')
      } catch (_) { if (seq !== loadSeq) return }
      rxPendingItems.value = []
      if (role.value === 'admin') {
        try {
          const xp = await http.get('/medical/prescriptions/pending', { silentToast: true }) // qc 403 不拉取
          if (seq !== loadSeq) return
          if (xp.status === 200) rxPendingItems.value = xp.data.items || []
        } catch (_) { /* 开药待审加载失败静默 */ }
      }
      // 角标：qc=其它助手高危项（drug 除外）；admin=全部待核对+开药待审
      badges.reviewTodoCount = (role.value === 'qc')
        ? (reviewItems.value || []).filter((i) => i.agent !== 'drug').length
        : (reviewItems.value || []).length + (rxPendingItems.value || []).length
    } else {
      const { data } = await http.get('/medical/review/my-pending-confirm')
      if (seq !== loadSeq) return
      myConfirmItems.value = data.items || []
      reviewMe.value = data.me || ''
      // 「我的历史」= 本人参与的记录（submitted_by=me OR reviewed_by=me）+ drug 过滤（全角色一致）；
      // 拉取失败静默不阻塞（legacy 同语义）
      try {
        const h = await http.get('/medical/review/history')
        if (seq !== loadSeq) return
        historyItems.value = (h.data.items || []).filter((i) => (i.submitted_by === reviewMe.value || i.reviewed_by === reviewMe.value) && i.status !== 'pending' && i.agent !== 'drug')
      } catch (_) { if (seq !== loadSeq) return }
      rxHistoryItems.value = []
      if (role.value === 'pharmacist') {
        try {
          const xp = await http.get('/medical/prescriptions/pending', { silentToast: true })
          if (seq !== loadSeq) return
          if (xp.status === 200) rxPendingItems.value = xp.data.items || []
        } catch (_) { /* 静默 */ }
        try {
          const rh = await http.get('/medical/prescriptions/reviewed-by-me', { silentToast: true })
          if (seq !== loadSeq) return
          if (rh.status === 200) rxHistoryItems.value = rh.data.items || []
        } catch (_) { /* 静默 */ }
        // pharmacist 待办角标只算待审处方数（药物待核对区已移除）
        badges.reviewTodoCount = (rxPendingItems.value || []).length
      }
      // 轮 A：进入审核中心即刷新「我的待确认」黄徽标与「待我意见的会诊」红徽标
      // （legacy loadReviewMine 末尾 renderRail/refreshConsultBadge 同语义）
      badges.myConfirmCount = (myConfirmItems.value || []).length
      badges.refreshConsultBadge()
    }
  } catch (e) {
    if (seq !== loadSeq) return
    loadErr.value = '⚠ ' + detailText(e)
  } finally {
    if (seq === loadSeq) loading.value = false
  }
}
onMounted(load)
// F1：KeepAlive 缓存复活（切路由回来）时重拉——保证列表数据新鲜，防陈旧/空列表误导。
// 首次 activated 由 onMounted 覆盖（同轮不重复拉取），仅缓存复活时触发。
const activatedOnce = ref(false)
onActivated(() => {
  if (activatedOnce.value) load()
  activatedOnce.value = true
})

// ---- 待处理列表（筛选+分页）----
const pendItems = computed(() => {
  const q = filterQ.value.trim().toLowerCase()
  const src = isMine.value ? myConfirmItems.value : reviewItems.value
  if (!q) return src
  return src.filter((p) => ((p.agent || '') + ' ' + (p.question || '') + ' ' + (p.submitted_by || '') + ' ' + (p.risk_reason || '')).toLowerCase().includes(q))
})
const pendPages = computed(() => Math.max(1, Math.ceil(pendItems.value.length / REVIEW_PER)))
const pendSlice = computed(() => {
  const pg = Math.min(pendPage.value, pendPages.value - 1)
  return pendItems.value.slice(pg * REVIEW_PER, pg * REVIEW_PER + REVIEW_PER)
})

// ---- 历史（pharmacist 合并处方审核记录：补 _rx/agent/submitted_by/risk_reason 供筛选与行渲染分流）----
const histItems = computed(() => {
  const q = filterQ.value.trim().toLowerCase()
  const rxh = (role.value === 'pharmacist' ? (rxHistoryItems.value || []) : [])
    .map((p) => ({ ...p, _rx: true, agent: '处方审核', submitted_by: p.doctor || '—', risk_reason: p.forced_high_risk ? '高危强制开立' : '处方审核' }))
  const hist = rxh.concat(historyItems.value || [])
  if (!q) return hist
  return hist.filter((p) => ((p.agent || '') + ' ' + (p.question || '') + ' ' + (p.submitted_by || '') + ' ' + (p.risk_reason || '')).toLowerCase().includes(q))
})
const histPages = computed(() => Math.max(1, Math.ceil(histItems.value.length / REVIEW_PER)))
const histSlice = computed(() => {
  const pg = Math.min(histPage.value, histPages.value - 1)
  return histItems.value.slice(pg * REVIEW_PER, pg * REVIEW_PER + REVIEW_PER)
})

function scrollTop() { window.scrollTo(0, 0) }

// ---- 签发/驳回/翻案/知情确认（review 队列）----
const detailOn = ref(false)
const detail = ref(null)
const detailMine = computed(() => !!detail.value && detail.value.submitted_by === reviewMe.value)
function openDetail(p) { detail.value = p; detailOn.value = true }

const rejectOn = ref(false)
const rejectNote = ref('')
const rejectTarget = ref('')
const signOn = ref(false)
const signDec = ref('approved')
const signNote = ref('')
const resolving = ref(false)

// resolve：驳回先填原因（可选备注），签发直接确认（F4：备注之后先确认再执行）
function resolve(id, dec) {
  if (dec === 'rejected') {
    rejectTarget.value = id
    rejectNote.value = ''
    rejectOn.value = true
    return
  }
  askSignConfirm(id, dec)
}
function askSignConfirm(id, dec, note) {
  signDec.value = dec
  signTarget.value = { id, dec, note: note || '' }
  signOn.value = true
}
const signTarget = ref(null)
// 确认驳回：备注之后先签发确认框再执行（legacy confirmReject 同语义）
function confirmReject() { askSignConfirm(rejectTarget.value, 'rejected', rejectNote.value.trim()) }
async function doResolve() {
  const t = signTarget.value
  if (!t) { signOn.value = false; return }
  resolving.value = true
  try {
    await http.post('/medical/review/' + encodeURIComponent(t.id) + '/resolve', { decision: t.dec, note: t.note })
    ElMessage.success(t.dec === 'approved' ? '已签发通过' : '已驳回')
    signOn.value = false
    detailOn.value = false
    load()
  } catch (e) {
    ElMessage.error('操作失败：' + detailText(e))
    load() // 整改轮 B 任务1（防僵尸按钮）：400/409 状态失效时强制刷新列表，清掉残留操作按钮
  } finally { resolving.value = false }
}
// 双控自动签发翻案：回到待核对由另一名人员人工复核（双控对称：提交人不可翻案）
async function reopen(id) {
  try {
    await http.post('/medical/review/' + encodeURIComponent(id) + '/reopen')
    ElMessage.success('已转人工复核，请在「待处理」中核对')
    load()
  } catch (e) {
    ElMessage.error('操作失败：' + detailText(e))
    load() // 任务1：状态失效（400/409）时同步刷新，防僵尸按钮
  }
}
// 医生对 AI 留痕自动签发项知情确认（仅本人；后端校验+审计 self_confirmed）
async function selfConfirm(id) {
  try {
    await http.post('/medical/review/' + encodeURIComponent(id) + '/self-confirm')
    ElMessage.success('已确认知悉，感谢配合')
    load()
  } catch (e) {
    ElMessage.error('操作失败：' + detailText(e))
    load() // 任务1：状态失效（400/409）时同步刷新，防僵尸按钮
  }
}

// ---- 开药待审：通过/驳回（POST /prescriptions/{rid}/review；驳回意见必填） ----
// 整改轮 B 任务1（驳回无反应修复·根因）：rxRejectOn 此前**未声明**（模板/处理函数均在用，
// script-setup 运行时解析为 undefined）→ 点「驳回」执行 rxRejectOn.value=true 抛 TypeError，
// 驳回弹条永不出现且无任何提示——即用户实测的「无反应」。补上声明即闭环。
const rxOpinion = ref('')
const rxRejectOn = ref(false)
const rxRejectId = ref('')
function rxReview(id, action) {
  if (action === 'reject') {
    rxRejectId.value = id
    rxOpinion.value = ''
    rxRejectOn.value = true
    return
  }
  rxReviewPost(id, { action: 'approve', opinion: '' })
}
async function rxRejectGo() {
  const op = rxOpinion.value.trim()
  if (!op) { ElMessage.warning('驳回必须填写审核意见'); return }
  rxRejectOn.value = false
  await rxReviewPost(rxRejectId.value, { action: 'reject', opinion: op })
}
async function rxReviewPost(id, body) {
  try {
    await http.post('/medical/prescriptions/' + encodeURIComponent(id) + '/review', body)
    ElMessage.success(body.action === 'approve' ? '处方已签发通过' : '已驳回，意见将反馈给开药医生')
    load()
  } catch (e) {
    // 整改轮 B 任务1（驳回无反应修复）：错误提示照常透出；无论失败原因（典型：该处方
    // 状态已变，400「非法状态流转」= 列表未刷新时的僵尸按钮），一律强制刷新列表——
    // 保证「驳回→弹条→意见→提交→列表刷新」闭环，按钮不再残留于已处理的处方卡上。
    ElMessage.error('操作失败：' + detailText(e))
    load()
  }
}

// ---- 会诊协助收件箱（轮 A：legacy loadConsultInbox/renderConsultInbox/submitOpinion/
//      updateConsultBadge 语义迁移，doctor/pharmacist 专属；动态内容经 Vue 模板转义） ----
const consultItems = ref([])
const consultMe = ref('')
const consultDept = ref('')
const consultLoading = ref(false)
const consultErr = ref('')
const opDrafts = reactive({}) // 各会诊单意见草稿（按 id 独立，切 tab 不丢）

async function loadConsultInbox() {
  consultLoading.value = true
  consultErr.value = ''
  try {
    const { data } = await http.get('/medical/consults/inbox', { silentToast: true })
    consultItems.value = data.items || []
    consultMe.value = data.me || ''
    consultDept.value = data.dept || ''
    // tab 徽标：待我（本科室）意见数——科室一票口径 o.dept===dept（legacy updateConsultBadge 同实现）
    badges.meDept = consultDept.value
    badges.consultPendingOps = (consultItems.value || [])
      .filter((c) => !((c.opinions || []).some((o) => o.dept === consultDept.value))).length
  } catch (e) {
    consultErr.value = '⚠ ' + detailText(e)
  } finally {
    consultLoading.value = false
  }
}

// 本人是否已提交意见（legacy 行级判定 o.doctor===consultMe，医生粒度）
function consultMine(c) {
  return (c.opinions || []).some((o) => o.doctor === consultMe.value)
}

// 随单影像缩略：data:image/ 前缀白名单过滤（legacy consultThumbs 同实现）
function consultThumbs(c) {
  return (c.images || []).filter((u) => String(u).startsWith('data:image/'))
}

// 提交科室意见（仅建议权无驳回权；空内容阻断文案逐字 legacy）
async function submitOpinion(id) {
  const content = String(opDrafts[id] || '').trim()
  if (!content) { ElMessage.warning('请先填写意见内容'); return }
  try {
    await http.post('/medical/consults/' + encodeURIComponent(id) + '/opinion', { content }, { silentToast: true })
    ElMessage.success('已提交科室意见')
    delete opDrafts[id]
    loadConsultInbox()
  } catch (e) {
    ElMessage.error('提交失败：' + detailText(e))
  }
}

// ---- 病例库（qc/admin；数据源 /case-archive，客户端二次筛选 dept/status/日期区间） ----
const caItems = ref([])
const caStats = ref({})
const caDept = ref('')
const caStatus = ref('')
const caFrom = ref('')
const caTo = ref('')
const caDepts = computed(() => [...new Set((caItems.value || []).map((i) => i.dept || '').filter(Boolean))])
const caList = computed(() => (caItems.value || []).filter((i) => (!caDept.value || (i.dept || '') === caDept.value)
  && (!caStatus.value || (i.status || '') === caStatus.value)
  && (!caFrom.value || String(i.archived_at || '').slice(0, 10) >= caFrom.value)
  && (!caTo.value || String(i.archived_at || '').slice(0, 10) <= caTo.value)))
async function loadCaseArchive() {
  try {
    const { data } = await http.get('/medical/case-archive')
    caItems.value = data.items || []
    caStats.value = data.stats || {}
  } catch (e) {
    ElMessage.error('病例库加载失败：' + detailText(e))
  }
}
// 移出病例库：原因必填（空值阻断 + 服务端 422 双保险）
const caRemoveOn = ref(false)
const caRemoveId = ref('')
const caRemoveReason = ref('')
function caRemove(aid) {
  caRemoveId.value = aid
  caRemoveReason.value = ''
  caRemoveOn.value = true
}
async function caRemoveGo() {
  const reason = caRemoveReason.value.trim()
  if (!reason) { ElMessage.warning('移除原因必填'); return }
  try {
    await http.post('/medical/case-archive/' + encodeURIComponent(caRemoveId.value) + '/remove', { reason })
    ElMessage.success('已移出病例库')
    caRemoveOn.value = false
    loadCaseArchive()
  } catch (e) {
    ElMessage.error('失败：' + detailText(e))
  }
}

// ---- 清空待核对（admin）：先取数量 → 确认词「清空」→ POST purge-pending ----
const purgeOn = ref(false)
const purgeN = ref(0)
const purgeWord = ref('')
async function purgePending() {
  let n = 0
  try {
    const { data } = await http.get('/medical/review/pending')
    n = (data.pending || []).length
  } catch (e) { ElMessage.error('加载失败：' + detailText(e)); return }
  purgeN.value = n
  purgeWord.value = ''
  purgeOn.value = true
}
async function purgeGo() {
  try {
    const { data } = await http.post('/medical/admin/review/purge-pending', { confirm_text: '清空' })
    ElMessage.success(`已清空 ${data.purged} 条待核对`)
    purgeOn.value = false
    load()
  } catch (e) {
    ElMessage.error('操作失败：' + detailText(e))
  }
}
</script>

<style scoped>
.tabbar { padding: 10px 26px; gap: 8px }
.pager {
  display: flex; align-items: center; gap: 12px; padding: 12px 16px;
  border-top: 1px solid var(--line);
}
.rv-table { width: 100%; border-collapse: collapse; font-size: 13px }
.rv-table th, .rv-table td { text-align: left; padding: 10px 14px; border-bottom: 1px solid var(--line); vertical-align: top }
.rv-table th { color: var(--ink-3); font-weight: 500; font-size: 12px; white-space: nowrap }
.qtext { font-weight: 500 }
.review-thumbs { display: grid; grid-template-columns: repeat(auto-fill, minmax(56px, 1fr)); gap: 6px; margin-top: 8px; max-width: 260px }
.review-thumbs .thumb { width: 100%; aspect-ratio: 1; object-fit: cover; border-radius: 6px; border: 1px solid var(--line) }
.record-pre {
  background: var(--rail); border: 1px solid var(--line); border-radius: 6px;
  padding: 10px; font-size: 11.5px; margin: 6px 0 0; overflow-x: auto;
  white-space: pre-wrap;
}
.empty-box { text-align: center; padding: 28px 0; color: var(--ink-2) }
/* 会诊缩略图（轮 A：与 ConsultView .cthumb 同款） */
.cthumb { width: 38px; height: 38px; border-radius: 6px; border: 1px solid var(--line); cursor: zoom-in; display: block }
</style>
