<template>
  <div class="page">
    <PageHeader
      title="租户管理"
      :sub="`一院一 Cell：独立数据库、独立服务组、独立入口。当前入口租户 ${list?.currentTenant || '—'}；跨院访问在网关即被拒绝。`"
    >
      <template #actions>
        <button class="btn" type="button" :disabled="loading" @click="load">
          <AppIcon name="refresh" :size="15" /> {{ loading ? '加载中…' : '刷新' }}
        </button>
        <button v-if="platformLevel" class="btn btn--primary" type="button" @click="openCreate">
          <AppIcon name="plus" :size="15" /> 开通租户
        </button>
      </template>
    </PageHeader>

    <TableSkeleton v-if="loading && !list" :rows="4" />
    <PermissionDenied v-else-if="forbidden" />
    <LoadError v-else-if="error" :message="error" :retrying="loading" @retry="load" />

    <template v-else-if="list">
      <div v-if="currentStatus && currentStatus !== 'ACTIVE'" class="alert-list" role="status">
        <div class="alert-item danger">
          <AppIcon name="alert" :size="15" />
          <span>
            当前租户状态为「{{ statusText(currentStatus) }}」：登录与续期已被拒绝{{ currentStatus === 'CLOSED' ? '，数据按合同保留期冻结' : '，恢复后立即生效' }}。
          </span>
        </div>
      </div>

      <div class="kpi-strip">
        <KpiCard label="租户总数" :value="list.total" unit="家" :meta="platformLevel ? '平台运营视角：全部租户' : '仅显示本院'" />
        <KpiCard label="在用" :value="countBy('ACTIVE')" unit="家" tone="ok" meta="可登录、可正常执行业务" />
        <KpiCard
          label="停用 / 待开通"
          :value="countBy('SUSPENDED') + countBy('PENDING')"
          unit="家"
          :tone="countBy('SUSPENDED') + countBy('PENDING') > 0 ? 'warn' : undefined"
          meta="停用即撤销全院刷新令牌"
        />
        <KpiCard label="本院账号" :value="ownTenant?.activeUserCount ?? 0" unit="个" :meta="`共 ${ownTenant?.userCount ?? 0} 个账号`" />
      </div>

      <section class="sheet">
        <div class="sheet-head">
          <span class="sheet-title">租户清单</span>
          <span class="badge badge--neutral">{{ list.total }}</span>
          <span class="spacer" />
          <span class="hint">
            {{ platformLevel
              ? '开通后可分配实例码与 Cell 入口；实例码与授权书一一对应，不可重复'
              : '租户管理员只能看到本院；如需开通新院请联系平台运营' }}
          </span>
        </div>
        <EmptyState
          v-if="!list.items.length"
          title="尚无租户"
          desc="平台运营可通过「开通租户」登记第一家医院；临床数据随对应 Cell 独立部署。"
        />
        <div v-else class="table-wrap">
          <table class="table">
            <thead>
              <tr>
                <th>租户</th>
                <th>实例码</th>
                <th>状态</th>
                <th class="right">账号</th>
                <th>Cell 入口</th>
                <th>更新时间</th>
                <th />
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="t in list.items"
                :key="t.code"
                class="row"
                :class="{ selected: selected?.code === t.code }"
                @click="selectTenant(t)"
              >
                <td>
                  <div class="tenant-name">{{ t.name }}</div>
                  <div class="mono sub">{{ t.code }}</div>
                </td>
                <td class="mono">{{ t.instanceCode }}</td>
                <td>
                  <span class="badge" :class="statusBadge(t.status)">{{ statusText(t.status) }}</span>
                </td>
                <td class="right mono">{{ t.activeUserCount }} / {{ t.userCount }}</td>
                <td class="mono ellipsis" :title="t.cellEndpoint || ''">{{ t.cellEndpoint || '—' }}</td>
                <td class="nowrap sub">{{ dateTime(t.updatedAt) }}</td>
                <td class="right nowrap" @click.stop>
                  <button class="btn btn--sm btn--ghost" type="button" @click="selectTenant(t)">详情</button>
                  <button
                    v-if="canSelfManage || platformLevel"
                    class="btn btn--sm btn--ghost"
                    type="button"
                    @click="openEdit(t)"
                  >
                    编辑
                  </button>
                  <button
                    v-if="platformLevel && t.status === 'ACTIVE'"
                    class="btn btn--sm btn--ghost danger"
                    type="button"
                    @click="openStatus(t, 'SUSPENDED')"
                  >
                    停用
                  </button>
                  <button
                    v-if="platformLevel && (t.status === 'SUSPENDED')"
                    class="btn btn--sm btn--ghost"
                    type="button"
                    @click="openStatus(t, 'ACTIVE')"
                  >
                    恢复
                  </button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>

      <div class="two-col">
        <section class="sheet">
          <div class="sheet-head">
            <span class="sheet-title">租户详情 · {{ selected?.name || '—' }}</span>
            <span class="spacer" />
            <span class="hint">隔离信息只读；入口与数据库主机由平台运营维护</span>
          </div>
          <div v-if="selected" class="detail-body">
            <dl class="kv">
              <div><dt>租户编码</dt><dd class="mono">{{ selected.code }}</dd></div>
              <div><dt>实例码</dt><dd class="mono">{{ selected.instanceCode }}</dd></div>
              <div><dt>产品版本</dt><dd>{{ selected.editionCode || '—' }}</dd></div>
              <div><dt>状态</dt><dd><span class="badge" :class="statusBadge(selected.status)">{{ statusText(selected.status) }}</span></dd></div>
              <div><dt>Cell 入口</dt><dd class="mono">{{ selected.cellEndpoint || '—' }}</dd></div>
              <div><dt>数据库主机</dt><dd class="mono">{{ selected.dbHost || '—' }}</dd></div>
              <div><dt>区域</dt><dd>{{ selected.region || '—' }}</dd></div>
              <div><dt>联系人</dt><dd>{{ contactText(selected) }}</dd></div>
            </dl>
            <div class="quota-grid">
              <div class="quota-item">
                <span class="quota-label">床位上限</span>
                <span class="quota-value mono">{{ selected.quotas?.maxBeds ?? '—' }}</span>
              </div>
              <div class="quota-item">
                <span class="quota-label">账号上限</span>
                <span class="quota-value mono">{{ selected.quotas?.maxUsers ?? '—' }}</span>
              </div>
              <div class="quota-item">
                <span class="quota-label">存储（GB）</span>
                <span class="quota-value mono">{{ selected.quotas?.maxStorageGb ?? '—' }}</span>
              </div>
              <div class="quota-item">
                <span class="quota-label">AI 日调用</span>
                <span class="quota-value mono">{{ selected.quotas?.maxAiCallsPerDay ?? '—' }}</span>
              </div>
            </div>
            <p v-if="selected.notes" class="hint notes">{{ selected.notes }}</p>
          </div>
        </section>

        <section class="sheet">
          <div class="sheet-head">
            <span class="sheet-title">变更记录</span>
            <span class="spacer" />
            <span class="hint">开通 / 改配 / 停用全部留痕，不可改写</span>
          </div>
          <EmptyState
            v-if="!selected?.recentChanges?.length"
            title="暂无变更"
            desc="开通或调整该租户后会在这里留下记录。"
          />
          <ul v-else class="audit-list">
            <li v-for="(c, i) in selected.recentChanges" :key="`${c.ts}-${i}`" class="audit-item">
              <div class="audit-main">
                <span class="badge badge--neutral">{{ actionText(c.action) }}</span>
                <span class="mono sub">{{ c.actor }}</span>
              </div>
              <span class="sub">{{ dateTime(c.ts) }}</span>
            </li>
          </ul>
        </section>
      </div>

      <section v-if="platformLevel" class="sheet">
        <div class="sheet-head">
          <span class="sheet-title">Cell 迁移版本</span>
          <span class="spacer" />
          <span class="hint">
            控制面直接读取该院数据库的 Flyway 历史（只读）；不可达时展示缓存并说明原因
          </span>
          <button class="btn btn--sm btn--ghost" type="button" :disabled="migLoading" @click="reloadMigrations()">
            <AppIcon name="refresh" :size="14" /> {{ migLoading ? '同步中…' : '重新同步' }}
          </button>
        </div>
        <div v-if="migrations && !migrations.reachable" class="alert-list" role="status">
          <div class="alert-item danger">
            <AppIcon name="alert" :size="15" />
            <span>{{ migrations.error || '该院数据库当前不可达，以下为上次同步结果' }}</span>
          </div>
        </div>
        <div v-if="migrations?.hasFailure" class="alert-list" role="status">
          <div class="alert-item danger">
            <AppIcon name="alert" :size="15" />
            <span>该院存在失败的迁移记录：请核对该服务的 schema 后再放量。</span>
          </div>
        </div>
        <EmptyState
          v-if="!migrations || !migrations.items.length"
          title="尚无迁移记录"
          desc="该院 Cell 尚未登记数据库坐标（db_host），或库内还没有 Flyway 历史。"
        />
        <div v-else class="mig-grid">
          <div v-for="m in migrations.items" :key="m.service" class="mig-item">
            <span class="mig-service">{{ m.service }}</span>
            <span class="mono mig-version">v{{ m.version }}</span>
            <span class="sub mig-desc" :title="m.description || ''">{{ m.description || '—' }}</span>
            <span v-if="!m.success" class="badge badge--danger">失败</span>
          </div>
        </div>
        <p v-if="migrations?.syncedAt" class="hint mig-foot">
          最近同步：{{ dateTime(migrations.syncedAt) }} · 共 {{ migrations.items.length }} 个服务域
        </p>
      </section>

      <section class="sheet">
        <div class="sheet-head">
          <span class="sheet-title">隔离契约</span>
          <span class="spacer" />
          <span class="hint">ADR-001：隔离强度接近私有化，运维与版本仍统一</span>
        </div>
        <ul class="contract">
          <li><b>数据</b><span>每院独立数据库实例；跨租户读取返回 404，不存在“忘写条件就串院”的可能。</span></li>
          <li><b>计算</b><span>独立服务组与资源配额；一院故障（进程被杀 / CPU 打满）不影响别院 P99。</span></li>
          <li><b>流量</b><span>入口与限流按 Cell 分桶；一院洪峰不会触发别院限流。</span></li>
          <li><b>发布</b><span>按 Cell 分批灰度；单院回滚不影响其他院，错误版本不跨 Cell 扩散。</span></li>
          <li><b>备份</b><span>独立备份与 PITR；单院恢复演练不锁其他院数据库。</span></li>
          <li><b>身份</b><span>令牌带 tenant 与 instance：拿 A 院令牌打 B 院入口会被网关拒绝（403）。</span></li>
        </ul>
      </section>
    </template>

    <!-- 开通租户 -->
    <AppModal v-model="createOpen" title="开通租户" width="760px" :dismissible="!busy">
      <div class="form">
        <p class="hint">
          先在控制面登记租户，再按该实例码签发授权书并在对应 Cell 导入。
          实例码与授权书一一对应，开通后不可修改。
        </p>
        <div class="form-grid">
          <label class="form-row">
            <span class="label">租户编码 <span class="req">*</span></span>
            <input v-model="createForm.code" class="input mono" type="text" placeholder="tenant-002" />
            <span class="hint">小写字母、数字与短横线，2-32 位；创建后不可修改</span>
          </label>
          <label class="form-row">
            <span class="label">实例码 <span class="req">*</span></span>
            <input v-model="createForm.instanceCode" class="input mono" type="text" placeholder="MEDASSIST-HOSP-0002" />
          </label>
          <label class="form-row">
            <span class="label">医院名称 <span class="req">*</span></span>
            <input v-model="createForm.name" class="input" type="text" placeholder="某某市第二人民医院" />
          </label>
          <label class="form-row">
            <span class="label">产品版本</span>
            <input v-model="createForm.editionCode" class="input" type="text" placeholder="ENTERPRISE" />
          </label>
          <label class="form-row">
            <span class="label">Cell 入口</span>
            <input v-model="createForm.cellEndpoint" class="input mono" type="text" placeholder="https://hospital-b.example" />
          </label>
          <label class="form-row">
            <span class="label">数据库主机</span>
            <input v-model="createForm.dbHost" class="input mono" type="text" placeholder="10.0.2.12:5432" />
          </label>
          <label class="form-row">
            <span class="label">联系人</span>
            <input v-model="createForm.contactName" class="input" type="text" />
          </label>
          <label class="form-row">
            <span class="label">联系电话</span>
            <input v-model="createForm.contactPhone" class="input" type="text" />
          </label>
          <label class="form-row">
            <span class="label">床位上限</span>
            <input v-model.number="createForm.maxBeds" class="input" type="number" min="1" />
          </label>
          <label class="form-row">
            <span class="label">账号上限</span>
            <input v-model.number="createForm.maxUsers" class="input" type="number" min="1" />
          </label>
        </div>
        <label class="form-row">
          <span class="label">备注</span>
          <textarea v-model="createForm.notes" class="input" rows="2" placeholder="合同编号 / 交付联系人 / 特殊约束" />
        </label>
        <p v-if="createError" class="form-error">{{ createError }}</p>
      </div>
      <template #footer>
        <button class="btn" type="button" :disabled="busy" @click="createOpen = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="busy" @click="doCreate">
          {{ busy ? '开通中…' : '登记租户' }}
        </button>
      </template>
    </AppModal>

    <!-- 编辑租户 -->
    <AppModal v-model="editOpen" title="编辑租户资料" width="680px" :dismissible="!busy">
      <div class="form">
        <p class="hint">
          {{ platformLevel
            ? '平台运营可调整入口、数据库与容量配额。'
            : '租户管理员只能维护名称、联系人与备注；入口与配额由平台运营管理。' }}
        </p>
        <div class="form-grid">
          <label class="form-row">
            <span class="label">医院名称</span>
            <input v-model="editForm.name" class="input" type="text" />
          </label>
          <label v-if="platformLevel" class="form-row">
            <span class="label">产品版本</span>
            <input v-model="editForm.editionCode" class="input" type="text" />
          </label>
          <label v-if="platformLevel" class="form-row">
            <span class="label">Cell 入口</span>
            <input v-model="editForm.cellEndpoint" class="input mono" type="text" />
          </label>
          <label v-if="platformLevel" class="form-row">
            <span class="label">数据库主机</span>
            <input v-model="editForm.dbHost" class="input mono" type="text" />
          </label>
          <label v-if="platformLevel" class="form-row">
            <span class="label">床位上限</span>
            <input v-model.number="editForm.maxBeds" class="input" type="number" min="1" />
          </label>
          <label v-if="platformLevel" class="form-row">
            <span class="label">账号上限</span>
            <input v-model.number="editForm.maxUsers" class="input" type="number" min="1" />
          </label>
          <label class="form-row">
            <span class="label">联系人</span>
            <input v-model="editForm.contactName" class="input" type="text" />
          </label>
          <label class="form-row">
            <span class="label">联系电话</span>
            <input v-model="editForm.contactPhone" class="input" type="text" />
          </label>
        </div>
        <label class="form-row">
          <span class="label">备注</span>
          <textarea v-model="editForm.notes" class="input" rows="2" />
        </label>
        <p v-if="editError" class="form-error">{{ editError }}</p>
      </div>
      <template #footer>
        <button class="btn" type="button" :disabled="busy" @click="editOpen = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="busy" @click="doEdit">
          {{ busy ? '保存中…' : '保存' }}
        </button>
      </template>
    </AppModal>

    <!-- 停用 / 恢复 / 注销 -->
    <AppModal v-model="statusOpen" :title="statusTitle" width="560px" :dismissible="!busy">
      <div class="form">
        <p class="hint">
          <template v-if="statusTarget === 'SUSPENDED'">
            停用后该院立即无法登录，已登录会话的续期会被拒绝（刷新令牌全部撤销），数据完整保留，可随时恢复。
          </template>
          <template v-else-if="statusTarget === 'CLOSED'">
            注销是终态：令牌立即失效、不允许再开通，数据按合同保留期冻结。请慎重操作。
          </template>
          <template v-else>
            恢复后该院可立即登录；此前被撤销的会话需要用户重新登录。
          </template>
        </p>
        <div class="form-row">
          <span class="label">目标租户</span>
          <span class="mono">{{ statusTenant?.name }}（{{ statusTenant?.code }}）</span>
        </div>
        <label v-if="statusTarget !== 'ACTIVE'" class="form-row">
          <span class="label">原因 <span class="req">*</span></span>
          <textarea v-model="statusReason" class="input" rows="3" placeholder="如：合同暂停 / 欠费 / 院方要求下线" />
        </label>
        <p v-if="statusError" class="form-error">{{ statusError }}</p>
      </div>
      <template #footer>
        <button class="btn" type="button" :disabled="busy" @click="statusOpen = false">取消</button>
        <button
          class="btn"
          :class="statusTarget === 'ACTIVE' ? 'btn--primary' : 'btn--danger'"
          type="button"
          :disabled="busy || (statusTarget !== 'ACTIVE' && statusReason.trim().length < 2)"
          @click="doStatus"
        >
          {{ busy ? '提交中…' : statusTitle }}
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
import KpiCard from '@/components/ui/KpiCard.vue'
import LoadError from '@/components/ui/LoadError.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import PermissionDenied from '@/components/ui/PermissionDenied.vue'
import TableSkeleton from '@/components/ui/TableSkeleton.vue'
import { errorText, isForbidden } from '@/api/http'
import {
  TENANT_STATUS_TEXT,
  tenantApi,
  type CellMigrationView,
  type Tenant,
  type TenantList,
  type TenantStatus,
} from '@/api/tenant'
import { session } from '@/stores/session'
import { toast } from '@/stores/toast'

