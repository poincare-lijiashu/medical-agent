<template>
  <div class="page">
    <PageHeader
      title="物资耗材"
      :sub="`耗材目录、批次效期、科室/手术出库与高值植入耗材患者追溯。当前角色 ${role}`"
    >
      <template #actions>
        <button class="btn" type="button" :disabled="loading" @click="reloadAll">
          <AppIcon name="refresh" :size="15" /> 刷新
        </button>
      </template>
    </PageHeader>

    <div class="kpi-strip">
      <KpiCard
        label="在用耗材"
        :value="summary.consumableCount"
        unit="项"
        :meta="`库存合计 ${summary.totalStock} 件`"
      />
      <KpiCard label="库存金额" :value="money(summary.stockValue)" meta="按批次单价 × 余量" />
      <KpiCard
        label="近效期批次"
        :value="summary.nearExpiryBatches"
        unit="批"
        :tone="summary.nearExpiryBatches > 0 ? 'warn' : 'default'"
        meta="剩余天数低于阈值；不拦截出库"
      />
      <KpiCard
        label="已过期批次"
        :value="summary.expiredBatches"
        unit="批"
        :tone="summary.expiredBatches > 0 ? 'danger' : 'default'"
        meta="禁止出库，须退货或报损"
      />
    </div>

    <div class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">{{ tabTitle }}</span>
        <span class="badge badge--neutral">{{ rowCount }}</span>
        <span class="spacer" />

        <template v-if="tab === 'catalog'">
          <label class="field f-q">
            <span class="label">检索</span>
            <input
              v-model.trim="keyword"
              class="input"
              placeholder="名称 / 编码 / 厂商 / 注册证号"
              aria-label="按名称、编码、厂商或注册证号检索"
              @keydown.enter="loadCatalog"
            />
          </label>
          <label class="field f-cat">
            <span class="label">类别</span>
            <select v-model="category" class="select" @change="loadCatalog">
              <option value="">全部</option>
              <option value="GENERAL">一般耗材</option>
              <option value="HIGH_VALUE">高值耗材</option>
              <option value="IMPLANT">植入类</option>
            </select>
          </label>
          <button class="btn btn--sm" type="button" :disabled="loading" @click="loadCatalog">
            <AppIcon name="search" :size="13" /> 查询
          </button>
          <button v-if="canCatalog" class="btn btn--sm btn--primary" type="button" @click="openCreate">
            <AppIcon name="plus" :size="13" /> 新建耗材
          </button>
        </template>

        <template v-else-if="tab === 'traces'">
          <label class="field f-q">
            <span class="label">批号</span>
            <input v-model.trim="traceBatchNo" class="input" placeholder="精确批号" @keydown.enter="loadTraces" />
          </label>
          <label class="field f-q">
            <span class="label">病案号</span>
            <input v-model.trim="tracePatientNo" class="input" placeholder="支持模糊" @keydown.enter="loadTraces" />
          </label>
          <button class="btn btn--sm" type="button" :disabled="loading" @click="loadTraces">
            <AppIcon name="search" :size="13" /> 查询
          </button>
          <button class="btn btn--sm" type="button" :disabled="loading" @click="openRecall">
            <AppIcon name="warning" :size="13" /> 批次召回
          </button>
        </template>

        <template v-else-if="tab === 'issues'">
          <button v-if="canIssue" class="btn btn--sm btn--primary" type="button" @click="openIssueCreate">
            <AppIcon name="plus" :size="13" /> 新建出库
          </button>
        </template>

        <div class="seg" role="tablist" aria-label="物资耗材视图">
          <button class="seg-btn" :class="{ active: tab === 'catalog' }" type="button" @click="switchTab('catalog')">
            目录
          </button>
          <button class="seg-btn" :class="{ active: tab === 'warnings' }" type="button" @click="switchTab('warnings')">
            库存预警
          </button>
          <button class="seg-btn" :class="{ active: tab === 'issues' }" type="button" @click="switchTab('issues')">
            出库记录
          </button>
          <button class="seg-btn" :class="{ active: tab === 'traces' }" type="button" @click="switchTab('traces')">
            使用追溯
          </button>
        </div>
      </div>

      <TableSkeleton v-if="loading" :rows="5" />

      <LoadError v-else-if="loadError" :message="loadError" :retrying="loading" @retry="reloadAll" />

      <EmptyState
        v-else-if="rowCount === 0"
        art="orders"
        :title="emptyTitle"
        :desc="emptyDesc"
      />

      <div v-else-if="tab === 'catalog'" class="table-wrap">
        <table class="table table--stack">
          <thead>
            <tr>
              <th>编码</th>
              <th>耗材</th>
              <th>类别</th>
              <th class="is-num">单价</th>
              <th class="is-num">库存</th>
              <th>状态</th>
              <th style="width: 92px">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="r in consumables"
              :key="r.consumable.id"
              class="clickable"
              tabindex="0"
              @click="openBatches(r)"
              @keydown.enter="openBatches(r)"
            >
              <td class="mono" data-primary>{{ r.consumable.code }}</td>
              <td data-label="耗材">
                <div class="is-strong">{{ r.consumable.name }}</div>
                <div class="muted" style="font-size: var(--fs-small)">
                  {{ r.consumable.spec }} · {{ r.consumable.manufacturer || '—' }}
                </div>
              </td>
              <td data-label="类别">
                <span class="badge" :class="categoryTone(r.consumable.category)">
                  {{ categoryLabel(r.consumable.category) }}
                </span>
              </td>
              <td class="is-num" data-label="单价">{{ money(r.consumable.price) }}</td>
              <td class="is-num is-strong" data-label="库存">{{ r.totalStock }} {{ r.consumable.unit }}</td>
              <td data-label="状态">
                <StatusPill :value="r.consumable.enabled ? 'ACTIVE' : 'INACTIVE'" />
              </td>
              <td data-label="操作">
                <button class="btn btn--sm" type="button" @click.stop="openBatches(r)">批次</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <div v-else-if="tab === 'warnings'" class="table-wrap">
        <table class="table table--stack">
          <thead>
            <tr>
              <th>耗材</th>
              <th>批号</th>
              <th>效期</th>
              <th class="is-num">余量</th>
              <th>状态</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="w in warnings" :key="`${w.consumableCode}-${w.batchNo}`">
              <td data-primary>
                <div class="is-strong">{{ w.consumableName }}</div>
                <div class="mono muted" style="font-size: var(--fs-small)">{{ w.consumableCode }}</div>
              </td>
              <td class="mono" data-label="批号">{{ w.batchNo }}</td>
              <td data-label="效期">{{ date(w.expireDate) }}</td>
              <td class="is-num" data-label="余量">{{ w.quantity }}</td>
              <td data-label="状态">
                <StatusPill :value="w.expired ? 'EXPIRED' : 'NEAR_EXPIRY'" />
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <div v-else-if="tab === 'issues'" class="table-wrap">
        <table class="table table--stack">
          <thead>
            <tr>
              <th>出库单号</th>
              <th>科室</th>
              <th>用途</th>
              <th class="is-num">金额</th>
              <th>操作人</th>
              <th>时间</th>
              <th style="width: 92px">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="v in issues"
              :key="v.issue.id"
              class="clickable"
              tabindex="0"
              @click="openIssueDetail(v)"
              @keydown.enter="openIssueDetail(v)"
            >
              <td class="mono" data-primary>{{ v.issue.issueNo }}</td>
              <td data-label="科室">{{ v.issue.deptName || shortId(v.issue.deptId) }}</td>
              <td data-label="用途">{{ purposeLabel(v.issue.purpose) }}</td>
              <td class="is-num is-strong" data-label="金额">{{ money(v.issue.totalAmount) }}</td>
              <td data-label="操作人">{{ v.issue.operator }}</td>
              <td data-label="时间">{{ dateTime(v.issue.createdAt) }}</td>
              <td data-label="操作">
                <button class="btn btn--sm" type="button" @click.stop="openIssueDetail(v)">明细</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <div v-else class="table-wrap">
        <table class="table table--stack">
          <thead>
            <tr>
              <th>耗材</th>
              <th>批号</th>
              <th>患者</th>
              <th class="is-num">数量</th>
              <th>手术</th>
              <th>使用时间</th>
              <th>登记人</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="t in traces" :key="t.id">
              <td data-primary>
                <div class="is-strong">{{ t.consumableName }}</div>
                <div class="mono muted" style="font-size: var(--fs-small)">{{ t.consumableCode }}</div>
              </td>
              <td class="mono" data-label="批号">{{ t.batchNo }}</td>
              <td class="mono" data-label="患者">{{ t.patientNo }}</td>
              <td class="is-num" data-label="数量">{{ t.quantity }}</td>
              <td class="mono" data-label="手术">{{ t.surgeryId ? shortId(t.surgeryId) : '—' }}</td>
              <td data-label="使用时间">{{ dateTime(t.usedAt) }}</td>
              <td data-label="登记人">{{ t.operator }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- 批次库存 + 入库 -->
    <AppModal
      v-model="batchOpen"
      title="批次库存"
      :subtitle="current ? `${current.consumable.name} · ${current.consumable.spec}` : ''"
      width="820px"
    >
      <TableSkeleton v-if="batchLoading" :rows="4" height="40px" />
      <div v-else-if="current" class="detail">
        <div class="detail-bar">
          <span class="badge badge--neutral">
            总库存 {{ current.totalStock }} {{ current.consumable.unit }}
          </span>
          <span class="mono">{{ current.consumable.code }}</span>
          <span v-if="current.consumable.registrationNo" class="mono muted">
            注册证 {{ current.consumable.registrationNo }}
          </span>
          <span class="spacer" />
          <span class="hint">
            {{ current.consumable.category === 'GENERAL' ? '一般耗材可无效期' : '高值/植入类必须登记有效期并追溯患者' }}
          </span>
        </div>

        <div v-if="batches.length === 0" class="muted">该耗材暂无批次库存，请先入库。</div>
        <div v-else class="table-wrap">
          <table class="table table--stack table--compact">
            <thead>
              <tr>
                <th>批号</th>
                <th>效期</th>
                <th class="is-num">余量</th>
                <th class="is-num">单价</th>
                <th>供应商</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="b in batches" :key="b.id">
                <td class="mono" data-primary>{{ b.batchNo }}</td>
                <td data-label="效期">{{ b.expireDate ? date(b.expireDate) : '无' }}</td>
                <td class="is-num" data-label="余量">{{ b.quantity }}</td>
                <td class="is-num" data-label="单价">{{ money(b.unitPrice) }}</td>
                <td data-label="供应商">{{ b.supplier || '—' }}</td>
              </tr>
            </tbody>
          </table>
        </div>

        <div v-if="canCatalog" class="detail-section">
          <div class="detail-section-title">调价（价格快照，不影响历史出库）</div>
          <div class="inline-form">
            <label class="field f-qty">
              <span class="label">单价</span>
              <input v-model.number="priceForm.price" class="input num" type="number" min="0" step="0.01" />
            </label>
            <button
              class="btn"
              type="button"
              :disabled="busy || priceForm.price < 0 || priceForm.price === current.consumable.price"
              @click="submitPrice"
            >
              保存价格
            </button>
          </div>
        </div>

        <div v-if="canStock" class="detail-section">
          <div class="detail-section-title">入库登记</div>
          <div class="receive-form">
            <label class="field">
              <span class="label">批号 <span class="req">*</span></span>
              <input v-model.trim="receiveForm.batchNo" class="input" maxlength="64" />
            </label>
            <label class="field">
              <span class="label">
                效期
                <span v-if="current.consumable.category !== 'GENERAL'" class="req">*</span>
              </span>
              <input v-model="receiveForm.expireDate" class="input" type="date" />
            </label>
            <label class="field f-qty">
              <span class="label">数量 <span class="req">*</span></span>
              <input v-model.number="receiveForm.quantity" class="input num" type="number" min="1" />
            </label>
            <label class="field">
              <span class="label">单价</span>
              <input v-model.number="receiveForm.unitPrice" class="input num" type="number" min="0" step="0.01" />
            </label>
            <label class="field grow">
              <span class="label">供应商</span>
              <input v-model.trim="receiveForm.supplier" class="input" maxlength="128" />
            </label>
            <button class="btn btn--primary" type="button" :disabled="busy || !receiveValid" @click="submitReceive">
              入库
            </button>
          </div>
          <span class="hint">同耗材同批号只能入库一次；过期批次会被拒绝。</span>
        </div>
      </div>
    </AppModal>

    <!-- 新建耗材 -->
    <AppModal v-model="createOpen" title="新建耗材" width="640px">
      <div class="form-grid">
        <label class="field">
          <span class="label">编码 <span class="req">*</span></span>
          <input v-model.trim="createForm.code" class="input" maxlength="64" />
        </label>
        <label class="field">
          <span class="label">名称 <span class="req">*</span></span>
          <input v-model.trim="createForm.name" class="input" maxlength="128" />
        </label>
        <label class="field">
          <span class="label">规格 <span class="req">*</span></span>
          <input v-model.trim="createForm.spec" class="input" maxlength="128" />
        </label>
        <label class="field">
          <span class="label">单位 <span class="req">*</span></span>
          <input v-model.trim="createForm.unit" class="input" maxlength="16" />
        </label>
        <label class="field">
          <span class="label">类别 <span class="req">*</span></span>
          <select v-model="createForm.category" class="select">
            <option value="GENERAL">一般耗材</option>
            <option value="HIGH_VALUE">高值耗材</option>
            <option value="IMPLANT">植入类</option>
          </select>
        </label>
        <label class="field">
          <span class="label">单价 <span class="req">*</span></span>
          <input v-model.number="createForm.price" class="input num" type="number" min="0" step="0.01" />
        </label>
        <label class="field">
          <span class="label">厂商</span>
          <input v-model.trim="createForm.manufacturer" class="input" maxlength="128" />
        </label>
        <label class="field">
          <span class="label">
            注册证号
            <span v-if="createForm.category !== 'GENERAL'" class="req">*</span>
          </span>
          <input v-model.trim="createForm.registrationNo" class="input" maxlength="64" />
        </label>
      </div>
      <template #footer>
        <span class="hint footer-hint">高值/植入类必须登记注册证，后续每次使用都要追溯到患者</span>
        <span class="spacer" />
        <button class="btn" type="button" @click="createOpen = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="busy || !createValid" @click="submitCreate">
          保存
        </button>
      </template>
    </AppModal>

    <!-- 新建出库 -->
    <AppModal v-model="issueOpen" title="新建耗材出库" width="820px">
      <div class="stack">
        <div class="form-grid">
          <label class="field">
            <span class="label">领用科室 <span class="req">*</span></span>
            <select v-model="issueForm.deptId" class="select">
              <option value="">请选择科室</option>
              <option v-for="d in departments" :key="d.id" :value="d.id">{{ d.name }}</option>
            </select>
          </label>
          <label class="field">
            <span class="label">用途 <span class="req">*</span></span>
            <select v-model="issueForm.purpose" class="select">
              <option value="DEPT_STOCK">科室备货</option>
              <option value="SURGERY">手术使用</option>
              <option value="CLINIC">门诊使用</option>
            </select>
          </label>
          <label class="field grow">
            <span class="label">备注</span>
            <input v-model.trim="issueForm.remark" class="input" maxlength="256" />
          </label>
        </div>

        <div class="detail-section">
          <div class="detail-section-title">出库明细（按近效期优先自动拆批）</div>
          <div v-for="(line, idx) in issueLines" :key="idx" class="line-row">
            <label class="field grow">
              <span class="label">耗材</span>
              <select v-model="line.consumableId" class="select">
                <option value="">请选择耗材</option>
                <option v-for="c in consumables" :key="c.consumable.id" :value="c.consumable.id">
                  {{ c.consumable.name }} {{ c.consumable.spec }} · 库存 {{ c.totalStock }}
                </option>
              </select>
            </label>
            <label class="field f-qty">
              <span class="label">数量</span>
              <input v-model.number="line.quantity" class="input num" type="number" min="1" />
            </label>
            <button
              v-if="issueLines.length > 1"
              class="btn btn--sm"
              type="button"
              @click="issueLines.splice(idx, 1)"
            >
              移除
            </button>
          </div>
          <div class="line-actions">
            <button class="btn btn--sm" type="button" @click="issueLines.push({ consumableId: '', quantity: 1 })">
              <AppIcon name="plus" :size="13" /> 增加一行
            </button>
            <span class="spacer" />
            <span class="hint">幂等键 <span class="mono">{{ issueForm.requestId }}</span></span>
          </div>
        </div>
      </div>
      <template #footer>
        <button class="btn" type="button" @click="issueOpen = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="busy || !issueValid" @click="submitIssue">
          确认出库
        </button>
      </template>
    </AppModal>

    <!-- 出库明细 + 追溯登记 -->
    <AppModal
      v-model="issueDetailOpen"
      title="出库明细"
      :subtitle="issueDetail ? `${issueDetail.issue.issueNo} · ${issueDetail.issue.deptName || ''}` : ''"
      width="880px"
    >
      <TableSkeleton v-if="issueDetailLoading" :rows="4" height="40px" />
      <div v-else-if="issueDetail" class="detail">
        <div class="detail-bar">
          <span class="badge badge--neutral">{{ purposeLabel(issueDetail.issue.purpose) }}</span>
          <span class="badge badge--neutral">合计 {{ money(issueDetail.issue.totalAmount) }}</span>
          <span class="hint">
            {{ issueDetail.issue.operator }} · {{ dateTime(issueDetail.issue.createdAt) }}
          </span>
          <span class="spacer" />
          <span class="hint">追溯只在出库批次上登记，不重复扣库存</span>
        </div>

        <div class="table-wrap">
          <table class="table table--stack table--compact">
            <thead>
              <tr>
                <th>耗材</th>
                <th>批号</th>
                <th>效期</th>
                <th class="is-num">出库量</th>
                <th class="is-num">已追溯</th>
                <th class="is-num">未追溯</th>
                <th style="width: 96px">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="it in issueDetail.items" :key="it.id">
                <td data-primary>
                  <div class="is-strong">{{ it.consumableName }}</div>
                  <div class="mono muted" style="font-size: var(--fs-small)">{{ it.consumableCode }}</div>
                </td>
                <td class="mono" data-label="批号">{{ it.batchNo }}</td>
                <td data-label="效期">{{ it.expireDate ? date(it.expireDate) : '无' }}</td>
                <td class="is-num" data-label="出库量">{{ it.quantity }}</td>
                <td class="is-num" data-label="已追溯">{{ it.tracedQuantity }}</td>
                <td class="is-num" data-label="未追溯">
                  {{ it.quantity - it.tracedQuantity }}
                </td>
                <td data-label="操作">
                  <button
                    v-if="canTraceWrite && it.quantity > it.tracedQuantity"
                    class="btn btn--sm"
                    type="button"
                    @click="openTrace(it)"
                  >
                    登记使用
                  </button>
                  <span v-else class="muted">—</span>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
      <template #footer>
        <button class="btn" type="button" @click="issueDetailOpen = false">关闭</button>
      </template>
    </AppModal>

    <!-- 使用追溯登记 -->
    <AppModal
      v-model="traceOpen"
      title="高值耗材使用登记"
      :subtitle="traceItem ? `${traceItem.consumableName} · 批号 ${traceItem.batchNo}` : ''"
      width="640px"
    >
      <div v-if="traceItem" class="stack">
        <div class="notice notice--warn">
          请核对腕带后登记：这批耗材将永久关联到患者，召回时按批号反查。
          本批次未追溯数量 {{ traceItem.quantity - traceItem.tracedQuantity }}。
        </div>
        <PatientVisitSearch @select="onPickPatient" />
        <div class="form-grid">
          <label class="field f-qty">
            <span class="label">使用数量 <span class="req">*</span></span>
            <input
              v-model.number="traceForm.quantity"
              class="input num"
              type="number"
              min="1"
              :max="traceItem.quantity - traceItem.tracedQuantity"
            />
          </label>
          <label class="field">
            <span class="label">手术 ID</span>
            <input v-model.trim="traceForm.surgeryId" class="input mono" maxlength="36" placeholder="可留空" />
          </label>
          <label class="field grow">
            <span class="label">备注</span>
            <input v-model.trim="traceForm.remark" class="input" maxlength="256" />
          </label>
        </div>
        <span class="hint">未选择患者时无法提交；患者号在本页按 phi:read 权限掩码显示。</span>
      </div>
      <template #footer>
        <button class="btn" type="button" @click="traceOpen = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="busy || !traceValid" @click="submitTrace">
          确认登记
        </button>
      </template>
    </AppModal>

    <!-- 批次召回 -->
    <AppModal v-model="recallOpen" title="批次召回分析" width="820px">
      <div class="stack">
        <div class="inline-form">
          <label class="field grow">
            <span class="label">批号 <span class="req">*</span></span>
            <input v-model.trim="recallBatchNo" class="input mono" placeholder="输入需召回的批号" />
          </label>
          <button class="btn btn--primary" type="button" :disabled="busy || !recallBatchNo" @click="submitRecall">
            分析影响面
          </button>
        </div>

        <template v-if="recall">
          <div class="kpi-strip recall-kpis">
            <KpiCard label="涉及患者" :value="recall.patientCount" unit="人" tone="danger" />
            <KpiCard label="涉及手术" :value="recall.surgeryCount" unit="台" tone="warn" />
            <KpiCard label="追溯记录" :value="recall.traceCount" unit="条" />
            <KpiCard label="涉及数量" :value="recall.totalQuantity" unit="件" />
          </div>
          <div v-if="recall.traces.length === 0" class="muted">
            该批号没有使用追溯记录（可能仍在库存或科室备货中）。
          </div>
          <div v-else class="table-wrap">
            <table class="table table--stack table--compact">
              <thead>
                <tr>
                  <th>患者</th>
                  <th>数量</th>
                  <th>手术</th>
                  <th>使用时间</th>
                  <th>登记人</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="t in recall.traces" :key="t.id">
                  <td class="mono" data-primary>{{ t.patientNo }}</td>
                  <td class="is-num" data-label="数量">{{ t.quantity }}</td>
                  <td class="mono" data-label="手术">{{ t.surgeryId ? shortId(t.surgeryId) : '—' }}</td>
                  <td data-label="使用时间">{{ dateTime(t.usedAt) }}</td>
                  <td data-label="登记人">{{ t.operator }}</td>
                </tr>
              </tbody>
            </table>
          </div>
          <span class="hint">明细最多展示 500 条；更大批次请按时间窗分段核对。</span>
        </template>
      </div>
      <template #footer>
        <button class="btn" type="button" @click="recallOpen = false">关闭</button>
      </template>
    </AppModal>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import AppIcon from '@/components/ui/AppIcon.vue'
import AppModal from '@/components/ui/AppModal.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import KpiCard from '@/components/ui/KpiCard.vue'
import LoadError from '@/components/ui/LoadError.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import StatusPill from '@/components/ui/StatusPill.vue'
import TableSkeleton from '@/components/ui/TableSkeleton.vue'
import PatientVisitSearch from '@/components/domain/PatientVisitSearch.vue'
import { clinicalApi, materialApi } from '@/api'
import { errorText } from '@/api/http'
import type {
  ConsumableBatch,
  ConsumableCategory,
  ConsumableIssueItem,
  ConsumableIssueView,
  ConsumableRecall,
  ConsumableTrace,
  ConsumableView,
  Dept,
  MaterialStockWarning,
  MaterialSummary,
  Patient,
  Visit,
} from '@/api/types'
import { hasPermission, session } from '@/stores/session'
import { toast } from '@/stores/toast'
import { date, dateTime, money } from '@/utils/format'

const role = session.roleLabel
const canCatalog = computed(() => hasPermission('material:catalog:manage'))
const canStock = computed(() => hasPermission('material:stock:manage'))
const canIssue = computed(() => hasPermission('material:issue:write'))
const canTraceWrite = computed(() => hasPermission('material:trace:write'))

type Tab = 'catalog' | 'warnings' | 'issues' | 'traces'
const tab = ref<Tab>('catalog')
const loading = ref(false)
const busy = ref(false)
const loadError = ref('')

const summary = ref<MaterialSummary>({
  consumableCount: 0,
  totalStock: 0,
  stockValue: 0,
  nearExpiryBatches: 0,
  expiredBatches: 0,
  issuesToday: 0,
  tracesToday: 0,
})

const consumables = ref<ConsumableView[]>([])
const warnings = ref<MaterialStockWarning[]>([])
const issues = ref<ConsumableIssueView[]>([])
const traces = ref<ConsumableTrace[]>([])
const departments = ref<Dept[]>([])

const keyword = ref('')
const category = ref('')
const traceBatchNo = ref('')
const tracePatientNo = ref('')

const tabTitle = computed(
  () =>
    ({
      catalog: '耗材目录',
      warnings: '库存预警',
      issues: '出库记录',
      traces: '使用追溯',
    })[tab.value],
)

const rowCount = computed(() => {
  switch (tab.value) {
    case 'catalog':
      return consumables.value.length
    case 'warnings':
      return warnings.value.length
    case 'issues':
      return issues.value.length
    default:
      return traces.value.length
  }
})

const emptyTitle = computed(
  () =>
    ({
      catalog: '当前条件下没有耗材',
      warnings: '暂无近效期或过期批次',
      issues: '还没有出库记录',
      traces: '没有匹配的使用追溯记录',
    })[tab.value],
)

const emptyDesc = computed(
  () =>
    ({
      catalog: '可调整类别或清空检索词；新建耗材后先入库才能出库。',
      warnings: '只有登记了有效期的批次会进入预警；出库按近效期优先，过期批次永不发出。',
      issues: '科室/手术间请领后在这里出库，出库即扣批次库存，并按批号留下追溯基础数据。',
      traces: '高值/植入耗材出库后，由手术室/病区登记患者与手术；召回时按批号反查。',
    })[tab.value],
)

function categoryLabel(c: ConsumableCategory): string {
  return { GENERAL: '一般耗材', HIGH_VALUE: '高值耗材', IMPLANT: '植入类' }[c] ?? c
}

function categoryTone(c: ConsumableCategory): string {
  return c === 'IMPLANT' ? 'badge--danger' : c === 'HIGH_VALUE' ? 'badge--warn' : 'badge--neutral'
}

function purposeLabel(p: string): string {
  return { DEPT_STOCK: '科室备货', SURGERY: '手术使用', CLINIC: '门诊使用' }[p] ?? p
}

function shortId(id: string | null): string {
  return id ? id.slice(0, 8) : '—'
}

function switchTab(next: Tab) {
  if (tab.value === next) return
  tab.value = next
  void reloadCurrent()
}

async function reloadAll() {
  loading.value = true
  loadError.value = ''
  try {
    await Promise.all([loadSummary(), loadDepartments()])
    await reloadCurrent()
  } catch (e) {
    loadError.value = errorText(e)
    toast.error('物资数据加载失败', errorText(e))
  } finally {
    loading.value = false
  }
}

async function reloadCurrent() {
  loading.value = true
  try {
    if (tab.value === 'catalog') await loadCatalog()
    else if (tab.value === 'warnings') await loadWarnings()
    else if (tab.value === 'issues') await loadIssues()
    else await loadTraces()
    loadError.value = ''
  } catch (e) {
    loadError.value = errorText(e)
    toast.error('物资数据加载失败', errorText(e))
  } finally {
    loading.value = false
  }
}

async function loadSummary() {
  summary.value = await materialApi.summary()
}

async function loadDepartments() {
  departments.value = await clinicalApi.departments()
}

async function loadCatalog() {
  consumables.value = await materialApi.consumables(
    keyword.value || undefined,
    category.value || undefined,
    undefined,
    200,
  )
}

async function loadWarnings() {
  warnings.value = await materialApi.stockWarnings()
}

async function loadIssues() {
  issues.value = await materialApi.issues(undefined, 100)
}

async function loadTraces() {
  traces.value = await materialApi.traces({
    batchNo: traceBatchNo.value || undefined,
    patientNo: tracePatientNo.value || undefined,
    limit: 200,
  })
}

onMounted(() => {
  void reloadAll()
})

// ---------------------------------------------------------------- 批次与入库

const batchOpen = ref(false)
const batchLoading = ref(false)
const current = ref<ConsumableView | null>(null)
const batches = ref<ConsumableBatch[]>([])
const priceForm = reactive({ price: 0 })
const receiveForm = reactive({
  batchNo: '',
  expireDate: '',
  quantity: 1,
  unitPrice: 0,
  supplier: '',
})

const receiveValid = computed(
  () =>
    !!receiveForm.batchNo &&
    receiveForm.quantity >= 1 &&
    (current.value?.consumable.category === 'GENERAL' || !!receiveForm.expireDate),
)

async function openBatches(row: ConsumableView) {
  current.value = row
  priceForm.price = row.consumable.price
  receiveForm.batchNo = ''
  receiveForm.expireDate = ''
  receiveForm.quantity = 1
  receiveForm.unitPrice = row.consumable.price
  receiveForm.supplier = ''
  batchOpen.value = true
  batchLoading.value = true
  try {
    batches.value = await materialApi.batches(row.consumable.id)
  } catch (e) {
    toast.error('批次加载失败', errorText(e))
    batches.value = []
  } finally {
    batchLoading.value = false
  }
}

async function submitReceive() {
  if (!current.value) return
  busy.value = true
  try {
    await materialApi.receiveBatch(current.value.consumable.id, {
      batchNo: receiveForm.batchNo,
      expireDate: receiveForm.expireDate || undefined,
      quantity: receiveForm.quantity,
      supplier: receiveForm.supplier || undefined,
      unitPrice: receiveForm.unitPrice,
    })
    toast.ok('入库完成', '批次库存与效期已登记')
    await refreshCurrent()
  } catch (e) {
    toast.error('入库失败', errorText(e))
  } finally {
    busy.value = false
  }
}

async function submitPrice() {
  if (!current.value) return
  busy.value = true
  try {
    const view = await materialApi.adjustConsumablePrice(
      current.value.consumable.id,
      priceForm.price,
      current.value.consumable.version,
    )
    current.value = view
    toast.ok('价格已更新')
    await reloadCurrent()
  } catch (e) {
    toast.error('调价失败', errorText(e))
  } finally {
    busy.value = false
  }
}

async function refreshCurrent() {
  if (current.value) {
    batches.value = await materialApi.batches(current.value.consumable.id)
    current.value = await materialApi.consumable(current.value.consumable.id)
  }
  await Promise.all([loadSummary(), reloadCurrent()])
}

// ---------------------------------------------------------------- 新建耗材

const createOpen = ref(false)
const createForm = reactive({
  code: '',
  name: '',
  spec: '',
  unit: '',
  category: 'GENERAL' as ConsumableCategory,
  price: 0,
  manufacturer: '',
  registrationNo: '',
})

const createValid = computed(
  () =>
    !!createForm.code &&
    !!createForm.name &&
    !!createForm.spec &&
    !!createForm.unit &&
    createForm.price >= 0 &&
    (createForm.category === 'GENERAL' || !!createForm.registrationNo),
)

function openCreate() {
  createForm.code = ''
  createForm.name = ''
  createForm.spec = ''
  createForm.unit = ''
  createForm.category = 'GENERAL'
  createForm.price = 0
  createForm.manufacturer = ''
  createForm.registrationNo = ''
  createOpen.value = true
}

async function submitCreate() {
  busy.value = true
  try {
    await materialApi.createConsumable({
      code: createForm.code,
      name: createForm.name,
      spec: createForm.spec,
      unit: createForm.unit,
      category: createForm.category,
      price: createForm.price,
      manufacturer: createForm.manufacturer || undefined,
      registrationNo: createForm.registrationNo || undefined,
    })
    toast.ok('耗材已建档')
    createOpen.value = false
    await reloadCurrent()
  } catch (e) {
    toast.error('建档失败', errorText(e))
  } finally {
    busy.value = false
  }
}

// ---------------------------------------------------------------- 出库

const issueOpen = ref(false)
const issueForm = reactive({
  deptId: '',
  purpose: 'DEPT_STOCK',
  requestId: '',
  remark: '',
})
const issueLines = ref<{ consumableId: string; quantity: number }[]>([
  { consumableId: '', quantity: 1 },
])

const issueValid = computed(
  () =>
    !!issueForm.deptId &&
    issueLines.value.some((l) => l.consumableId && l.quantity >= 1) &&
    new Set(issueLines.value.filter((l) => l.consumableId).map((l) => l.consumableId)).size ===
      issueLines.value.filter((l) => l.consumableId).length,
)

function newId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  return `${Date.now()}-${Math.random().toString(16).slice(2)}`
}

