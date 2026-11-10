<template>
  <div class="page">
    <PageHeader :title="t('aiReview.title')" :sub="headSub">
      <template #actions>
        <button v-if="isAdmin" class="btn" type="button" :disabled="loading" @click="openPurge">
          <AppIcon name="trash" :size="15" /> {{ t('aiReview.purge.open') }}
        </button>
        <button class="btn" type="button" :disabled="loading" @click="reloadAll">
          <AppIcon name="refresh" :size="15" /> {{ t('aiReview.refresh') }}
        </button>
      </template>
    </PageHeader>

    <div class="kpi-strip">
      <KpiCard :label="t('aiReview.kpi.pending')" :value="pendCount" :meta="pendingMeta" />
      <KpiCard :label="t('aiReview.kpi.history')" :value="histCount" :meta="t('aiReview.kpi.historyMeta')" />
      <KpiCard
        v-if="canRx"
        :label="t('aiReview.kpi.rx')"
        :value="rxTotal"
        :unit="t('aiReview.unit.rx')"
        :meta="t('aiReview.kpi.rxMeta')"
      />
      <KpiCard
        v-if="isMine"
        :label="t('aiReview.kpi.consult')"
        :value="consultPending"
        :unit="t('aiReview.unit.consult')"
        :meta="t('aiReview.kpi.consultMeta')"
      />
      <KpiCard
        v-if="!isMine"
        :label="t('aiReview.kpi.cases')"
        :value="caseLoaded ? (caseStats.active ?? 0) : '—'"
        :unit="t('aiReview.unit.case')"
        :meta="t('aiReview.kpi.casesMeta')"
      />
    </div>

    <LoadError v-if="loadErr" :message="loadErr" :retrying="loading" @retry="reloadAll" />

    <div class="seg" role="tablist" :aria-label="t('aiReview.tabs')">
      <button
        v-for="tb in tabs"
        :key="tb.key"
        class="seg-btn"
        :class="{ active: tab === tb.key }"
        type="button"
        role="tab"
        :aria-selected="tab === tb.key"
        @click="switchTab(tb.key)"
      >
        {{ tb.label }}
        <span v-if="tb.count !== undefined" class="seg-count">{{ tb.count }}</span>
      </button>
    </div>

    <!-- ============ 待处理 / 我的待确认 ============ -->
    <template v-if="tab === 'pending'">
      <!-- 开药待审（admin / pharmacist）：服务端分页 + 关键字检索 -->
      <div v-if="canRx" class="sheet">
        <div class="sheet-head">
          <span class="sheet-title">{{ t('aiReview.rx.title') }}</span>
          <span class="badge badge--neutral">{{ rxTotal }}</span>
          <span class="spacer" />
          <label class="field">
            <span class="label">{{ t('aiReview.search') }}</span>
            <input
              v-model="rxKeyword"
              class="input"
              type="search"
              spellcheck="false"
              data-testid="review-rx-search"
              :placeholder="t('aiReview.rx.search')"
            />
          </label>
        </div>

        <TableSkeleton v-if="rxLoading && rxRows.length === 0" :rows="3" />
        <EmptyState
          v-else-if="rxRows.length === 0"
          :title="t('aiReview.rx.empty.title')"
          :desc="t('aiReview.rx.empty.desc')"
        />
        <div v-else class="rx-list">
          <article v-for="p in rxRows" :key="p.id" class="rx-card">
            <div class="rx-head">
              <span class="mono is-strong">{{ p.id }}</span>
              <span v-if="p.forced_high_risk" class="badge badge--danger">{{ t('aiReview.rx.forced') }}</span>
              <span v-if="p.out_of_dict" class="badge badge--danger">{{ t('aiReview.rx.outOfDict') }}</span>
              <span class="muted">
                {{ t('aiReview.rx.meta', { doctor: p.doctor || '—', dept: p.dept || '—', time: dateTime(p.created_at) }) }}
              </span>
              <span class="spacer" />
              <button class="btn btn--sm btn--primary" type="button" @click="openRxModal(p, 'approve')">
                <AppIcon name="check" :size="13" /> {{ t('aiReview.action.approve') }}
              </button>
              <button class="btn btn--sm" type="button" @click="openRxModal(p, 'reject')">
                <AppIcon name="x" :size="13" /> {{ t('aiReview.action.reject') }}
              </button>
            </div>
            <p class="rx-case">{{ p.case_text || t('aiReview.rx.noCase') }}</p>
            <ul class="rx-drugs">
              <li v-for="(d, i) in p.drugs || []" :key="i">
                <span :class="['rx-drug-name', { 'is-ood': d.out_of_dict }]">{{ d.name }}</span>
                <span v-if="d.out_of_dict" class="badge badge--danger">{{ t('aiReview.rx.drugOutOfDict') }}</span>
                <span class="muted">
                  {{ d.dose || t('aiReview.rx.doseTbd') }} · {{ d.freq || t('aiReview.rx.freqTbd') }}
                  <template v-if="d.note"> · {{ t('aiReview.rx.drugNote', { note: d.note }) }}</template>
                </span>
              </li>
            </ul>
            <p v-if="p.contraindication_reason || p.forced_high_risk" class="rx-reason">
              {{ t('aiReview.rx.reason', { reason: p.contraindication_reason || t('aiReview.rx.reasonEmpty') }) }}
            </p>
          </article>
        </div>
        <div v-if="rxTotal > PER" class="pager">
          <button class="btn btn--sm" type="button" :disabled="rxPage === 0" @click="rxGo(-1)">
            {{ t('aiReview.action.prev') }}
          </button>
          <span class="pager-info">
            {{ t('aiReview.pager', { page: rxPage + 1, pages: Math.max(1, Math.ceil(rxTotal / PER)), total: rxTotal }) }}
          </span>
          <button class="btn btn--sm" type="button" :disabled="rxPage >= Math.ceil(rxTotal / PER) - 1" @click="rxGo(1)">
            {{ t('aiReview.action.next') }}
          </button>
        </div>
      </div>

      <!-- 高危待核对 / 我的待确认 -->
      <div class="sheet">
        <div class="sheet-head">
          <span class="sheet-title">{{ pendingTitle }}</span>
          <span class="badge badge--neutral">{{ pendCount }}</span>
          <span class="spacer" />
          <label class="field">
            <span class="label">{{ t('aiReview.search') }}</span>
            <input
              v-model="keyword"
              class="input"
              type="search"
              spellcheck="false"
              data-testid="review-search"
              :placeholder="t('aiReview.searchPlaceholder')"
            />
          </label>
        </div>

        <TableSkeleton v-if="loading && pendView.length === 0" :rows="5" />
        <EmptyState
          v-else-if="pendView.length === 0"
          :title="keyword.trim() ? t('aiReview.empty.search.title') : isPh ? t('aiReview.empty.ph.title') : isMine ? t('aiReview.empty.mine.title') : t('aiReview.empty.pending.title')"
          :desc="keyword.trim() ? t('aiReview.empty.search.desc') : isPh ? t('aiReview.empty.ph.desc') : isMine ? t('aiReview.empty.mine.desc') : t('aiReview.empty.pending.desc')"
        />
        <div v-else class="table-wrap">
          <table class="table table--stack">
            <thead>
              <tr>
                <th>{{ t('aiReview.col.item') }}</th>
                <th class="is-num">{{ t('aiReview.col.confidence') }}</th>
                <th>{{ t('aiReview.col.risk') }}</th>
                <th>{{ t('aiReview.col.submitter') }}</th>
                <th class="col-action">{{ t('aiReview.col.action') }}</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in pendView" :key="row.id">
                <td data-primary>
                  <button class="link-btn" type="button" @click="openDetail(row, 'pending')">
                    {{ row.agent || '—' }}：{{ preview(row.question) }}
                  </button>
                  <p v-if="row.answerPreview !== undefined" class="preview">
                    {{ row.answerPreview || t('aiReview.item.noPreview') }}
                  </p>
                  <p v-else-if="row.answer" class="preview">{{ preview(row.answer) }}</p>
                  <p v-if="row.imageCount || imagesOf(row).length" class="muted small">
                    {{ t('aiReview.item.images', { n: row.imageCount ?? imagesOf(row).length }) }}
                  </p>
                </td>
                <td class="is-num">{{ (row.confidence ?? 0).toFixed(2) }}</td>
                <td><span class="badge badge--warn">{{ row.risk_reason || t('aiReview.risk.high') }}</span></td>
                <td>
                  {{ row.submitted_by || '—' }}
                  <div class="muted small">{{ dateTime(row.ts) }}</div>
                </td>
                <td class="col-action">
                  <template v-if="isMine">
                    <span v-if="needsSelfConfirm(row)" class="badge badge--accent">{{ t('aiReview.item.selfConfirmPending') }}</span>
                    <div class="row-actions">
                      <button v-if="needsSelfConfirm(row)" class="btn btn--sm btn--primary" type="button" :disabled="busy" @click="selfConfirm(row.id)">
                        {{ t('aiReview.action.selfConfirm') }}
                      </button>
                      <button class="btn btn--sm" type="button" @click="openDetail(row, 'pending')">
                        {{ t('aiReview.action.detail') }}
                      </button>
                    </div>
                  </template>
                  <template v-else>
                    <span v-if="row.submitted_by === me" class="muted small">{{ t('aiReview.doubleControl.self') }}</span>
                    <div v-else-if="canSignItem(row)" class="row-actions">
                      <button class="btn btn--sm btn--primary" type="button" :disabled="busy" @click="askResolve(row.id, 'approved')">
                        {{ t('aiReview.action.approve') }}
                      </button>
                      <button class="btn btn--sm" type="button" :disabled="busy" @click="askResolve(row.id, 'rejected')">
                        {{ t('aiReview.action.reject') }}
                      </button>
                      <button class="btn btn--sm" type="button" @click="openDetail(row, 'pending')">
                        {{ t('aiReview.action.detail') }}
                      </button>
                    </div>
                    <div v-else class="row-actions">
                      <span class="muted small">{{ signDeniedHint(row) }}</span>
                      <button class="btn btn--sm" type="button" @click="openDetail(row, 'pending')">
                        {{ t('aiReview.action.detail') }}
                      </button>
                    </div>
                  </template>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <div v-if="pendPages > 1" class="pager">
          <button class="btn btn--sm" type="button" :disabled="pendPage === 0" @click="pendPage -= 1">
            {{ t('aiReview.action.prev') }}
          </button>
          <span class="pager-info">
            {{ t('aiReview.pager', { page: Math.min(pendPage, pendPages - 1) + 1, pages: pendPages, total: pendCount }) }}
          </span>
          <button class="btn btn--sm" type="button" :disabled="pendPage >= pendPages - 1" @click="pendPage += 1">
            {{ t('aiReview.action.next') }}
          </button>
        </div>
      </div>
    </template>

    <!-- ============ 历史记录 / 我的历史 ============ -->
    <div v-if="tab === 'history'" class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">{{ isMine ? t('aiReview.tab.myHistory') : t('aiReview.tab.history') }}</span>
        <span class="badge badge--neutral">{{ histCount }}</span>
        <span class="spacer" />
        <label class="field">
          <span class="label">{{ t('aiReview.search') }}</span>
          <input
            v-model="keyword"
            class="input"
            type="search"
            spellcheck="false"
            data-testid="review-history-search"
            :placeholder="t('aiReview.searchPlaceholder')"
          />
        </label>
      </div>

      <TableSkeleton v-if="loading && histCount === 0" :rows="5" />
      <EmptyState
        v-else-if="histCount === 0"
        :title="keyword.trim() ? t('aiReview.empty.search.title') : t('aiReview.empty.history.title')"
        :desc="keyword.trim() ? t('aiReview.empty.search.desc') : t('aiReview.empty.history.desc')"
      />
      <div v-else class="table-wrap">
        <table class="table table--stack">
          <thead>
            <tr>
              <th>{{ t('aiReview.col.item') }}</th>
              <th class="is-num">{{ t('aiReview.col.confidence') }}</th>
              <th>{{ t('aiReview.col.risk') }}</th>
              <th>{{ t('aiReview.col.submitter') }}</th>
              <th>{{ t('aiReview.col.result') }}</th>
            </tr>
          </thead>
          <tbody>
            <!-- 药师：处方审核轨（与待核对同源的处方数据，状态/意见/时间取自处方字段） -->
            <tr v-for="p in histRxRows" :key="'rx-' + p.id">
              <td data-primary>
                <div class="is-strong">{{ t('aiReview.history.rxItem', { id: p.id }) }}</div>
                <p class="preview">{{ preview(p.case_text) }}</p>
                <p class="muted small">
                  {{ (p.drugs || []).map((d) => d.name).join('、') || t('aiReview.rx.noDrugs') }}
                </p>
              </td>
              <td class="is-num">—</td>
              <td>
                <span class="badge" :class="p.forced_high_risk ? 'badge--danger' : 'badge--neutral'">
                  {{ p.forced_high_risk ? t('aiReview.rx.forced') : t('aiReview.history.rxReview') }}
                </span>
              </td>
              <td>
                {{ p.doctor || '—' }}
                <div class="muted small">{{ dateTime(p.created_at) }}</div>
              </td>
              <td>
                <StatusPill :value="p.status === 'approved' ? 'APPROVED' : 'REJECTED'" :label="p.status === 'approved' ? t('aiReview.history.rxApproved') : t('aiReview.history.rejected')" />
                <div class="muted small">
                  {{ p.pharm_reviewer || '—' }}
                  <template v-if="p.pharm_reviewed_at"> · {{ dateTime(p.pharm_reviewed_at) }}</template>
                  <template v-if="p.pharm_opinion"> · {{ p.pharm_opinion }}</template>
                </div>
              </td>
            </tr>

            <!-- 复核队列历史 -->
            <tr v-for="p in histReviewRows" :key="p.id">
              <td data-primary>
                <button class="link-btn" type="button" @click="openDetail(p, 'history')">
                  {{ p.agent || '—' }}：{{ preview(p.question) }}
                </button>
                <p v-if="p.answerPreview !== undefined" class="preview">
                  {{ p.answerPreview || t('aiReview.item.noPreview') }}
                </p>
                <p v-else-if="p.answer" class="preview">{{ preview(p.answer) }}</p>
                <p v-if="p.imageCount || imagesOf(p).length" class="muted small">
                  {{ t('aiReview.item.images', { n: p.imageCount ?? imagesOf(p).length }) }}
                </p>
              </td>
              <td class="is-num">{{ (p.confidence ?? 0).toFixed(2) }}</td>
              <td><span class="badge badge--warn">{{ p.risk_reason || t('aiReview.risk.high') }}</span></td>
              <td>
                {{ p.submitted_by || '—' }}
                <div class="muted small">{{ dateTime(p.ts) }}</div>
              </td>
              <td>
                <StatusPill
                  :value="p.status === 'approved' ? 'APPROVED' : 'REJECTED'"
                  :label="p.status === 'approved' ? (isAuto(p) ? t('aiReview.history.autoSigned') : t('aiReview.history.approved')) : t('aiReview.history.rejected')"
                />
                <div class="muted small">
                  {{ p.reviewed_by || '—' }}
                  <template v-if="p.resolved_at"> · {{ dateTime(p.resolved_at) }}</template>
                  <template v-if="p.review_note"> · {{ p.review_note }}</template>
                </div>
                <div class="row-actions">
                  <button v-if="canReopen(p)" class="btn btn--sm" type="button" :disabled="busy" @click="reopen(p.id)">
                    {{ t('aiReview.action.reopen') }}
                  </button>
                  <button class="btn btn--sm" type="button" @click="openDetail(p, 'history')">
                    {{ t('aiReview.action.detail') }}
                  </button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <div v-if="histPages > 1" class="pager">
        <button class="btn btn--sm" type="button" :disabled="histPage === 0" @click="histPage -= 1">
          {{ t('aiReview.action.prev') }}
        </button>
        <span class="pager-info">
          {{ t('aiReview.pager', { page: Math.min(histPage, histPages - 1) + 1, pages: histPages, total: histCount }) }}
        </span>
        <button class="btn btn--sm" type="button" :disabled="histPage >= histPages - 1" @click="histPage += 1">
          {{ t('aiReview.action.next') }}
        </button>
      </div>
    </div>

    <!-- ============ 会诊协助收件箱（doctor / pharmacist） ============ -->
    <div v-if="tab === 'consults'" class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">{{ t('aiReview.consult.title') }}</span>
        <span class="badge badge--neutral">{{ consultTotal }}</span>
        <span class="spacer" />
        <label class="field">
          <span class="label">{{ t('aiReview.search') }}</span>
          <input
            v-model="consultKeyword"
            class="input"
            type="search"
            spellcheck="false"
            data-testid="review-consult-search"
            :placeholder="t('aiReview.consult.search')"
          />
        </label>
      </div>

      <LoadError v-if="consultErr" :message="consultErr" :retrying="consultLoading" @retry="loadConsults()" />
      <TableSkeleton v-else-if="consultLoading && consultRows.length === 0" :rows="3" />
      <EmptyState
        v-else-if="consultRows.length === 0"
        :title="t('aiReview.empty.consults.title')"
        :desc="consultKeyword.trim() ? t('aiReview.empty.search.desc') : t('aiReview.consult.emptyDesc', { dept: consultDept || t('aiReview.consult.noDept') })"
      />
      <div v-else class="card-list">
        <article v-for="c in consultRows" :key="c.id" class="block-card">
          <div class="rx-head">
            <span class="is-strong">{{ t('aiReview.consult.cardTitle') }}</span>
            <span class="badge badge--warn">{{ t('aiReview.consult.active') }}</span>
            <span class="muted">
              {{ t('aiReview.consult.meta', { initiator: c.initiator || '—', dept: c.initiator_dept || '—', time: dateTime(c.created_at) }) }}
            </span>
          </div>
          <div class="tag-row">
            <span class="muted">{{ t('aiReview.consult.targets') }}</span>
            <span v-for="(d, i) in c.target_depts || []" :key="i" class="badge badge--neutral">{{ d }}</span>
          </div>
          <h4 class="block-title">{{ t('aiReview.consult.summary') }}</h4>
          <p class="case-text">{{ splitConsult(c.question).caseText || t('aiReview.consult.emptyCase') }}</p>
          <template v-if="splitConsult(c.question).draft">
            <h4 class="block-title">{{ t('aiReview.consult.draft') }}</h4>
            <div class="md-body" v-html="md(splitConsult(c.question).draft)" />
          </template>
          <template v-if="c.ai_analysis">
            <h4 class="block-title">{{ t('aiReview.consult.aiByDept') }}</h4>
            <div class="md-body" v-html="md(c.ai_analysis)" />
          </template>
          <div v-if="consultThumbs(c).length" class="thumb-row">
            <img
              v-for="(u, i) in consultThumbs(c)"
              :key="i"
              class="thumb"
              :src="u"
              :alt="t('aiReview.detail.images')"
              role="button"
              tabindex="0"
              @click="showImage(u)"
              @keydown.enter.prevent="showImage(u)"
            />
          </div>
          <div v-if="consultMine(c)" class="consult-done">{{ t('aiReview.consult.opinion.done') }}</div>
          <div v-else class="consult-form">
            <textarea
              v-model="opDrafts[c.id]"
              class="textarea"
              rows="2"
              :aria-label="t('aiReview.consult.opinion.aria', { id: c.id })"
              :placeholder="t('aiReview.consult.opinion.placeholder')"
            />
            <button class="btn btn--sm btn--primary" type="button" :disabled="busy" @click="submitOpinion(c)">
              {{ t('aiReview.action.submitOpinion') }}
            </button>
          </div>
        </article>
      </div>
    </div>

    <!-- ============ 我发起的会诊（发起者视角：跟踪 / 结束汇总） ============ -->
    <ConsultInitiatedPanel v-if="tab === 'mine-consults'" />

    <!-- ============ 病例库（qc / admin，质控通过自动归档） ============ -->
    <div v-if="tab === 'cases'" class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">{{ t('aiReview.case.title') }}</span>
        <span class="badge badge--ok">{{ t('aiReview.case.statActive', { n: caseStats.active ?? 0 }) }}</span>
        <span class="badge badge--neutral">{{ t('aiReview.case.statRemoved', { n: caseStats.removed ?? 0 }) }}</span>
        <span class="spacer" />
        <label class="field">
          <span class="label">{{ t('aiReview.case.filter.dept') }}</span>
          <select v-model="caseDept" class="select" @change="loadCase()">
            <option value="">{{ t('aiReview.case.filter.allDept') }}</option>
            <option v-for="d in caseDepts" :key="d" :value="d">{{ d }}</option>
          </select>
        </label>
        <label class="field">
          <span class="label">{{ t('aiReview.case.filter.status') }}</span>
          <select v-model="caseStatus" class="select" @change="loadCase()">
            <option value="">{{ t('aiReview.case.filter.allStatus') }}</option>
            <option value="active">{{ t('aiReview.case.status.active') }}</option>
            <option value="removed">{{ t('aiReview.case.status.removed') }}</option>
          </select>
        </label>
        <label class="field">
          <span class="label">{{ t('aiReview.case.filter.from') }}</span>
          <input v-model="caseFrom" class="input" type="date" @change="loadCase()" />
        </label>
        <label class="field">
          <span class="label">{{ t('aiReview.case.filter.to') }}</span>
          <input v-model="caseTo" class="input" type="date" @change="loadCase()" />
        </label>
      </div>

      <TableSkeleton v-if="caseLoading && caseRows.length === 0" :rows="4" />
      <LoadError v-else-if="caseErr" :message="caseErr" :retrying="caseLoading" @retry="loadCase()" />
      <EmptyState
        v-else-if="caseRows.length === 0"
        :title="t('aiReview.empty.cases.title')"
        :desc="t('aiReview.empty.cases.desc')"
      />
      <div v-else class="card-list">
        <article v-for="c in caseRows" :key="c.id" class="block-card">
          <div class="rx-head">
            <span class="mono is-strong">{{ c.id }}</span>
            <span class="badge" :class="c.status === 'active' ? 'badge--ok' : 'badge--neutral'">
              {{ c.status === 'active' ? t('aiReview.case.status.active') : t('aiReview.case.status.removed') }}
            </span>
            <span class="badge badge--neutral">{{ c.dept || t('aiReview.case.noDept') }}</span>
            <span class="muted">
              {{ t('aiReview.case.meta', { patient: c.patient_ref || '—', time: dateTime(c.archived_at), reviewer: c.reviewed_by || '—' }) }}
            </span>
            <span class="spacer" />
            <button v-if="c.status === 'active'" class="btn btn--sm btn--danger" type="button" @click="openCaseRemove(c.id)">
              <AppIcon name="trash" :size="13" /> {{ t('aiReview.action.remove') }}
            </button>
          </div>
          <p class="case-text">{{ c.qc_conclusion || t('aiReview.case.noConclusion') }}</p>
          <p class="muted small">{{ t('aiReview.case.confidence', { v: c.qc_confidence ?? '—' }) }}</p>
          <details class="case-details">
            <summary>{{ t('aiReview.case.record') }}</summary>
            <pre class="record-pre">{{ JSON.stringify(c.record || {}, null, 2) }}</pre>
            <p v-if="c.labs && Object.keys(c.labs).length" class="muted small">
              {{ t('aiReview.case.labs', { list: labLine(c.labs) }) }}
            </p>
          </details>
          <p v-if="c.status !== 'active'" class="muted small">
            {{ t('aiReview.case.removedBy', { who: c.removed_by || '—', reason: c.removed_reason || '—' }) }}
          </p>
        </article>
      </div>
    </div>

    <!-- ============ 核对详情 ============ -->
    <AppModal
      v-model="detailOpen"
      :title="detail ? t('aiReview.detail.title', { agent: detail.agent || '—' }) : t('aiReview.detail.titlePlain')"
      width="760px"
    >
      <div v-if="detail" class="detail">
        <div class="detail-bar">
          <span class="badge" :class="`badge--${confMeta(detail.confidence).tone}`">
            {{ t('aiReview.detail.confidence', { label: confMeta(detail.confidence).label, value: (detail.confidence ?? 0).toFixed(2) }) }}
          </span>
          <span class="badge badge--warn">{{ detail.risk_reason || t('aiReview.risk.high') }}</span>
          <span v-if="isAuto(detail)" class="badge badge--accent">{{ t('aiReview.detail.autoSigned') }}</span>
          <span v-if="detail.confirmed_by_self" class="badge badge--ok">{{ t('aiReview.detail.selfConfirmed') }}</span>
          <span class="spacer" />
          <span class="mono muted">{{ detail.id }}</span>
        </div>

        <TableSkeleton v-if="detailLoading" :rows="5" />
        <template v-else>
          <section>
            <h4 class="block-title">{{ t('aiReview.detail.question') }}</h4>
            <div class="md-body" v-html="md(detail.question)" />
          </section>
          <section>
            <h4 class="block-title">{{ t('aiReview.detail.answer') }}</h4>
            <div class="md-body" v-html="md(detail.answer || t('aiReview.detail.noAnswer'))" />
          </section>
          <section v-if="imagesOf(detail).length">
            <h4 class="block-title">{{ t('aiReview.detail.images') }}</h4>
            <div class="thumb-row">
              <img
                v-for="(u, i) in imagesOf(detail)"
                :key="i"
                class="thumb"
                :src="u"
                :alt="t('aiReview.detail.images')"
                role="button"
                tabindex="0"
                @click="showImage(u)"
                @keydown.enter.prevent="showImage(u)"
              />
            </div>
          </section>
          <section>
            <h4 class="block-title">{{ t('aiReview.detail.submitter') }}</h4>
            <p>{{ detail.submitted_by || '—' }} · {{ dateTime(detail.ts) }}</p>
          </section>
          <section v-if="detail.reviewed_by && detail.status !== 'pending'">
            <h4 class="block-title">{{ t('aiReview.detail.review') }}</h4>
            <p>
              {{ detail.reviewed_by }}
              <template v-if="detail.resolved_at"> · {{ dateTime(detail.resolved_at) }}</template>
              <template v-if="detail.review_note"> · {{ detail.review_note }}</template>
            </p>
          </section>
        </template>
      </div>

      <template #footer>
        <button
          v-if="detail && needsSelfConfirm(detail)"
          class="btn btn--primary"
          type="button"
          :disabled="busy"
          @click="selfConfirm(detail.id)"
        >
          {{ t('aiReview.action.selfConfirm') }}
        </button>
        <template v-if="detail && detail.status === 'pending' && detail.submitted_by !== me && canSignItem(detail)">
          <button class="btn btn--primary" type="button" :disabled="busy" @click="askResolve(detail.id, 'approved')">
            {{ t('aiReview.action.approve') }}
          </button>
          <button class="btn btn--danger" type="button" :disabled="busy" @click="askResolve(detail.id, 'rejected')">
            {{ t('aiReview.action.reject') }}
          </button>
        </template>
        <button v-if="detail && canReopen(detail)" class="btn" type="button" :disabled="busy" @click="reopen(detail.id)">
          {{ t('aiReview.action.reopen') }}
        </button>
        <button class="btn" type="button" @click="detailOpen = false">{{ t('aiReview.action.close') }}</button>
      </template>
    </AppModal>

    <!-- ============ 签发 / 驳回确认（备注写入审计留痕） ============ -->
    <AppModal
      v-model="signOpen"
      :title="signDecision === 'approved' ? t('aiReview.sign.title.approve') : t('aiReview.sign.title.reject')"
      width="460px"
    >
      <p class="modal-note">{{ t('aiReview.sign.sub') }}</p>
      <label class="field block">
        <span class="label">{{ t('aiReview.sign.note') }}</span>
        <textarea v-model="signNote" class="textarea" rows="3" :placeholder="t('aiReview.sign.notePlaceholder')" />
      </label>
      <template #footer>
        <button class="btn" type="button" :disabled="busy" @click="signOpen = false">{{ t('aiReview.action.cancel') }}</button>
        <button
          class="btn"
          :class="signDecision === 'approved' ? 'btn--primary' : 'btn--danger'"
          type="button"
          :disabled="busy"
          @click="confirmResolve"
        >
          {{ t('aiReview.action.confirm') }}
        </button>
      </template>
    </AppModal>

    <!-- ============ 处方审核（通过 / 驳回，驳回意见必填） ============ -->
    <AppModal
      v-model="rxModalOpen"
      :title="rxModalMode === 'approve' ? t('aiReview.rx.modal.approveTitle') : t('aiReview.rx.modal.rejectTitle')"
      width="480px"
      :subtitle="rxTargetId"
    >
      <p class="modal-note">
        {{ rxModalMode === 'approve' ? t('aiReview.rx.modal.approveSub') : t('aiReview.rx.modal.rejectSub') }}
      </p>
      <label class="field block">
        <span class="label">
          {{ rxModalMode === 'reject' ? t('aiReview.rx.modal.opinionRequired') : t('aiReview.rx.modal.opinionOptional') }}
        </span>
        <textarea v-model="rxOpinion" class="textarea" rows="3" :placeholder="t('aiReview.rx.modal.opinionPlaceholder')" />
      </label>
      <template #footer>
        <button class="btn" type="button" :disabled="busy" @click="rxModalOpen = false">{{ t('aiReview.action.cancel') }}</button>
        <button
          class="btn"
          :class="rxModalMode === 'approve' ? 'btn--primary' : 'btn--danger'"
          type="button"
          :disabled="busy"
          @click="submitRx"
        >
          {{ t('aiReview.action.confirm') }}
        </button>
      </template>
    </AppModal>

    <!-- ============ 移出病例库（原因必填，软删除留痕） ============ -->
    <AppModal v-model="caseRemoveOpen" :title="t('aiReview.case.remove.title', { id: caseRemoveId })" width="460px">
      <p class="modal-note">{{ t('aiReview.case.remove.hint') }}</p>
      <label class="field block">
        <span class="label">{{ t('aiReview.case.remove.reason') }}</span>
        <textarea v-model="caseRemoveReason" class="textarea" rows="3" :placeholder="t('aiReview.case.remove.placeholder')" />
      </label>
      <template #footer>
        <button class="btn" type="button" :disabled="busy" @click="caseRemoveOpen = false">{{ t('aiReview.action.cancel') }}</button>
        <button class="btn btn--danger" type="button" :disabled="busy" @click="confirmCaseRemove">
          {{ t('aiReview.action.confirm') }}
        </button>
      </template>
    </AppModal>

    <!-- ============ 清空待核对（admin；确认词双保险） ============ -->
    <AppModal v-model="purgeOpen" :title="t('aiReview.purge.title')" width="480px">
      <p class="modal-note">{{ t('aiReview.purge.sub', { n: purgeN }) }}</p>
      <p class="modal-note">{{ t('aiReview.purge.wordHint') }} <code class="purge-word">{{ PURGE_WORD }}</code></p>
      <label class="field block">
        <span class="label">{{ t('aiReview.purge.word') }}</span>
        <input v-model="purgeWord" class="input" type="text" autocomplete="off" spellcheck="false" :placeholder="PURGE_WORD" />
      </label>
      <template #footer>
        <button class="btn" type="button" :disabled="busy" @click="purgeOpen = false">{{ t('aiReview.action.cancel') }}</button>
        <button
          class="btn btn--danger"
          type="button"
          :disabled="busy || purgeWord.trim() !== PURGE_WORD"
          @click="confirmPurge"
        >
          {{ t('aiReview.action.confirm') }}
        </button>
      </template>
    </AppModal>

    <!-- ============ 影像查看（仅 data:image/ 白名单） ============ -->
    <AppModal v-model="imageOpen" :title="t('aiReview.image.title')" width="640px">
      <img v-if="imageSrc" class="image-full" :src="imageSrc" :alt="t('aiReview.detail.images')" />
    </AppModal>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import AppIcon from '@/components/ui/AppIcon.vue'
