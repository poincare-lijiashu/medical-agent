<template>
  <div class="page">
    <PageHeader
      title="系统管理"
      :sub="`账号与授权矩阵 · 机构与多科室绑定 · 跨域审计检索。账号启停、角色分配与密码重置均留痕，最后一个管理员不可停用。当前角色 ${role}`"
    >
      <template #actions>
        <button
          class="btn"
          type="button"
          :disabled="tab === 'users' ? usersLoading : auditLoading"
          @click="tab === 'users' ? loadUsers() : loadAudit()"
        >
          <AppIcon name="refresh" :size="15" /> 刷新
        </button>
      </template>
    </PageHeader>

    <div class="card tabs-card">
      <div class="seg" role="tablist" aria-label="系统管理分区">
        <button
          v-if="canReadUsers"
          class="seg-btn"
          :class="{ active: tab === 'users' }"
          type="button"
          @click="switchTab('users')"
        >
          账号管理
        </button>
        <button
          v-if="canReadAudit"
          class="seg-btn"
          :class="{ active: tab === 'audit' }"
          type="button"
          @click="switchTab('audit')"
        >
          审计日志
        </button>
      </div>
      <span class="spacer" />
      <span class="hint">
        {{ tab === 'users' ? '角色决定权限点；科室绑定决定数据范围' : '审计只读，查询审计本身不产生新事件' }}
      </span>
    </div>

    <template v-if="tab === 'users'">
      <div class="kpi-strip">
        <KpiCard label="账号总数" :value="usersTotal" unit="个" :meta="`当前筛选：${userStatus ? statusLabel(userStatus) : '全部状态'}`" />
        <KpiCard label="本页启用" :value="enabledCount" unit="个" />
        <KpiCard label="本页停用" :value="disabledCount" unit="个" :tone="disabledCount > 0 ? 'warn' : 'default'" />
        <KpiCard label="可分配角色" :value="roles.length" unit="种" meta="来自授权矩阵，只读" />
      </div>

      <div class="sheet">
        <div class="sheet-head">
          <span class="sheet-title">账号</span>
          <span class="badge badge--neutral">{{ usersTotal }}</span>
          <span class="spacer" />
          <label class="field filter-status">
            <span class="label">状态</span>
            <select v-model="userStatus" class="select" @change="resetUserPage">
              <option value="">全部</option>
              <option value="ACTIVE">启用</option>
              <option value="DISABLED">停用</option>
            </select>
          </label>
          <label class="field filter-org">
            <span class="label">机构</span>
            <select v-model="userOrg" class="select" @change="resetUserPage">
              <option value="">全部</option>
              <option v-for="o in orgs" :key="o.code" :value="o.code">
                {{ o.name }}（{{ o.code }}）
              </option>
            </select>
          </label>
          <button v-if="canCreate" class="btn btn--sm btn--primary" type="button" @click="openCreate">
            <AppIcon name="plus" :size="13" /> 新建账号
          </button>
        </div>

        <TableSkeleton v-if="usersLoading" :rows="5" />

        <LoadError
          v-else-if="usersError"
          :message="usersError"
          :retrying="usersLoading"
          @retry="loadUsers"
        />

        <EmptyState
          v-else-if="users.length === 0"
          art="orders"
          title="当前筛选条件下没有账号"
          desc="账号由管理员创建并分配角色；临床角色必须绑定至少一个科室。"
        />

        <div v-else class="table-wrap">
          <table class="table table--stack">
            <thead>
              <tr>
                <th>用户名</th>
                <th>姓名 / 工号</th>
                <th>机构</th>
                <th class="is-num">科室</th>
                <th>角色</th>
                <th>状态</th>
                <th>最后登录</th>
                <th style="width: 240px">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="u in users" :key="u.id">
                <td class="mono" data-primary>{{ u.username }}</td>
                <td data-label="姓名 / 工号">
                  <div class="is-strong">{{ u.displayName }}</div>
                  <div class="muted" style="font-size: var(--fs-small)">{{ u.employeeNo || '无工号' }}</div>
                </td>
                <td data-label="机构">
                  <div>{{ orgName(u.orgCode) }}</div>
                  <div class="mono muted" style="font-size: var(--fs-small)">{{ u.orgCode || '—' }}</div>
                </td>
                <td class="is-num" data-label="科室">{{ u.deptIds.length }}</td>
                <td data-label="角色">
                  <span v-for="r in u.roles" :key="r" class="badge badge--neutral role-badge">
                    {{ roleName(r) }}
                  </span>
                </td>
                <td data-label="状态"><StatusPill :value="u.status" /></td>
                <td data-label="最后登录">{{ u.lastLoginAt ? dateTime(u.lastLoginAt) : '从未登录' }}</td>
                <td data-label="操作">
                  <div class="row-actions">
                    <button v-if="canUpdate" class="btn btn--sm" type="button" @click="openEdit(u)">编辑</button>
                    <button v-if="canAssign" class="btn btn--sm" type="button" @click="openRoles(u)">角色</button>
                    <button v-if="canUpdate" class="btn btn--sm" type="button" @click="openReset(u)">重置密码</button>
                    <button
                      v-if="canDisable"
                      class="btn btn--sm"
                      :class="{ 'btn--danger': u.status === 'ACTIVE' }"
                      type="button"
                      :disabled="busy"
                      @click="toggleUser(u)"
                    >
                      {{ u.status === 'ACTIVE' ? '停用' : '启用' }}
                    </button>
                  </div>
                </td>
              </tr>
            </tbody>
          </table>
        </div>

        <div class="pager">
          <span class="muted">共 {{ usersTotal }} 条 · 第 {{ userPage }} / {{ userPages }} 页</span>
          <span class="spacer" />
          <button class="btn btn--sm" type="button" :disabled="userPage <= 1 || usersLoading" @click="prevUserPage">
            上一页
          </button>
          <button
            class="btn btn--sm"
            type="button"
            :disabled="userPage >= userPages || usersLoading"
            @click="nextUserPage"
          >
            下一页
          </button>
        </div>
      </div>
    </template>

    <template v-else>
      <div class="sheet">
        <div class="sheet-head">
          <span class="sheet-title">审计日志</span>
          <span class="badge badge--neutral">{{ auditTotal }}</span>
          <span class="spacer" />
          <span class="hint">跨 auth / empi 等域的变更日志；时间留空表示不限</span>
        </div>

        <div class="toolbar">
          <label class="field">
            <span class="label">操作人</span>
            <input v-model.trim="auditForm.actor" class="input" placeholder="例如 admin01" @keydown.enter="resetAuditPage" />
          </label>
          <label class="field">
            <span class="label">动作</span>
            <input v-model.trim="auditForm.action" class="input" placeholder="例如 USER_DISABLE" @keydown.enter="resetAuditPage" />
          </label>
          <label class="field">
            <span class="label">对象类型</span>
            <input v-model.trim="auditForm.targetType" class="input" placeholder="例如 USER" @keydown.enter="resetAuditPage" />
          </label>
          <label class="field">
            <span class="label">开始时间</span>
            <input v-model="auditForm.from" class="input" type="datetime-local" />
          </label>
          <label class="field">
            <span class="label">结束时间</span>
            <input v-model="auditForm.to" class="input" type="datetime-local" />
          </label>
          <button class="btn btn--primary" type="button" :disabled="auditLoading" @click="resetAuditPage">
            查询
          </button>
          <button class="btn" type="button" :disabled="auditLoading" @click="clearAudit">清空条件</button>
        </div>

        <TableSkeleton v-if="auditLoading" :rows="5" />

        <LoadError
          v-else-if="auditError"
          :message="auditError"
          :retrying="auditLoading"
          @retry="loadAudit"
        />

        <EmptyState
          v-else-if="auditRows.length === 0"
          art="orders"
          title="当前条件下没有审计事件"
          desc="可放宽时间范围或清空操作人/动作条件后重试。"
        />

        <div v-else class="table-wrap">
          <table class="table table--stack">
            <thead>
              <tr>
                <th>时间</th>
                <th>来源</th>
                <th>操作人</th>
                <th>动作</th>
                <th>对象</th>
                <th style="width: 88px">详情</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="e in auditRows" :key="`${e.source}-${e.id}`">
                <td data-primary>{{ dateTime(e.ts) }}</td>
                <td data-label="来源"><span class="badge badge--neutral">{{ e.source }}</span></td>
                <td data-label="操作人">{{ e.actor }}</td>
                <td class="mono" data-label="动作">{{ e.action }}</td>
                <td data-label="对象">
                  <div>{{ e.targetType || '—' }}</div>
                  <div class="mono muted" style="font-size: var(--fs-small)">{{ shortId(e.targetId) }}</div>
                </td>
                <td data-label="详情">
                  <button
                    class="btn btn--sm"
                    type="button"
                    :disabled="!e.detail"
                    @click="openAuditDetail(e)"
                  >
                    查看
                  </button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>

        <div class="pager">
          <span class="muted">共 {{ auditTotal }} 条 · 第 {{ auditPage }} / {{ auditPages }} 页</span>
          <span class="spacer" />
          <button class="btn btn--sm" type="button" :disabled="auditPage <= 1 || auditLoading" @click="prevAuditPage">
            上一页
          </button>
          <button
            class="btn btn--sm"
            type="button"
            :disabled="auditPage >= auditPages || auditLoading"
            @click="nextAuditPage"
          >
            下一页
          </button>
        </div>
      </div>
    </template>

    <AppModal
      v-model="userModalOpen"
      :title="userMode === 'create' ? '新建账号' : '编辑账号'"
      :subtitle="userMode === 'edit' ? editing?.username : ''"
      width="760px"
    >
      <div class="form-grid">
        <template v-if="userMode === 'create'">
          <label class="field">
            <span class="label">用户名 <span class="req">*</span></span>
            <input v-model.trim="userForm.username" class="input" maxlength="64" autocomplete="off" />
          </label>
          <label class="field">
            <span class="label">初始密码 <span class="req">*</span></span>
            <input
              v-model="userForm.password"
              class="input"
              type="password"
              maxlength="64"
              autocomplete="new-password"
            />
            <span class="hint">至少 8 位，同时含字母与数字。</span>
          </label>
        </template>
        <label class="field">
          <span class="label">姓名 <span class="req">*</span></span>
          <input v-model.trim="userForm.displayName" class="input" maxlength="64" />
        </label>
        <label class="field">
          <span class="label">工号</span>
          <input v-model.trim="userForm.employeeNo" class="input" maxlength="32" />
        </label>
        <label class="field">
          <span class="label">机构</span>
          <select v-model="userForm.orgCode" class="select">
            <option value="">未指定</option>
            <option v-for="o in orgs" :key="o.code" :value="o.code">
              {{ o.name }}（{{ o.code }}）
            </option>
          </select>
        </label>

        <div v-if="deptEditable" class="field field--full">
          <span class="label">临床科室绑定 <span class="hint">医师/护士至少绑定一个</span></span>
          <div class="check-grid">
            <label v-for="d in departments" :key="d.id" class="check">
              <input v-model="userForm.deptIds" type="checkbox" :value="d.id" />
              <span>{{ d.name }}</span>
            </label>
          </div>
        </div>
        <div v-else class="field field--full">
          <span class="hint">当前账号没有科室字典读取权限：创建时无法绑定科室，编辑时保持原有绑定不变。</span>
        </div>

        <div v-if="userMode === 'create'" class="field field--full">
          <span class="label">角色 <span class="req">*</span></span>
          <div class="check-grid">
            <label v-for="r in roles" :key="r.code" class="check">
              <input v-model="userForm.roles" type="checkbox" :value="r.code" />
              <span>{{ r.name }}</span>
              <span class="mono muted">{{ r.code }}</span>
            </label>
          </div>
        </div>
      </div>
      <template #footer>
        <button class="btn" type="button" @click="userModalOpen = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="busy || !userFormValid" @click="submitUser">
          保存
        </button>
      </template>
    </AppModal>

    <AppModal
      v-model="roleModalOpen"
      title="分配角色"
      :subtitle="roleTarget ? `${roleTarget.displayName} · ${roleTarget.username}` : ''"
      width="620px"
    >
      <div class="stack">
        <div class="notice notice--warn">
          角色是全量替换：取消某个角色会立即收回其权限点；临床角色必须保留科室绑定。
        </div>
        <div class="check-grid">
          <label v-for="r in roles" :key="r.code" class="check">
            <input v-model="roleSelection" type="checkbox" :value="r.code" />
            <span>{{ r.name }}</span>
            <span class="mono muted">{{ r.code }}</span>
          </label>
        </div>
      </div>
      <template #footer>
        <button class="btn" type="button" @click="roleModalOpen = false">取消</button>
        <button
          class="btn btn--primary"
          type="button"
          :disabled="busy || roleSelection.length === 0"
          @click="submitRoles"
        >
          保存角色
        </button>
      </template>
    </AppModal>

    <AppModal
      v-model="resetModalOpen"
      title="重置密码"
      :subtitle="resetTarget ? `${resetTarget.displayName} · ${resetTarget.username}` : ''"
      width="520px"
    >
      <div class="stack">
        <div class="notice notice--danger">
          重置后旧密码立即失效；请通过安全渠道把新密码交给本人，并在首次登录后修改。
        </div>
        <label class="field">
          <span class="label">新密码 <span class="req">*</span></span>
          <input v-model="resetForm.password" class="input" type="password" maxlength="64" autocomplete="new-password" />
          <span class="hint">至少 8 位，同时含字母与数字，且不能与用户名相同。</span>
        </label>
        <label class="field">
          <span class="label">确认新密码 <span class="req">*</span></span>
          <input v-model="resetForm.confirm" class="input" type="password" maxlength="64" autocomplete="new-password" />
        </label>
      </div>
      <template #footer>
        <button class="btn" type="button" @click="resetModalOpen = false">取消</button>
        <button
          class="btn btn--danger"
          type="button"
          :disabled="busy || !resetValid"
          @click="submitReset"
        >
          确认重置
        </button>
      </template>
    </AppModal>

    <AppModal
      v-model="auditDetailOpen"
      title="审计详情"
      :subtitle="auditDetail ? `${auditDetail.action} · ${auditDetail.actor}` : ''"
      width="720px"
    >
      <div v-if="auditDetail" class="stack">
        <div class="detail-grid">
          <div class="kv"><span>时间</span><strong>{{ dateTime(auditDetail.ts) }}</strong></div>
          <div class="kv"><span>来源</span><strong>{{ auditDetail.source }}</strong></div>
          <div class="kv"><span>操作人</span><strong>{{ auditDetail.actor }}</strong></div>
          <div class="kv"><span>动作</span><strong class="mono">{{ auditDetail.action }}</strong></div>
          <div class="kv"><span>对象类型</span><strong>{{ auditDetail.targetType || '—' }}</strong></div>
          <div class="kv"><span>对象 ID</span><strong class="mono">{{ auditDetail.targetId || '—' }}</strong></div>
        </div>
        <div class="detail-block">
          <span class="muted">原始明细</span>
          <pre class="detail-pre">{{ prettyDetail(auditDetail.detail) }}</pre>
        </div>
      </div>
      <template #footer>
        <button class="btn" type="button" @click="auditDetailOpen = false">关闭</button>
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
import { clinicalApi, systemApi } from '@/api'
import { errorText } from '@/api/http'
import type { AuditEvent, Dept, OrgRef, RoleRef, UserAccount } from '@/api/types'
import { hasPermission, session } from '@/stores/session'
import { toast } from '@/stores/toast'
import { dateTime } from '@/utils/format'

