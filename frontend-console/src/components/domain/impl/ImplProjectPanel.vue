<template>
  <div class="panel">
    <div class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">实施项目</span>
        <span class="badge badge--neutral">{{ total }}</span>
        <span class="spacer" />
        <label class="field f-q">
          <span class="label">状态</span>
          <select v-model="filter.status" class="input" @change="reload">
            <option value="">全部</option>
            <option value="PLANNED">计划中</option>
            <option value="IN_PROGRESS">实施中</option>
            <option value="GO_LIVE">已上线</option>
            <option value="ACCEPTED">已验收</option>
            <option value="CANCELLED">已取消</option>
          </select>
        </label>
        <button class="btn btn--sm" type="button" :disabled="loading" @click="loadProjects">
          <AppIcon name="refresh" :size="14" /> 刷新
        </button>
        <button v-if="canManage" class="btn btn--sm btn--primary" type="button" @click="openCreate">
          <AppIcon name="plus" :size="13" /> 新建项目
        </button>
      </div>
      <TableSkeleton v-if="loading" :rows="4" />
      <LoadError v-else-if="error" :message="error" :retrying="loading" @retry="loadProjects" />
      <EmptyState
        v-else-if="projects.length === 0"
        title="还没有实施项目"
        desc="从实施模板创建项目：任务清单会按模板复制，后续模板修改不影响在建项目。"
      />
      <div v-else class="table-wrap">
        <table class="table table--compact">
          <thead>
            <tr>
              <th>项目号</th>
              <th>医院 / 院区</th>
              <th>模板</th>
              <th>状态</th>
              <th>负责人</th>
              <th>目标上线</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="p in projects" :key="p.id" class="clickable" @click="openProject(p)">
              <td class="mono" data-primary>{{ p.projectNo }}</td>
              <td data-label="医院">{{ p.hospitalName }}</td>
              <td data-label="模板">{{ p.templateCode }}</td>
              <td data-label="状态"><span class="badge" :class="statusBadge(p.status)">{{ statusText(p.status) }}</span></td>
              <td data-label="负责人">{{ p.owner }}</td>
              <td data-label="目标上线">{{ p.targetDate || '—' }}</td>
              <td data-label="操作">
                <button class="btn btn--sm" type="button" @click.stop="openProject(p)">查看</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <div v-if="total > filter.size" class="pager">
        <button class="btn btn--sm" type="button" :disabled="filter.page <= 1" @click="page(-1)">上一页</button>
        <span class="sub">{{ filter.page }} / {{ Math.ceil(total / filter.size) }}</span>
        <button class="btn btn--sm" type="button" :disabled="filter.page * filter.size >= total" @click="page(1)">
          下一页
        </button>
      </div>
    </div>

    <!-- 项目详情 -->
    <div v-if="selected" class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">{{ selected.projectNo }} · {{ selected.hospitalName }}</span>
        <span class="badge" :class="statusBadge(selected.status)">{{ statusText(selected.status) }}</span>
        <span class="spacer" />
        <button
          v-if="canManage && selected.status === 'PLANNED'"
          class="btn btn--sm btn--primary"
          type="button"
          :disabled="actionBusy"
          @click="startProject"
        >
          <AppIcon name="play" :size="14" /> 开始实施
        </button>
        <button
          v-if="canVerify && selected.status === 'IN_PROGRESS'"
          class="btn btn--sm btn--primary"
          type="button"
          :disabled="actionBusy || selected.owner === myName || !selected.readiness?.ready"
          :title="approveTitle"
          @click="openGoLive"
        >
          批准上线
        </button>
        <button
          v-if="canVerify && selected.status === 'GO_LIVE'"
          class="btn btn--sm btn--primary"
          type="button"
          :disabled="actionBusy || selected.owner === myName"
          @click="openAccept"
        >
          验收签署
        </button>
        <button
          v-if="canManage && (selected.status === 'PLANNED' || selected.status === 'IN_PROGRESS')"
          class="btn btn--sm"
          type="button"
          :disabled="actionBusy"
          @click="openCancel"
        >
          取消项目
        </button>
      </div>
      <p v-if="selected.owner === myName && (selected.status === 'IN_PROGRESS' || selected.status === 'GO_LIVE')" class="hint">
        你是项目负责人，不能自己批准上线或验收；请由运营经理或其他负责人复核。
      </p>

      <div v-if="selected.readiness" class="readiness">
        <div class="readiness-head">
          <span class="score">{{ selected.readiness.score }}</span>
          <span class="sub">/ {{ selected.readiness.minScore }} 就绪分</span>
          <span class="badge" :class="selected.readiness.ready ? 'badge--ok' : 'badge--danger'">
            {{ selected.readiness.ready ? '具备上线条件' : '未达上线条件' }}
          </span>
          <span class="spacer" />
          <span class="sub">
            培训通过率 {{ selected.readiness.trainingPassRate }}%（门槛 {{ selected.readiness.trainingThreshold }}%）
          </span>
        </div>
        <div class="phase-bars">
          <div v-for="ph in selected.readiness.phases" :key="ph.phaseNo" class="phase">
            <div class="phase-top">
              <span>{{ ph.name }}</span>
              <span class="sub">{{ ph.done }}/{{ ph.total }} · 权重 {{ ph.weight }}</span>
            </div>
            <div class="bar"><span :style="{ width: `${ph.ratio}%` }" /></div>
          </div>
        </div>
        <ul v-if="selected.readiness.missingRequiredTasks.length" class="issues">
          <li v-for="item in selected.readiness.missingRequiredTasks" :key="item">必填未完成：{{ item }}</li>
        </ul>
        <ul v-if="selected.readiness.warnings.length" class="issues issues--warn">
          <li v-for="item in selected.readiness.warnings" :key="item">{{ item }}</li>
        </ul>
        <div v-if="selected.readiness.training.length" class="training-mini">
          <span v-for="c in selected.readiness.training" :key="c.courseCode" class="badge" :class="c.met ? 'badge--ok' : 'badge--warn'">
            {{ c.courseCode }} {{ c.passRate }}%
          </span>
        </div>
      </div>

      <div class="table-wrap">
        <table class="table table--compact">
          <thead>
            <tr>
              <th>阶段</th>
              <th>序号</th>
              <th>任务</th>
              <th>必填</th>
              <th>状态</th>
              <th>负责人角色</th>
              <th>执行人</th>
              <th>证据</th>
              <th v-if="canManage" style="width: 130px">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="t in selected.tasks" :key="t.id">
              <td data-primary>{{ t.phaseName }}</td>
              <td class="mono">{{ t.taskNo }}</td>
              <td data-label="任务">{{ t.title }}</td>
              <td data-label="必填">{{ t.required ? '是' : '否' }}</td>
              <td data-label="状态"><span class="badge" :class="taskBadge(t.status)">{{ taskText(t.status) }}</span></td>
              <td data-label="角色">{{ t.ownerRole || '—' }}</td>
              <td data-label="执行人">{{ t.assignee || t.completedBy || '—' }}</td>
              <td data-label="证据" class="ellipsis" :title="t.evidence || ''">{{ t.evidence || t.blockedReason || '—' }}</td>
              <td v-if="canManage" data-label="操作">
                <button
                  class="btn btn--sm"
                  type="button"
                  :disabled="selected.status !== 'IN_PROGRESS' && selected.status !== 'PLANNED'"
                  @click="openTask(t)"
                >
                  登记
                </button>
                <button
                  v-if="canVerify && t.status !== 'WAIVED'"
                  class="btn btn--sm"
                  type="button"
                  :disabled="selected.status !== 'IN_PROGRESS' && selected.status !== 'PLANNED'"
                  @click="openWaive(t)"
                >
                  豁免
                </button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <div v-if="events.length" class="events">
        <div class="events-title">项目轨迹</div>
        <div v-for="e in events" :key="e.id" class="event">
          <span class="mono sub">{{ fmtTime(e.ts) }}</span>
          <span class="badge badge--neutral">{{ e.action }}</span>
          <span>{{ e.actor }}</span>
          <span class="sub">{{ e.note }}</span>
        </div>
      </div>
    </div>

    <!-- 新建项目 -->
    <AppModal v-model="createOpen" title="新建实施项目" width="760px">
      <div class="form-row">
        <label class="field f-grow">
          <span class="label">实施模板 <span class="req">*</span></span>
          <select v-model="createForm.templateCode" class="input">
            <option value="">请选择</option>
            <option v-for="t in templates" :key="t.code" :value="t.code">{{ t.code }} · {{ t.name }}</option>
          </select>
        </label>
        <label class="field f-grow">
          <span class="label">医院 / 院区 <span class="req">*</span></span>
          <input v-model.trim="createForm.hospitalName" class="input" maxlength="128" />
        </label>
      </div>
      <div class="form-row">
        <label class="field f-q">
          <span class="label">租户编码</span>
          <input v-model.trim="createForm.tenantCode" class="input" maxlength="64" placeholder="tenant-001" />
        </label>
        <label class="field f-q">
          <span class="label">负责人</span>
          <input v-model.trim="createForm.owner" class="input" maxlength="64" placeholder="默认当前操作人" />
        </label>
        <label class="field f-q">
          <span class="label">开始日期</span>
          <input v-model="createForm.startDate" class="input" type="date" />
        </label>
        <label class="field f-q">
          <span class="label">目标上线</span>
          <input v-model="createForm.targetDate" class="input" type="date" />
        </label>
      </div>
      <div class="field">
        <span class="label">必修培训课程（未配置则不参与就绪判定）</span>
        <div class="course-picks">
          <label v-for="c in courses" :key="c.code" class="check-row">
            <input v-model="createForm.courseCodes" type="checkbox" :value="c.code" />
            <span>{{ c.code }} · {{ c.title }}</span>
          </label>
        </div>
      </div>
      <template #footer>
        <button class="btn" type="button" :disabled="createBusy" @click="createOpen = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="createBusy" @click="submitCreate">
          {{ createBusy ? '创建中…' : '创建' }}
        </button>
      </template>
    </AppModal>

    <!-- 任务登记 -->
    <AppModal v-model="taskOpen" title="登记任务" width="640px">
      <p class="hint">{{ taskForm.title }}（任务 {{ taskForm.taskNo }}，{{ taskForm.phaseName }}）</p>
      <div class="form-row">
        <label class="field f-q">
          <span class="label">状态 <span class="req">*</span></span>
          <select v-model="taskForm.status" class="input">
            <option value="PENDING">待处理</option>
            <option value="IN_PROGRESS">进行中</option>
            <option value="BLOCKED">阻塞</option>
            <option value="DONE">已完成</option>
          </select>
        </label>
        <label class="field f-q">
          <span class="label">执行人</span>
          <input v-model.trim="taskForm.assignee" class="input" maxlength="64" />
        </label>
        <label class="field f-q">
          <span class="label">截止日期</span>
          <input v-model="taskForm.dueDate" class="input" type="date" />
        </label>
      </div>
      <label v-if="taskForm.status === 'DONE'" class="field">
        <span class="label">完成证据 <span class="req">*</span></span>
        <input v-model.trim="taskForm.evidence" class="input" maxlength="1000" :placeholder="taskForm.evidenceHint || '签字件 / 截图 / 报告地址'" />
      </label>
      <label v-if="taskForm.status === 'BLOCKED'" class="field">
        <span class="label">阻塞原因 <span class="req">*</span></span>
        <textarea v-model.trim="taskForm.blockedReason" class="textarea" rows="2" maxlength="1000" />
      </label>
      <label class="field">
        <span class="label">备注</span>
        <input v-model.trim="taskForm.note" class="input" maxlength="1000" />
      </label>
      <template #footer>
        <button class="btn" type="button" :disabled="taskBusy" @click="taskOpen = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="taskBusy" @click="submitTask">
          {{ taskBusy ? '保存中…' : '保存' }}
        </button>
      </template>
    </AppModal>

    <!-- 豁免 -->
    <AppModal v-model="waiveOpen" title="任务豁免" width="620px">
      <p class="hint">{{ waiveForm.title }}：豁免会计入就绪报告的例外数，必须写清依据。</p>
      <label class="field">
        <span class="label">豁免原因 <span class="req">*</span></span>
        <textarea v-model.trim="waiveReason" class="textarea" rows="3" maxlength="1000" />
      </label>
      <template #footer>
        <button class="btn" type="button" :disabled="waiveBusy" @click="waiveOpen = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="waiveBusy" @click="submitWaive">
          {{ waiveBusy ? '提交中…' : '确认豁免' }}
        </button>
      </template>
    </AppModal>

    <!-- 上线批准 -->
    <AppModal v-model="goLiveOpen" title="批准上线" width="620px">
      <p class="hint">批准前系统会再次校验就绪分、必填任务与培训通过率；复核人不能是项目负责人。</p>
      <label class="field">
        <span class="label">审批意见</span>
        <textarea v-model.trim="goLiveNote" class="textarea" rows="3" maxlength="1000" />
      </label>
      <template #footer>
        <button class="btn" type="button" :disabled="actionBusy" @click="goLiveOpen = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="actionBusy" @click="submitGoLive">
          {{ actionBusy ? '提交中…' : '确认批准' }}
        </button>
      </template>
    </AppModal>

    <!-- 验收 -->
    <AppModal v-model="acceptOpen" title="验收签署" width="620px">
      <label class="field">
        <span class="label">验收证据 <span class="req">*</span></span>
        <input v-model.trim="acceptForm.evidence" class="input" maxlength="1000" placeholder="验收报告编号 / 签署件地址" />
      </label>
      <label class="field">
        <span class="label">备注</span>
        <textarea v-model.trim="acceptForm.note" class="textarea" rows="2" maxlength="1000" />
      </label>
      <template #footer>
        <button class="btn" type="button" :disabled="actionBusy" @click="acceptOpen = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="actionBusy" @click="submitAccept">
          {{ actionBusy ? '提交中…' : '确认验收' }}
        </button>
      </template>
    </AppModal>

    <!-- 取消 -->
    <AppModal v-model="cancelOpen" title="取消实施项目" width="620px">
      <label class="field">
        <span class="label">取消原因 <span class="req">*</span></span>
        <textarea v-model.trim="cancelReason" class="textarea" rows="3" maxlength="1000" />
      </label>
      <template #footer>
        <button class="btn" type="button" :disabled="actionBusy" @click="cancelOpen = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="actionBusy" @click="submitCancel">
          {{ actionBusy ? '提交中…' : '确认取消' }}
        </button>
      </template>
    </AppModal>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import AppIcon from '@/components/ui/AppIcon.vue'
