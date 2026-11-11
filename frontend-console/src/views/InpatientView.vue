<template>
  <div class="page">
    <PageHeader
      title="住院工作台"
      :sub="`病区床位与在院患者 · 入出院 / 分床转床 / 预交金 / 欠费预警 · 当前角色 ${role}`"
    >
      <template #actions>
        <button class="btn" type="button" :disabled="loading" @click="reloadAll">
          <AppIcon name="refresh" :size="15" /> 刷新
        </button>
      </template>
    </PageHeader>

    <LoadError v-if="loadErr" :message="loadErr" :retrying="loading" @retry="reloadAll" />

    <div class="kpi-strip">
      <KpiCard label="在院患者" :value="activeCount" unit="人" :meta="`含待入科 ${pendingAdmitCount} 人`" />
      <KpiCard
        label="空闲床位"
        :value="freeBeds.length"
        unit="张"
        :meta="`病区床位 ${beds.length} 张`"
        :tone="freeBeds.length === 0 && beds.length > 0 ? 'warn' : 'default'"
      />
      <KpiCard
        label="待分床"
        :value="unassignedCount"
        unit="人"
        :meta="unassignedCount > 0 ? '未分床患者无法按床护理与计费核对' : '全部患者已分床'"
        :tone="unassignedCount > 0 ? 'warn' : 'default'"
      />
      <KpiCard
        label="欠费预警"
        :value="alerts.length"
        unit="人"
        :meta="alerts.length > 0 ? '预交金不足以覆盖未结与待计费用' : '本日无欠费缺口'"
        :tone="alerts.length > 0 ? 'danger' : 'default'"
      />
    </div>

    <div class="card ward-bar">
      <label class="field ward-pick">
        <span class="label">病区</span>
        <select v-model="selectedWardId" class="select" :disabled="wards.length === 0" @change="reloadWard">
          <option value="" disabled>选择病区</option>
          <option v-for="w in wards" :key="w.id" :value="w.id">{{ w.name }}（{{ w.code }}）</option>
        </select>
      </label>
      <span class="hint">{{ deptName(currentWard?.deptId) }}</span>
      <span class="spacer" />
      <button
        v-if="selectedWardId"
        class="btn btn--sm"
        type="button"
        :disabled="readinessLoading"
        @click="openReadiness"
      >
        <AppIcon name="warning" :size="13" /> 出院预检
      </button>
      <button v-if="canManageWard && selectedWardId" class="btn btn--sm" type="button" @click="openBeds">
        <AppIcon name="plus" :size="13" /> 批量建床
      </button>
      <button
        v-if="canAdmit && selectedWardId"
        class="btn btn--sm btn--primary"
        type="button"
        :disabled="busy"
        @click="openAdmit()"
      >
        <AppIcon name="plus" :size="13" /> 入院登记
      </button>
    </div>

    <div v-if="!wards.length && !loading" class="card">
      <EmptyState
        title="当前账号没有可管理的病区"
        desc="住院数据按科室范围隔离。请让管理员在账号上绑定临床科室，或为该科室建立病区与床位。"
      />
    </div>

    <template v-else-if="selectedWardId">
      <div v-if="alerts.length" class="card alert-card">
        <div class="alert-head">
          <AppIcon name="warning" :size="15" />
          <strong>欠费预警 {{ alerts.length }} 人</strong>
          <span class="muted">按缺口倒序，仅显示缺口大于 0 的在院患者</span>
        </div>
        <div class="alert-list">
          <span v-for="a in alerts.slice(0, 4)" :key="a.admissionId" class="alert-chip">
            <span class="mono">{{ a.bedNo || '未分床' }}</span>
            <span class="mono">{{ a.patientNo }}</span>
            <span class="is-danger num">缺 {{ money(a.shortfall) }}</span>
          </span>
        </div>
      </div>

      <div class="ward-grid">
        <section class="card bed-board">
          <div class="card-head">
            <span class="card-title">床位看板</span>
            <span class="badge badge--neutral num">{{ freeBeds.length }} 空 / {{ beds.length }} 总</span>
            <span class="spacer" />
            <span class="hint">点击空床直接入院；点击在床患者可转床</span>
          </div>
          <div v-if="beds.length === 0" class="hint pad">
            该病区还没有床位。拥有 inpatient:ward:manage 权限的账号可批量建床后再入院。
          </div>
          <div v-else class="bed-grid">
            <button
              v-for="b in beds"
              :key="b.id"
              class="bed"
              :class="bedClass(b)"
              type="button"
              @click="onBedClick(b)"
            >
              <span class="bed-no mono">{{ b.bedNo }}</span>
              <span class="bed-state">{{ bedLabel(b) }}</span>
              <span v-if="patientOfBed(b.id)" class="bed-patient mono">
                {{ patientOfBed(b.id)?.patientNo }}
              </span>
            </button>
          </div>
        </section>

        <section class="card">
          <div class="card-head">
            <span class="card-title">在院患者</span>
            <span class="badge badge--neutral num">{{ admissions.length }}</span>
            <span class="spacer" />
            <span v-if="!canFinance" class="hint">费用与欠费需 inpatient:finance:read</span>
          </div>

          <div v-if="loading" class="loading">
            <div v-for="i in 4" :key="i" class="skeleton" style="height: 40px" />
          </div>

          <EmptyState
            v-else-if="admissions.length === 0"
            title="该病区暂无在院患者"
            desc="通过右上角“入院登记”办理入科；已有登记但未分床的患者会显示为“待入科”。"
          />

          <div v-else class="table-wrap">
            <table class="table table--compact table--stack">
              <thead>
                <tr>
                  <th>住院号</th>
                  <th>患者</th>
                  <th>床位</th>
                  <th>主治</th>
                  <th>入科时间</th>
                  <th>状态</th>
                  <th style="width: 236px">操作</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="a in admissions" :key="a.id">
                  <td class="mono" data-primary>{{ a.admissionNo }}</td>
                  <td class="mono" data-label="患者">{{ a.patientNo }}</td>
                  <td data-label="床位">
                    <span v-if="a.bedNo" class="mono">{{ a.bedNo }}</span>
                    <span v-else class="badge badge--warn">未分床</span>
                  </td>
                  <td data-label="主治">{{ a.attendingDoctor }}</td>
                  <td data-label="入科时间">{{ dateTime(a.admittedAt) }}</td>
                  <td data-label="状态"><StatusPill :value="a.status" /></td>
                  <td data-label="操作">
                    <div class="row-actions">
                      <button
                        v-if="canAssignBed"
                        class="btn btn--sm"
                        type="button"
                        :disabled="busy"
                        @click="openAssign(a)"
                      >
                        {{ a.bedId ? '转床' : '分床' }}
                      </button>
                      <button
                        v-if="canDeposit"
                        class="btn btn--sm"
                        type="button"
                        :disabled="busy"
                        @click="openMoney(a)"
                      >
                        预交金
                      </button>
                      <button
                        v-if="canReadPatient"
                        class="btn btn--sm"
                        type="button"
                        :disabled="printingWristId === a.id"
                        @click="printWristband(a)"
                      >
                        {{ printingWristId === a.id ? '读取…' : '腕带' }}
                      </button>
                      <button
                        v-if="canFinance"
                        class="btn btn--sm"
                        type="button"
                        :disabled="busy"
                        @click="openFinance(a)"
                      >
                        费用
                      </button>
                      <button
                        v-if="canDischarge && (a.status === 'IN_HOSPITAL' || a.status === 'ADMITTED')"
                        class="btn btn--sm btn--danger"
                        type="button"
                        :disabled="busy"
                        @click="openDischarge(a)"
                      >
                        出院
                      </button>
                    </div>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>
      </div>
    </template>

    <!-- 入院登记 -->
    <AppModal v-model="admitOpen" title="入院登记" subtitle="登记后生成住院就诊；可先登记后分床" width="620px">
      <div class="stack">
        <div class="search-row">
          <input
            v-model.trim="patientKeyword"
            class="input"
            placeholder="姓名或病案号，回车检索"
            @keydown.enter="searchPatients"
          />
          <button class="btn" type="button" :disabled="patientSearching" @click="searchPatients">检索</button>
        </div>
        <div v-if="patientResults.length" class="patient-results">
          <button
            v-for="p in patientResults"
            :key="p.id"
            class="patient-row"
            type="button"
            @click="choosePatient(p)"
          >
            <span class="is-strong">{{ p.name }}</span>
            <span class="muted">{{ genderLabel(p.gender) }} · {{ ageOf(p.birthDate) }}</span>
            <span class="mono muted">{{ p.patientNo }}</span>
            <span class="spacer" />
            <AppIcon name="chevron-right" :size="14" class="muted" />
          </button>
        </div>
        <div v-if="admitPatient" class="picked-patient">
          <span class="is-strong">{{ admitPatient.name }}</span>
          <span class="mono muted">{{ admitPatient.patientNo }}</span>
          <span class="muted">{{ genderLabel(admitPatient.gender) }} · {{ ageOf(admitPatient.birthDate) }}</span>
        </div>
        <div class="form-grid">
          <label class="field">
            <span class="label">病区</span>
            <input class="input" :value="currentWard?.name ?? '—'" disabled />
          </label>
          <label class="field">
            <span class="label">床位</span>
            <select v-model="admitForm.bedId" class="select">
              <option value="">先不分床</option>
              <option v-for="b in freeBeds" :key="b.id" :value="b.id">{{ b.bedNo }}</option>
            </select>
          </label>
          <label class="field">
            <span class="label">主治医师工号 <span class="req">*</span></span>
            <input v-model.trim="admitForm.attendingDoctor" class="input" placeholder="如 D0001" />
          </label>
        </div>
        <p class="hint">入院后就诊状态为接诊中，可直接在医嘱工作台开立长期医嘱并按日计费。</p>
      </div>
      <template #footer>
        <button class="btn" type="button" @click="admitOpen = false">取消</button>
        <button
          class="btn btn--primary"
          type="button"
          :disabled="busy || !admitPatient || !admitForm.attendingDoctor"
          @click="submitAdmit"
        >
          确认入院
        </button>
      </template>
    </AppModal>

    <!-- 分床 / 转床 -->
    <AppModal
      v-model="assignOpen"
      :title="assignTarget?.bedId ? '转床' : '分床'"
      :subtitle="assignTarget ? `${assignTarget.admissionNo} · ${assignTarget.patientNo}` : ''"
      width="480px"
    >
      <div class="stack">
        <label class="field">
          <span class="label">目标床位 <span class="req">*</span></span>
          <select v-model="assignBedId" class="select">
            <option value="" disabled>选择空床</option>
            <option v-for="b in freeBeds" :key="b.id" :value="b.id">{{ b.bedNo }}</option>
          </select>
        </label>
        <label class="field">
          <span class="label">原因</span>
          <input v-model.trim="assignReason" class="input" placeholder="如：病情需要靠近护士站" />
        </label>
        <p class="hint">转床会在同一事务内释放原床位；床位不属于本病区时服务端会拒绝。</p>
      </div>
      <template #footer>
        <button class="btn" type="button" @click="assignOpen = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="busy || !assignBedId" @click="submitAssign">
          确认
        </button>
      </template>
    </AppModal>

    <!-- 预交金 -->
    <AppModal
      v-model="moneyOpen"
      title="预交金"
      :subtitle="moneyTarget ? `${moneyTarget.admissionNo} · 余额 ${money(deposit?.balance ?? 0)}` : ''"
      width="560px"
    >
      <div class="stack">
        <div class="seg" role="tablist" aria-label="预交金操作">
          <button
            class="seg-btn"
            :class="{ active: moneyKind === 'DEPOSIT' }"
            type="button"
            @click="moneyKind = 'DEPOSIT'"
          >
            缴纳
          </button>
          <button
            class="seg-btn"
            :class="{ active: moneyKind === 'REFUND' }"
            type="button"
            @click="moneyKind = 'REFUND'"
          >
            退还
          </button>
        </div>
        <div class="form-grid">
          <label class="field">
            <span class="label">金额（元） <span class="req">*</span></span>
            <input v-model.number="moneyForm.amount" class="input num" type="number" min="0.01" step="0.01" />
          </label>
          <label class="field">
            <span class="label">方式 <span class="req">*</span></span>
            <select v-model="moneyForm.method" class="select">
              <option value="CASH">现金</option>
              <option value="BANK_CARD">银行卡</option>
              <option value="MOBILE">移动支付</option>
              <option value="INSURANCE">医保</option>
            </select>
          </label>
          <label class="field">
            <span class="label">备注</span>
            <input v-model.trim="moneyForm.remark" class="input" placeholder="可写入审计" />
          </label>
        </div>
        <div v-if="deposit" class="flow-list">
          <div class="section-label">最近流水</div>
          <div v-for="d in deposit.items.slice(0, 5)" :key="d.id" class="flow-row">
            <span class="mono muted">{{ dateTime(d.createdAt) }}</span>
            <span>{{ depositKindLabel(d.kind) }}</span>
            <span class="mono muted">{{ d.method }}</span>
            <span class="spacer" />
            <span class="num" :class="{ 'is-danger': d.amount < 0 }">{{ money(d.amount) }}</span>
          </div>
          <div v-if="deposit.items.length === 0" class="hint">暂无预交金流水。</div>
        </div>
      </div>
      <template #footer>
        <button class="btn" type="button" @click="moneyOpen = false">取消</button>
        <button
          class="btn btn--primary"
          type="button"
          :disabled="busy || !moneyForm.amount || moneyForm.amount <= 0"
          @click="submitMoney"
        >
          {{ moneyKind === 'DEPOSIT' ? '确认收取' : '确认退还' }}
        </button>
      </template>
    </AppModal>

    <!-- 费用看板 -->
    <AppModal
      v-model="financeOpen"
      title="住院费用看板"
      :subtitle="finance ? `${finance.admissionNo} · ${finance.wardName} ${finance.bedNo ?? '未分床'}` : ''"
      width="640px"
    >
      <div v-if="financeLoading" class="loading">
        <div v-for="i in 4" :key="i" class="skeleton" style="height: 40px" />
      </div>
      <div v-else-if="finance" class="stack">
        <div class="finance-grid">
          <div class="finance-cell">
            <span class="muted">累计计费</span>
            <strong class="num">{{ money(finance.billedTotal) }}</strong>
          </div>
          <div class="finance-cell">
            <span class="muted">已结算</span>
            <strong class="num">{{ money(finance.settledTotal) }}</strong>
          </div>
          <div class="finance-cell">
            <span class="muted">未结算</span>
            <strong class="num">{{ money(finance.unpaidTotal) }}</strong>
          </div>
          <div class="finance-cell">
            <span class="muted">待计（{{ finance.pendingOrderCount }} 单）</span>
            <strong class="num">{{ money(finance.pendingAmount) }}</strong>
          </div>
          <div class="finance-cell">
            <span class="muted">今日未计长嘱（{{ finance.dailyLongOrderCount }} 条）</span>
            <strong class="num">{{ money(finance.dailyLongAmount) }}</strong>
          </div>
          <div class="finance-cell">
            <span class="muted">预交金余额</span>
            <strong class="num">{{ money(finance.depositBalance) }}</strong>
          </div>
          <div class="finance-cell">
            <span class="muted">今日已计</span>
            <strong class="num">{{ money(finance.todayBilledAmount) }}</strong>
          </div>
          <div class="finance-cell" :class="{ 'is-danger': finance.warning }">
            <span class="muted">欠费缺口</span>
            <strong class="num">{{ money(finance.shortfall) }}</strong>
          </div>
        </div>
        <p class="hint">
          缺口 = 未结算 + 待计 + 当日未计长嘱 − 预交金余额；大于 0 即提示补交预交金，避免出院结算被卡住。
        </p>
      </div>
      <template #footer>
        <button class="btn" type="button" :disabled="printingFinance" @click="printFinanceSummary">
          {{ printingFinance ? '正在汇总…' : '打印费用汇总单' }}
        </button>
        <button class="btn" type="button" @click="financeOpen = false">关闭</button>
      </template>
    </AppModal>

    <!-- 病区出院预检 -->
    <AppModal
      v-model="readinessOpen"
      title="病区出院预检"
      :subtitle="wardLabel"
      width="720px"
    >
      <div v-if="readinessLoading" class="loading">
        <div v-for="i in 4" :key="i" class="skeleton" style="height: 40px" />
      </div>
      <div v-else-if="readiness" class="stack">
        <div class="check-summary" :class="readiness.blockedCount ? 'is-blocked' : 'is-ok'">
          <strong>
            {{ readiness.blockedCount ? `${readiness.blockedCount} 人还不能出院` : '在院患者当前均可出院' }}
          </strong>
          <span class="hint">
            在院 {{ readiness.total }} 人 · 可出院 {{ readiness.readyCount }} 人 ·
            {{ dateTime(readiness.checkedAt) }}
          </span>
          <span v-if="!readiness.emrChecked" class="hint">
            病历归档未核查（电子病历未接入或暂不可达），点开患者可看逐项结论。
          </span>
        </div>
        <div v-if="readiness.items.length === 0" class="hint">该病区暂无在院患者。</div>
        <template v-else>
          <div class="readiness-toolbar">
            <div class="seg" role="tablist" aria-label="预检筛选">
              <button
                v-for="f in READINESS_FILTERS"
                :key="f.value"
                class="seg-btn"
                :class="{ active: readinessFilter === f.value }"
                type="button"
                @click="readinessFilter = f.value"
              >
                {{ f.label }} {{ readinessCounts[f.value] }}
              </button>
            </div>
            <span class="spacer" />
            <span class="hint">当前显示 {{ readinessRows.length }} / {{ readiness.items.length }} 人</span>
          </div>
          <div v-if="readinessRows.length === 0" class="hint">
            当前筛选条件下没有患者，切换筛选可查看其余条目。
          </div>
          <div v-else class="readiness-list">
            <div v-for="it in readinessRows" :key="it.admissionId" class="readiness-row">
              <span class="badge" :class="it.ready ? 'badge--ok' : 'badge--danger'">
                {{ it.ready ? '可出院' : `${it.blockerCount} 项阻断` }}
              </span>
              <div class="check-body">
                <div class="check-title">
                  <span class="mono">{{ it.bedNo || '未分床' }}</span> ·
                  <span class="mono">{{ it.patientNo }}</span>
                  <span class="muted"> {{ it.admissionNo }}</span>
                </div>
                <div class="readiness-flags">
                  <span v-for="c in it.blockers" :key="c" class="badge badge--danger">
                    {{ checkCodeLabel(c) }}
                  </span>
                  <span v-for="c in it.warnings" :key="c" class="badge badge--warn">
                    {{ checkCodeLabel(c) }}
                  </span>
                  <span v-if="!it.blockers.length && !it.warnings.length" class="hint">无待处理项</span>
                </div>
              </div>
              <button class="btn btn--sm" type="button" @click="openDischargeFromReadiness(it)">处理</button>
            </div>
          </div>
        </template>
      </div>
      <p v-else class="hint">预检加载失败（{{ readinessError || '网络异常' }}），请稍后重试。</p>
      <template #footer>
        <button class="btn" type="button" :disabled="readinessLoading" @click="loadReadiness">重新预检</button>
        <button
          class="btn"
          type="button"
          :disabled="!readiness || readinessRows.length === 0"
          @click="exportReadinessCsv"
        >
          导出 CSV
        </button>
        <button
          class="btn"
          type="button"
          :disabled="readinessLoading || printingReadiness"
          @click="printReadiness"
        >
          {{ printingReadiness ? '准备中…' : '打印交接单' }}
        </button>
        <button class="btn" type="button" @click="readinessOpen = false">关闭</button>
      </template>
    </AppModal>

    <!-- 出院 -->
    <AppModal
      v-model="dischargeOpen"
      title="办理出院"
      :subtitle="dischargeTarget ? `${dischargeTarget.admissionNo} · ${dischargeTarget.patientNo}` : ''"
      width="560px"
    >
      <div class="stack">
        <div v-if="checkLoading" class="loading">
          <div v-for="i in 3" :key="i" class="skeleton" style="height: 36px" />
        </div>
        <template v-else-if="check">
          <div class="check-summary" :class="check.ready ? 'is-ok' : 'is-blocked'">
            <strong>{{ check.ready ? '可以办理出院' : `有 ${check.blockerCount} 项阻断，暂不能出院` }}</strong>
            <span class="hint">
              建议处理 {{ check.warningCount }} 项 · 未核查 {{ check.uncheckedCount }} 项 ·
              {{ dateTime(check.checkedAt) }}
            </span>
          </div>
          <div class="check-list">
            <div v-for="it in check.items" :key="it.code" class="check-row">
              <span class="badge" :class="checkBadgeClass(it)">{{ checkBadgeText(it) }}</span>
              <div class="check-body">
                <div class="check-title">
                  {{ it.title }}
                  <span v-if="it.count" class="muted">· {{ it.count }} 条</span>
                </div>
                <div class="hint">{{ it.detail }}</div>
                <div v-if="it.nextAction && it.result !== 'PASS'" class="check-next">{{ it.nextAction }}</div>
              </div>
              <span v-if="it.amount != null && it.amount > 0" class="num">{{ money(it.amount) }}</span>
            </div>
          </div>
        </template>
        <p v-else class="hint">
          体检未加载（{{ checkError || '网络异常' }}）。仍可直接出院，服务端会再次校验。
        </p>
        <p class="hint" style="line-height: 1.7">
          体检与出院门禁同一口径；阻断项必须处理完才能出院。出院会释放床位、结束就诊，
          并把未停长期医嘱按出院日自动停止。
        </p>
      </div>
      <template #footer>
        <button class="btn" type="button" :disabled="busy || checkLoading" @click="loadDischargeCheck">
          重新体检
        </button>
        <button class="btn" type="button" @click="dischargeOpen = false">取消</button>
        <button
          class="btn btn--danger"
          type="button"
          :disabled="busy || (check ? !check.ready : false)"
          @click="submitDischarge"
        >
          确认出院
        </button>
      </template>
    </AppModal>

    <!-- 批量建床 -->
    <AppModal v-model="bedsOpen" title="批量建床" :subtitle="currentWard?.name ?? ''" width="480px">
      <div class="stack">
        <label class="field">
          <span class="label">床位号 <span class="req">*</span></span>
          <textarea
            v-model.trim="bedNosText"
            class="input"
            rows="3"
            placeholder="用逗号或换行分隔，如：01,02,03"
          />
          <span class="hint">床位号在同一病区内唯一；创建后状态为空床。</span>
        </label>
      </div>
      <template #footer>
        <button class="btn" type="button" @click="bedsOpen = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="busy || !bedNosText" @click="submitBeds">
          创建
        </button>
      </template>
    </AppModal>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
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
  AdmissionView,
  Bed,
  DepositView,
  Dept,
  DischargeCheckItem,
  DischargeCheckView,
  InpatientAlert,
  InpatientFinanceView,
  Patient,
  Ward,
  WardDischargeReadiness,
  WardDischargeReadinessView,
} from '@/api/types'
import { hasPermission, session } from '@/stores/session'
import { toast } from '@/stores/toast'
import { downloadCsv } from '@/utils/csv'
import { ageOf, dateTime, genderLabel, money, todayIso } from '@/utils/format'
import { escapeHtml } from '@/utils/html'
import { printHtml } from '@/utils/print'
import { recordPrint } from '@/utils/printAudit'