import AppModal from '@/components/ui/AppModal.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import KpiCard from '@/components/ui/KpiCard.vue'
import LoadError from '@/components/ui/LoadError.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import StatusPill from '@/components/ui/StatusPill.vue'
import TableSkeleton from '@/components/ui/TableSkeleton.vue'
import ConsultInitiatedPanel from '@/components/domain/ai/ConsultInitiatedPanel.vue'
import {
  aiCaseArchive,
  aiCaseArchiveRemove,
  aiConsultInbox,
  aiConsultOpinion,
  aiMyPendingConfirm,
  aiPurgePending,
  aiReviewHistory,
  aiReviewItem,
  aiReviewPending,
  aiReviewReopen,
  aiReviewResolve,
  aiReviewSelfConfirm,
  aiRxPending,
  aiRxReview,
  aiRxReviewedByMe,
  type AiCaseArchive,
  type AiConsult,
  type AiPrescription,
  type AiReviewItem,
} from '@/api/ai'
import { errorText } from '@/api/http'
import { t } from '@/i18n'
import { session } from '@/stores/session'
import { toast } from '@/stores/toast'
import { dateTime } from '@/utils/format'
import { md, splitConsultQuestion } from '@/utils/markdown'

defineOptions({ name: 'AiReviewView' })

/** 每页条数（与 AI 中台列表口径一致：12/页）。 */
const PER = 12
/** 清空待核对的确认词由后端硬校验，界面只做双保险（不随界面语言变化）。 */
const PURGE_WORD = '清空'