const list = ref<TenantList | null>(null)
const selected = ref<Tenant | null>(null)
const migrations = ref<CellMigrationView | null>(null)
const migLoading = ref(false)
const loading = ref(false)
const busy = ref(false)
const error = ref('')
const forbidden = ref(false)

const platformLevel = computed(() => list.value?.platformLevel === true)
const canSelfManage = computed(() => session.hasPermission('tenant:self:manage'))
const ownTenant = computed(() => {
  const current = list.value?.currentTenant
  return list.value?.items.find((t) => t.code === current) || null
})
const currentStatus = computed(() => ownTenant.value?.status || null)

onMounted(load)

async function load() {
  loading.value = true
  error.value = ''
  forbidden.value = false
  try {
    const data = await tenantApi.list()
    list.value = data
    // 优先展示本院详情：租户管理员打开页面就是要看"我在哪家院、入口是什么"
    const preferred = data.items.find((t) => t.code === data.currentTenant) || data.items[0]
    if (preferred) {
      await selectTenant(preferred)
    } else {
      selected.value = null
    }
  } catch (e) {
    if (isForbidden(e)) forbidden.value = true
    else error.value = `租户服务暂不可达：${errorText(e)}`
  } finally {
    loading.value = false
  }
}

/** 选中某一行并加载它的详情（含最近变更）与迁移台账（仅平台运营）。 */
async function selectTenant(t: Tenant) {
  try {
    selected.value = await tenantApi.detail(t.code)
  } catch {
    // 列表已经能展示关键信息；详情失败不打断整页
    selected.value = t
  }
  if (platformLevel.value) {
    await reloadMigrations(false)
  } else {
    migrations.value = null
  }
}