const role = computed(() => session.roleLabel.value)
const route = useRoute()
const loading = ref(false)
const busy = ref(false)
/** 主数据加载失败：非空即错误态，不能退化成"没有患者/床位"。 */
const loadErr = ref('')

const canAdmit = computed(() => hasPermission('inpatient:admission:create'))
const canAssignBed = computed(() => hasPermission('inpatient:bed:assign'))
const canDischarge = computed(() => hasPermission('inpatient:admission:discharge'))
const canDeposit = computed(() => hasPermission('inpatient:deposit:manage'))
const canFinance = computed(() => hasPermission('inpatient:finance:read'))
/** 腕带要印姓名/性别/年龄，必须有患者主索引读权限（与病案号可见性同源）。 */
const canReadPatient = computed(() => hasPermission('empi:patient:read'))
const canManageWard = computed(() => hasPermission('inpatient:ward:manage'))

const wards = ref<Ward[]>([])
const departments = ref<Dept[]>([])
const selectedWardId = ref('')
const beds = ref<Bed[]>([])
const admissions = ref<AdmissionView[]>([])
const alerts = ref<InpatientAlert[]>([])

const currentWard = computed(() => wards.value.find((w) => w.id === selectedWardId.value))
const freeBeds = computed(() => beds.value.filter((b) => b.status === 'FREE'))
const activeCount = computed(() => admissions.value.length)
const pendingAdmitCount = computed(() => admissions.value.filter((a) => a.status === 'ADMITTED').length)
const unassignedCount = computed(() => admissions.value.filter((a) => !a.bedId).length)