type TabKey = 'pending' | 'history' | 'consults' | 'cases' | 'mine-consults'
type Decision = 'approved' | 'rejected'

/* ---------- 角色（控制台角色为大写常量，AI 中台用小写；统一转小写比较） ---------- */
const roles = computed(() => (session.user.value?.roles ?? []).map((r) => r.toLowerCase()))
const isAdmin = computed(() => roles.value.includes('admin'))
const isQc = computed(() => roles.value.includes('qc'))
const isPh = computed(() => roles.value.includes('pharmacist'))
/** 我的记录视角：医生与药师（qc/admin 是全局档案视角）。 */
const isMine = computed(() => !isQc.value && !isAdmin.value)
/** 开药待审队列：药剂科 + 管理员（质控科不管处方，后端对 qc 直接 403）。 */
const canRx = computed(() => isAdmin.value || isPh.value)
/** 非药物类高危结论的签字权：质控员 / 管理员。 */
const canSign = computed(() => isQc.value || isAdmin.value)

const headSub = computed(() => {
  if (isQc.value || isAdmin.value) return t('aiReview.sub.qc')
  if (isPh.value) return t('aiReview.sub.ph')
  return t('aiReview.sub.doctor')
})

/** 药师视角的待办就是「开药待审」，不叫「我的待确认」（与旧界面口径一致）。 */
const pendingTitle = computed(() =>
  isPh.value || !isMine.value ? t('aiReview.tab.pending') : t('aiReview.tab.myPending'),
)

