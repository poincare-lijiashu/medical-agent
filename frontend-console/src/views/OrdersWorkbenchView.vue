<template>
  <div class="page">
    <PageHeader
      title="医嘱工作台"
      :sub="`以就诊为上下文组织医嘱、转送与费用。数据源 clinical_svc · 更新 ${updatedAt || '—'} · 当前角色 ${role}`"
    >
      <template #actions>
        <button class="btn" type="button" :disabled="loading" @click="refreshAll">
          <AppIcon name="refresh" :size="15" /> 刷新
        </button>
      </template>
    </PageHeader>

    <LoadError v-if="loadErr" :message="loadErr" :retrying="loading" @retry="refreshAll" />

    <!-- 患者上下文 -->
    <section class="card ctx">
      <div class="ctx-search">
        <AppIcon name="search" :size="16" class="ctx-search-icon" />
        <input
          ref="searchInput"
          v-model="keyword"
          class="ctx-input"
          type="search"
          placeholder="输入姓名或病案号，回车检索"
          aria-label="检索患者"
          @keydown.enter="searchPatients"
          @input="onKeywordInput"
        />
        <span class="kbd">Enter</span>
      </div>

      <div v-if="selectedPatient" class="patient-chip">
        <span class="avatar-lg" aria-hidden="true">{{ selectedPatient.name.slice(0, 1) }}</span>
        <div class="patient-main">
          <div class="patient-line">
            <strong>{{ selectedPatient.name }}</strong>
            <span class="muted">{{ genderLabel(selectedPatient.gender) }} · {{ ageOf(selectedPatient.birthDate) }}</span>
            <StatusPill :value="selectedPatient.status" />
          </div>
          <div class="patient-sub mono">
            {{ selectedPatient.patientNo }}
            <template v-if="selectedPatient.bloodType"> · 血型 {{ selectedPatient.bloodType }}</template>
          </div>
        </div>
        <span v-if="selectedPatient.allergy" class="allergy">
          <AppIcon name="warning" :size="13" /> 过敏：{{ selectedPatient.allergy }}
        </span>
        <button
          v-if="canReveal"
          class="btn btn--ghost btn--sm"
          type="button"
          @click="revealPatient"
        >
          <AppIcon name="lock" :size="13" /> 查看明文
        </button>
      </div>
      <div v-else class="ctx-placeholder">
        <AppIcon name="user" :size="15" />
        <span>尚未选择患者。检索后会显示过敏史与有效档案状态。</span>
      </div>
    </section>

    <!-- 检索结果 -->
    <section v-if="showResults" class="card results">
      <div class="results-head">
        <span class="card-title">检索结果</span>
        <span class="muted" style="font-size: var(--fs-12)">{{ patientTotalLabel }}</span>
        <span class="spacer" />
        <button class="btn btn--ghost btn--sm" type="button" @click="showResults = false">收起</button>
      </div>
      <div v-if="searching" class="results-loading">
        <div v-for="i in 3" :key="i" class="skeleton" style="height: 44px" />
      </div>
      <div v-else-if="patients.length === 0" class="results-empty">
        未找到匹配的患者。请核对姓名或用完整病案号检索。
      </div>
      <ul v-else class="results-list">
        <li v-for="p in patients" :key="p.id">
          <button class="result-row" type="button" @click="pickPatient(p)">
            <span class="result-name">{{ p.name }}</span>
            <span class="muted">{{ genderLabel(p.gender) }} · {{ ageOf(p.birthDate) }}</span>
            <span class="mono muted">{{ p.patientNo }}</span>
            <span class="spacer" />
            <StatusPill :value="p.status" />
            <AppIcon name="chevron-right" :size="14" class="muted" />
          </button>
        </li>
      </ul>
    </section>

    <section v-if="!selectedPatient" class="card onboarding">
      <div class="onboarding-body">
        <h2>从这里开始</h2>
        <ol class="steps">
          <li>
            <span class="step-mark">1</span>
            <div>
              <div class="step-title">检索患者</div>
              <p>输入姓名或完整病案号，确认过敏史与档案状态后选中。</p>
            </div>
          </li>
          <li>
            <span class="step-mark">2</span>
            <div>
              <div class="step-title">选择就诊</div>
              <p>医嘱必须挂在一次就诊下；未接诊的就诊需先接诊。</p>
            </div>
          </li>
          <li>
            <span class="step-mark">3</span>
            <div>
              <div class="step-title">开立与核对</div>
              <p>临时医嘱一次性计费，长期医嘱按日计费；停撤与结算都会留痕。</p>
            </div>
          </li>
        </ol>
        <p class="onboarding-note">
          当前账号角色：{{ role }}。入口与按钮会按权限点显隐，但最终裁决在服务端。
        </p>
      </div>
    </section>

    <div v-if="selectedPatient && selectedVisit" class="kpi-strip">
      <KpiCard
        label="本次就诊医嘱"
        :value="orders.length"
        unit="条"
        :meta="`长期 ${longCount} · 临时 ${tempCount}`"
      />
      <KpiCard
        label="待执行 / 未计费"
        :value="placedCount"
        unit="条"
        :meta="`已计费 ${chargedCount} 条`"
        :tone="placedCount > 0 ? 'accent' : 'default'"
      />
      <KpiCard
        label="收费单金额"
        :value="chargeValue"
        :meta="chargeMeta"
        :tone="chargeError ? 'danger' : charge?.status === 'PAID' ? 'ok' : 'warn'"
      />
      <KpiCard
        label="转送异常"
        :value="transferFailedCount"
        unit="条"
        :meta="transferFailedCount > 0 ? '需要人工重试或联系药房/医技' : '药房与医技转送正常'"
        :tone="transferFailedCount > 0 ? 'danger' : 'default'"
      />
    </div>

    <div v-if="selectedPatient" class="workbench">
      <!-- 就诊列表 -->
      <aside class="card visit-pane">
        <div class="card-head">
          <span class="card-title">就诊记录</span>
          <span class="spacer" />
          <button
            v-if="canRegister"
            class="btn btn--sm"
            type="button"
            @click="openRegister"
          >
            <AppIcon name="plus" :size="14" /> 新建
          </button>
        </div>
        <div v-if="visitsLoading" class="visit-loading">
          <div v-for="i in 3" :key="i" class="skeleton" style="height: 58px" />
        </div>
        <EmptyState
          v-else-if="visits.length === 0"
          art="none"
          title="该患者暂无就诊"
          desc="住院患者请在住院处完成入院登记；门诊患者可在此新建就诊。"
        />
        <ul v-else class="visit-list">
          <li v-for="v in visits" :key="v.id">
            <button
              class="visit-item"
              :class="{ active: selectedVisit?.id === v.id }"
              type="button"
              @click="pickVisit(v)"
            >
              <span class="visit-top">
                <span class="mono">{{ v.visitNo }}</span>
                <StatusPill :value="v.status" />
              </span>
              <span class="visit-meta">
                <span>{{ deptName(v.deptId) }}</span>
                <span class="muted">·</span>
                <span>{{ date(v.visitDate) }}</span>
                <span class="muted">·</span>
                <span>{{ visitTypeLabel(v.visitType) }}</span>
              </span>
            </button>
          </li>
        </ul>
      </aside>

      <!-- 医嘱工作区 -->
      <section class="order-pane">
        <div v-if="!selectedVisit" class="card">
          <EmptyState
            title="选择一次就诊开始工作"
            desc="左侧列出该患者的就诊记录。医嘱必须挂在一次就诊下，避免无主医嘱与错账。"
          />
        </div>

        <template v-else>
          <div class="card visit-head">
            <div class="visit-head-main">
              <div class="row" style="gap: 8px">
                <span class="mono visit-no">{{ selectedVisit.visitNo }}</span>
                <StatusPill :value="selectedVisit.status" />
              </div>
              <div class="visit-head-meta">
                {{ deptName(selectedVisit.deptId) }} · 医师 {{ selectedVisit.doctorId }} ·
                就诊日期 {{ date(selectedVisit.visitDate) }}
              </div>
            </div>
            <span class="spacer" />
            <button
              v-if="canTransfer"
              class="btn btn--sm"
              type="button"
              :disabled="busy"
              @click="openTransfer"
            >
              <AppIcon name="arrow-right" :size="13" /> 转科
            </button>
            <button
              v-if="selectedVisit.status === 'REGISTERED' && canTreat"
              class="btn btn--primary btn--sm"
              type="button"
              :disabled="busy"
              @click="startVisit"
            >
              <AppIcon name="play" :size="13" /> 开始接诊
            </button>
            <span v-else-if="selectedVisit.status === 'REGISTERED'" class="hint">
              需 <code>clinical:visit:treat</code> 权限才能接诊
            </span>
          </div>

          <!-- 开立医嘱 -->
          <div v-if="selectedVisit.status === 'IN_TREATMENT'" class="card entry">
            <div class="card-head">
              <span class="card-title">开立医嘱</span>
              <span class="hint">价格取自主数据，提交后由服务端重新计价</span>
              <span class="spacer" />
              <div class="seg" role="tablist" aria-label="医嘱类型">
                <button
                  v-for="m in modes"
                  :key="m.value"
                  class="seg-btn"
                  :class="{ active: line.orderMode === m.value }"
                  type="button"
                  role="tab"
                  :aria-selected="line.orderMode === m.value"
                  @click="setMode(m.value)"
                >
                  {{ m.label }}
                </button>
              </div>
            </div>

            <div class="entry-body">
              <div class="field" style="flex: 2">
                <span class="label">医嘱项目 <span class="req">*</span></span>
                <button class="item-picker" type="button" @click="openCatalog">
                  <template v-if="line.itemCode">
                    <span class="item-picker-name">{{ line.itemName }}</span>
                    <span class="mono muted">{{ line.itemCode }}</span>
                    <span class="spacer" />
                    <span class="num">{{ money(line.price) }} / {{ line.unit }}</span>
                  </template>
                  <template v-else>
                    <AppIcon name="search" :size="15" class="muted" />
                    <span class="muted picker-placeholder">选择收费项目</span>
                    <span class="spacer" />
                    <AppIcon name="chevron-right" :size="14" class="muted" />
                  </template>
                </button>
              </div>

              <label class="field entry-qty">
                <span class="label">数量 <span class="req">*</span></span>
                <input v-model.number="line.quantity" class="input num" type="number" min="1" step="1" />
              </label>

              <label class="field">
                <span class="label">剂量 / 规格</span>
                <input v-model.trim="line.dose" class="input" placeholder="如 0.5g、250ml" />
              </label>

              <label class="field entry-freq">
                <span class="label">频次</span>
                <select v-model="line.frequency" class="select">
                  <option value="">不指定（临时医嘱）</option>
                  <option v-for="f in frequencyOptions" :key="f.code" :value="f.code">
                    {{ f.label }}（{{ f.code }}）
                  </option>
                </select>
              </label>

              <button
                class="btn btn--primary"
                type="button"
                :disabled="!line.itemCode || !line.quantity || busy"
                @click="submitOrder"
              >
                <AppIcon name="plus" :size="15" /> 开立
              </button>
            </div>
            <div v-if="modeHint" class="entry-hint">
              <AppIcon name="info" :size="13" /> {{ modeHint }}
            </div>

            <!-- 内联审方（第 132 批）：边开医嘱边核对，不离开当前页面 -->
            <div class="entry-ai">
              <InlineRxAudit :items="rxAuditItems" />
            </div>
          </div>

          <div v-else-if="selectedVisit.status === 'REGISTERED'" class="notice">
            <AppIcon name="info" :size="15" />
            该就诊尚未接诊。接诊后才能开立医嘱（服务端会拒绝非接诊中的就诊）。
          </div>

          <!-- 医嘱列表 -->
          <div class="card">
            <div class="card-head orders-toolbar">
              <span class="card-title">医嘱明细</span>
              <span class="badge badge--neutral num">{{ visibleOrders.length }} / {{ orders.length }}</span>
              <div class="seg" role="tablist" aria-label="医嘱类型筛选">
                <button
                  v-for="f in orderFilters"
                  :key="f.value"
                  class="seg-btn"
                  :class="{ active: orderFilter === f.value }"
                  type="button"
                  role="tab"
                  :aria-selected="orderFilter === f.value"
                  @click="orderFilter = f.value"
                >
                  {{ f.label }}
                </button>
              </div>
              <div class="orders-search">
                <AppIcon name="search" :size="14" class="muted" />
                <input v-model.trim="orderQuery" class="orders-search-input" placeholder="项目 / 单号" aria-label="筛选医嘱" />
              </div>
              <span class="spacer" />
              <button class="btn btn--ghost btn--sm" type="button" :disabled="ordersLoading" @click="loadOrders">
                <AppIcon name="refresh" :size="14" /> 刷新
              </button>
            </div>

            <div v-if="ordersLoading" class="orders-loading">
              <div v-for="i in 4" :key="i" class="skeleton" style="height: 40px" />
            </div>

            <EmptyState
              v-else-if="orders.length === 0"
              title="本次就诊还没有医嘱"
              desc="在左侧选择收费项目、填写数量与频次后开立。长期医嘱按日计费，临时医嘱一次性计费。"
            />

            <div v-else class="table-wrap">
              <table class="table table--compact table--stack">
                <thead>
                  <tr>
                    <th>单号</th>
                    <th>项目</th>
                    <th>剂量 / 频次</th>
                    <th class="is-num">数量</th>
                    <th class="is-num">金额</th>
                    <th>状态</th>
                    <th>转送</th>
                    <th style="width: 132px">操作</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="o in visibleOrders" :key="o.id">
                    <td class="mono" data-primary>{{ o.orderNo }}</td>
                    <td data-label="项目">
                      <div class="item-line">
                        <span class="is-strong" :title="o.itemName">{{ o.itemName }}</span>
                        <span class="mode-tag" :class="{ 'is-long': o.orderMode === 'LONG' }">
                          {{ o.orderMode === 'LONG' ? '长期' : '临时' }}
                        </span>
                      </div>
                      <div class="mono muted item-code">{{ o.itemCode }}</div>
                    </td>
                    <td class="nowrap" data-label="剂量 / 频次">
                      <div>{{ o.dose || '—' }}</div>
                      <div class="mono muted" style="font-size: var(--fs-10)" :title="freqLabel(o.frequency)">
                        {{ (o.frequency || '—').toUpperCase() }}
                      </div>
                    </td>
                    <td class="is-num nowrap" data-label="数量">{{ o.quantity }} {{ o.unit }}</td>
                    <td class="is-num is-strong nowrap" data-label="金额">{{ money(o.amount) }}</td>
                    <td data-label="状态"><StatusPill :value="o.status" /></td>
                    <td data-label="转送">
                      <span v-if="o.transferStatus === 'FAILED'" class="badge badge--danger" :title="o.transferError || ''">
                        转送失败
                      </span>
                      <span v-else-if="o.externalNo" class="mono muted">{{ o.externalNo }}</span>
                      <span v-else class="muted">—</span>
                    </td>
                    <td data-label="操作">
                      <div class="row" style="gap: 4px">
                        <button
                          v-if="o.orderMode !== 'LONG' && o.status === 'PLACED'"
                          class="btn btn--danger btn--sm"
                          type="button"
                          @click="askCancel(o)"
                        >
                          撤销
                        </button>
                        <button
                          v-if="o.orderMode === 'LONG' && o.status === 'PLACED'"
                          class="btn btn--sm"
                          type="button"
                          @click="askStop(o)"
                        >
                          停嘱
                        </button>
                        <button
                          v-if="o.transferStatus === 'FAILED'"
                          class="btn btn--sm"
                          type="button"
                          @click="retryTransfer(o)"
                        >
                          重试转送
                        </button>
                        <span v-if="o.status !== 'PLACED' && o.transferStatus !== 'FAILED'" class="muted">—</span>
                      </div>
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
          </div>

          <!-- 费用 -->
          <div class="card charge">
            <div class="charge-head">
              <div>
                <div class="card-title">费用与结算</div>
                <div class="hint" style="margin-top: 4px">
                  {{ charge ? `收费单 ${charge.chargeNo}` : '尚未生成收费单' }}
                </div>
              </div>
              <span class="spacer" />
              <div class="charge-total">
                <span class="muted">应结金额</span>
                <strong class="num">{{ money(charge?.totalAmount ?? 0) }}</strong>
              </div>
              <StatusPill :value="charge?.status || 'UNPAID'" />
              <button
                v-if="canSettle && charge && charge.status !== 'PAID'"
                class="btn btn--primary"
                type="button"
                :disabled="busy"
                @click="openSettle"
              >
                结算
              </button>
            </div>
            <div v-if="charge && charge.items.length" class="charge-items">
              <div v-for="(it, i) in charge.items" :key="i" class="charge-item">
                <span class="mono muted">{{ it.itemCode }}</span>
                <span>{{ it.itemName }}</span>
                <span class="spacer" />
                <span class="num">{{ money(it.amount) }}</span>
              </div>
            </div>
            <div v-else class="charge-empty">暂无计费明细。临时医嘱在结算时计费，长期医嘱按日计费。</div>
          </div>
        </template>
      </section>

      <!-- 右侧检查器：患者、费用构成、转送与审计 -->
      <aside class="card inspector">
        <div class="inspector-block">
          <div class="section-label">患者上下文</div>
          <div class="inspector-patient">
            <span class="avatar-lg" aria-hidden="true">{{ selectedPatient.name.slice(0, 1) }}</span>
            <div class="min-w-0">
              <div class="inspector-name">{{ selectedPatient.name }}</div>
              <div class="mono muted" style="font-size: var(--fs-11)">
                {{ selectedPatient.patientNo }}
              </div>
            </div>
          </div>
          <dl class="kv-list">
            <div><dt>性别 / 年龄</dt><dd>{{ genderLabel(selectedPatient.gender) }} · {{ ageOf(selectedPatient.birthDate) }}</dd></div>
            <div><dt>血型</dt><dd>{{ selectedPatient.bloodType || '—' }}</dd></div>
            <div>
              <dt>过敏</dt>
              <dd :class="{ 'is-danger': Boolean(selectedPatient.allergy) }">{{ selectedPatient.allergy || '未记录' }}</dd>
            </div>
            <div><dt>档案状态</dt><dd><StatusPill :value="selectedPatient.status" /></dd></div>
          </dl>
          <button v-if="canReveal" class="btn btn--sm btn--block" type="button" @click="revealPatient">
            <AppIcon name="lock" :size="13" /> 查看敏感信息（留痕）
          </button>

          <!-- 患者卡片旁的会诊建议（第 132 批）：不离开当前患者即可发起 AI 多智能体会诊 -->
          <InlineConsult
            :patient-name="selectedPatient.name"
            :patient-no="selectedPatient.patientNo"
            :dept-name="selectedVisit ? deptName(selectedVisit.deptId) : ''"
            :visit-no="selectedVisit?.visitNo || ''"
          />
        </div>

        <div class="inspector-block">
          <div class="section-label">费用构成</div>
          <div v-if="feeGroups.length && !netZero" class="fee-bars">
            <div v-for="g in feeGroups" :key="g.key" class="fee-bar">
              <div class="fee-bar-head">
                <span>{{ g.label }}</span>
                <span class="num">{{ money(g.amount) }}</span>
              </div>
              <div class="fee-bar-track"><span :style="{ width: g.pct + '%' }" /></div>
            </div>
          </div>
          <div v-else-if="netZero" class="hint">
            本次费用已全额红冲（退费/更正），净额为 0；原始明细仍保留在收费单中可追溯。
          </div>
          <div v-else class="hint">暂无计费明细。临时医嘱在结算时计费，长期医嘱按天累计。</div>
          <div v-if="charge" class="inspector-total">
            <span class="muted">合计</span>
            <strong class="num">{{ money(charge.totalAmount) }}</strong>
          </div>
          <p v-if="hasReversal" class="hint" style="margin: 0">含红冲记录，以上为净额。</p>
        </div>

        <div class="inspector-block">
          <div class="section-label">转送与联动</div>
          <dl class="kv-list">
            <div><dt>药房</dt><dd>{{ pharmacyTransfers }} 条</dd></div>
            <div><dt>医技</dt><dd>{{ techTransfers }} 条</dd></div>
            <div>
              <dt>失败待处理</dt>
              <dd :class="{ 'is-danger': transferFailedCount > 0 }">{{ transferFailedCount }} 条</dd>
            </div>
          </dl>
          <div v-if="recentTransfers.length" class="transfer-list">
            <div v-for="o in recentTransfers" :key="o.id" class="transfer-row">
              <span class="mono">{{ o.externalNo }}</span>
              <span class="truncate">{{ o.itemName }}</span>
            </div>
          </div>
          <div v-else class="hint">本次就诊尚未产生外送单号。</div>
        </div>

        <div v-if="transfers.length" class="inspector-block">
          <div class="section-label">转科记录</div>
          <div class="transfer-history">
            <div v-for="t in transfers" :key="t.id" class="transfer-history-row">
              <span class="mono muted">{{ dateTime(t.transferredAt) }}</span>
              <span>{{ deptName(t.fromDeptId) }} → {{ deptName(t.toDeptId) }}</span>
              <span class="hint">{{ t.reason }}</span>
            </div>
          </div>
        </div>

        <div class="inspector-block is-last">
          <div class="section-label">审计边界</div>
          <p class="hint" style="line-height: 1.7">
            撤销、停嘱与结算均写入审计，记录操作人、时间与原因。界面显示的状态以服务端返回为准，不做本地乐观改写。
          </p>
        </div>
      </aside>
    </div>

    <!-- 项目选择 -->
    <AppModal v-model="catalogOpen" title="选择收费项目" subtitle="来自院内收费项目主数据，仅显示启用项" width="720px">
      <div class="catalog-search">
        <AppIcon name="search" :size="15" class="muted" />
        <input
          v-model="catalogQuery"
          class="catalog-input"
          placeholder="按项目编码或名称检索，回车搜索"
          @keydown.enter="loadCatalog"
          @input="onCatalogInput"
        />
        <button class="btn btn--sm" type="button" @click="loadCatalog">搜索</button>
      </div>
      <div class="catalog-filters">
        <button
          v-for="c in categories"
          :key="c.value"
          class="chip"
          :class="{ active: catalogCategory === c.value }"
          type="button"
          @click="setCatalogCategory(c.value)"
        >
          {{ c.label }}
        </button>
      </div>
      <div v-if="catalogLoading" class="catalog-list">
        <div v-for="i in 5" :key="i" class="skeleton" style="height: 42px" />
      </div>
      <div v-else-if="catalog.length === 0" class="catalog-empty">未找到匹配项目。</div>
      <ul v-else class="catalog-list">
        <li v-for="it in catalog" :key="it.itemCode">
          <button class="catalog-row" type="button" @click="chooseItem(it)">
            <span class="mono">{{ it.itemCode }}</span>
            <span class="catalog-name">{{ it.name }}</span>
            <span class="badge badge--neutral">{{ it.category }}</span>
            <span class="spacer" />
            <span class="num">{{ money(it.price) }}</span>
            <span class="muted">/ {{ it.unit }}</span>
          </button>
        </li>
      </ul>
    </AppModal>

    <!-- 新建就诊 -->
    <AppModal v-model="registerOpen" title="新建门诊就诊" subtitle="就诊建立后即可接诊并开立医嘱" width="520px">
      <div class="stack">
        <label class="field">
          <span class="label">科室 <span class="req">*</span></span>
          <select v-model="registerForm.deptId" class="select">
            <option value="" disabled>请选择科室</option>
            <option v-for="d in departments" :key="d.id" :value="d.id">{{ d.name }}（{{ d.deptCode }}）</option>
          </select>
        </label>
        <label class="field">
          <span class="label">接诊医师工号 <span class="req">*</span></span>
          <input v-model.trim="registerForm.doctorId" class="input" placeholder="如 D0001" />
        </label>
        <label class="field">
          <span class="label">就诊类型</span>
          <select v-model="registerForm.visitType" class="select">
            <option value="OUTPATIENT">门诊</option>
            <option value="EMERGENCY">急诊</option>
          </select>
        </label>
      </div>
      <template #footer>
        <button class="btn" type="button" @click="registerOpen = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="busy" @click="submitRegister">
          建立就诊
        </button>
      </template>
    </AppModal>

    <!-- 转科 -->
    <AppModal
      v-model="transferOpen"
      title="转科（变更主诊科室）"
      subtitle="转科后本科室不再可见该就诊，原床位自动释放"
      width="560px"
    >
      <div class="stack">
        <label class="field">
          <span class="label">目标科室 <span class="req">*</span></span>
          <select v-model="transferForm.toDeptId" class="select">
            <option value="" disabled>请选择住院科室</option>
            <option v-for="d in transferDepts" :key="d.id" :value="d.id">
              {{ d.name }}（{{ d.deptCode }}）
            </option>
          </select>
        </label>
        <label v-if="transferForm.toDeptId" class="field">
          <span class="label">目标病区 <span class="req">*</span></span>
          <select v-model="transferForm.wardId" class="select" :disabled="transferWards.length === 0">
            <option value="" disabled>
              {{ transferWards.length ? '请选择病区' : '该科室暂无启用病区' }}
            </option>
            <option v-for="w in transferWards" :key="w.id" :value="w.id">
              {{ w.name }}（{{ w.code }}）
            </option>
          </select>
        </label>
        <label class="field">
          <span class="label">目标床位 <span class="req">*</span></span>
          <select
            v-model="transferForm.bedId"
            class="select"
            :disabled="bedsLoading || !transferForm.wardId"
          >
            <option value="" disabled>{{ transferBedHint }}</option>
            <option v-for="b in freeBeds" :key="b.id" :value="b.id">{{ b.bedNo }}</option>
          </select>
        </label>
        <label class="field">
          <span class="label">转科原因 <span class="req">*</span></span>
          <input v-model.trim="transferForm.reason" class="input" placeholder="将写入转科记录与审计" />
        </label>
        <p class="hint">
          转科会同时撤回本科室发起的进行中会诊，并释放原床位；医嘱与费用继续保留在本次就诊下。
        </p>
      </div>
      <template #footer>
        <button class="btn" type="button" @click="transferOpen = false">取消</button>
        <button
          class="btn btn--primary"
          type="button"
          :disabled="busy || !transferForm.toDeptId || !transferForm.bedId || !transferForm.reason"
          @click="submitTransfer"
        >
          确认转科
        </button>
      </template>
    </AppModal>

    <!-- 危险动作确认 -->
    <AppModal
      v-model="confirmOpen"
      :title="confirmState.title"
      :subtitle="confirmState.desc"
      width="480px"
    >
      <label v-if="confirmState.needReason" class="field">
        <span class="label">原因 <span class="req">*</span></span>
        <input v-model.trim="confirmState.reason" class="input" placeholder="将写入审计，请说明原因" />
      </label>
      <template #footer>
        <button class="btn" type="button" @click="confirmOpen = false">取消</button>
        <button
          class="btn"
          :class="confirmState.tone === 'danger' ? 'btn--danger' : 'btn--primary'"
          type="button"
          :disabled="busy || (confirmState.needReason && !confirmState.reason)"
          @click="runConfirm"
        >
          {{ confirmState.confirmText }}
        </button>
      </template>
    </AppModal>

    <!-- 结算 -->
    <AppModal v-model="settleOpen" title="确认结算" :subtitle="`收费单 ${charge?.chargeNo ?? ''}`" width="460px">
      <div class="stack">
        <div class="settle-amount">
          <span class="muted">应结金额</span>
          <strong class="num">{{ money(charge?.totalAmount ?? 0) }}</strong>
        </div>
        <label class="field">
          <span class="label">支付方式 <span class="req">*</span></span>
          <select v-model="paymentMethod" class="select">
            <option value="INSURANCE">医保结算</option>
            <option value="CASH">现金</option>
            <option value="BANK_CARD">银行卡</option>
            <option value="MOBILE">移动支付</option>
          </select>
        </label>
        <p class="hint">结算后不可直接修改金额；如需退费请走退费流程并保留审计原因。</p>
      </div>
      <template #footer>
        <button class="btn" type="button" @click="settleOpen = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="busy" @click="submitSettle">确认结算</button>
      </template>
    </AppModal>

    <!-- 明文（需 read_phi，服务端强制） -->
    <AppModal v-model="revealOpen" title="患者敏感信息" subtitle="本次查看已记录审计" width="520px">
      <div v-if="revealed" class="stack reveal">
        <div class="reveal-row"><span>姓名</span><strong>{{ revealed.name }}</strong></div>
        <div class="reveal-row"><span>证件号</span><strong class="mono">{{ revealed.idNo || '—' }}</strong></div>
        <div class="reveal-row"><span>手机号</span><strong class="mono">{{ revealed.phone || '—' }}</strong></div>
        <div class="reveal-row"><span>住址</span><strong>{{ revealed.address || '—' }}</strong></div>
      </div>
    </AppModal>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import InlineConsult from '@/components/ai/InlineConsult.vue'