function openIssueCreate() {
  if (consumables.value.length === 0) {
    void loadCatalog()
  }
  issueForm.deptId = ''
  issueForm.purpose = 'DEPT_STOCK'
  issueForm.requestId = newId()
  issueForm.remark = ''
  issueLines.value = [{ consumableId: '', quantity: 1 }]
  issueOpen.value = true
}

async function submitIssue() {
  const items = issueLines.value
    .filter((l) => l.consumableId && l.quantity >= 1)
    .map((l) => ({ consumableId: l.consumableId, quantity: l.quantity }))
  if (!issueForm.deptId || items.length === 0) return
  const dept = departments.value.find((d) => d.id === issueForm.deptId)
  busy.value = true
  try {
    await materialApi.createIssue({
      deptId: issueForm.deptId,
      deptName: dept?.name,
      purpose: issueForm.purpose,
      requestId: issueForm.requestId,
      remark: issueForm.remark || undefined,
      items,
    })
    toast.ok('出库完成', '库存已按近效期优先扣减')
    issueOpen.value = false
    tab.value = 'issues'
    await Promise.all([loadSummary(), loadIssues()])
  } catch (e) {
    toast.error('出库失败', errorText(e))
  } finally {
    busy.value = false
  }
}

const issueDetailOpen = ref(false)
const issueDetailLoading = ref(false)
const issueDetail = ref<ConsumableIssueView | null>(null)