const tabs = computed<{ key: TabKey; label: string; count?: number }[]>(() => {
  const list: { key: TabKey; label: string; count?: number }[] = [
    {
      key: 'pending',
      label: pendingTitle.value,
      count: pendCount.value,
    },
    {
      key: 'history',
      label: isMine.value ? t('aiReview.tab.myHistory') : t('aiReview.tab.history'),
      count: histCount.value,
    },
  ]
  if (isMine.value) list.push({ key: 'consults', label: t('aiReview.tab.consults'), count: consultPending.value })
  else list.push({ key: 'cases', label: t('aiReview.tab.cases') })
  // 第 200 批：发起者视角（所有角色都可能发起跨科室会诊），与「会诊协助」形成闭环
  list.push({ key: 'mine-consults', label: t('aiReview.tab.mineConsults') })
  return list
})

/* ---------- 通用状态 ---------- */
const route = useRoute()
/** 支持从「深度会诊」结果卡与「AI 病历质控」直达对应 tab。 */
function initialTab(): TabKey {
  const value = String(route.query.tab || '')
  if (value === 'mine-consults') return 'mine-consults'
  if (value === 'cases' && !isMine.value) return 'cases'
  return 'pending'
}
const tab = ref<TabKey>(initialTab())
watch(
  () => route.query.tab,
  (value) => {
    if (value === 'mine-consults') tab.value = 'mine-consults'
    if (value === 'cases' && !isMine.value) tab.value = 'cases'
  },
)
const loading = ref(false)
const busy = ref(false)
const loadErr = ref('')
const me = ref('')
const keyword = ref('')
let loadSeq = 0