const role = session.roleLabel
const canReadUsers = computed(() => hasPermission('auth:user:read'))
const canReadAudit = computed(() => hasPermission('audit:log:read'))
const canCreate = computed(() => hasPermission('auth:user:create'))
const canUpdate = computed(() => hasPermission('auth:user:update'))
const canDisable = computed(() => hasPermission('auth:user:disable'))
const canAssign = computed(() => hasPermission('auth:role:assign'))

const tab = ref<'users' | 'audit'>(canReadUsers.value ? 'users' : 'audit')
const busy = ref(false)

const roles = ref<RoleRef[]>([])
const orgs = ref<OrgRef[]>([])
const departments = ref<Dept[]>([])
const deptEditable = computed(() => departments.value.length > 0)

// ---------------------------------------------------------------- 账号

const USERS_PAGE_SIZE = 20
const users = ref<UserAccount[]>([])
const usersTotal = ref(0)
const userPage = ref(1)
const userStatus = ref('')
const userOrg = ref('')
const usersLoading = ref(false)
/** 账号 / 审计两条链路的失败文案：非空即展示错误态，不能退化成空态。 */
const usersError = ref('')
const auditError = ref('')

const userPages = computed(() => Math.max(1, Math.ceil(usersTotal.value / USERS_PAGE_SIZE)))
const enabledCount = computed(() => users.value.filter((u) => u.status === 'ACTIVE').length)
const disabledCount = computed(() => users.value.filter((u) => u.status === 'DISABLED').length)