function deptName(id?: string) {
  if (!id) return ''
  return departments.value.find((d) => d.id === id)?.name ?? id.slice(0, 8)
}

function bedLabel(b: Bed) {
  if (b.status === 'FREE') return '空床'
  if (b.status === 'OCCUPIED') return '在床'
  if (b.status === 'RESERVED') return '预留'
  return '停用'
}

function bedClass(b: Bed) {
  return {
    'is-free': b.status === 'FREE',
    'is-occupied': b.status === 'OCCUPIED',
    'is-reserved': b.status === 'RESERVED',
    'is-disabled': b.status === 'DISABLED',
  }
}

function patientOfBed(bedId: string) {
  return admissions.value.find((a) => a.bedId === bedId)
}

/* ------------------------------ 加载 ------------------------------ */
async function loadWards() {
  try {
    wards.value = await clinicalApi.wards()
    if (!selectedWardId.value || !wards.value.some((w) => w.id === selectedWardId.value)) {
      selectedWardId.value = wards.value[0]?.id ?? ''
    }
  } catch (e) {
    toast.error('病区加载失败', errorText(e))
    loadErr.value = errorText(e)
    wards.value = []
  }
}

async function reloadWard() {
  if (!selectedWardId.value) return
  const wardId = selectedWardId.value
  loading.value = true
  loadErr.value = ''
  try {
    const [bedList, inHospital, admitted] = await Promise.all([
      clinicalApi.beds(wardId),
      clinicalApi.admissions(wardId, 'IN_HOSPITAL'),
      clinicalApi.admissions(wardId, 'ADMITTED'),
    ])
    if (selectedWardId.value !== wardId) return
    beds.value = bedList
    admissions.value = [...admitted, ...inHospital].sort((a, b) =>
      (a.bedNo ?? '~').localeCompare(b.bedNo ?? '~'),
    )
    if (canFinance.value) {
      try {
        alerts.value = await clinicalApi.inpatientAlerts(wardId)
      } catch {
        // 欠费预警失败不影响床位与在院列表
        alerts.value = []
      }
    } else {
      alerts.value = []
    }
  } catch (e) {
    if (selectedWardId.value !== wardId) return
    toast.error('住院数据加载失败', errorText(e))
    loadErr.value = errorText(e)
    beds.value = []
    admissions.value = []
  } finally {
    if (selectedWardId.value === wardId) loading.value = false
  }
}