import AppModal from '@/components/ui/AppModal.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import LoadError from '@/components/ui/LoadError.vue'
import TableSkeleton from '@/components/ui/TableSkeleton.vue'
import {
  implApi,
  type ImplCourse,
  type ImplProject,
  type ImplProjectEvent,
  type ImplProjectStatus,
  type ImplProjectTask,
  type ImplTaskStatus,
  type ImplTemplate,
} from '@/api/impl'
import { errorText } from '@/api/http'
import { hasPermission, session } from '@/stores/session'
import { toast } from '@/stores/toast'

const emit = defineEmits<{ changed: [] }>()

const canManage = hasPermission('ops:impl:manage')
const canVerify = hasPermission('ops:impl:verify')
const myName = computed(() => session.user.value?.displayName ?? '')

const projects = ref<ImplProject[]>([])
const total = ref(0)
const loading = ref(false)
const error = ref('')
const filter = reactive({ status: '', page: 1, size: 20 })

const templates = ref<ImplTemplate[]>([])
const courses = ref<ImplCourse[]>([])

const selected = ref<ImplProject | null>(null)
const events = ref<ImplProjectEvent[]>([])
const actionBusy = ref(false)

const createOpen = ref(false)
const createBusy = ref(false)
const createForm = reactive({
  templateCode: '',
  hospitalName: '',
  tenantCode: '',
  owner: '',
  startDate: '',
  targetDate: '',
  courseCodes: [] as string[],
})