/* ---------- 待处理 / 我的待确认 ---------- */
const pendRows = ref<AiReviewItem[]>([])
const pendTotal = ref(0)
const pendPage = ref(0)
const myRows = ref<AiReviewItem[]>([])

function haystack(row: AiReviewItem): string {
  return [row.agent, row.question, row.submitted_by, row.risk_reason].join(' ').toLowerCase()
}

const myFiltered = computed(() => {
  const q = keyword.value.trim().toLowerCase()
  if (!q) return myRows.value
  return myRows.value.filter((row) => haystack(row).includes(q))
})

const pendCount = computed(() => (isMine.value ? myFiltered.value.length : pendTotal.value))
const pendPages = computed(() => Math.max(1, Math.ceil(pendCount.value / PER)))
const pendView = computed<AiReviewItem[]>(() => {
  if (!isMine.value) return pendRows.value
  const page = Math.min(pendPage.value, pendPages.value - 1)
  return myFiltered.value.slice(page * PER, page * PER + PER)
})
const pendingMeta = computed(() =>
  isMine.value ? t('aiReview.kpi.pendingMetaMine') : t('aiReview.kpi.pendingMeta'),
)

/* ---------- 历史 ---------- */
const histRows = ref<AiReviewItem[]>([])
const histTotal = ref(0)
const histPage = ref(0)
const rxHistory = ref<AiPrescription[]>([])

interface RxHistoryRow extends AiPrescription {
  _rx: true
}
type HistRow = AiReviewItem | RxHistoryRow