async function loadUsers() {
  if (!canReadUsers.value) return
  usersLoading.value = true
  try {
    const page = await systemApi.users({
      orgCode: userOrg.value || undefined,
      status: userStatus.value || undefined,
      page: userPage.value,
      size: USERS_PAGE_SIZE,
    })
    users.value = page.items
    usersTotal.value = page.total
    usersError.value = ''
  } catch (e) {
    toast.error('账号列表加载失败', errorText(e))
    users.value = []
    usersTotal.value = 0
    usersError.value = errorText(e)
  } finally {
    usersLoading.value = false
  }
}

async function loadRefs() {
  if (!canReadUsers.value) return
  const [roleRes, orgRes, deptRes] = await Promise.allSettled([
    systemApi.roles(),
    systemApi.orgs(),
    clinicalApi.departments(),
  ])
  if (roleRes.status === 'fulfilled') roles.value = roleRes.value
  if (orgRes.status === 'fulfilled') orgs.value = orgRes.value
  if (deptRes.status === 'fulfilled') departments.value = deptRes.value.filter((d) => d.enabled)
}

function resetUserPage() {
  userPage.value = 1
  void loadUsers()
}

function prevUserPage() {
  if (userPage.value <= 1) return
  userPage.value -= 1
  void loadUsers()
}