/** 平台运营视角：拉取该院 Cell 的迁移版本（force=true 时忽略后端 30s 节流）。 */
async function reloadMigrations(force = true) {
  if (!selected.value) return
  migLoading.value = true
  try {
    migrations.value = await tenantApi.migrations(selected.value.code, force)
  } catch {
    migrations.value = {
      tenantCode: selected.value.code,
      reachable: false,
      hasFailure: false,
      error: '迁移台账暂不可达（需要 tenant:manage 权限或租户服务异常）',
      items: [],
    }
  } finally {
    migLoading.value = false
  }
}

/* ---------------------------------------------------------------- 开通 */

const createOpen = ref(false)
const createError = ref('')
const createForm = reactive({
  code: '',
  instanceCode: '',
  name: '',
  editionCode: 'ENTERPRISE',
  cellEndpoint: '',
  dbHost: '',
  contactName: '',
  contactPhone: '',
  maxBeds: undefined as number | undefined,
  maxUsers: undefined as number | undefined,
  notes: '',
})

function openCreate() {
  createError.value = ''
  createForm.code = suggestCode()
  createForm.instanceCode = ''
  createForm.name = ''
  createForm.editionCode = 'ENTERPRISE'
  createForm.cellEndpoint = ''
  createForm.dbHost = ''
  createForm.contactName = ''
  createForm.contactPhone = ''
  createForm.maxBeds = undefined
  createForm.maxUsers = undefined
  createForm.notes = ''
  createOpen.value = true
}