/** 病区与床位/在院数据都算主数据：先拉病区，失败时不再用空病区覆盖页面。 */
async function reloadAll() {
  loadErr.value = ''
  await loadWards()
  if (!loadErr.value) await reloadWard()
}

/**
 * 第 249 批：深链 `/inpatient?readiness=1[&ward=<id>]`。
 *
 * <p>驾驶舱与全局待办都指向这里：有 ward 就切到该病区，没有就用当前默认病区；
 * 最终一定落到“病区出院预检”弹窗，避免用户落页后还要自己找入口。
 */
function applyReadinessDeepLink() {
  if (String(route.query.readiness ?? '') !== '1') return
  const ward = String(route.query.ward ?? '')
  if (ward && ward !== selectedWardId.value && wards.value.some((w) => w.id === ward)) {
    selectedWardId.value = ward
    void reloadWard()
  }
  if (selectedWardId.value) openReadiness()
}

/* ------------------------------ 入院登记 ------------------------------ */

const admitOpen = ref(false)
const admitPatient = ref<Patient | null>(null)
const patientKeyword = ref('')
const patientResults = ref<Patient[]>([])
const patientSearching = ref(false)
const admitForm = reactive({ bedId: '', attendingDoctor: '' })

function openAdmit(bedId?: string) {
  admitPatient.value = null
  patientKeyword.value = ''
  patientResults.value = []
  admitForm.bedId = bedId ?? ''
  admitForm.attendingDoctor = ''
  admitOpen.value = true
}