function nextUserPage() {
  if (userPage.value >= userPages.value) return
  userPage.value += 1
  void loadUsers()
}

function replaceUser(updated: UserAccount) {
  const index = users.value.findIndex((u) => u.id === updated.id)
  if (index >= 0) users.value[index] = updated
}

async function toggleUser(u: UserAccount) {
  busy.value = true
  try {
    const updated = u.status === 'ACTIVE'
      ? await systemApi.disableUser(u.id)
      : await systemApi.enableUser(u.id)
    replaceUser(updated)
    toast.ok(
      updated.status === 'ACTIVE' ? '账号已启用' : '账号已停用',
      `${updated.displayName} · ${updated.username}`,
    )
  } catch (e) {
    toast.error('账号状态变更失败', errorText(e))
  } finally {
    busy.value = false
  }
}

// ---------------------------------------------------------------- 新建/编辑

const userModalOpen = ref(false)
const userMode = ref<'create' | 'edit'>('create')
const editing = ref<UserAccount | null>(null)
const userForm = reactive({
  username: '',
  password: '',
  displayName: '',
  employeeNo: '',
  orgCode: '',
  deptIds: [] as string[],
  roles: [] as string[],
})

function hasClinicalRole(roleCodes: string[]): boolean {
  return roleCodes.includes('DOCTOR') || roleCodes.includes('NURSE')
}