async function doCreate() {
  busy.value = true
  createError.value = ''
  try {
    const created = await tenantApi.create({
      code: createForm.code.trim(),
      instanceCode: createForm.instanceCode.trim(),
      name: createForm.name.trim(),
      editionCode: createForm.editionCode.trim() || undefined,
      cellEndpoint: createForm.cellEndpoint.trim() || undefined,
      dbHost: createForm.dbHost.trim() || undefined,
      contactName: createForm.contactName.trim() || undefined,
      contactPhone: createForm.contactPhone.trim() || undefined,
      notes: createForm.notes.trim() || undefined,
      quotas: {
        maxBeds: createForm.maxBeds || undefined,
        maxUsers: createForm.maxUsers || undefined,
      },
    })
    createOpen.value = false
    toast.ok(`租户已登记：${created.code}（待开通）`)
    await load()
    await selectTenant(created)
  } catch (e) {
    createError.value = errorText(e)
  } finally {
    busy.value = false
  }
}

/* ---------------------------------------------------------------- 编辑 */

const editOpen = ref(false)
const editError = ref('')
const editCode = ref('')
const editVersion = ref(0)
const editForm = reactive({
  name: '',
  editionCode: '',
  cellEndpoint: '',
  dbHost: '',
  contactName: '',
  contactPhone: '',
  maxBeds: undefined as number | undefined,
  maxUsers: undefined as number | undefined,
  notes: '',
})