async function searchPatients() {
  const kw = patientKeyword.value.trim()
  if (!kw) return
  patientSearching.value = true
  try {
    const looksLikeNo = /^[A-Za-z]{1,4}\d{2,}$/.test(kw)
    const res = looksLikeNo ? await empiApi.searchByNo(kw) : await empiApi.searchByName(kw)
    patientResults.value = res.items
    if (res.items.length === 0) toast.info('未找到患者', '请核对姓名或用完整病案号检索')
  } catch (e) {
    toast.error('患者检索失败', errorText(e))
  } finally {
    patientSearching.value = false
  }
}

function choosePatient(p: Patient) {
  admitPatient.value = p
  patientKeyword.value = `${p.name} · ${p.patientNo}`
  patientResults.value = []
}

async function submitAdmit() {
  if (!admitPatient.value || !selectedWardId.value) return
  busy.value = true
  try {
    const created = await clinicalApi.admit({
      patientId: admitPatient.value.id,
      wardId: selectedWardId.value,
      bedId: admitForm.bedId || undefined,
      attendingDoctor: admitForm.attendingDoctor,
    })
    admitOpen.value = false
    toast.ok('入院已登记', `${created.admissionNo} · ${created.bedNo || '待分床'}`)
    await reloadWard()
  } catch (e) {
    toast.error('入院登记失败', errorText(e))
  } finally {
    busy.value = false
  }
}

/* ------------------------------ 分床 / 转床 ------------------------------ */

const assignOpen = ref(false)
const assignTarget = ref<AdmissionView | null>(null)
const assignBedId = ref('')
const assignReason = ref('')

function openAssign(a: AdmissionView) {
  assignTarget.value = a
  assignBedId.value = ''
  assignReason.value = ''
  assignOpen.value = true
}

async function submitAssign() {
  if (!assignTarget.value || !assignBedId.value) return
  busy.value = true
  try {
    const updated = await clinicalApi.assignAdmissionBed(
      assignTarget.value.id,
      assignBedId.value,
      assignReason.value || undefined,
    )
    assignOpen.value = false
    toast.ok(assignTarget.value.bedId ? '转床完成' : '分床完成', updated.bedNo ?? '')
    await reloadWard()
  } catch (e) {
    toast.error('床位操作失败', errorText(e))
  } finally {
    busy.value = false
  }
}

/* ------------------------------ 预交金 ------------------------------ */

const moneyOpen = ref(false)
const moneyTarget = ref<AdmissionView | null>(null)
const moneyKind = ref<'DEPOSIT' | 'REFUND'>('DEPOSIT')
const deposit = ref<DepositView | null>(null)
const moneyForm = reactive({ amount: 0, method: 'CASH', remark: '' })

function depositKindLabel(kind: string) {
  if (kind === 'DEPOSIT') return '缴纳'
  if (kind === 'REFUND') return '退还'
  return '结算抵扣'
}

async function openMoney(a: AdmissionView) {
  moneyTarget.value = a
  moneyKind.value = 'DEPOSIT'
  moneyForm.amount = 0
  moneyForm.method = 'CASH'
  moneyForm.remark = ''
  moneyOpen.value = true
  try {
    deposit.value = await clinicalApi.deposits(a.id)
  } catch (e) {
    deposit.value = null
    toast.error('预交金读取失败', errorText(e))
  }
}

async function submitMoney() {
  if (!moneyTarget.value || !moneyForm.amount || moneyForm.amount <= 0) return
  busy.value = true
  try {
    deposit.value =
      moneyKind.value === 'DEPOSIT'
        ? await clinicalApi.payDeposit(
            moneyTarget.value.id,
            moneyForm.amount,
            moneyForm.method,
            moneyForm.remark || undefined,
          )
        : await clinicalApi.refundDeposit(
            moneyTarget.value.id,
            moneyForm.amount,
            moneyForm.method,
            moneyForm.remark || undefined,
          )
    moneyForm.amount = 0
    moneyForm.remark = ''
    toast.ok(moneyKind.value === 'DEPOSIT' ? '预交金已收取' : '预交金已退还')
  } catch (e) {
    toast.error('预交金操作失败', errorText(e))
  } finally {
    busy.value = false
  }
}

/* ------------------------------ 费用看板 ------------------------------ */

const financeOpen = ref(false)
const financeLoading = ref(false)
const finance = ref<InpatientFinanceView | null>(null)

async function openFinance(a: AdmissionView) {
  finance.value = null
  financeOpen.value = true
  financeLoading.value = true
  try {
    finance.value = await clinicalApi.inpatientFinance(a.id)
  } catch (e) {
    toast.error('费用看板加载失败', errorText(e))
  } finally {
    financeLoading.value = false
  }
}

/** 费用汇总单：看板只有金额汇总，明细按需拉一次收费单（不影响看板首屏）。 */
const printingFinance = ref(false)

function financeStatusLabel(status: string): string {
  const map: Record<string, string> = {
    ADMITTED: '待入科',
    IN_HOSPITAL: '在院',
    DISCHARGED: '已出院',
  }
  return map[status] ?? status
}