const taskOpen = ref(false)
const taskBusy = ref(false)
const taskForm = reactive({
  taskId: '',
  taskNo: 0,
  title: '',
  phaseName: '',
  evidenceHint: '',
  status: 'IN_PROGRESS' as 'PENDING' | 'IN_PROGRESS' | 'BLOCKED' | 'DONE',
  assignee: '',
  dueDate: '',
  evidence: '',
  blockedReason: '',
  note: '',
})

const waiveOpen = ref(false)
const waiveBusy = ref(false)
const waiveForm = reactive({ taskId: '', title: '' })
const waiveReason = ref('')

const goLiveOpen = ref(false)
const goLiveNote = ref('')

const acceptOpen = ref(false)
const acceptForm = reactive({ evidence: '', note: '' })

const cancelOpen = ref(false)
const cancelReason = ref('')

const approveTitle = computed(() => {
  if (selected.value?.owner === myName.value) return '项目负责人不能自己批准上线'
  if (!selected.value?.readiness?.ready) return '尚未达到上线就绪条件'
  return '由非负责人复核批准'
})

onMounted(async () => {
  await Promise.all([loadProjects(), loadTemplates(), loadCourses()])
})

async function loadProjects() {
  loading.value = true
  error.value = ''
  try {
    const res = await implApi.projects({
      status: filter.status || undefined,
      page: filter.page,
      size: filter.size,
    })
    projects.value = res.items
    total.value = res.total
  } catch (e) {
    error.value = errorText(e)
  } finally {
    loading.value = false
  }
}