const userFormValid = computed(() => {
  if (!userForm.displayName.trim()) return false
  if (userMode.value === 'create') {
    if (!userForm.username.trim() || !userForm.password) return false
    if (userForm.roles.length === 0) return false
    if (hasClinicalRole(userForm.roles) && deptEditable.value && userForm.deptIds.length === 0) return false
  }
  if (userMode.value === 'edit' && editing.value) {
    if (hasClinicalRole(editing.value.roles) && deptEditable.value && userForm.deptIds.length === 0) {
      return false
    }
  }
  return true
})

function openCreate() {
  userMode.value = 'create'
  editing.value = null
  userForm.username = ''
  userForm.password = ''
  userForm.displayName = ''
  userForm.employeeNo = ''
  userForm.orgCode = ''
  userForm.deptIds = []
  userForm.roles = []
  userModalOpen.value = true
}

function openEdit(u: UserAccount) {
  userMode.value = 'edit'
  editing.value = u
  userForm.username = u.username
  userForm.password = ''
  userForm.displayName = u.displayName
  userForm.employeeNo = u.employeeNo ?? ''
  userForm.orgCode = u.orgCode ?? ''
  userForm.deptIds = [...u.deptIds]
  userForm.roles = [...u.roles]
  userModalOpen.value = true
}