async function printFinanceSummary() {
  const f = finance.value
  if (!f || printingFinance.value) return
  printingFinance.value = true
  try {
    const charge = await clinicalApi.charge(f.visitId)
    const esc = escapeHtml
    const rows = charge.items
      .map(
        (i) => `<tr>
          <td>${esc(i.itemName)}<div class="muted mono">${esc(i.itemCode)}</div></td>
          <td>${esc(i.category || '—')}</td>
          <td class="num">${esc(money(i.amount))}</td>
        </tr>`,
      )
      .join('')
    const body = `
      <h1>住院费用汇总单</h1>
      <div class="muted meta">${esc(f.admissionNo)} · ${esc(f.wardName)} ${esc(f.bedNo ?? '未分床')} · ${esc(financeStatusLabel(f.status))}</div>
      <table class="head">
        <tr><th>病案号</th><td>${esc(f.patientNo)}</td><th>就诊号</th><td>${esc(f.visitNo)}</td></tr>
        <tr><th>病区</th><td>${esc(f.wardName)}</td><th>床位</th><td>${esc(f.bedNo ?? '未分床')}</td></tr>
        <tr><th>入院时间</th><td colspan="3">${f.admittedAt ? esc(dateTime(f.admittedAt)) : '—'}</td></tr>
      </table>
      <table class="sum">
        <tr><th>累计计费</th><td class="num">${esc(money(f.billedTotal))}</td><th>已结算</th><td class="num">${esc(money(f.settledTotal))}</td></tr>
        <tr><th>未结算</th><td class="num">${esc(money(f.unpaidTotal))}</td><th>待计（${f.pendingOrderCount} 单）</th><td class="num">${esc(money(f.pendingAmount))}</td></tr>
        <tr><th>今日未计长嘱（${f.dailyLongOrderCount} 条）</th><td class="num">${esc(money(f.dailyLongAmount))}</td><th>预交金余额</th><td class="num">${esc(money(f.depositBalance))}</td></tr>
        <tr><th>今日已计</th><td class="num">${esc(money(f.todayBilledAmount))}</td><th>欠费缺口</th><td class="num">${esc(money(f.shortfall))}</td></tr>
      </table>
      <h2>收费明细${charge.chargeNo ? `（${esc(charge.chargeNo)}）` : ''}</h2>
      <table class="items">
        <thead><tr><th>项目</th><th>类别</th><th class="num">金额</th></tr></thead>
        <tbody>${rows || '<tr><td colspan="3" class="muted">暂无已计价明细</td></tr>'}</tbody>
      </table>
      <div class="total">明细合计：<strong>${esc(money(charge.totalAmount))}</strong></div>
      <div class="foot">本单为系统汇总件，最终金额以出院结算为准；预交金余额为打印时点数值。</div>`
    const ok = printHtml({
      title: `住院费用汇总单 ${f.admissionNo}`,
      body,
      css: `
        .meta { margin-bottom: 14px; }
        .head th, .sum th { width: 18%; }
        .sum { margin-top: 14px; }
        h2 { margin: 20px 0 8px; font-size: 15px; }
        .items { font-size: 13px; }
        .num { text-align: right; }
        .total { margin-top: 12px; text-align: right; font-size: 15px; }
        .mono { font-family: ui-monospace, Menlo, Consolas, monospace; font-size: 11px; }
      `,
    })
    if (!ok) {
      toast.error('浏览器拦截了打印窗口', '请允许弹窗后重试')
      return
    }
    recordPrint({
      docType: 'inpatientFinance',
      docNo: f.admissionNo,
      targetType: 'ADMISSION',
      targetId: f.admissionId,
      patientNo: f.patientNo,
    })
  } catch (e) {
    toast.error('费用明细读取失败', errorText(e))
  } finally {
    printingFinance.value = false
  }
}

/** 腕带：读一次患者主索引（性别/年龄不在住院视图里），按两联打印便于备用替换。 */
const printingWristId = ref('')

async function printWristband(a: AdmissionView) {
  if (printingWristId.value) return
  printingWristId.value = a.id
  try {
    const p = await empiApi.patient(a.patientId)
    const esc = escapeHtml
    const printedAt = dateTime(new Date().toISOString())
    const wardName = wards.value.find((w) => w.id === a.wardId)?.name ?? ''
    const band = `
      <div class="band">
        <div class="row"><span class="label">铭医 · 住院腕带</span><span class="label">打印 ${esc(printedAt)}</span></div>
        <div class="row"><span class="name">${esc(p.name)}</span><span>${esc(genderLabel(p.gender))} · ${esc(ageOf(p.birthDate))}</span></div>
        <div class="row"><span class="label">住院号</span><span class="no">${esc(a.admissionNo)}</span></div>
        <div class="row"><span>病案号 ${esc(p.patientNo)}</span><span>${esc(wardName)} ${esc(a.bedNo ?? '未分床')}</span></div>
        <div class="row"><span class="label">入院 ${a.admittedAt ? esc(dateTime(a.admittedAt)) : '—'}</span><span>${esc(a.attendingDoctor)} 医师</span></div>
        <div class="barcode">条码区（标签号 / 住院号）</div>
      </div>`
    const ok = printHtml({
      title: `腕带 ${a.admissionNo}`,
      body: band + band,
      css: `
        .band { border: 2px solid #111; border-radius: 6px; padding: 10px 14px; height: 92mm;
                display: flex; flex-direction: column; justify-content: space-between; page-break-inside: avoid; }
        .band + .band { margin-top: 10mm; }
        .row { display: flex; align-items: baseline; justify-content: space-between; gap: 8px; }
        .name { font-size: 26px; font-weight: 700; letter-spacing: 1px; }
        .no { font-size: 20px; font-family: ui-monospace, Menlo, Consolas, monospace; letter-spacing: 1px; }
        .label { font-size: 11px; color: #555; }
        .barcode { margin-top: 8px; height: 26mm; border: 1px dashed #999; color: #777;
                   display: flex; align-items: center; justify-content: center; font-size: 11px; }
      `,
    })
    if (!ok) {
      toast.error('浏览器拦截了打印窗口', '请允许弹窗后重试')
      return
    }
    recordPrint({
      docType: 'wristband',
      docNo: a.admissionNo,
      targetType: 'ADMISSION',
      targetId: a.id,
      patientNo: p.patientNo,
    })
  } catch (e) {
    toast.error('患者信息读取失败', errorText(e))
  } finally {
    printingWristId.value = ''
  }
}

/* ------------------------------ 出院 ------------------------------ */
/** 出院弹窗只需要这三个字段：批量预检条目也能直接打开同一弹窗。 */
type DischargeTarget = Pick<AdmissionView, 'id' | 'admissionNo' | 'patientNo'>

const dischargeOpen = ref(false)
const dischargeTarget = ref<DischargeTarget | null>(null)
/** 出院前体检：拿不到就退化为"直接提交"，由服务端门禁兜底。 */
const checkLoading = ref(false)
const check = ref<DischargeCheckView | null>(null)
const checkError = ref('')

function openDischarge(a: DischargeTarget) {
  dischargeTarget.value = a
  dischargeOpen.value = true
  void loadDischargeCheck()
}

async function loadDischargeCheck() {
  if (!dischargeTarget.value) return
  checkLoading.value = true
  checkError.value = ''
  check.value = null
  try {
    check.value = await clinicalApi.dischargeCheck(dischargeTarget.value.id)
  } catch (e) {
    checkError.value = errorText(e)
  } finally {
    checkLoading.value = false
  }
}

function checkBadgeClass(it: DischargeCheckItem): string {
  if (it.result === 'UNKNOWN') return 'badge--neutral'
  if (it.result === 'PASS') return 'badge--ok'
  return it.severity === 'BLOCKER' ? 'badge--danger' : 'badge--warn'
}

function checkBadgeText(it: DischargeCheckItem): string {
  if (it.result === 'UNKNOWN') return '未核查'
  if (it.result === 'PASS') return '通过'
  return it.severity === 'BLOCKER' ? '阻断' : '建议'
}