async function loadTemplates() {
  try {
    templates.value = await implApi.templates(true)
  } catch {
    // 模板为空不阻塞项目列表
  }
}

async function loadCourses() {
  try {
    courses.value = await implApi.courses(true)
  } catch {
    // 课程为空不阻塞项目列表
  }
}

function reload() {
  filter.page = 1
  loadProjects()
}

function page(delta: number) {
  filter.page = Math.max(1, filter.page + delta)
  loadProjects()
}

async function openProject(project: ImplProject) {
  try {
    selected.value = await implApi.project(project.id)
    events.value = await implApi.projectEvents(project.id, 30)
  } catch (e) {
    toast.error('项目详情加载失败', errorText(e))
  }
}

function openCreate() {
  createForm.templateCode = templates.value[0]?.code ?? ''
  createForm.hospitalName = ''
  createForm.tenantCode = ''
  createForm.owner = ''
  createForm.startDate = toDateInput(new Date())
  createForm.targetDate = ''
  createForm.courseCodes = courses.value.filter((c) => c.required).map((c) => c.code)
  createOpen.value = true
}

async function submitCreate() {
  if (!createForm.templateCode || !createForm.hospitalName) {
    toast.warn('请选择模板并填写医院名称')
    return
  }
  createBusy.value = true
  try {
    const created = await implApi.createProject({
      templateCode: createForm.templateCode,
      hospitalName: createForm.hospitalName,
      tenantCode: createForm.tenantCode || undefined,
      owner: createForm.owner || undefined,
      startDate: createForm.startDate || undefined,
      targetDate: createForm.targetDate || undefined,
      requiredCourseCodes: createForm.courseCodes,
    })
    createOpen.value = false
    toast.ok('实施项目已创建', created.projectNo)
    await loadProjects()
    await openProject(created)
    emit('changed')
  } catch (e) {
    toast.error('创建失败', errorText(e))
  } finally {
    createBusy.value = false
  }
}