function openEdit(t: Tenant) {
  editCode.value = t.code
  editVersion.value = t.version
  editError.value = ''
  editForm.name = t.name
  editForm.editionCode = t.editionCode || ''
  editForm.cellEndpoint = t.cellEndpoint || ''
  editForm.dbHost = t.dbHost || ''
  editForm.contactName = t.contactName || ''
  editForm.contactPhone = t.contactPhone || ''
  editForm.maxBeds = t.quotas?.maxBeds ?? undefined
  editForm.maxUsers = t.quotas?.maxUsers ?? undefined
  editForm.notes = t.notes || ''
  editOpen.value = true
}

async function doEdit() {
  busy.value = true
  editError.value = ''
  try {
    const updated = await tenantApi.update(editCode.value, {
      name: editForm.name.trim(),
      editionCode: editForm.editionCode.trim(),
      cellEndpoint: editForm.cellEndpoint.trim(),
      dbHost: editForm.dbHost.trim(),
      contactName: editForm.contactName.trim(),
      contactPhone: editForm.contactPhone.trim(),
      notes: editForm.notes.trim(),
      quotas: {
        ...(selected.value?.quotas || {}),
        maxBeds: editForm.maxBeds ?? null,
        maxUsers: editForm.maxUsers ?? null,
      },
      version: editVersion.value,
    })
    editOpen.value = false
    toast.ok('租户资料已更新')
    await load()
    await selectTenant(updated)
  } catch (e) {
    editError.value = errorText(e)
  } finally {
    busy.value = false
  }
}