async function openIssueDetail(view: ConsumableIssueView) {
  issueDetailOpen.value = true
  issueDetailLoading.value = true
  issueDetail.value = null
  try {
    issueDetail.value = await materialApi.issue(view.issue.id)
  } catch (e) {
    toast.error('出库明细加载失败', errorText(e))
  } finally {
    issueDetailLoading.value = false
  }
}

// ---------------------------------------------------------------- 追溯

const traceOpen = ref(false)
const traceItem = ref<ConsumableIssueItem | null>(null)
const pickedPatient = ref<Patient | null>(null)
const pickedVisit = ref<Visit | null>(null)
const traceForm = reactive({ quantity: 1, surgeryId: '', remark: '' })

const traceValid = computed(
  () => !!pickedPatient.value && traceForm.quantity >= 1 && !!traceItem.value,
)

function openTrace(item: ConsumableIssueItem) {
  traceItem.value = item
  pickedPatient.value = null
  pickedVisit.value = null
  traceForm.quantity = item.quantity - item.tracedQuantity
  traceForm.surgeryId = ''
  traceForm.remark = ''
  traceOpen.value = true
}

function onPickPatient(payload: { patient: Patient; visit: Visit }) {
  pickedPatient.value = payload.patient
  pickedVisit.value = payload.visit
}