async function startProject() {
  if (!selected.value) return
  actionBusy.value = true
  try {
    selected.value = await implApi.startProject(selected.value.id)
    toast.ok('已开始实施', selected.value.projectNo)
    await loadProjects()
  } catch (e) {
    toast.error('操作失败', errorText(e))
  } finally {
    actionBusy.value = false
  }
}

function openTask(task: ImplProjectTask) {
  taskForm.taskId = task.id
  taskForm.taskNo = task.taskNo
  taskForm.title = task.title
  taskForm.phaseName = task.phaseName
  taskForm.evidenceHint = task.evidenceHint ?? ''
  taskForm.status = task.status === 'DONE' ? 'DONE' : task.status === 'BLOCKED' ? 'BLOCKED' : 'IN_PROGRESS'
  taskForm.assignee = task.assignee ?? task.completedBy ?? ''
  taskForm.dueDate = task.dueDate ?? ''
  taskForm.evidence = task.evidence ?? ''
  taskForm.blockedReason = task.blockedReason ?? ''
  taskForm.note = ''
  taskOpen.value = true
}

async function submitTask() {
  if (!selected.value) return
  if (taskForm.status === 'DONE' && !taskForm.evidence) {
    toast.warn('完成必须填写证据')
    return
  }
  if (taskForm.status === 'BLOCKED' && !taskForm.blockedReason) {
    toast.warn('阻塞必须填写原因')
    return
  }
  taskBusy.value = true
  try {
    selected.value = await implApi.updateTask(selected.value.id, taskForm.taskId, {
      status: taskForm.status === 'DONE' ? 'DONE' : taskForm.status === 'BLOCKED' ? 'BLOCKED' : taskForm.status,
      assignee: taskForm.assignee || undefined,
      dueDate: taskForm.dueDate || undefined,
      evidence: taskForm.evidence || undefined,
      blockedReason: taskForm.blockedReason || undefined,
      note: taskForm.note || undefined,
    })
    taskOpen.value = false
    toast.ok('任务已更新', `任务 ${taskForm.taskNo}`)
    events.value = await implApi.projectEvents(selected.value.id, 30)
    await loadProjects()
  } catch (e) {
    toast.error('保存失败', errorText(e))
  } finally {
    taskBusy.value = false
  }
}