async function submitUser() {
  if (!userFormValid.value) return
  busy.value = true
  try {
    if (userMode.value === 'create') {
      const created = await systemApi.createUser({
        username: userForm.username.trim(),
        password: userForm.password,
        displayName: userForm.displayName.trim(),
        employeeNo: userForm.employeeNo.trim() || undefined,
        orgCode: userForm.orgCode || undefined,
        deptIds: deptEditable.value ? userForm.deptIds : undefined,
        roles: userForm.roles,
      })
      toast.ok('账号已创建', `${created.displayName} · ${created.username}`)
    } else if (editing.value) {
      const updated = await systemApi.updateUser(editing.value.id, {
        displayName: userForm.displayName.trim(),
        employeeNo: userForm.employeeNo.trim() || undefined,
        orgCode: userForm.orgCode || undefined,
        deptIds: deptEditable.value ? userForm.deptIds : undefined,
        version: editing.value.version,
      })
      replaceUser(updated)
      toast.ok('账号已更新', `${updated.displayName} · ${updated.username}`)
    }
    userModalOpen.value = false
    await loadUsers()
  } catch (e) {
    toast.error('保存账号失败', errorText(e))
  } finally {
    busy.value = false
  }
}

// ---------------------------------------------------------------- 角色

const roleModalOpen = ref(false)
const roleTarget = ref<UserAccount | null>(null)
const roleSelection = ref<string[]>([])

function openRoles(u: UserAccount) {
  roleTarget.value = u
  roleSelection.value = [...u.roles]
  roleModalOpen.value = true
}

async function submitRoles() {
  if (!roleTarget.value || roleSelection.value.length === 0) return
  busy.value = true
  try {
    const updated = await systemApi.assignRoles(roleTarget.value.id, roleSelection.value)
    replaceUser(updated)
    roleModalOpen.value = false
    toast.ok('角色已更新', `${updated.displayName} 的权限点立即生效`)
  } catch (e) {
    toast.error('角色分配失败', errorText(e))
  } finally {
    busy.value = false
  }
}

// ---------------------------------------------------------------- 重置密码

const resetModalOpen = ref(false)
const resetTarget = ref<UserAccount | null>(null)
const resetForm = reactive({ password: '', confirm: '' })

const resetValid = computed(() => {
  const p = resetForm.password
  if (p.length < 8) return false
  if (!/[A-Za-z]/.test(p) || !/\d/.test(p)) return false
  if (resetTarget.value && p.toLowerCase() === resetTarget.value.username.toLowerCase()) return false
  return p === resetForm.confirm
})

function openReset(u: UserAccount) {
  resetTarget.value = u
  resetForm.password = ''
  resetForm.confirm = ''
  resetModalOpen.value = true
}

async function submitReset() {
  if (!resetTarget.value || !resetValid.value) return
  busy.value = true
  try {
    await systemApi.resetPassword(resetTarget.value.id, resetForm.password)
    resetModalOpen.value = false
    toast.ok('密码已重置', `${resetTarget.value.displayName} 需使用新密码登录`)
  } catch (e) {
    toast.error('重置密码失败', errorText(e))
  } finally {
    busy.value = false
  }
}

// ---------------------------------------------------------------- 审计

const AUDIT_PAGE_SIZE = 20
const auditRows = ref<AuditEvent[]>([])
const auditTotal = ref(0)
const auditPage = ref(1)
const auditLoading = ref(false)
const auditForm = reactive({ actor: '', action: '', targetType: '', from: '', to: '' })

const auditPages = computed(() => Math.max(1, Math.ceil(auditTotal.value / AUDIT_PAGE_SIZE)))

function toIso(local: string): string | undefined {
  if (!local) return undefined
  const d = new Date(local)
  return Number.isNaN(d.getTime()) ? undefined : d.toISOString()
}

async function loadAudit() {
  if (!canReadAudit.value) return
  auditLoading.value = true
  try {
    const page = await systemApi.auditEvents({
      actor: auditForm.actor || undefined,
      action: auditForm.action || undefined,
      targetType: auditForm.targetType || undefined,
      from: toIso(auditForm.from),
      to: toIso(auditForm.to),
      page: auditPage.value,
      size: AUDIT_PAGE_SIZE,
    })
    auditRows.value = page.items
    auditTotal.value = page.total
    auditError.value = ''
  } catch (e) {
    toast.error('审计日志加载失败', errorText(e))
    auditRows.value = []
    auditTotal.value = 0
    auditError.value = errorText(e)
  } finally {
    auditLoading.value = false
  }
}