async function submitDischarge() {
  if (!dischargeTarget.value) return
  busy.value = true
  try {
    const updated = await clinicalApi.dischargeAdmission(dischargeTarget.value.id)
    dischargeOpen.value = false
    toast.ok('出院已办理', `${updated.admissionNo} · 床位已释放，未停长期医嘱已自动停止`)
    await reloadWard()
  } catch (e) {
    toast.error('出院失败', errorText(e))
    // 被拒多半是门禁条件变化（并发计费/结算），立刻重查，让用户看到最新阻断项。
    void loadDischargeCheck()
  } finally {
    busy.value = false
  }
}

/* ------------------------------ 病区出院预检 ------------------------------ */
const readinessOpen = ref(false)
const readinessLoading = ref(false)
const readiness = ref<WardDischargeReadinessView | null>(null)
const readinessError = ref('')

/** 预检筛选：护士站通常只关心"还走不了的人"，全部/可出院用于核对与导出。 */
type ReadinessFilter = 'all' | 'blocked' | 'ready'
const READINESS_FILTERS: { value: ReadinessFilter; label: string }[] = [
  { value: 'all', label: '全部' },
  { value: 'blocked', label: '只看受阻' },
  { value: 'ready', label: '只看可出院' },
]
const readinessFilter = ref<ReadinessFilter>('all')

const readinessCounts = computed<Record<ReadinessFilter, number>>(() => {
  const items = readiness.value?.items ?? []
  const blocked = items.filter((i) => !i.ready).length
  return { all: items.length, blocked, ready: items.length - blocked }
})

const readinessRows = computed(() => {
  const items = readiness.value?.items ?? []
  if (readinessFilter.value === 'blocked') return items.filter((i) => !i.ready)
  if (readinessFilter.value === 'ready') return items.filter((i) => i.ready)
  return items
})

const wardLabel = computed(() =>
  currentWard.value ? `${currentWard.value.name}（${currentWard.value.code}）` : '',
)

/** 与后端 DischargeCheckService 的机器码一一对应；未知码原样显示，便于发现新码。 */
const CHECK_CODE_LABELS: Record<string, string> = {
  ADMISSION_STATUS: '状态异常',
  UNSETTLED_CHARGE: '未结费用',
  UNBILLED_TEMP_ORDER: '临时医嘱未计费',
  UNBILLED_DAILY_ORDER: '长嘱未计费',
  UNSTOPPED_LONG_ORDER: '长嘱未停',
  DISCHARGE_MED_PENDING: '带药未完成',
  EMR_ARCHIVE: '病历未归档',
}

function checkCodeLabel(code: string): string {
  return CHECK_CODE_LABELS[code] ?? code
}

function openReadiness() {
  readinessOpen.value = true
  readinessFilter.value = 'all'
  void loadReadiness()
}

async function loadReadiness() {
  if (!selectedWardId.value) return
  readinessLoading.value = true
  readinessError.value = ''
  readiness.value = null
  try {
    readiness.value = await clinicalApi.wardDischargeReadiness(selectedWardId.value)
  } catch (e) {
    readinessError.value = errorText(e)
  } finally {
    readinessLoading.value = false
  }
}

/** 从预检条目直接打开单患者出院弹窗（同一个门禁，不会出现"预检能走、点进去不能走"）。 */
function openDischargeFromReadiness(it: WardDischargeReadiness) {
  readinessOpen.value = false
  openDischarge({ id: it.admissionId, admissionNo: it.admissionNo, patientNo: it.patientNo })
}

/**
 * 导出当前筛选结果（所见即所得）。
 *
 * <p>只导机器码翻译后的中文，不导内部码：这张表是给护士站交接与线下核对用的，
 * 不是给系统对接用的；需要机器码请走 API。
 */
function exportReadinessCsv() {
  const data = readiness.value
  const ward = currentWard.value
  if (!data || !ward || readinessRows.value.length === 0) return
  const rows: string[][] = [['床位', '住院号', '病案号', '结论', '阻断项', '建议项']]
  for (const it of readinessRows.value) {
    rows.push([
      it.bedNo || '未分床',
      it.admissionNo,
      it.patientNo,
      it.ready ? '可出院' : `${it.blockerCount} 项阻断`,
      it.blockers.map((c) => checkCodeLabel(c)).join('、') || '—',
      it.warnings.map((c) => checkCodeLabel(c)).join('、') || '—',
    ])
  }
  downloadCsv(`出院预检-${ward.code}-${todayIso()}.csv`, rows)
}

/* ---------- 交接单打印（第 250 批） ---------- */
const printingReadiness = ref(false)

/**
 * 打印病区出院预检交接单。
 *
 * <p>交接单是“纸上的一次快照”：数据还没加载就先拉一次，但**不重新计算**已展示的结论，
 * 避免打印出来的数字与屏幕上看到的不是同一时刻。
 * 单据号按“病区 + 日期”固定，同一天再打即视为补打，走既有补打原因通道。
 */
async function printReadiness() {
  if (printingReadiness.value) return
  if (!readiness.value) await loadReadiness()
  const data = readiness.value
  const ward = currentWard.value
  if (!data || !ward) {
    toast.error('预检数据未加载', '请先重新预检再打印')
    return
  }
  printingReadiness.value = true
  try {
    const esc = escapeHtml
    const rows = data.items
      .map(
        (it) => `<tr>
          <td class="mono">${esc(it.bedNo || '未分床')}</td>
          <td class="mono">${esc(it.patientNo)}</td>
          <td class="mono">${esc(it.admissionNo)}</td>
          <td>${it.ready ? '可出院' : `${it.blockerCount} 项阻断`}</td>
          <td>${it.blockers.length ? it.blockers.map((c) => esc(checkCodeLabel(c))).join('、') : '—'}</td>
          <td>${it.warnings.length ? it.warnings.map((c) => esc(checkCodeLabel(c))).join('、') : '—'}</td>
        </tr>`,
      )
      .join('')
    const body = `
      <h1>病区出院预检交接单</h1>
      <div class="muted meta">${esc(ward.name)}（${esc(ward.code)}） · ${esc(dateTime(data.checkedAt))}
        · 在院 ${data.total} 人 / 可出院 ${data.readyCount} 人 / 受阻 ${data.blockedCount} 人</div>
      <table class="items">
        <thead><tr><th>床位</th><th>病案号</th><th>住院号</th><th>结论</th><th>阻断项</th><th>建议项</th></tr></thead>
        <tbody>${rows || '<tr><td colspan="6" class="muted">该病区暂无在院患者</td></tr>'}</tbody>
      </table>
      <div class="foot">
        阻断项与出院门禁同口径，必须处理完才能出院；建议项不阻断出院。
        ${data.emrChecked ? '' : '<br/>病历归档未核查：电子病历未接入或暂不可达。'}
        <br/>交班护士：__________　接班护士：__________
      </div>`
    const ok = printHtml({
      title: `出院预检 ${ward.name}`,
      body,
      css: `
        .meta { margin-bottom: 14px; }
        .items { font-size: 12px; margin-top: 8px; }
        .items th, .items td { padding: 6px 8px; }
        .mono { font-family: ui-monospace, Menlo, Consolas, monospace; }
        .foot { margin-top: 16px; font-size: 12px; color: #444; line-height: 1.8; }
      `,
    })
    if (!ok) {
      toast.error('浏览器拦截了打印窗口', '请允许弹窗后重试')
      return
    }
    recordPrint({
      docType: 'dischargeReadiness',
      docNo: `DR-${ward.code}-${todayIso()}`,
      targetType: 'WARD',
      targetId: ward.id,
    })
  } finally {
    printingReadiness.value = false
  }
}