function openWaive(task: ImplProjectTask) {
  waiveForm.taskId = task.id
  waiveForm.title = task.title
  waiveReason.value = ''
  waiveOpen.value = true
}

async function submitWaive() {
  if (!selected.value) return
  if (waiveReason.value.trim().length < 2) {
    toast.warn('请填写豁免原因')
    return
  }
  waiveBusy.value = true
  try {
    selected.value = await implApi.waiveTask(selected.value.id, waiveForm.taskId, waiveReason.value.trim())
    waiveOpen.value = false
    toast.ok('任务已豁免')
    events.value = await implApi.projectEvents(selected.value.id, 30)
  } catch (e) {
    toast.error('豁免失败', errorText(e))
  } finally {
    waiveBusy.value = false
  }
}

function openGoLive() {
  goLiveNote.value = ''
  goLiveOpen.value = true
}

async function submitGoLive() {
  if (!selected.value) return
  actionBusy.value = true
  try {
    selected.value = await implApi.goLive(selected.value.id, goLiveNote.value || undefined)
    goLiveOpen.value = false
    toast.ok('已批准上线', selected.value.projectNo)
    await loadProjects()
    emit('changed')
  } catch (e) {
    toast.error('批准失败', errorText(e))
  } finally {
    actionBusy.value = false
  }
}

function openAccept() {
  acceptForm.evidence = ''
  acceptForm.note = ''
  acceptOpen.value = true
}

async function submitAccept() {
  if (!selected.value) return
  if (acceptForm.evidence.trim().length < 2) {
    toast.warn('请填写验收证据')
    return
  }
  actionBusy.value = true
  try {
    selected.value = await implApi.accept(selected.value.id, acceptForm.evidence.trim(), acceptForm.note || undefined)
    acceptOpen.value = false
    toast.ok('已验收', selected.value.projectNo)
    await loadProjects()
    emit('changed')
  } catch (e) {
    toast.error('验收失败', errorText(e))
  } finally {
    actionBusy.value = false
  }
}

function openCancel() {
  cancelReason.value = ''
  cancelOpen.value = true
}