function isRxRow(row: HistRow): row is RxHistoryRow {
  return (row as RxHistoryRow)._rx === true
}

/** 药师把「处方审核轨」并入我的历史（与旧前端同语义，本地合并后本地分页）。 */
const histMerged = computed<HistRow[]>(() => {
  if (!isPh.value) return histRows.value
  const q = keyword.value.trim().toLowerCase()
  const merged: HistRow[] = [
    ...rxHistory.value.map((p) => ({ ...p, _rx: true as const })),
    ...histRows.value,
  ]
  if (!q) return merged
  return merged.filter((row) => {
    const text = isRxRow(row)
      ? [row.id, row.case_text, row.doctor, (row.drugs ?? []).map((d) => d.name).join(' ')].join(' ')
      : [row.agent, row.question, row.submitted_by, row.risk_reason].join(' ')
    return text.toLowerCase().includes(q)
  })
})
const histCount = computed(() => (isPh.value ? histMerged.value.length : histTotal.value))
const histPages = computed(() => Math.max(1, Math.ceil(histCount.value / PER)))
const histPageRows = computed<HistRow[]>(() => {
  if (!isPh.value) return histRows.value
  const page = Math.min(histPage.value, histPages.value - 1)
  return histMerged.value.slice(page * PER, page * PER + PER)
})
const histRxRows = computed(() => histPageRows.value.filter(isRxRow))
const histReviewRows = computed(() => histPageRows.value.filter((row) => !isRxRow(row)) as AiReviewItem[])

/* ---------- 开药待审 ---------- */
const rxRows = ref<AiPrescription[]>([])
const rxTotal = ref(0)
const rxPage = ref(0)
const rxKeyword = ref('')
const rxLoading = ref(false)

/* ---------- 会诊协助 ---------- */
const consultRows = ref<AiConsult[]>([])
const consultTotal = ref(0)
const consultPending = ref(0)
const consultDept = ref('')
const consultMe = ref('')
const consultLoading = ref(false)
const consultErr = ref('')
const consultKeyword = ref('')
const opDrafts = reactive<Record<string, string>>({})

/* ---------- 病例库 ---------- */
const caseRows = ref<AiCaseArchive[]>([])
const caseStats = ref<{ active?: number; removed?: number; total?: number }>({})
const caseDepts = ref<string[]>([])
const caseDept = ref('')
const caseStatus = ref('')
const caseFrom = ref('')
const caseTo = ref('')
const caseLoaded = ref(false)
const caseLoading = ref(false)
const caseErr = ref('')

/* ---------- 加载（统一序号守卫：并发只应用最新一次响应） ---------- */
async function loadPending(seq = loadSeq) {
  const kw = keyword.value.trim()
  const res = await aiReviewPending({
    limit: PER,
    offset: pendPage.value * PER,
    compact: 1,
    ...(kw ? { q: kw } : {}),
  })
  if (seq !== loadSeq) return
  pendRows.value = res.pending ?? []
  pendTotal.value = Number(res.total ?? pendRows.value.length)
  if (res.me) me.value = res.me
  // 越界纠偏（签发/驳回后总数变小）：回到最后一页，由 watch 触发重拉
  if (pendRows.value.length === 0 && pendTotal.value > 0 && pendPage.value > 0) {
    pendPage.value = Math.max(0, Math.ceil(pendTotal.value / PER) - 1)
  }
}

async function loadHistory(seq = loadSeq) {
  if (isPh.value) {
    // 药师需与处方审核轨合并，保持全量拉取 + 本地过滤/分页
    const res = await aiReviewHistory()
    if (seq !== loadSeq) return
    histRows.value = (res.items ?? []).filter(
      (i) =>
        (i.submitted_by === me.value || i.reviewed_by === me.value) &&
        i.status !== 'pending' &&
        i.agent !== 'drug',
    )
    histTotal.value = histRows.value.length
    return
  }
  const kw = keyword.value.trim()
  const res = await aiReviewHistory({
    limit: PER,
    offset: histPage.value * PER,
    compact: 1,
    hide_pending: 1,
    hide_drug: 1,
    ...(kw ? { q: kw } : {}),
  })
  if (seq !== loadSeq) return
  histRows.value = res.items ?? []
  histTotal.value = Number(res.total ?? histRows.value.length)
  if (histRows.value.length === 0 && histTotal.value > 0 && histPage.value > 0) {
    histPage.value = Math.max(0, Math.ceil(histTotal.value / PER) - 1)
  }
}

async function loadRx(seq = loadSeq) {
  const kw = rxKeyword.value.trim()
  rxLoading.value = true
  try {
    const res = await aiRxPending({
      limit: PER,
      offset: rxPage.value * PER,
      ...(kw ? { q: kw } : {}),
    })
    if (seq !== loadSeq) return
    rxRows.value = res.items ?? []
    rxTotal.value = Number(res.total ?? rxRows.value.length)
    if (rxRows.value.length === 0 && rxTotal.value > 0 && rxPage.value > 0) {
      rxPage.value = Math.max(0, Math.ceil(rxTotal.value / PER) - 1)
      await loadRx(seq)
    }
  } finally {
    if (seq === loadSeq) rxLoading.value = false
  }
}

async function loadRxHistory(seq = loadSeq) {
  const res = await aiRxReviewedByMe()
  if (seq !== loadSeq) return
  rxHistory.value = res.items ?? []
}

async function loadConsults(seq = loadSeq) {
  consultLoading.value = true
  consultErr.value = ''
  try {
    const kw = consultKeyword.value.trim()
    const res = await aiConsultInbox(kw ? { q: kw } : {})
    if (seq !== loadSeq) return
    consultRows.value = res.items ?? []
    consultTotal.value = Number(res.total ?? consultRows.value.length)
    consultDept.value = res.dept || ''
    consultMe.value = res.me || ''
    // 过滤中不覆盖角标（否则"搜 1 条"会把"整箱待我填"的口径改小）
    if (!kw) consultPending.value = Number(res.pendingForMe ?? 0)
  } catch (e) {
    if (seq !== loadSeq) return
    consultErr.value = errorText(e)
  } finally {
    if (seq === loadSeq) consultLoading.value = false
  }
}

async function loadCase(seq = loadSeq) {
  caseLoading.value = true
  caseErr.value = ''
  const filtered = Boolean(caseDept.value || caseStatus.value || caseFrom.value || caseTo.value)
  try {
    const res = await aiCaseArchive({
      ...(caseDept.value ? { dept: caseDept.value } : {}),
      ...(caseStatus.value ? { status: caseStatus.value } : {}),
      ...(caseFrom.value ? { date_from: caseFrom.value } : {}),
      ...(caseTo.value ? { date_to: caseTo.value } : {}),
    })
    if (seq !== loadSeq) return
    caseRows.value = res.items ?? []
    caseStats.value = res.stats ?? {}
    caseLoaded.value = true
    if (!filtered) {
      caseDepts.value = Array.from(
        new Set(caseRows.value.map((i) => i.dept).filter((d): d is string => Boolean(d))),
      )
    }
  } catch (e) {
    if (seq !== loadSeq) return
    caseErr.value = errorText(e)
  } finally {
    if (seq === loadSeq) caseLoading.value = false
  }
}