function resetAuditPage() {
  auditPage.value = 1
  void loadAudit()
}

function clearAudit() {
  auditForm.actor = ''
  auditForm.action = ''
  auditForm.targetType = ''
  auditForm.from = ''
  auditForm.to = ''
  resetAuditPage()
}

function prevAuditPage() {
  if (auditPage.value <= 1) return
  auditPage.value -= 1
  void loadAudit()
}

function nextAuditPage() {
  if (auditPage.value >= auditPages.value) return
  auditPage.value += 1
  void loadAudit()
}

const auditDetailOpen = ref(false)
const auditDetail = ref<AuditEvent | null>(null)

function openAuditDetail(e: AuditEvent) {
  auditDetail.value = e
  auditDetailOpen.value = true
}

function prettyDetail(detail: string | null): string {
  if (!detail) return '—'
  try {
    return JSON.stringify(JSON.parse(detail), null, 2)
  } catch {
    return detail
  }
}

// ---------------------------------------------------------------- 展示映射

function switchTab(next: 'users' | 'audit') {
  tab.value = next
  if (next === 'users' && users.value.length === 0) void loadUsers()
  if (next === 'audit' && auditRows.value.length === 0) void loadAudit()
}

function roleName(code: string): string {
  return roles.value.find((r) => r.code === code)?.name ?? code
}

function orgName(code: string | null): string {
  if (!code) return '—'
  return orgs.value.find((o) => o.code === code)?.name ?? code
}

function statusLabel(status: string): string {
  return status === 'ACTIVE' ? '启用' : status === 'DISABLED' ? '停用' : status
}

function shortId(value: string | null): string {
  return value ? `${value.slice(0, 8)}…` : '—'
}

onMounted(() => {
  if (canReadUsers.value) {
    void loadRefs()
    void loadUsers()
  }
  if (tab.value === 'audit') void loadAudit()
})
</script>

<style scoped>
.tabs-card {
  display: flex;
  align-items: center;
  gap: var(--s3);
  padding: var(--s2) var(--s3);
  flex-wrap: wrap;
}

.filter-status {
  width: 110px;
}

.filter-org {
  width: 200px;
}

.role-badge {
  margin-right: 4px;
}

.row-actions {
  display: flex;
  gap: 4px;
  flex-wrap: wrap;
}

.pager {
  display: flex;
  align-items: center;
  gap: var(--s2);
  padding: var(--s3) var(--card-pad);
  border-top: 1px solid var(--line);
  font-size: var(--fs-12);
}

.form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--s3) var(--s4);
}

.field--full {
  grid-column: 1 / -1;
}

.check-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: var(--s2) var(--s3);
  max-height: 200px;
  overflow: auto;
  padding: var(--s2) var(--s3);
  border: 1px solid var(--line);
  border-radius: var(--r-md);
}

.check {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: var(--fs-12);
  color: var(--ink-2);
  cursor: pointer;
}

.notice {
  padding: var(--s3) var(--s4);
  border-radius: var(--r-md);
  font-size: var(--fs-12);
  line-height: 1.6;
}

.notice--warn {
  background: var(--warn-weak);
  color: var(--warning-ink);
}

.notice--danger {
  background: var(--danger-weak);
  color: var(--negative-ink);
}

.detail-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: var(--s3) var(--s4);
}

.kv {
  display: flex;
  flex-direction: column;
  gap: 2px;
  font-size: var(--fs-12);
  color: var(--ink-3);
}

.kv strong {
  font-size: var(--fs-13);
  font-weight: 500;
  color: var(--ink);
}

.detail-block {
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: var(--fs-12);
}

.detail-pre {
  margin: 0;
  padding: var(--s3) var(--s4);
  background: var(--sunken);
  border-radius: var(--r-md);
  max-height: 360px;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-word;
  font-family: var(--font-mono);
  font-size: var(--fs-small);
  color: var(--ink-2);
}

@media (max-width: 900px) {
  .form-grid,
  .detail-grid,
  .check-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