async function submitCancel() {
  if (!selected.value) return
  if (cancelReason.value.trim().length < 2) {
    toast.warn('请填写取消原因')
    return
  }
  actionBusy.value = true
  try {
    selected.value = await implApi.cancel(selected.value.id, cancelReason.value.trim())
    cancelOpen.value = false
    toast.ok('项目已取消', selected.value.projectNo)
    await loadProjects()
    emit('changed')
  } catch (e) {
    toast.error('取消失败', errorText(e))
  } finally {
    actionBusy.value = false
  }
}

function statusText(status: ImplProjectStatus): string {
  const map: Record<ImplProjectStatus, string> = {
    PLANNED: '计划中',
    IN_PROGRESS: '实施中',
    GO_LIVE: '已上线',
    ACCEPTED: '已验收',
    CANCELLED: '已取消',
  }
  return map[status]
}

function statusBadge(status: ImplProjectStatus): string {
  if (status === 'ACCEPTED' || status === 'GO_LIVE') return 'badge--ok'
  if (status === 'IN_PROGRESS') return 'badge--warn'
  if (status === 'CANCELLED') return 'badge--danger'
  return 'badge--neutral'
}

function taskText(status: ImplTaskStatus): string {
  const map: Record<ImplTaskStatus, string> = {
    PENDING: '待处理',
    IN_PROGRESS: '进行中',
    BLOCKED: '阻塞',
    DONE: '已完成',
    WAIVED: '已豁免',
  }
  return map[status]
}

function taskBadge(status: ImplTaskStatus): string {
  if (status === 'DONE') return 'badge--ok'
  if (status === 'WAIVED') return 'badge--neutral'
  if (status === 'BLOCKED') return 'badge--danger'
  if (status === 'IN_PROGRESS') return 'badge--warn'
  return 'badge--neutral'
}

function fmtTime(value?: string | null): string {
  if (!value) return '—'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN', { hour12: false })
}

function toDateInput(date: Date): string {
  const pad = (n: number) => `${n}`.padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
}
</script>

<style scoped>
.panel {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.sheet {
  padding: 14px 16px;
}
.spacer {
  flex: 1;
}
.sub {
  font-size: var(--fs-small);
  color: var(--muted);
}
.f-grow {
  flex: 1 1 220px;
  min-width: 160px;
}
.f-q {
  flex: 0 1 150px;
  min-width: 120px;
}
.form-row {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
}
.req {
  color: var(--danger-ink, #b91c1c);
}
.hint {
  margin: 6px 0 8px;
  color: var(--muted);
  font-size: var(--fs-small);
  line-height: 1.6;
}
.readiness {
  border: 1px solid var(--line, #e5e7eb);
  border-radius: 8px;
  padding: 12px;
  margin-bottom: 12px;
}
.readiness-head {
  display: flex;
  align-items: baseline;
  gap: 8px;
  flex-wrap: wrap;
}
.score {
  font-size: 30px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
}
.phase-bars {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 10px;
  margin-top: 10px;
}
.phase-top {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  font-size: var(--fs-small);
}
.bar {
  height: 6px;
  border-radius: 4px;
  background: var(--line, #e5e7eb);
  margin-top: 5px;
  overflow: hidden;
}
.bar span {
  display: block;
  height: 100%;
  background: var(--accent-ink, #1d4ed8);
}
.issues {
  margin: 10px 0 0;
  padding-left: 18px;
  font-size: var(--fs-small);
  color: var(--danger-ink, #b91c1c);
  line-height: 1.7;
}
.issues--warn {
  color: var(--warn-ink, #92400e);
}
.training-mini {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 8px;
}
.ellipsis {
  max-width: 220px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.events {
  margin-top: 12px;
  border-top: 1px dashed var(--line, #e5e7eb);
  padding-top: 10px;
}
.events-title {
  font-weight: 600;
  margin-bottom: 6px;
}
.event {
  display: flex;
  gap: 10px;
  align-items: baseline;
  font-size: var(--fs-small);
  line-height: 1.7;
}
.course-picks {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-top: 6px;
}
.check-row {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: var(--fs-body);
}
.pager {
  display: flex;
  align-items: center;
  gap: 10px;
  justify-content: flex-end;
  margin-top: 8px;
}
</style>
