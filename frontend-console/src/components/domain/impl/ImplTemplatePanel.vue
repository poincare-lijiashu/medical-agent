<template>
  <div class="panel">
    <div class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">实施模板</span>
        <span class="badge badge--neutral">{{ templates.length }}</span>
        <span class="spacer" />
        <span class="sub">阶段权重之和必须为 100；项目创建时复制任务快照</span>
        <button v-if="canManage" class="btn btn--sm btn--primary" type="button" @click="openCreate">
          <AppIcon name="plus" :size="13" /> 新建模板
        </button>
      </div>
      <TableSkeleton v-if="loading" :rows="3" />
      <LoadError v-else-if="error" :message="error" :retrying="loading" @retry="load" />
      <EmptyState
        v-else-if="templates.length === 0"
        title="还没有实施模板"
        desc="内置模板通常随迁移写入；也可以按院方方法论自建阶段与任务。"
      />
      <div v-else class="table-wrap">
        <table class="table table--compact">
          <thead>
            <tr>
              <th>编码</th>
              <th>名称</th>
              <th>阶段</th>
              <th>任务数</th>
              <th>权重合计</th>
              <th>状态</th>
              <th>创建人</th>
              <th v-if="canManage" style="width: 100px">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="t in templates" :key="t.id">
              <td class="mono" data-primary>{{ t.code }}</td>
              <td data-label="名称">{{ t.name }}</td>
              <td data-label="阶段">{{ t.phases.length }}</td>
              <td data-label="任务数">{{ taskCount(t) }}</td>
              <td data-label="权重合计">{{ weightSum(t) }}</td>
              <td data-label="状态">
                <span class="badge" :class="t.status === 'ACTIVE' ? 'badge--ok' : 'badge--neutral'">
                  {{ t.status === 'ACTIVE' ? '启用' : '停用' }}
                </span>
              </td>
              <td data-label="创建人">{{ t.createdBy }}</td>
              <td v-if="canManage" data-label="操作">
                <button class="btn btn--sm" type="button" @click="toggle(t)">
                  {{ t.status === 'ACTIVE' ? '停用' : '启用' }}
                </button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <AppModal v-model="createOpen" title="新建实施模板" width="860px">
      <div class="form-row">
        <label class="field f-q">
          <span class="label">编码 <span class="req">*</span></span>
          <input v-model.trim="form.code" class="input" maxlength="64" placeholder="STD-HOSP-v2" />
        </label>
        <label class="field f-grow">
          <span class="label">名称 <span class="req">*</span></span>
          <input v-model.trim="form.name" class="input" maxlength="128" />
        </label>
      </div>
      <label class="field">
        <span class="label">说明</span>
        <input v-model.trim="form.description" class="input" maxlength="1000" />
      </label>

      <div class="phase-editor">
        <div class="phase-editor-head">
          <span class="label">阶段与任务</span>
          <span class="sub">当前权重合计 {{ weightTotal }}</span>
          <span class="spacer" />
          <button class="btn btn--sm" type="button" @click="addPhase">添加阶段</button>
        </div>
        <div v-for="(phase, index) in form.phases" :key="index" class="phase-card">
          <div class="form-row">
            <label class="field f-q">
              <span class="label">阶段名称 <span class="req">*</span></span>
              <input v-model.trim="phase.name" class="input" maxlength="128" />
            </label>
            <label class="field f-q">
              <span class="label">权重 <span class="req">*</span></span>
              <input v-model.number="phase.weight" class="input" type="number" min="1" max="100" />
            </label>
            <span class="spacer" />
            <button class="btn btn--sm" type="button" @click="removePhase(index)">移除</button>
          </div>
          <label class="field">
            <span class="label">任务（每行一个：标题 | 必填/选填 | 负责角色 | 证据提示）</span>
            <textarea
              v-model="phase.tasksText"
              class="textarea"
              rows="4"
              placeholder="项目章程与范围确认 | 必填 | OPS_MANAGER | 签字章程地址"
            />
          </label>
        </div>
      </div>

      <template #footer>
        <button class="btn" type="button" :disabled="busy" @click="createOpen = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="busy || weightTotal !== 100" @click="submit">
          {{ busy ? '创建中…' : '创建模板' }}
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
import { implApi, type ImplPhaseInput, type ImplTemplate } from '@/api/impl'
import { errorText } from '@/api/http'
import { hasPermission } from '@/stores/session'
import { toast } from '@/stores/toast'