async function submitTrace() {
  if (!traceItem.value || !pickedPatient.value) return
  busy.value = true
  try {
    await materialApi.registerTrace({
      issueItemId: traceItem.value.id,
      quantity: traceForm.quantity,
      patientId: pickedPatient.value.id,
      patientNo: pickedPatient.value.patientNo,
      visitId: pickedVisit.value?.id,
      surgeryId: traceForm.surgeryId || undefined,
      remark: traceForm.remark || undefined,
    })
    toast.ok('使用追溯已登记', '该批次已关联患者，可用于召回反查')
    traceOpen.value = false
    issueDetail.value = await materialApi.issue(traceItem.value.issueId)
    await loadSummary()
  } catch (e) {
    toast.error('登记失败', errorText(e))
  } finally {
    busy.value = false
  }
}

const recallOpen = ref(false)
const recallBatchNo = ref('')
const recall = ref<ConsumableRecall | null>(null)

function openRecall() {
  recallBatchNo.value = traceBatchNo.value
  recall.value = null
  recallOpen.value = true
}

async function submitRecall() {
  if (!recallBatchNo.value) return
  busy.value = true
  try {
    recall.value = await materialApi.recall(recallBatchNo.value)
  } catch (e) {
    toast.error('召回分析失败', errorText(e))
  } finally {
    busy.value = false
  }
}
</script>

<style scoped>
.f-qty {
  max-width: 120px;
}
.f-cat {
  max-width: 150px;
}
.form-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: var(--s3);
}
.inline-form {
  display: flex;
  align-items: flex-end;
  gap: var(--s2);
}
.line-row {
  display: flex;
  align-items: flex-end;
  gap: var(--s2);
  margin-bottom: var(--s2);
}
.line-actions {
  display: flex;
  align-items: center;
  gap: var(--s2);
  margin-top: var(--s2);
}
.receive-form {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-end;
  gap: var(--s2);
}
.recall-kpis {
  margin: var(--s2) 0;
}
</style>