async function reloadAll() {
  const seq = ++loadSeq
  loading.value = true
  loadErr.value = ''
  try {
    if (isMine.value) {
      const mine = await aiMyPendingConfirm()
      if (seq !== loadSeq) return
      myRows.value = mine.items ?? []
      me.value = mine.me || ''
      try {
        await loadHistory(seq)
      } catch {
        // 「我的历史」失败不阻塞待确认列表（与旧前端同语义）
      }
      if (isPh.value) await Promise.allSettled([loadRx(seq), loadRxHistory(seq)])
      await loadConsults(seq)
    } else {
      await loadPending(seq)
      if (seq !== loadSeq) return
      try {
        await loadHistory(seq)
      } catch {
        // 历史失败静默，待处理列表照常可用
      }
      if (isAdmin.value) await Promise.allSettled([loadRx(seq)])
      if (caseLoaded.value) await loadCase(seq)
    }
  } catch (e) {
    if (seq !== loadSeq) return
    loadErr.value = errorText(e)
  } finally {
    if (seq === loadSeq) loading.value = false
  }
}

function switchTab(key: TabKey) {
  tab.value = key
  if (key === 'consults') void loadConsults()
  if (key === 'cases') void loadCase()
}

function rxGo(delta: number) {
  const pages = Math.max(1, Math.ceil(rxTotal.value / PER))
  const next = Math.min(pages - 1, Math.max(0, rxPage.value + delta))
  if (next === rxPage.value) return
  rxPage.value = next
  void loadRx()
}

watch(pendPage, () => {
  if (!isMine.value) void loadPending()
})
watch(histPage, () => {
  if (!isPh.value) void loadHistory()
})

/** 关键字检索：300ms 防抖 + 回第 1 页（服务端过滤，分页不会漏配）。 */
let keywordTimer = 0
watch(keyword, () => {
  window.clearTimeout(keywordTimer)
  keywordTimer = window.setTimeout(() => {
    if (tab.value === 'pending' && !isMine.value) {
      if (pendPage.value !== 0) pendPage.value = 0
      else void loadPending()
    }
    if (tab.value === 'history' && !isPh.value) {
      if (histPage.value !== 0) histPage.value = 0
      else void loadHistory()
    }
  }, 300)
})

let rxKeywordTimer = 0
watch(rxKeyword, () => {
  window.clearTimeout(rxKeywordTimer)
  rxKeywordTimer = window.setTimeout(() => {
    rxPage.value = 0
    void loadRx()
  }, 300)
})

let consultTimer = 0
watch(consultKeyword, () => {
  window.clearTimeout(consultTimer)
  consultTimer = window.setTimeout(() => void loadConsults(), 300)
})

/* ---------- 详情 ---------- */
const detailOpen = ref(false)
const detail = ref<AiReviewItem | null>(null)
const detailLoading = ref(false)
let detailSeq = 0

async function openDetail(row: AiReviewItem, kind: 'pending' | 'history') {
  detail.value = row
  detailOpen.value = true
  // 列表是 compact（无 answer/images）：首次打开按单条接口取全文与影像
  if (row.answer !== undefined) return
  const seq = ++detailSeq
  detailLoading.value = true
  try {
    const res = await aiReviewItem(row.id, kind)
    if (seq !== detailSeq) return
    if (detail.value && detail.value.id === row.id && res.item) {
      detail.value = { ...row, ...res.item }
    }
  } catch (e) {
    toast.error(t('aiReview.toast.detailFail'), errorText(e))
  } finally {
    if (seq === detailSeq) detailLoading.value = false
  }
}

/* ---------- 签发 / 驳回 / 翻案 / 知情确认 ---------- */
const signOpen = ref(false)
const signId = ref('')
const signDecision = ref<Decision>('approved')
const signNote = ref('')

function askResolve(id: string, decision: Decision) {
  signId.value = id
  signDecision.value = decision
  signNote.value = ''
  signOpen.value = true
}

async function confirmResolve() {
  busy.value = true
  try {
    await aiReviewResolve(signId.value, signDecision.value, signNote.value.trim())
    toast.ok(
      signDecision.value === 'approved' ? t('aiReview.toast.approved') : t('aiReview.toast.rejected'),
    )
    signOpen.value = false
    detailOpen.value = false
    await reloadAll()
  } catch (e) {
    toast.error(t('aiReview.toast.opFail'), errorText(e))
    // 状态已变（400/409）时同步刷新，防僵尸按钮
    await reloadAll()
  } finally {
    busy.value = false
  }
}

async function reopen(id: string) {
  busy.value = true
  try {
    await aiReviewReopen(id)
    toast.ok(t('aiReview.toast.reopened'))
    detailOpen.value = false
    await reloadAll()
  } catch (e) {
    toast.error(t('aiReview.toast.opFail'), errorText(e))
    await reloadAll()
  } finally {
    busy.value = false
  }
}

async function selfConfirm(id: string) {
  busy.value = true
  try {
    await aiReviewSelfConfirm(id)
    toast.ok(t('aiReview.toast.confirmed'))
    detailOpen.value = false
    await reloadAll()
  } catch (e) {
    toast.error(t('aiReview.toast.opFail'), errorText(e))
    await reloadAll()
  } finally {
    busy.value = false
  }
}

/* ---------- 处方审核 ---------- */
const rxModalOpen = ref(false)
const rxModalMode = ref<'approve' | 'reject'>('approve')
const rxTargetId = ref('')
const rxOpinion = ref('')

function openRxModal(p: AiPrescription, mode: 'approve' | 'reject') {
  rxTargetId.value = p.id
  rxModalMode.value = mode
  rxOpinion.value = ''
  rxModalOpen.value = true
}

async function submitRx() {
  const opinion = rxOpinion.value.trim()
  if (rxModalMode.value === 'reject' && !opinion) {
    toast.warn(t('aiReview.rx.modal.opinionEmpty'))
    return
  }
  busy.value = true
  try {
    await aiRxReview(rxTargetId.value, rxModalMode.value, opinion)
    toast.ok(
      rxModalMode.value === 'approve' ? t('aiReview.toast.rxApproved') : t('aiReview.toast.rxRejected'),
    )
    rxModalOpen.value = false
    await reloadAll()
  } catch (e) {
    toast.error(t('aiReview.toast.opFail'), errorText(e))
    await reloadAll()
  } finally {
    busy.value = false
  }
}

/* ---------- 会诊协助 ---------- */
function splitConsult(question?: string) {
  return splitConsultQuestion(question)
}

function consultMine(c: AiConsult) {
  return (c.opinions ?? []).some((o) => o.doctor === consultMe.value)
}

function consultThumbs(c: AiConsult) {
  return (c.images ?? []).filter((u) => String(u).startsWith('data:image/'))
}

async function submitOpinion(c: AiConsult) {
  const content = String(opDrafts[c.id] ?? '').trim()
  if (!content) {
    toast.warn(t('aiReview.consult.opinion.empty'))
    return
  }
  busy.value = true
  try {
    await aiConsultOpinion(c.id, content)
    toast.ok(t('aiReview.toast.opinionOk'))
    delete opDrafts[c.id]
    await loadConsults()
  } catch (e) {
    toast.error(t('aiReview.toast.opFail'), errorText(e))
  } finally {
    busy.value = false
  }
}

/* ---------- 病例库 ---------- */
const caseRemoveOpen = ref(false)
const caseRemoveId = ref('')
const caseRemoveReason = ref('')

function openCaseRemove(id: string) {
  caseRemoveId.value = id
  caseRemoveReason.value = ''
  caseRemoveOpen.value = true
}

async function confirmCaseRemove() {
  const reason = caseRemoveReason.value.trim()
  if (!reason) {
    toast.warn(t('aiReview.case.remove.required'))
    return
  }
  busy.value = true
  try {
    await aiCaseArchiveRemove(caseRemoveId.value, reason)
    toast.ok(t('aiReview.toast.removed'))
    caseRemoveOpen.value = false
    await loadCase()
  } catch (e) {
    toast.error(t('aiReview.toast.opFail'), errorText(e))
  } finally {
    busy.value = false
  }
}