/* ---------------------------------------------------------------- 状态 */

const statusOpen = ref(false)
const statusError = ref('')
const statusReason = ref('')
const statusTarget = ref<'ACTIVE' | 'SUSPENDED' | 'CLOSED'>('SUSPENDED')
const statusTenant = ref<Tenant | null>(null)

const statusTitle = computed(() => {
  if (statusTarget.value === 'ACTIVE') return '恢复租户'
  if (statusTarget.value === 'CLOSED') return '注销租户'
  return '停用租户'
})

function openStatus(t: Tenant, target: 'ACTIVE' | 'SUSPENDED' | 'CLOSED') {
  statusTenant.value = t
  statusTarget.value = target
  statusReason.value = ''
  statusError.value = ''
  statusOpen.value = true
}

async function doStatus() {
  if (!statusTenant.value) return
  busy.value = true
  statusError.value = ''
  try {
    const updated = await tenantApi.changeStatus(
      statusTenant.value.code,
      statusTarget.value,
      statusReason.value.trim(),
      statusTenant.value.version,
    )
    statusOpen.value = false
    toast.ok(statusTitle.value + '成功')
    await load()
    await selectTenant(updated)
  } catch (e) {
    statusError.value = errorText(e)
  } finally {
    busy.value = false
  }
}

/* ---------------------------------------------------------------- 展示辅助 */

function countBy(status: TenantStatus): number {
  return (list.value?.items || []).filter((t) => t.status === status).length
}

function statusText(status: TenantStatus): string {
  return TENANT_STATUS_TEXT[status] || status
}

function statusBadge(status: TenantStatus): string {
  if (status === 'ACTIVE') return 'badge--ok'
  if (status === 'SUSPENDED') return 'badge--danger'
  if (status === 'PENDING') return 'badge--warn'
  return 'badge--neutral'
}

function contactText(t: Tenant): string {
  const parts = [t.contactName, t.contactPhone].filter(Boolean)
  return parts.length ? parts.join(' · ') : '—'
}