import InlineRxAudit from '@/components/ai/InlineRxAudit.vue'
import AppIcon from '@/components/ui/AppIcon.vue'
import AppModal from '@/components/ui/AppModal.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import KpiCard from '@/components/ui/KpiCard.vue'
import LoadError from '@/components/ui/LoadError.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import StatusPill from '@/components/ui/StatusPill.vue'
import { clinicalApi, empiApi } from '@/api'
import { errorText } from '@/api/http'
import type {
  Bed,
  ChargeItem,
  ChargeView,
  Dept,
  Frequency,
  Patient,
  Visit,
  VisitOrder,
  VisitTransfer,
  Ward,
} from '@/api/types'
import { hasPermission, session } from '@/stores/session'
import { toast } from '@/stores/toast'
import { ageOf, date, dateTime, genderLabel, money } from '@/utils/format'

const busy = ref(false)
const loading = ref(false)
/** 基础数据（科室/频次/医嘱/收费）加载失败：非空即错误态。 */
const loadErr = ref('')
const updatedAt = ref('')

function stamp() {
  const d = new Date()
  const p = (n: number) => String(n).padStart(2, '0')
  updatedAt.value = `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

/* ------------------------------ 患者检索 ------------------------------ */

const keyword = ref('')
const searchInput = ref<HTMLInputElement | null>(null)
const patients = ref<Patient[]>([])
const patientTotal = ref(0)
const searching = ref(false)
const showResults = ref(false)
const selectedPatient = ref<Patient | null>(null)
let searchTimer: number | undefined

const canReveal = computed(() => hasPermission('empi:patient:read_phi'))
const role = computed(() => session.roleLabel.value)

const patientTotalLabel = computed(() =>
  patientTotal.value ? `共 ${patientTotal.value} 条` : patients.value.length ? '' : '',
)

function onKeywordInput() {
  window.clearTimeout(searchTimer)
  showResults.value = false
  if (keyword.value.trim().length < 1) {
    patients.value = []
    return
  }
  searchTimer = window.setTimeout(searchPatients, 350)
}

async function searchPatients() {
  const kw = keyword.value.trim()
  if (!kw) return
  searching.value = true
  showResults.value = true
  try {
    // 后端 patientNo 为精确匹配、name 为模糊匹配，且两者是 AND 关系，
    // 因此按输入形态二选一，避免"两个都传导致必然查不到"。
    const looksLikeNo = /^[A-Za-z]{1,4}\d{2,}$/.test(kw)
    const res = looksLikeNo ? await empiApi.searchByNo(kw, 1, 20) : await empiApi.searchByName(kw, 1, 20)
    patients.value = res.items
    patientTotal.value = res.total
    if (res.items.length === 0) {
      toast.info('未找到患者', '请核对姓名或用完整病案号检索')
    }
  } catch (e) {
    toast.error('患者检索失败', errorText(e))
  } finally {
    searching.value = false
  }
}

async function pickPatient(p: Patient) {
  selectedPatient.value = p
  showResults.value = false
  keyword.value = ''
  await loadVisits(p.id)
}

const revealed = ref<Patient | null>(null)
const revealOpen = ref(false)

async function revealPatient() {
  if (!selectedPatient.value) return
  try {
    revealed.value = await empiApi.patient(selectedPatient.value.id, true)
    revealOpen.value = true
  } catch (e) {
    toast.error('无法查看明文', errorText(e))
  }
}

/* ------------------------------ 就诊 ------------------------------ */

const visits = ref<Visit[]>([])
const visitsLoading = ref(false)
const selectedVisit = ref<Visit | null>(null)
const departments = ref<Dept[]>([])

const canRegister = computed(() => hasPermission('clinical:registration:create'))
const canTreat = computed(() => hasPermission('clinical:visit:treat'))
const canSettle = computed(() => hasPermission('clinical:charge:settle'))

function deptName(id: string) {
  return departments.value.find((d) => d.id === id)?.name ?? id.slice(0, 8)
}

function visitTypeLabel(type: string) {
  if (type === 'INPATIENT') return '住院'
  return type === 'EMERGENCY' ? '急诊' : '门诊'
}

async function loadVisits(patientId: string) {
  visitsLoading.value = true
  loadErr.value = ''
  selectedVisit.value = null
  orders.value = []
  charge.value = null
  transfers.value = []
  try {
    visits.value = await clinicalApi.visits(patientId)
    const active = visits.value.find((v) => v.status === 'IN_TREATMENT') ?? visits.value[0]
    if (active) await pickVisit(active)
  } catch (e) {
    toast.error('就诊记录加载失败', errorText(e))
    loadErr.value = errorText(e)
    visits.value = []
  } finally {
    visitsLoading.value = false
  }
}

async function pickVisit(v: Visit) {
  selectedVisit.value = v
  // 换就诊先清空，避免上一个患者的医嘱与收费单短暂留在界面上。
  orders.value = []
  charge.value = null
  chargeError.value = ''
  transfers.value = []
  pendingOrderKey = null
  await Promise.all([loadOrders(), loadCharge(), loadTransfers()])
}

/* ------------------------------ 转科 ------------------------------ */

const canTransfer = computed(
  () =>
    canTreat.value &&
    selectedVisit.value?.visitType === 'INPATIENT' &&
    selectedVisit.value?.status === 'IN_TREATMENT',
)

const transfers = ref<VisitTransfer[]>([])
const transferOpen = ref(false)
const transferForm = reactive({ toDeptId: '', wardId: '', bedId: '', reason: '' })
const wards = ref<Ward[]>([])
const transferBeds = ref<Bed[]>([])
const bedsLoading = ref(false)

/** 目标科室只能是启用中的住院科室，且不能是当前主诊科室。 */
const transferDepts = computed(() =>
  departments.value.filter(
    (d) =>
      (d.type === 'INPATIENT' || d.type === 'BOTH') && d.id !== selectedVisit.value?.deptId,
  ),
)

const transferWards = computed(() =>
  wards.value.filter((w) => w.deptId === transferForm.toDeptId),
)

const freeBeds = computed(() => transferBeds.value.filter((b) => b.status === 'FREE'))

const transferBedHint = computed(() => {
  if (bedsLoading.value) return '加载中…'
  if (!transferForm.wardId) return '请先选择病区'
  return freeBeds.value.length ? '请选择床位' : '该病区暂无空床'
})

async function loadTransfers() {
  const visit = selectedVisit.value
  if (!visit) return
  try {
    const list = await clinicalApi.visitTransfers(visit.id)
    if (selectedVisit.value?.id === visit.id) transfers.value = list
  } catch {
    // 非住院就诊或权限不足时不影响工作台主流程
    transfers.value = []
  }
}

async function openTransfer() {
  if (!selectedVisit.value) return
  transferForm.toDeptId = ''
  transferForm.wardId = ''
  transferForm.bedId = ''
  transferForm.reason = ''
  transferBeds.value = []
  transferOpen.value = true
  if (wards.value.length === 0) {
    try {
      wards.value = await clinicalApi.wards()
    } catch (e) {
      toast.error('病区加载失败', errorText(e))
    }
  }
}

watch(
  () => transferForm.toDeptId,
  () => {
    transferForm.bedId = ''
    transferBeds.value = []
    const list = transferWards.value
    transferForm.wardId = list.length === 1 ? list[0].id : ''
    if (list.length <= 1) void loadTransferBeds()
  },
)

watch(
  () => transferForm.wardId,
  () => {
    transferForm.bedId = ''
    void loadTransferBeds()
  },
)

async function loadTransferBeds() {
  if (!transferForm.wardId) {
    transferBeds.value = []
    return
  }
  bedsLoading.value = true
  try {
    transferBeds.value = await clinicalApi.beds(transferForm.wardId)
  } catch (e) {
    transferBeds.value = []
    toast.error('床位加载失败', errorText(e))
  } finally {
    bedsLoading.value = false
  }
}

async function submitTransfer() {
  if (!selectedVisit.value) return
  if (!transferForm.toDeptId || !transferForm.bedId || !transferForm.reason) {
    toast.warn('请填写完整', '目标科室、床位与转科原因必填')
    return
  }
  busy.value = true
  try {
    const t = await clinicalApi.transferVisit(selectedVisit.value.id, {
      toDeptId: transferForm.toDeptId,
      bedId: transferForm.bedId,
      reason: transferForm.reason,
    })
    transferOpen.value = false
    toast.ok('转科完成', `${deptName(t.fromDeptId)} → ${deptName(t.toDeptId)}`)
    // 转科后本科室已失去该就诊的访问权：重新按科室加载，不在本地保留旧就诊。
    selectedVisit.value = null
    if (selectedPatient.value) await loadVisits(selectedPatient.value.id)
  } catch (e) {
    toast.error('转科失败', errorText(e))
  } finally {
    busy.value = false
  }
}

/* ------------------------------ 医嘱 ------------------------------ */

/**
 * 开立医嘱的幂等键：同一次提交失败重试时复用同一个键，成功后清空。
 * 弱网下重复点击不会重复开单（后端按 visit + actor + key 去重）。
 */
let pendingOrderKey: string | null = null

function ensureOrderKey(): string {
  if (!pendingOrderKey) {
    pendingOrderKey =
      typeof crypto !== 'undefined' && 'randomUUID' in crypto
        ? crypto.randomUUID()
        : `${Date.now()}-${Math.random().toString(36).slice(2, 12)}`
  }
  return pendingOrderKey
}

const orders = ref<VisitOrder[]>([])
const ordersLoading = ref(false)
const frequencies = ref<Frequency[]>([])

/* ------------------------------ 派生指标与筛选 ------------------------------ */

const orderFilters: { value: 'ALL' | 'LONG' | 'TEMP'; label: string }[] = [
  { value: 'ALL', label: '全部' },
  { value: 'LONG', label: '长期' },
  { value: 'TEMP', label: '临时' },
]
const orderFilter = ref<'ALL' | 'LONG' | 'TEMP'>('ALL')
const orderQuery = ref('')

const longCount = computed(() => orders.value.filter((o) => o.orderMode === 'LONG').length)
const tempCount = computed(() => orders.value.filter((o) => o.orderMode !== 'LONG').length)
const placedCount = computed(() => orders.value.filter((o) => o.status === 'PLACED').length)
const chargedCount = computed(() => orders.value.filter((o) => o.status === 'CHARGED').length)
const transferFailedCount = computed(() => orders.value.filter((o) => o.transferStatus === 'FAILED').length)

const visibleOrders = computed(() => {
  const q = orderQuery.value.toLowerCase()
  return orders.value.filter((o) => {
    if (orderFilter.value === 'LONG' && o.orderMode !== 'LONG') return false
    if (orderFilter.value === 'TEMP' && o.orderMode === 'LONG') return false
    if (!q) return true
    return (
      o.itemName.toLowerCase().includes(q) ||
      o.itemCode.toLowerCase().includes(q) ||
      o.orderNo.toLowerCase().includes(q)
    )
  })
})

const CATEGORY_LABEL: Record<string, string> = {
  DRUG: '药品',
  TREATMENT: '治疗',
  EXAM: '检查',
  MATERIAL: '耗材',
  REGISTRATION: '挂号',
  CONSULTATION: '诊查',
}

const feeGroups = computed(() => {
  const map = new Map<string, number>()
  for (const it of charge.value?.items ?? []) {
    map.set(it.category, (map.get(it.category) ?? 0) + Number(it.amount))
  }
  const total = [...map.values()].reduce((a, b) => a + b, 0) || 1
  return [...map.entries()]
    .map(([key, amount]) => ({
      key,
      label: CATEGORY_LABEL[key] ?? key,
      amount,
      pct: Math.max(0, Math.round((amount / total) * 100)),
    }))
    .sort((a, b) => b.amount - a.amount)
})

const hasReversal = computed(() => (charge.value?.items ?? []).some((it) => Number(it.amount) < 0))
const netZero = computed(() => Boolean(charge.value) && Math.abs(Number(charge.value?.totalAmount ?? 0)) < 0.005)

const pharmacyTransfers = computed(
  () => orders.value.filter((o) => o.externalType === 'PRESCRIPTION').length,
)
const techTransfers = computed(
  () => orders.value.filter((o) => o.externalType === 'EXAM_APPLICATION').length,
)
const recentTransfers = computed(() =>
  orders.value.filter((o) => Boolean(o.externalNo)).slice(-3).reverse(),
)

const modes = [
  { value: 'TEMP', label: '临时医嘱' },
  { value: 'LONG', label: '长期医嘱' },
]

const line = reactive({
  itemCode: '',
  itemName: '',
  unit: '',
  price: 0,
  quantity: 1,
  dose: '',
  frequency: '',
  orderMode: 'TEMP',
  // 收费项目分类（DRUG/TREATMENT/EXAM/…）：内联审方只核对药品，避免把检查项误报"字典外药品"
  category: '',
})

/**
 * 内联审方的输入：本次就诊已开立的**药品类**医嘱 + 当前正在填写的药品行。
 * 规则在 AI 中台侧执行（确定性、毫秒级），这里只负责把上下文拼对。
 */
const rxAuditItems = computed(() => {
  const out: { name: string; dose: string; frequency: string }[] = []
  for (const o of orders.value) {
    if (o.orderType !== 'DRUG') continue
    if (o.status === 'CANCELLED' || o.status === 'STOPPED') continue
    out.push({ name: o.itemName, dose: o.dose || '', frequency: o.frequency || '' })
  }
  if (line.itemCode && line.category === 'DRUG') {
    out.push({ name: line.itemName, dose: line.dose, frequency: line.frequency })
  }
  return out
})

const frequencyOptions = computed(() => {
  if (line.orderMode === 'LONG') return frequencies.value.filter((f) => f.daily)
  return frequencies.value
})

const modeHint = computed(() => {
  if (line.orderMode === 'LONG') {
    return '长期医嘱按日计费：数量口径为「每日总量」，频次须为可每日执行的频次。'
  }
  return '临时医嘱一次性计费：数量为本次总量，结算时并入收费单。'
})

function setMode(mode: string) {
  line.orderMode = mode
  if (mode === 'LONG' && line.frequency && !frequencyOptions.value.some((f) => f.code === line.frequency)) {
    line.frequency = ''
  }
}

function freqLabel(code: string | null) {
  if (!code) return '—'
  const f = frequencies.value.find((x) => x.code.toLowerCase() === code.toLowerCase())
  return f ? `${f.label}（${f.code}）` : code
}

async function loadOrders() {
  const visit = selectedVisit.value
  if (!visit) return
  ordersLoading.value = true
  loadErr.value = ''
  try {
    const list = await clinicalApi.orders(visit.id)
    // 期间切换了就诊：丢弃旧就诊的医嘱，避免写进新表头。
    if (selectedVisit.value?.id !== visit.id) return
    orders.value = list
    stamp()
  } catch (e) {
    if (selectedVisit.value?.id !== visit.id) return
    toast.error('医嘱加载失败', errorText(e))
    loadErr.value = errorText(e)
  } finally {
    if (selectedVisit.value?.id === visit.id) ordersLoading.value = false
  }
}

async function submitOrder() {
  if (!selectedVisit.value || !line.itemCode) return
  if (!Number.isFinite(line.quantity) || line.quantity < 1) {
    toast.warn('数量不合法', '数量必须为大于 0 的整数')
    return
  }
  busy.value = true
  try {
    await clinicalApi.placeOrders(
      selectedVisit.value.id,
      [
        {
          itemCode: line.itemCode,
          quantity: Math.floor(line.quantity),
          dose: line.dose || undefined,
          frequency: line.frequency || undefined,
          orderMode: line.orderMode,
        },
      ],
      ensureOrderKey(),
    )
    pendingOrderKey = null
    toast.ok('医嘱已开立', `${line.itemName} · ${line.quantity}${line.unit}`)
    line.itemCode = ''
    line.itemName = ''
    line.unit = ''
    line.price = 0
    line.quantity = 1
    line.dose = ''
    line.category = ''
    await Promise.all([loadOrders(), loadCharge()])
  } catch (e) {
    // 保留 pendingOrderKey：用户重试时仍是同一个键，不会重复开单
    toast.error('开立失败', errorText(e))
  } finally {
    busy.value = false
  }
}

async function retryTransfer(o: VisitOrder) {
  if (!selectedVisit.value) return
  try {
    await clinicalApi.retryTransfer(selectedVisit.value.id, o.id)
    toast.ok('已重新转送')
    await loadOrders()
  } catch (e) {
    toast.error('转送重试失败', errorText(e))
  }
}

/* ------------------------------ 项目目录 ------------------------------ */

const catalogOpen = ref(false)
const catalogQuery = ref('')
const catalogCategory = ref('')
const catalog = ref<ChargeItem[]>([])
const catalogLoading = ref(false)

const categories = [
  { value: '', label: '全部' },
  { value: 'DRUG', label: '药品' },
  { value: 'TREATMENT', label: '治疗' },
  { value: 'EXAM', label: '检查' },
  { value: 'MATERIAL', label: '耗材' },
  { value: 'REGISTRATION', label: '挂号' },
  { value: 'CONSULTATION', label: '诊查' },
]
let catalogTimer: number | undefined

function openCatalog() {
  catalogOpen.value = true
  if (catalog.value.length === 0) void loadCatalog()
}

function onCatalogInput() {
  window.clearTimeout(catalogTimer)
  catalogTimer = window.setTimeout(loadCatalog, 300)
}

async function loadCatalog() {
  catalogLoading.value = true
  try {
    catalog.value = await clinicalApi.chargeItems(catalogQuery.value.trim(), catalogCategory.value || undefined, 60)
  } catch (e) {
    toast.error('项目加载失败', errorText(e))
  } finally {
    catalogLoading.value = false
  }
}

function setCatalogCategory(v: string) {
  catalogCategory.value = v
  void loadCatalog()
}

function chooseItem(it: ChargeItem) {
  line.itemCode = it.itemCode
  line.itemName = it.name
  line.unit = it.unit
  line.price = it.price
  line.category = it.category
  catalogOpen.value = false
}

/* ------------------------------ 费用 ------------------------------ */

const charge = ref<ChargeView | null>(null)
const settleOpen = ref(false)
const paymentMethod = ref('INSURANCE')

/** 收费单读取失败时保留上一次成功值，不把失败伪装成 0 元。 */
const chargeError = ref('')
const chargeUpdatedAt = ref('')

const chargeValue = computed(() => {
  if (chargeError.value) return '—'
  return charge.value ? money(charge.value.totalAmount) : '—'
})

const chargeMeta = computed(() => {
  if (chargeError.value) {
    return chargeUpdatedAt.value
      ? `收费单读取失败 · 上次成功 ${chargeUpdatedAt.value}`
      : '收费单读取失败'
  }
  if (!charge.value) return '尚未生成收费单'
  return `${charge.value.chargeNo} · ${charge.value.status === 'PAID' ? '已结算' : '未结算'}`
})

async function loadCharge() {
  const visit = selectedVisit.value
  if (!visit) return
  try {
    const next = await clinicalApi.charge(visit.id)
    if (selectedVisit.value?.id !== visit.id) return
    charge.value = next
    chargeError.value = ''
    chargeUpdatedAt.value = new Date().toLocaleTimeString('zh-CN', {
      hour: '2-digit',
      minute: '2-digit',
      hour12: false,
    })
  } catch (e) {
    if (selectedVisit.value?.id !== visit.id) return
    chargeError.value = errorText(e)
  }
}

function openSettle() {
  settleOpen.value = true
}

async function submitSettle() {
  if (!selectedVisit.value) return
  busy.value = true
  try {
    const res = await clinicalApi.settle(selectedVisit.value.id, paymentMethod.value)
    charge.value = res
    settleOpen.value = false
    toast.ok('结算完成', `${res.chargeNo} · ${money(res.totalAmount)}`)
  } catch (e) {
    toast.error('结算失败', errorText(e))
  } finally {
    busy.value = false
  }
}

/* ------------------------------ 确认动作 ------------------------------ */

const confirmOpen = ref(false)
const confirmState = reactive({
  title: '',
  desc: '',
  confirmText: '确认',
  tone: 'danger' as 'danger' | 'primary',
  needReason: false,
  reason: '',
  action: null as null | (() => Promise<void>),
})

function askCancel(o: VisitOrder) {
  confirmState.title = '撤销临时医嘱'
  confirmState.desc = `${o.itemName}（${o.orderNo}）撤销后不可恢复；已计费的医嘱不能撤销。`
  confirmState.confirmText = '确认撤销'
  confirmState.tone = 'danger'
  confirmState.needReason = false
  confirmState.reason = ''
  confirmState.action = async () => {
    if (!selectedVisit.value) return
    await clinicalApi.cancelOrder(selectedVisit.value.id, o.id)
    toast.ok('医嘱已撤销')
    await Promise.all([loadOrders(), loadCharge()])
  }
  confirmOpen.value = true
}

function askStop(o: VisitOrder) {
  confirmState.title = '停止长期医嘱'
  confirmState.desc = `${o.itemName}（${o.orderNo}）停嘱当日仍计费，次日起不再计费。`
  confirmState.confirmText = '确认停嘱'
  confirmState.tone = 'primary'
  confirmState.needReason = true
  confirmState.reason = ''
  confirmState.action = async () => {
    if (!selectedVisit.value) return
    await clinicalApi.stopOrder(selectedVisit.value.id, o.id, confirmState.reason)
    toast.ok('已停嘱')
    await Promise.all([loadOrders(), loadCharge()])
  }
  confirmOpen.value = true
}

async function runConfirm() {
  if (!confirmState.action) return
  busy.value = true
  try {
    await confirmState.action()
    confirmOpen.value = false
  } catch (e) {
    toast.error('操作失败', errorText(e))
  } finally {
    busy.value = false
  }
}

/* ------------------------------ 新建就诊 ------------------------------ */

const registerOpen = ref(false)
const registerForm = reactive({ deptId: '', doctorId: '', visitType: 'OUTPATIENT' })

function openRegister() {
  registerForm.deptId = departments.value[0]?.id ?? ''
  registerForm.doctorId = ''
  registerForm.visitType = 'OUTPATIENT'
  registerOpen.value = true
}

async function submitRegister() {
  if (!selectedPatient.value) return
  if (!registerForm.deptId || !registerForm.doctorId) {
    toast.warn('请填写完整', '科室与接诊医师工号必填')
    return
  }
  busy.value = true
  try {
    const v = await clinicalApi.register({
      patientId: selectedPatient.value.id,
      deptId: registerForm.deptId,
      doctorId: registerForm.doctorId,
      visitType: registerForm.visitType,
    })
    registerOpen.value = false
    toast.ok('就诊已建立', v.visitNo)
    await loadVisits(selectedPatient.value.id)
    const created = visits.value.find((x) => x.id === v.id)
    if (created) await pickVisit(created)
  } catch (e) {
    toast.error('建立就诊失败', errorText(e))
  } finally {
    busy.value = false
  }
}

async function startVisit() {
  if (!selectedVisit.value) return
  busy.value = true
  try {
    const v = await clinicalApi.startVisit(selectedVisit.value.id)
    selectedVisit.value = v
    const idx = visits.value.findIndex((x) => x.id === v.id)
    if (idx >= 0) visits.value[idx] = v
    toast.ok('已开始接诊', '现在可以开立医嘱')
  } catch (e) {
    toast.error('接诊失败', errorText(e))
  } finally {
    busy.value = false
  }
}

/* ------------------------------ 初始化 ------------------------------ */

async function refreshAll() {
  loading.value = true
  loadErr.value = ''
  try {
    departments.value = await clinicalApi.departments()
    frequencies.value = await clinicalApi.frequencies()
    if (selectedVisit.value) await Promise.all([loadOrders(), loadCharge()])
  } catch (e) {
    toast.error('基础数据加载失败', errorText(e))
    loadErr.value = errorText(e)
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  searchInput.value?.focus()
  await refreshAll()
})

onBeforeUnmount(() => {
  window.clearTimeout(searchTimer)
  window.clearTimeout(catalogTimer)
})
</script>


<style scoped src="../styles/orders-view.css"></style>
