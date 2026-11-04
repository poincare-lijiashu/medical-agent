<template>
  <div class="page">
    <PageHeader
      title="临床路径"
      :sub="`住院患者按路径模板执行每日任务：完成或跳过（变异必须写原因）→ 结束/退出路径。当前角色 ${role}`"
    >
      <template #actions>
        <button v-if="canManage" class="btn btn--primary" type="button" @click="openEnroll">
          <AppIcon name="plus" :size="14" /> 患者入径
        </button>
        <button v-if="canManage" class="btn" type="button" @click="openCreate">
          <AppIcon name="plus" :size="14" /> 新建模板
        </button>
        <button class="btn" type="button" :disabled="loading" @click="loadAll">
          <AppIcon name="refresh" :size="15" /> 刷新
        </button>
      </template>
    </PageHeader>

    <div class="kpi-strip">
      <KpiCard label="在径患者" :value="counts.active" unit="人" tone="accent" meta="待执行每日任务" />
      <KpiCard label="已完成" :value="counts.completed" unit="人" tone="ok" meta="路径全部任务完成" />
      <KpiCard label="已退出" :value="counts.exited" unit="人" meta="变异退出，保留原因" />
      <KpiCard label="路径模板" :value="templates.length" unit="个" meta="按当前筛选" />
    </div>

    <div class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">在径与历史</span>
        <span class="badge badge--neutral">{{ instances.length }}</span>
        <span class="spacer" />
        <label class="field f-q">
          <span class="label">状态</span>
          <select v-model="statusFilter" class="select" aria-label="按路径状态筛选" @change="loadInstances">
            <option value="">全部</option>
            <option value="ACTIVE">在径</option>
            <option value="COMPLETED">已完成</option>
            <option value="EXITED">已退出</option>
          </select>
        </label>
      </div>

      <TableSkeleton v-if="loading" :rows="4" />
      <LoadError v-else-if="loadError" :message="loadError" :retrying="loading" @retry="loadAll" />
      <EmptyState
        v-else-if="instances.length === 0"
        title="还没有入径患者"
        desc="选择已发布的路径模板并输入住院号即可入径；入径时按模板节点生成任务快照，后续模板调整不影响在途患者。"
      />
      <div v-else class="table-wrap">
        <table class="table table--stack">
          <thead>
            <tr>
              <th>入径号</th>
              <th>患者 / 住院号</th>
              <th>路径模板</th>
              <th>科室</th>
              <th class="is-num">当前天</th>
              <th>状态</th>
              <th>起止</th>
              <th style="width: 80px">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="i in instances" :key="i.id" class="clickable" tabindex="0" @click="openDetail(i.id)" @keydown.enter="openDetail(i.id)">
              <td class="mono" data-primary>{{ i.instanceNo }}</td>
              <td data-label="患者">{{ i.patientNo || '—' }}<div class="mono muted small">{{ i.visitNo }}</div></td>
              <td data-label="路径模板">{{ i.templateName }}</td>
              <td data-label="科室">{{ i.deptName || '—' }}</td>
              <td class="is-num" data-label="当前天">第 {{ i.currentDay }} 天</td>
              <td data-label="状态">
                <StatusPill :value="i.status" :label="instanceStatusLabel(i.status)" domain="clinical" />
              </td>
              <td data-label="起止">{{ date(i.startDate) }} → {{ i.endDate ? date(i.endDate) : '进行中' }}</td>
              <td data-label="操作">
                <button class="btn btn--sm" type="button" @click.stop="openDetail(i.id)">查看</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <div class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">路径模板</span>
        <span class="badge badge--neutral">{{ templates.length }}</span>
        <span class="spacer" />
        <label class="field f-grow">
          <span class="label">检索</span>
          <input v-model.trim="keyword" class="input" placeholder="编码 / 名称" @keydown.enter="loadTemplates" />
        </label>
        <button class="btn btn--sm" type="button" :disabled="loading" @click="loadTemplates">查询</button>
      </div>

      <TableSkeleton v-if="loading" :rows="3" />
      <EmptyState
        v-else-if="templates.length === 0"
        title="还没有路径模板"
        desc="新建模板并添加每日节点后发布；发布后的模板不可修改，只能停用后新建版本。"
      />
      <div v-else class="table-wrap">
        <table class="table">
          <thead>
            <tr>
              <th>编码</th>
              <th>名称</th>
              <th>诊断编码</th>
              <th class="is-num">天数</th>
              <th>状态</th>
              <th>发布人</th>
              <th style="width: 220px">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="t in templates" :key="t.id">
              <td class="mono" data-primary>{{ t.code }}</td>
              <td data-label="名称">{{ t.name }}</td>
              <td data-label="诊断编码">{{ t.diagnosisCode || '—' }}</td>
              <td class="is-num" data-label="天数">{{ t.dayCount }}</td>
              <td data-label="状态">
                <StatusPill :value="t.status" :label="templateStatusLabel(t.status)" domain="clinical" />
              </td>
              <td data-label="发布人">{{ t.publishedBy || '—' }}</td>
              <td data-label="操作">
                <button class="btn btn--sm" type="button" @click="openTemplate(t.id)">节点</button>
                <button
                  v-if="t.status === 'DRAFT' && canManage"
                  class="btn btn--sm btn--primary"
                  type="button"
                  :disabled="busy"
                  @click="publishTemplate(t)"
                >
                  发布
                </button>
                <button
                  v-if="t.status === 'PUBLISHED' && canManage"
                  class="btn btn--sm"
                  type="button"
                  :disabled="busy"
                  @click="disableTemplate(t)"
                >
                  停用
                </button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <AppModal
      v-model="detailOpen"
      title="路径执行"
      :subtitle="detail ? `${detail.instance.instanceNo} · ${detail.instance.templateName}` : ''"
      width="960px"
    >
      <TableSkeleton v-if="detailLoading" :rows="4" />
      <LoadError v-else-if="detailError" :message="detailError" :retrying="detailLoading" @retry="reloadDetail" />
      <template v-else-if="detail">
        <div class="detail-meta">
          <StatusPill :value="detail.instance.status" :label="instanceStatusLabel(detail.instance.status)" domain="clinical" />
          <span>患者 <strong>{{ detail.instance.patientNo || '—' }}</strong> · 住院号 {{ detail.instance.visitNo }}</span>
          <span>科室 {{ detail.instance.deptName || '—' }}</span>
          <span>第 <strong>{{ detail.instance.currentDay }}</strong> 天 · 开始 {{ date(detail.instance.startDate) }}</span>
          <span v-if="detail.instance.endDate">结束 {{ date(detail.instance.endDate) }}</span>
          <span v-if="detail.instance.exitReason" class="danger">退出原因：{{ detail.instance.exitReason }}</span>
        </div>
        <div class="progress-line">
          <span>进度 {{ detail.progress.done + detail.progress.skipped }}/{{ detail.progress.total }}</span>
          <span class="ok">完成 {{ detail.progress.done }}</span>
          <span class="warn">变异 {{ detail.progress.skipped }}</span>
          <span class="muted">待办 {{ detail.progress.pending }}</span>
        </div>

        <div class="table-wrap">
          <table class="table">
            <thead>
              <tr>
                <th class="is-num">天</th>
                <th>任务</th>
                <th>分类</th>
                <th>必做</th>
                <th>状态</th>
                <th>执行人 / 时间</th>
                <th>备注</th>
                <th v-if="canExecute && detail.instance.status === 'ACTIVE'" style="width: 160px">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="t in detail.tasks" :key="t.id">
                <td class="is-num" data-primary>D{{ t.dayNo }}</td>
                <td data-label="任务">{{ t.name }}</td>
                <td data-label="分类">{{ categoryLabel(t.category) }}</td>
                <td data-label="必做">{{ t.required ? '是' : '否' }}</td>
                <td data-label="状态">
                  <StatusPill :value="t.status" :label="taskStatusLabel(t.status)" domain="clinical" />
                </td>
                <td data-label="执行人">{{ t.doneBy || '—' }}<span v-if="t.doneAt" class="muted small"> · {{ dateTime(t.doneAt) }}</span></td>
                <td data-label="备注">{{ t.note || '—' }}</td>
                <td v-if="canExecute && detail.instance.status === 'ACTIVE'" data-label="操作">
                  <template v-if="t.status === 'PENDING'">
                    <button class="btn btn--sm btn--primary" type="button" :disabled="busy" @click="completeTask(t)">完成</button>
                    <button class="btn btn--sm" type="button" :disabled="busy" @click="openSkip(t)">跳过</button>
                  </template>
                  <span v-else class="muted">—</span>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </template>

      <template #footer>
        <button class="btn" type="button" @click="detailOpen = false">关闭</button>
        <template v-if="detail?.instance.status === 'ACTIVE'">
          <button v-if="canManage" class="btn" type="button" :disabled="busy" @click="openExit">退出路径</button>
          <button
            v-if="canExecute"
            class="btn btn--primary"
            type="button"
            :disabled="busy || detail.progress.pending > 0"
            :title="detail.progress.pending > 0 ? '仍有待办任务，不能结束路径' : '结束路径'"
            @click="openComplete"
          >
            结束路径
          </button>
        </template>
      </template>
    </AppModal>

    <AppModal v-model="enrollOpen" title="患者入径" subtitle="仅住院且在院患者可入径；同一就诊只能有一条进行中的路径" width="520px">
      <label class="field">
        <span class="label">住院就诊号</span>
        <input v-model.trim="enrollVisitNo" class="input" placeholder="例如 V20261031000011" />
      </label>
      <label class="field">
        <span class="label">路径模板（仅已发布）</span>
        <select v-model="enrollTemplateId" class="select">
          <option value="">选择模板…</option>
          <option v-for="t in publishedTemplates" :key="t.id" :value="t.id">
            {{ t.name }}（{{ t.code }} · {{ t.dayCount }} 天）
          </option>
        </select>
      </label>
      <template #footer>
        <button class="btn" type="button" @click="enrollOpen = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="busy" @click="submitEnroll">确认入径</button>
      </template>
    </AppModal>

    <AppModal v-model="createOpen" title="新建路径模板" subtitle="保存为草稿；节点按天维护，发布后不可修改" width="960px">
      <div class="form-row">
        <label class="field f-q">
          <span class="label">编码</span>
          <input v-model.trim="newCode" class="input" maxlength="32" placeholder="CP-XXX" />
        </label>
        <label class="field f-grow">
          <span class="label">名称</span>
          <input v-model.trim="newName" class="input" maxlength="100" placeholder="例如 急性阑尾炎（成人）" />
        </label>
        <label class="field f-q">
          <span class="label">诊断编码</span>
          <input v-model.trim="newDiagnosis" class="input" maxlength="32" placeholder="K35.900" />
        </label>
        <button class="btn btn--sm" type="button" @click="addNode">添加节点</button>
      </div>

      <div v-if="newNodes.length === 0" class="empty-nodes">还没有节点：先添加「第几天 + 顺序 + 名称 + 分类」。</div>
      <div v-else class="table-wrap">
        <table class="table">
          <thead>
            <tr>
              <th style="width: 70px">天</th>
              <th style="width: 70px">顺序</th>
              <th>任务名称</th>
              <th style="width: 130px">分类</th>
              <th style="width: 70px">必做</th>
              <th>说明</th>
              <th style="width: 70px">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(n, idx) in newNodes" :key="idx">
              <td><input v-model.number="n.dayNo" type="number" min="1" max="60" class="input input--sm" /></td>
              <td><input v-model.number="n.seq" type="number" min="1" max="99" class="input input--sm" /></td>
              <td><input v-model.trim="n.name" class="input input--sm" maxlength="100" placeholder="任务名称" /></td>
              <td>
                <select v-model="n.category" class="select select--sm">
                  <option v-for="c in CATEGORIES" :key="c.value" :value="c.value">{{ c.label }}</option>
                </select>
              </td>
              <td><input v-model="n.required" type="checkbox" /></td>
              <td><input v-model.trim="n.description" class="input input--sm" maxlength="500" placeholder="可选" /></td>
              <td><button class="btn btn--sm btn--ghost" type="button" @click="removeNode(idx)">移除</button></td>
            </tr>
          </tbody>
        </table>
      </div>

      <template #footer>
        <button class="btn" type="button" @click="createOpen = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="busy || newNodes.length === 0" @click="submitCreate">
          保存草稿
        </button>
      </template>
    </AppModal>

    <AppModal v-model="templateOpen" title="模板节点" :subtitle="templateDetail?.template.name" width="860px">
      <TableSkeleton v-if="templateLoading" :rows="4" />
      <template v-else-if="templateDetail">
        <div class="detail-meta">
          <span class="mono">{{ templateDetail.template.code }}</span>
          <span>诊断 {{ templateDetail.template.diagnosisCode || '—' }}</span>
          <StatusPill :value="templateDetail.template.status" :label="templateStatusLabel(templateDetail.template.status)" domain="clinical" />
          <span>{{ templateDetail.template.dayCount }} 天 · {{ templateDetail.nodes.length }} 个节点</span>
        </div>
        <div class="table-wrap">
          <table class="table">
            <thead>
              <tr><th class="is-num">天</th><th class="is-num">顺序</th><th>任务</th><th>分类</th><th>必做</th><th>说明</th></tr>
            </thead>
            <tbody>
              <tr v-for="n in templateDetail.nodes" :key="n.id">
                <td class="is-num" data-primary>D{{ n.dayNo }}</td>
                <td class="is-num" data-label="顺序">{{ n.seq }}</td>
                <td data-label="任务">{{ n.name }}</td>
                <td data-label="分类">{{ categoryLabel(n.category) }}</td>
                <td data-label="必做">{{ n.required ? '是' : '否' }}</td>
                <td data-label="说明">{{ n.description || '—' }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </template>
      <template #footer>
        <button class="btn" type="button" @click="templateOpen = false">关闭</button>
      </template>
    </AppModal>

    <AppModal v-model="skipOpen" title="跳过任务（变异）" subtitle="跳过必须记录原因，进入路径变异统计" width="480px">
      <label class="field">
        <span class="label">变异原因</span>
        <input v-model.trim="skipNote" class="input" maxlength="200" placeholder="例如：患者拒绝检查 / 转 ICU" />
      </label>
      <template #footer>
        <button class="btn" type="button" @click="skipOpen = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="busy" @click="submitSkip">确认跳过</button>
      </template>
    </AppModal>

    <AppModal v-model="exitOpen" title="退出临床路径" subtitle="退出即变异，必须填写原因" width="480px">
      <label class="field">
        <span class="label">退出原因</span>
        <input v-model.trim="exitReason" class="input" maxlength="200" placeholder="例如：病情变化转 ICU" />
      </label>
      <template #footer>
        <button class="btn" type="button" @click="exitOpen = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="busy" @click="submitExit">确认退出</button>
      </template>
    </AppModal>

    <AppModal v-model="completeOpen" title="结束临床路径" subtitle="所有任务已处理，确认结束本次路径" width="480px">
      <label class="field">
        <span class="label">备注（可选）</span>
        <input v-model.trim="completeNote" class="input" maxlength="200" placeholder="例如：如期完成，准予出院" />
      </label>
      <template #footer>
        <button class="btn" type="button" @click="completeOpen = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="busy" @click="submitComplete">确认结束</button>
      </template>
    </AppModal>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import AppIcon from '@/components/ui/AppIcon.vue'
import AppModal from '@/components/ui/AppModal.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import KpiCard from '@/components/ui/KpiCard.vue'
import LoadError from '@/components/ui/LoadError.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import StatusPill from '@/components/ui/StatusPill.vue'
import TableSkeleton from '@/components/ui/TableSkeleton.vue'
import { clinicalApi } from '@/api'
import { errorText } from '@/api/http'
import type {
  PathwayCategory,
  PathwayInstance,
  PathwayInstanceStatus,
  PathwayInstanceView,
  PathwayTask,
  PathwayTaskStatus,
  PathwayTemplate,
  PathwayTemplateStatus,
  PathwayTemplateView,
} from '@/api/types'
import { hasPermission, session } from '@/stores/session'
import { toast } from '@/stores/toast'
import { date, dateTime } from '@/utils/format'

const role = session.roleLabel
const canManage = computed(() => hasPermission('inpatient:pathway:manage'))
const canExecute = computed(() => hasPermission('inpatient:pathway:execute'))

const CATEGORIES: { value: PathwayCategory; label: string }[] = [
  { value: 'ASSESSMENT', label: '评估' },
  { value: 'ORDER', label: '医嘱' },
  { value: 'NURSING', label: '护理' },
  { value: 'EXAM', label: '检查检验' },
  { value: 'SURGERY', label: '手术' },
  { value: 'OTHER', label: '其他' },
]

const loading = ref(false)
const busy = ref(false)
const loadError = ref('')
const instances = ref<PathwayInstance[]>([])
const templates = ref<PathwayTemplate[]>([])
const statusFilter = ref('')
const keyword = ref('')

const counts = computed(() => ({
  active: instances.value.filter((i) => i.status === 'ACTIVE').length,
  completed: instances.value.filter((i) => i.status === 'COMPLETED').length,
  exited: instances.value.filter((i) => i.status === 'EXITED').length,
}))

const publishedTemplates = computed(() => templates.value.filter((t) => t.status === 'PUBLISHED'))

const detailOpen = ref(false)
const detailLoading = ref(false)
const detailError = ref('')
const detail = ref<PathwayInstanceView | null>(null)

const enrollOpen = ref(false)
const enrollVisitNo = ref('')
const enrollTemplateId = ref('')

const createOpen = ref(false)
const newCode = ref('')
const newName = ref('')
const newDiagnosis = ref('')
interface NewNode {
  dayNo: number
  seq: number
  name: string
  category: PathwayCategory
  required: boolean
  description: string
}
const newNodes = ref<NewNode[]>([])

const templateOpen = ref(false)
const templateLoading = ref(false)
const templateDetail = ref<PathwayTemplateView | null>(null)

const skipOpen = ref(false)
const skipNote = ref('')
const skipTaskId = ref('')

const exitOpen = ref(false)
const exitReason = ref('')

const completeOpen = ref(false)
const completeNote = ref('')

onMounted(loadAll)

function templateStatusLabel(status: PathwayTemplateStatus): string {
  return { DRAFT: '草稿', PUBLISHED: '已发布', DISABLED: '已停用' }[status]
}

function instanceStatusLabel(status: PathwayInstanceStatus): string {
  return { ACTIVE: '在径', COMPLETED: '已完成', EXITED: '已退出' }[status]
}

function taskStatusLabel(status: PathwayTaskStatus): string {
  return { PENDING: '待执行', DONE: '已完成', SKIPPED: '已跳过' }[status]
}

function categoryLabel(category: PathwayCategory): string {
  return CATEGORIES.find((c) => c.value === category)?.label ?? category
}

async function loadAll() {
  await Promise.all([loadInstances(), loadTemplates()])
}

async function loadInstances() {
  loading.value = true
  loadError.value = ''
  try {
    instances.value = await clinicalApi.pathwayInstances({
      status: statusFilter.value || undefined,
      limit: 50,
    })
  } catch (e) {
    loadError.value = errorText(e)
    instances.value = []
  } finally {
    loading.value = false
  }
}

async function loadTemplates() {
  try {
    templates.value = await clinicalApi.pathwayTemplates({
      keyword: keyword.value || undefined,
      limit: 50,
    })
  } catch (e) {
    toast.error(errorText(e))
  }
}

async function openDetail(id: string) {
  detailOpen.value = true
  detailLoading.value = true
  detailError.value = ''
  try {
    detail.value = await clinicalApi.pathwayInstance(id)
  } catch (e) {
    detailError.value = errorText(e)
    detail.value = null
  } finally {
    detailLoading.value = false
  }
}

async function reloadDetail() {
  if (detail.value) {
    await openDetail(detail.value.instance.id)
  }
}

function openEnroll() {
  enrollVisitNo.value = ''
  enrollTemplateId.value = publishedTemplates.value[0]?.id ?? ''
  enrollOpen.value = true
}

async function submitEnroll() {
  if (busy.value) return
  if (!enrollVisitNo.value) {
    toast.error('请填写住院就诊号')
    return
  }
  if (!enrollTemplateId.value) {
    toast.error('请选择已发布的路径模板')
    return
  }
  busy.value = true
  try {
    const created = await clinicalApi.enrollPathway({
      visitNo: enrollVisitNo.value,
      templateId: enrollTemplateId.value,
    })
    enrollOpen.value = false
    toast.ok(`入径成功：${created.instance.instanceNo}`)
    await loadInstances()
    await openDetail(created.instance.id)
  } catch (e) {
    toast.error(errorText(e))
  } finally {
    busy.value = false
  }
}

function openCreate() {
  newCode.value = ''
  newName.value = ''
  newDiagnosis.value = ''
  newNodes.value = [
    { dayNo: 1, seq: 1, name: '', category: 'ASSESSMENT', required: true, description: '' },
  ]
  createOpen.value = true
}

function addNode() {
  const last = newNodes.value[newNodes.value.length - 1]
  newNodes.value.push({
    dayNo: last?.dayNo ?? 1,
    seq: (last?.seq ?? 0) + 1,
    name: '',
    category: 'ASSESSMENT',
    required: true,
    description: '',
  })
}

function removeNode(index: number) {
  newNodes.value.splice(index, 1)
}

async function submitCreate() {
  if (busy.value) return
  if (!newCode.value || !newName.value) {
    toast.error('请填写路径编码与名称')
    return
  }
  if (newNodes.value.some((n) => !n.name || !n.dayNo || !n.seq)) {
    toast.error('节点必须填写天、顺序与名称')
    return
  }
  busy.value = true
  try {
    const created = await clinicalApi.createPathwayTemplate({
      code: newCode.value,
      name: newName.value,
      diagnosisCode: newDiagnosis.value || undefined,
      nodes: newNodes.value.map((n) => ({
        dayNo: n.dayNo,
        seq: n.seq,
        name: n.name,
        category: n.category,
        required: n.required,
        description: n.description || undefined,
      })),
    })
    createOpen.value = false
    toast.ok(`模板 ${created.template.code} 已保存为草稿，发布后即可入径`)
    await loadTemplates()
  } catch (e) {
    toast.error(errorText(e))
  } finally {
    busy.value = false
  }
}

async function openTemplate(id: string) {
  templateOpen.value = true
  templateLoading.value = true
  templateDetail.value = null
  try {
    templateDetail.value = await clinicalApi.pathwayTemplate(id)
  } catch (e) {
    toast.error(errorText(e))
    templateOpen.value = false
  } finally {
    templateLoading.value = false
  }
}

async function publishTemplate(t: PathwayTemplate) {
  if (busy.value) return
  busy.value = true
  try {
    await clinicalApi.publishPathwayTemplate(t.id)
    toast.ok(`模板 ${t.code} 已发布`)
    await loadTemplates()
  } catch (e) {
    toast.error(errorText(e))
  } finally {
    busy.value = false
  }
}

async function disableTemplate(t: PathwayTemplate) {
  if (busy.value) return
  busy.value = true
  try {
    await clinicalApi.disablePathwayTemplate(t.id)
    toast.ok(`模板 ${t.code} 已停用，在途患者不受影响`)
    await loadTemplates()
  } catch (e) {
    toast.error(errorText(e))
  } finally {
    busy.value = false
  }
}

async function completeTask(t: PathwayTask) {
  if (busy.value || !detail.value) return
  busy.value = true
  try {
    detail.value = await clinicalApi.updatePathwayTask(detail.value.instance.id, t.id, 'DONE')
    toast.ok(`已完成：${t.name}`)
    await loadInstances()
  } catch (e) {
    toast.error(errorText(e))
  } finally {
    busy.value = false
  }
}

function openSkip(t: PathwayTask) {
  skipTaskId.value = t.id
  skipNote.value = ''
  skipOpen.value = true
}

async function submitSkip() {
  if (busy.value || !detail.value) return
  if (!skipNote.value) {
    toast.error('跳过任务必须填写原因')
    return
  }
  busy.value = true
  try {
    detail.value = await clinicalApi.updatePathwayTask(
      detail.value.instance.id,
      skipTaskId.value,
      'SKIPPED',
      skipNote.value,
    )
    skipOpen.value = false
    toast.ok('已跳过并记录变异原因')
    await loadInstances()
  } catch (e) {
    toast.error(errorText(e))
  } finally {
    busy.value = false
  }
}

function openExit() {
  exitReason.value = ''
  exitOpen.value = true
}

async function submitExit() {
  if (busy.value || !detail.value) return
  if (!exitReason.value) {
    toast.error('退出路径必须填写原因')
    return
  }
  busy.value = true
  try {
    detail.value = await clinicalApi.exitPathway(detail.value.instance.id, exitReason.value)
    exitOpen.value = false
    toast.ok('已退出路径并保留原因')
    await loadInstances()
  } catch (e) {
    toast.error(errorText(e))
  } finally {
    busy.value = false
  }
}

function openComplete() {
  completeNote.value = ''
  completeOpen.value = true
}

async function submitComplete() {
  if (busy.value || !detail.value) return
  busy.value = true
  try {
    detail.value = await clinicalApi.completePathway(
      detail.value.instance.id,
      completeNote.value || undefined,
    )
    completeOpen.value = false
    toast.ok('路径已完成')
    await loadInstances()
  } catch (e) {
    toast.error(errorText(e))
  } finally {
    busy.value = false
  }
}
</script>

<style scoped>
.detail-meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--s2) var(--s4);
  padding: var(--s3) 0;
  color: var(--muted);
  font-size: var(--fs-small);
}

.progress-line {
  display: flex;
  flex-wrap: wrap;
  gap: var(--s4);
  padding: var(--s2) 0 var(--s3);
  font-size: var(--fs-small);
}

.form-row {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-end;
  gap: var(--s3);
  margin-bottom: var(--s4);
}

.f-grow {
  flex: 1 1 180px;
  min-width: 160px;
}

.f-q {
  flex: 0 0 130px;
}

.input--sm,
.select--sm {
  padding: 4px 8px;
  font-size: var(--fs-small);
}

.empty-nodes {
  padding: var(--s5);
  color: var(--muted);
  text-align: center;
  border: 1px dashed var(--line);
  border-radius: var(--r-md);
}

.small {
  font-size: var(--fs-small);
}

.ok {
  color: var(--ok);
}

.warn {
  color: var(--warn);
}

.danger {
  color: var(--danger);
}
</style>