function actionText(action: string): string {
  const map: Record<string, string> = {
    TENANT_CREATE: '开通登记',
    TENANT_UPDATE: '平台改配',
    TENANT_SELF_UPDATE: '本院维护',
    TENANT_STATUS: '状态变更',
  }
  return map[action] || action
}

function dateTime(value?: string | null): string {
  if (!value) return '—'
  const d = new Date(value)
  if (Number.isNaN(d.getTime())) return value
  return d.toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })
}

/** 按现有租户推算下一个编码，减少手工输入导致的格式错误。 */
function suggestCode(): string {
  const codes = (list.value?.items || [])
    .map((t) => /^tenant-(\d+)$/.exec(t.code))
    .filter((m): m is RegExpExecArray => Boolean(m))
    .map((m) => Number(m[1]))
  const next = (codes.length ? Math.max(...codes) : 0) + 1
  return `tenant-${String(next).padStart(3, '0')}`
}
</script>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.spacer {
  flex: 1;
}
.hint {
  color: var(--muted);
  font-size: var(--fs-small);
}
.req {
  color: var(--negative);
}
.right {
  text-align: right;
}
.nowrap {
  white-space: nowrap;
}
.ellipsis {
  max-width: 220px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.sub {
  color: var(--muted);
  font-size: var(--fs-small);
}
.alert-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.alert-item {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 8px 12px;
  border-radius: 8px;
  border: 1px solid var(--border);
  background: var(--surface);
  font-size: var(--fs-small);
  line-height: 1.6;
}
.alert-item.danger {
  border-color: color-mix(in srgb, var(--negative) 35%, var(--border));
  background: var(--negative-bg);
  color: var(--negative);
}
.table-wrap {
  overflow: auto;
  max-height: calc(100vh - 430px);
}
.row {
  cursor: pointer;
}
.row.selected {
  background: var(--raised);
}
.tenant-name {
  font-weight: 600;
}
.two-col {
  display: grid;
  grid-template-columns: minmax(0, 1.35fr) minmax(0, 1fr);
  gap: 12px;
}
.detail-body {
  padding: 10px 16px 16px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.kv {
  margin: 0;
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px 18px;
}
.kv div {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}
.kv dt {
  color: var(--muted);
  font-size: var(--fs-small);
}
.kv dd {
  margin: 0;
  overflow-wrap: anywhere;
}
.quota-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 8px;
}
.quota-item {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 8px 10px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--ground);
}
.quota-label {
  color: var(--muted);
  font-size: var(--fs-small);
}
.quota-value {
  font-size: 16px;
}
.notes {
  margin: 0;
  padding: 8px 10px;
  border-radius: 8px;
  background: var(--ground);
}
.audit-list {
  list-style: none;
  margin: 0;
  padding: 4px 16px 12px;
  max-height: calc(100vh - 430px);
  overflow: auto;
}
.audit-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  padding: 8px 0;
  border-bottom: 1px solid var(--border);
}
.audit-item:last-child {
  border-bottom: 0;
}
.audit-main {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}
.mig-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(230px, 1fr));
  gap: 8px;
  padding: 10px 16px 4px;
}
.mig-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 10px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--ground);
  min-width: 0;
}
.mig-service {
  font-size: var(--fs-small);
  color: var(--muted);
  min-width: 62px;
}
.mig-version {
  font-weight: 600;
}
.mig-desc {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.mig-foot {
  padding: 0 16px 12px;
}
.contract {
  list-style: none;
  margin: 0;
  padding: 8px 16px 16px;
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px 18px;
}
.contract li {
  display: flex;
  gap: 10px;
  padding: 8px 10px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--ground);
  font-size: var(--fs-small);
  line-height: 1.6;
}
.contract b {
  flex: 0 0 34px;
  color: var(--text);
}
.contract span {
  color: var(--muted);
}
.form {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px 14px;
}
.form-row {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.label {
  color: var(--muted);
  font-size: var(--fs-small);
}
.form-error {
  margin: 0;
  color: var(--negative);
  font-size: var(--fs-small);
}
.danger {
  color: var(--negative);
}
@media (max-width: 1200px) {
  .two-col,
  .contract {
    grid-template-columns: 1fr;
  }
  .quota-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .form-grid {
    grid-template-columns: 1fr;
  }
}
</style>