function labLine(labs: Record<string, unknown>) {
  return Object.entries(labs)
    .map(([k, v]) => `${k}=${String(v)}`)
    .join('；')
}

/* ---------- 清空待核对（admin） ---------- */
const purgeOpen = ref(false)
const purgeN = ref(0)
const purgeWord = ref('')

async function openPurge() {
  try {
    const res = await aiReviewPending({ limit: 0 })
    purgeN.value = Number(res.total ?? 0)
    purgeWord.value = ''
    purgeOpen.value = true
  } catch (e) {
    toast.error(t('aiReview.toast.loadFail'), errorText(e))
  }
}

async function confirmPurge() {
  if (purgeWord.value.trim() !== PURGE_WORD) return
  busy.value = true
  try {
    const res = await aiPurgePending()
    toast.ok(t('aiReview.toast.purged', { n: res.purged ?? 0 }))
    purgeOpen.value = false
    await reloadAll()
  } catch (e) {
    toast.error(t('aiReview.toast.opFail'), errorText(e))
  } finally {
    busy.value = false
  }
}

/* ---------- 展示辅助 ---------- */
const imageOpen = ref(false)
const imageSrc = ref('')

function showImage(src: string) {
  if (!String(src).startsWith('data:image/')) return
  imageSrc.value = src
  imageOpen.value = true
}

function imagesOf(row: AiReviewItem) {
  return (row.images ?? []).filter((u) => String(u).startsWith('data:image/'))
}

function preview(text?: string | null) {
  const clean = String(text ?? '').replace(/\s+/g, ' ').trim()
  if (!clean) return '—'
  return clean.length > 80 ? `${clean.slice(0, 80)}…` : clean
}

function confMeta(confidence?: number) {
  const v = confidence ?? 0
  if (v >= 0.75) return { label: t('aiReview.conf.high'), tone: 'ok' }
  if (v >= 0.6) return { label: t('aiReview.conf.medium'), tone: 'accent' }
  return { label: t('aiReview.conf.low'), tone: 'warn' }
}

/** 双控自动签发判定（reviewed_by 含「AI·阈值自动 / AI·留痕模式」）。 */
function isAuto(row: AiReviewItem) {
  return row.status === 'approved' && /AI·(阈值自动|留痕模式)/.test(String(row.reviewed_by ?? ''))
}

function canSignItem(row: AiReviewItem) {
  if (!row) return false
  // 药物核对项：药师 / 管理员（质控科只管病例，后端已同口径收权）
  if (row.agent === 'drug') return isAdmin.value || isPh.value
  return canSign.value
}

function canReopen(row: AiReviewItem) {
  return isAuto(row) && canSign.value && row.submitted_by !== me.value
}

function needsSelfConfirm(row: AiReviewItem) {
  return Boolean(row.self_confirm_required && !row.confirmed_by_self)
}

function signDeniedHint(row: AiReviewItem) {
  if (row.agent === 'drug') return t('aiReview.denied.drug')
  return t('aiReview.denied.qc')
}

onMounted(reloadAll)
</script>

<style scoped>
.seg-count {
  margin-left: 6px;
  font-size: var(--fs-small);
  color: var(--faint);
  font-variant-numeric: tabular-nums;
}

.small {
  font-size: var(--fs-small);
}

/* ---------- 列表（处方 / 会诊 / 病例共用卡片骨架） ---------- */
.card-list {
  display: flex;
  flex-direction: column;
  gap: var(--s3);
  padding: var(--card-pad);
}

.rx-list {
  display: flex;
  flex-direction: column;
  gap: var(--s3);
  padding: var(--card-pad);
}

.block-card,
.rx-card {
  display: flex;
  flex-direction: column;
  gap: var(--s2);
  padding: var(--s4);
  border: 1px solid var(--border);
  border-radius: var(--r-md);
  background: var(--surface);
  min-width: 0;
}

.rx-head {
  display: flex;
  align-items: center;
  gap: var(--s2);
  flex-wrap: wrap;
}

.rx-case,
.case-text {
  margin: 0;
  font-size: var(--fs-body);
  line-height: 1.6;
  color: var(--ink-2);
  white-space: pre-wrap;
  word-break: break-word;
}

.rx-drugs {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.rx-drugs li {
  display: flex;
  align-items: center;
  gap: var(--s2);
  flex-wrap: wrap;
  font-size: var(--fs-body);
}

.rx-drug-name {
  font-weight: 500;
  color: var(--ink);
}

/* 字典外药：颜色 + 「字典外」徽标双重标识（颜色不是唯一信息） */
.rx-drug-name.is-ood {
  color: var(--negative);
}

.rx-reason {
  margin: 0;
  padding-left: var(--s3);
  border-left: 3px solid var(--negative);
  font-size: var(--fs-small);
  color: var(--negative-ink);
}

.tag-row {
  display: flex;
  align-items: center;
  gap: var(--s2);
  flex-wrap: wrap;
  font-size: var(--fs-small);
}

.block-title {
  margin: var(--s2) 0 0;
  font-size: var(--fs-13);
  font-weight: 600;
  color: var(--text);
}

.consult-done {
  margin-top: var(--s2);
  font-size: var(--fs-small);
  color: var(--positive-ink);
}

.consult-form {
  display: flex;
  flex-direction: column;
  gap: var(--s2);
  margin-top: var(--s2);
}

.case-details {
  font-size: var(--fs-small);
  color: var(--muted);
}

.case-details summary {
  cursor: pointer;
}

.record-pre {
  margin: var(--s2) 0 0;
  padding: var(--s3);
  background: var(--sunken);
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  font-family: var(--font-mono);
  font-size: 11.5px;
  line-height: 1.55;
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 320px;
  overflow: auto;
}

/* ---------- 表格辅助 ---------- */
.link-btn {
  padding: 0;
  border: 0;
  background: none;
  color: var(--accent);
  font-size: var(--fs-body);
  font-weight: 500;
  text-align: left;
  cursor: pointer;
  line-height: 1.5;
}

.link-btn:hover {
  text-decoration: underline;
}

.preview {
  margin: 4px 0 0;
  font-size: var(--fs-small);
  line-height: 1.55;
  color: var(--muted);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.row-actions {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
  margin-top: 6px;
}

.col-action {
  min-width: 190px;
}

/* ---------- 分页 ---------- */
.pager {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: var(--s2);
  padding: var(--s3) var(--card-pad);
  border-top: 1px solid var(--border);
}

.pager-info {
  font-size: var(--fs-small);
  color: var(--muted);
  font-variant-numeric: tabular-nums;
}

/* ---------- 详情 / 弹窗 ---------- */
.detail {
  display: flex;
  flex-direction: column;
  gap: var(--s4);
}

.detail-bar {
  display: flex;
  align-items: center;
  gap: var(--s2);
  flex-wrap: wrap;
  padding-bottom: var(--s3);
  border-bottom: 1px solid var(--line);
}

.md-body {
  font-size: var(--fs-body);
  line-height: 1.7;
  color: var(--ink-2);
  word-break: break-word;
}

.thumb-row {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.thumb {
  width: 56px;
  height: 56px;
  object-fit: cover;
  border: 1px solid var(--line);
  border-radius: 6px;
  cursor: zoom-in;
}

.image-full {
  max-width: 100%;
  display: block;
  margin: 0 auto;
}

.modal-note {
  margin: 0 0 var(--s3);
  font-size: var(--fs-small);
  line-height: 1.6;
  color: var(--muted);
}

.purge-word {
  padding: 1px 6px;
  border-radius: 4px;
  background: var(--sunken);
  font-family: var(--font-mono);
  color: var(--text);
}

.field.block {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

@media (max-width: 900px) {
  .col-action {
    min-width: 0;
  }
}
</style>