const emit = defineEmits<{ changed: [] }>()
const canManage = hasPermission('ops:impl:manage')

const templates = ref<ImplTemplate[]>([])
const loading = ref(false)
const error = ref('')

const createOpen = ref(false)
const busy = ref(false)
const form = reactive({
  code: '',
  name: '',
  description: '',
  phases: [] as { name: string; weight: number; tasksText: string }[],
})

const weightTotal = computed(() => form.phases.reduce((sum, p) => sum + (Number(p.weight) || 0), 0))

onMounted(load)

async function load() {
  loading.value = true
  error.value = ''
  try {
    templates.value = await implApi.templates(false)
  } catch (e) {
    error.value = errorText(e)
  } finally {
    loading.value = false
  }
}

function taskCount(t: ImplTemplate): number {
  return t.phases.reduce((sum, p) => sum + p.tasks.length, 0)
}

function weightSum(t: ImplTemplate): number {
  return t.phases.reduce((sum, p) => sum + p.weight, 0)
}

async function toggle(t: ImplTemplate) {
  try {
    await implApi.templateStatus(t.id, t.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE')
    toast.ok(t.status === 'ACTIVE' ? '模板已停用' : '模板已启用', t.code)
    await load()
    emit('changed')
  } catch (e) {
    toast.error('操作失败', errorText(e))
  }
}

function openCreate() {
  form.code = ''
  form.name = ''
  form.description = ''
  form.phases = [
    { name: '项目启动与范围', weight: 20, tasksText: '' },
    { name: '上线与移交', weight: 80, tasksText: '' },
  ]
  createOpen.value = true
}

function addPhase() {
  form.phases.push({ name: '', weight: 10, tasksText: '' })
}

function removePhase(index: number) {
  form.phases.splice(index, 1)
}

async function submit() {
  if (!form.code || !form.name) {
    toast.warn('请填写模板编码与名称')
    return
  }
  if (weightTotal.value !== 100) {
    toast.warn('阶段权重之和必须为 100')
    return
  }
  const phases: ImplPhaseInput[] = []
  let taskNo = 1
  for (let i = 0; i < form.phases.length; i += 1) {
    const phase = form.phases[i]
    if (!phase.name.trim()) {
      toast.warn(`第 ${i + 1} 个阶段缺少名称`)
      return
    }
    const tasks: ImplPhaseInput['tasks'] = []
    for (const line of phase.tasksText.split('\n')) {
      const text = line.trim()
      if (!text) continue
      const parts = text.split('|').map((s) => s.trim())
      if (!parts[0]) continue
      tasks.push({
        taskNo: taskNo++,
        title: parts[0],
        required: parts[1] !== '选填' && parts[1] !== '非必填',
        ownerRole: parts[2] || undefined,
        evidenceHint: parts[3] || undefined,
      })
    }
    if (tasks.length === 0) {
      toast.warn(`阶段「${phase.name}」至少需要一个任务`)
      return
    }
    phases.push({
      phaseNo: i + 1,
      name: phase.name.trim(),
      weight: Number(phase.weight),
      tasks,
    })
  }
  busy.value = true
  try {
    await implApi.createTemplate({
      code: form.code,
      name: form.name,
      description: form.description || undefined,
      phases,
    })
    createOpen.value = false
    toast.ok('模板已创建', form.code)
    await load()
    emit('changed')
  } catch (e) {
    toast.error('创建失败', errorText(e))
  } finally {
    busy.value = false
  }
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
  flex: 0 1 160px;
  min-width: 120px;
}
.form-row {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: flex-end;
}
.req {
  color: var(--danger-ink, #b91c1c);
}
.phase-editor {
  margin-top: 10px;
}
.phase-editor-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 8px;
}
.phase-card {
  border: 1px solid var(--line, #e5e7eb);
  border-radius: 8px;
  padding: 10px 12px;
  margin-bottom: 10px;
}
</style>