/* ------------------------------ 批量建床 ------------------------------ */

const bedsOpen = ref(false)
const bedNosText = ref('')

function openBeds() {
  bedNosText.value = ''
  bedsOpen.value = true
}

async function submitBeds() {
  if (!selectedWardId.value || !bedNosText.value) return
  const bedNos = bedNosText.value
    .split(/[\s,，;；]+/)
    .map((s) => s.trim())
    .filter(Boolean)
  if (bedNos.length === 0) {
    toast.warn('请填写床位号')
    return
  }
  busy.value = true
  try {
    await clinicalApi.createBeds(selectedWardId.value, bedNos)
    bedsOpen.value = false
    toast.ok('床位已创建', `${bedNos.length} 张`)
    await reloadWard()
  } catch (e) {
    toast.error('建床失败', errorText(e))
  } finally {
    busy.value = false
  }
}

/* ------------------------------ 床位点击 ------------------------------ */

function onBedClick(b: Bed) {
  if (b.status === 'OCCUPIED') {
    const admission = patientOfBed(b.id)
    if (admission && canAssignBed.value) openAssign(admission)
    return
  }
  if (b.status === 'FREE' && canAdmit.value) {
    openAdmit(b.id)
  }
}

onMounted(async () => {
  try {
    departments.value = await clinicalApi.departments()
  } catch {
    /* 科室名失败不影响住院主流程 */
  }
  await reloadAll()
  applyReadinessDeepLink()
})
</script>

<style scoped>
.ward-bar {
  display: flex;
  align-items: flex-end;
  gap: var(--s3);
  padding: var(--s3) var(--s4);
  flex-wrap: wrap;
}

.ward-pick {
  width: 260px;
}

.ward-grid {
  display: grid;
  grid-template-columns: minmax(320px, 2fr) minmax(420px, 3fr);
  gap: var(--s4);
  align-items: start;
}

@media (max-width: 1100px) {
  .ward-grid {
    grid-template-columns: 1fr;
  }
}

.bed-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(88px, 1fr));
  gap: var(--s2);
  padding: var(--s3) var(--s4) var(--s4);
}

.bed {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 8px 9px;
  min-height: 66px;
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  background: var(--panel);
  text-align: left;
  cursor: pointer;
}

.bed:hover {
  border-color: var(--line-strong);
}

.bed-no {
  font-size: var(--fs-13);
  font-weight: 500;
}

.bed-state {
  font-size: var(--fs-10);
  color: var(--ink-3);
}

.bed-patient {
  font-size: var(--fs-10);
  color: var(--ink-3);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.bed.is-free {
  border-style: dashed;
  background: var(--sunken);
}

.bed.is-occupied {
  border-color: var(--accent-line);
  background: var(--accent-weak);
}

.bed.is-reserved {
  border-color: var(--warn-line, var(--line-strong));
  background: var(--warn-weak, var(--panel-3));
}

.bed.is-disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

.pad {
  padding: var(--s4);
}

.row-actions {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}

.loading {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: var(--s4);
}

.alert-card {
  padding: var(--s3) var(--s4);
  display: flex;
  flex-direction: column;
  gap: var(--s2);
  border-left: 3px solid var(--danger, #b42318);
}

.alert-head {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--danger, #b42318);
  font-size: var(--fs-13);
}

.alert-head .muted {
  color: var(--ink-3);
  font-weight: 400;
}

.alert-list {
  display: flex;
  gap: var(--s2);
  flex-wrap: wrap;
}

.alert-chip {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  padding: 3px 8px;
  border: 1px solid var(--line);
  border-radius: var(--r-pill);
  font-size: var(--fs-11);
  background: var(--panel);
}

.search-row {
  display: flex;
  gap: var(--s2);
}

.search-row .input {
  flex: 1;
}

.patient-results {
  display: flex;
  flex-direction: column;
  max-height: 200px;
  overflow: auto;
  border: 1px solid var(--line);
  border-radius: var(--r-md);
}

.patient-row {
  display: flex;
  align-items: center;
  gap: var(--s3);
  padding: 7px 10px;
  border: 0;
  border-bottom: 1px solid var(--line);
  background: var(--panel);
  cursor: pointer;
  text-align: left;
  font-size: var(--fs-13);
}

.patient-row:hover {
  background: var(--panel-3);
}

.picked-patient {
  display: flex;
  align-items: center;
  gap: var(--s2);
  padding: 8px 10px;
  border-radius: var(--r-md);
  background: var(--accent-weak);
  border: 1px solid var(--accent-line);
  font-size: var(--fs-13);
}

.form-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
  gap: var(--s3);
}

.flow-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
  border-top: 1px solid var(--line);
  padding-top: var(--s3);
}

.flow-row {
  display: flex;
  align-items: center;
  gap: var(--s2);
  font-size: var(--fs-12);
  color: var(--ink-2);
}

.finance-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
  gap: var(--s2);
}

.finance-cell {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 9px 10px;
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  background: var(--sunken);
  font-size: var(--fs-12);
}

.finance-cell strong {
  font-size: var(--fs-15);
}

.finance-cell.is-danger {
  border-color: var(--danger-line, var(--line-strong));
  color: var(--danger, #b42318);
  background: var(--danger-weak, var(--panel-3));
}

/* 出院体检：一行一项，徽章在左、说明居中、金额在右 */
.check-summary {
  display: flex;
  flex-direction: column;
  gap: 3px;
  padding: 10px 12px;
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  background: var(--sunken);
}

.check-summary.is-ok {
  border-color: var(--positive-line, var(--line-strong));
  color: var(--positive-ink);
}

.check-summary.is-blocked {
  border-color: var(--danger-line, var(--line-strong));
  color: var(--danger, #b42318);
}

.check-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.check-row {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 8px 10px;
  border: 1px solid var(--line);
  border-radius: var(--r-md);
}

.check-body {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
  flex: 1;
}

.check-title {
  font-size: var(--fs-12);
  font-weight: 600;
}

.check-next {
  font-size: var(--fs-small);
  color: var(--accent-ink);
}

/* 病区出院预检：一人一行，机器码徽章横向排 */
.readiness-toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.readiness-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.readiness-row {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 8px 10px;
  border: 1px solid var(--line);
  border-radius: var(--r-md);
}

.readiness-flags {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  margin-top: 2px;
}

.is-danger {
  color: var(--danger, #b42318);
}
</style>
