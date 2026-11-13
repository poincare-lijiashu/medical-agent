<template>
  <div class="panel">
    <!-- 发布门禁 ------------------------------------------------------------------>
    <div class="sheet gate" :class="gateResult ? (gateResult.eligible ? 'gate--ok' : 'gate--warn') : ''">
      <div class="sheet-head">
        <span class="sheet-title">发布门禁自检</span>
        <span v-if="gateResult" class="badge" :class="gateResult.eligible ? 'badge--ok' : 'badge--danger'">
          {{ gateResult.eligible ? '可放行' : '不可放行' }}
        </span>
        <span class="spacer" />
        <label class="field f-q">
          <span class="label">范围</span>
          <input v-model.trim="gateForm.scope" class="input" maxlength="64" placeholder="ALL 或组件编码" />
        </label>
        <label class="field f-q">
          <span class="label">环境</span>
          <select v-model="gateForm.environment" class="input">
            <option value="PROD">生产</option>
            <option value="STAGING">预发</option>
            <option value="DRILL">演练环境</option>
          </select>
        </label>
        <label class="field f-q">
          <span class="label">有效期（天）</span>
          <input v-model.number="gateForm.days" class="input" type="number" min="1" max="3650" />
        </label>
        <button class="btn btn--sm" type="button" :disabled="gateLoading" @click="runGate">
          <AppIcon name="refresh" :size="14" /> {{ gateLoading ? '查询中…' : '查询' }}
        </button>
      </div>
      <p v-if="!gateResult" class="hint">
        生产部署必须存在有效期内的灾备演练通过记录，且复核人不能是执行人；过期即视为没有演练。
      </p>
      <div v-else class="gate-body">
        <p class="gate-reason">{{ gateResult.reason }}</p>
        <div v-if="gateResult.eligible" class="gate-meta">
          <span class="mono">{{ gateResult.drillNo }}</span>
          <span>{{ gateResult.title }}</span>
          <span>完成 {{ fmtTime(gateResult.completedAt) }}</span>
          <span>复核 {{ gateResult.verifier }}</span>
          <span>RTO {{ gateResult.rtoActualMinutes }} / {{ gateResult.rtoTargetMinutes }} 分</span>
          <span>RPO {{ gateResult.rpoActualMinutes }} / {{ gateResult.rpoTargetMinutes }} 分</span>
          <span>距今 {{ gateResult.ageDays }} 天</span>
        </div>
      </div>
    </div>

    <!-- 备份策略 ------------------------------------------------------------------>
    <div class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">备份策略</span>
        <span class="badge badge--neutral">{{ policies.length }}</span>
        <span class="spacer" />
        <span class="sub">RPO 是对外承诺；加密与保留期按等保/院内制度配置</span>
        <button v-if="canManageBackup" class="btn btn--sm btn--primary" type="button" @click="openPolicy()">
          <AppIcon name="plus" :size="13" /> 新建策略
        </button>
      </div>
      <TableSkeleton v-if="policiesLoading" :rows="3" />
      <LoadError v-else-if="policiesError" :message="policiesError" :retrying="policiesLoading" @retry="loadPolicies" />
      <EmptyState
        v-else-if="policies.length === 0"
        title="还没有备份策略"
        desc="先定义范围、备份类型、计划与 RPO；策略是备份运行与演练的锚点。"
      />
      <div v-else class="table-wrap">
        <table class="table table--stack">
          <thead>
            <tr>
              <th>编码</th>
              <th>名称</th>
              <th>范围</th>
              <th>类型</th>
              <th>计划（cron）</th>
              <th>保留</th>
              <th>加密</th>
              <th>RPO</th>
              <th>负责人</th>
              <th>最近备份</th>
              <th>状态</th>
              <th v-if="canManageBackup" style="width: 90px">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="p in policies" :key="p.code">
              <td class="mono" data-primary>{{ p.code }}</td>
              <td data-label="名称">{{ p.name }}</td>
              <td data-label="范围">{{ scopeText(p) }}</td>
              <td data-label="类型">{{ p.backupType }}</td>
              <td data-label="计划" class="mono">{{ p.scheduleCron }}</td>
              <td data-label="保留">{{ p.retentionDays }} 天</td>
              <td data-label="加密">{{ p.encryption === 'NONE' ? '未加密' : p.encryption }}</td>
              <td data-label="RPO">{{ p.rpoMinutes }} 分</td>
              <td data-label="负责人">{{ p.owner }}</td>
              <td data-label="最近备份">{{ fmtTime(p.lastRunAt) }}</td>
              <td data-label="状态">
                <span class="badge" :class="p.status === 'ACTIVE' ? 'badge--ok' : 'badge--neutral'">
                  {{ p.status === 'ACTIVE' ? '启用' : '停用' }}
                </span>
              </td>
              <td v-if="canManageBackup" data-label="操作">
                <button class="btn btn--sm" type="button" @click="openPolicy(p)">编辑</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- 备份运行 ------------------------------------------------------------------>
    <div class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">备份运行</span>
        <span class="badge badge--neutral">{{ runsTotal }}</span>
        <span class="spacer" />
        <label class="field f-q">
          <span class="label">策略</span>
          <select v-model="runFilter.policyCode" class="input" @change="reloadRuns">
            <option value="">全部</option>
            <option v-for="p in policies" :key="p.code" :value="p.code">{{ p.code }}</option>
          </select>
        </label>
        <label class="field f-q">
          <span class="label">状态</span>
          <select v-model="runFilter.status" class="input" @change="reloadRuns">
            <option value="">全部</option>
            <option value="SUCCESS">成功</option>
            <option value="FAILED">失败</option>
            <option value="RUNNING">进行中</option>
          </select>
        </label>
        <button class="btn btn--sm" type="button" :disabled="runsLoading" @click="loadRuns">
          <AppIcon name="refresh" :size="14" /> 刷新
        </button>
        <button v-if="canManageBackup" class="btn btn--sm btn--primary" type="button" @click="openRun">
          <AppIcon name="upload" :size="14" /> 上报运行
        </button>
      </div>
      <p class="hint">
        幂等键由备份平台生成：同一作业重试必须复用，服务端据此避免一次备份记两笔成功。
      </p>
      <TableSkeleton v-if="runsLoading" :rows="4" />
      <LoadError v-else-if="runsError" :message="runsError" :retrying="runsLoading" @retry="loadRuns" />
      <EmptyState
        v-else-if="runs.length === 0"
        title="暂无备份运行记录"
        desc="备份平台完成作业后按幂等键上报；失败也必须上报，否则无法区分“没做”和“失败”。"
      />
      <div v-else class="table-wrap">
        <table class="table table--compact">
          <thead>
            <tr>
              <th>运行号</th>
              <th>策略</th>
              <th>类型</th>
              <th>状态</th>
              <th>大小</th>
              <th>校验和</th>
              <th>存储位置</th>
              <th>开始</th>
              <th>结束</th>
              <th>上报人</th>
              <th>错误</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="r in runs" :key="r.id">
              <td class="mono" data-primary>{{ r.runNo }}</td>
              <td data-label="策略">{{ r.policyCode }}</td>
              <td data-label="类型">{{ r.backupType }}</td>
              <td data-label="状态">
                <span class="badge" :class="runBadge(r.status)">{{ runText(r.status) }}</span>
              </td>
              <td data-label="大小">{{ fmtSize(r.sizeBytes) }}</td>
              <td data-label="校验和" class="mono ellipsis" :title="r.checksum || ''">{{ r.checksum || '—' }}</td>
              <td data-label="存储位置" class="mono ellipsis" :title="r.storageLocation || ''">
                {{ r.storageLocation || '—' }}
              </td>
              <td data-label="开始">{{ fmtTime(r.startedAt) }}</td>
              <td data-label="结束">{{ fmtTime(r.finishedAt) }}</td>
              <td data-label="上报人">{{ r.reportedBy }}</td>
              <td data-label="错误" class="ellipsis" :title="r.errorMessage || ''">{{ r.errorMessage || '—' }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <div v-if="runsTotal > runFilter.size" class="pager">
        <button class="btn btn--sm" type="button" :disabled="runFilter.page <= 1" @click="runPage(-1)">上一页</button>
        <span class="sub">{{ runFilter.page }} / {{ Math.ceil(runsTotal / runFilter.size) }}</span>
        <button
          class="btn btn--sm"
          type="button"
          :disabled="runFilter.page * runFilter.size >= runsTotal"
          @click="runPage(1)"
        >
          下一页
        </button>
      </div>
    </div>

    <!-- 灾备演练 ------------------------------------------------------------------>
    <div class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">灾备演练</span>
        <span class="badge badge--neutral">{{ drillsTotal }}</span>
        <span class="spacer" />
        <label class="field f-q">
          <span class="label">状态</span>
          <select v-model="drillFilter.status" class="input" @change="reloadDrills">
            <option value="">全部</option>
            <option value="PLANNED">计划中</option>
            <option value="RUNNING">进行中</option>
            <option value="PASSED">已通过</option>
            <option value="FAILED">失败</option>
            <option value="ABORTED">已中止</option>
          </select>
        </label>
        <button class="btn btn--sm" type="button" :disabled="drillsLoading" @click="loadDrills">
          <AppIcon name="refresh" :size="14" /> 刷新
        </button>
        <button v-if="canManageDr" class="btn btn--sm btn--primary" type="button" @click="openDrill">
          <AppIcon name="plus" :size="13" /> 新建演练
        </button>
      </div>
      <TableSkeleton v-if="drillsLoading" :rows="3" />
      <LoadError v-else-if="drillsError" :message="drillsError" :retrying="drillsLoading" @retry="loadDrills" />
      <EmptyState
        v-else-if="drills.length === 0"
        title="还没有灾备演练"
        desc="按季度至少演练一次：备份可恢复、基础设施拉起、数据恢复、应用启动、业务冒烟与一致性核对。"
      />
      <div v-else class="table-wrap">
        <table class="table table--compact">
          <thead>
            <tr>
              <th>编号</th>
              <th>标题</th>
              <th>范围</th>
              <th>环境</th>
              <th>状态</th>
              <th>RTO 实测/目标</th>
              <th>RPO 实测/目标</th>
              <th>执行人</th>
              <th>复核人</th>
              <th>完成时间</th>
              <th style="width: 100px">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="d in drills"
              :key="d.id"
              class="clickable"
              :class="{ 'is-selected': selectedDrill?.id === d.id }"
              tabindex="0"
              @click="selectDrill(d)"
              @keydown.enter="selectDrill(d)"
            >
              <td class="mono" data-primary>{{ d.drillNo }}</td>
              <td data-label="标题">{{ d.title }}</td>
              <td data-label="范围">{{ d.scope }}</td>
              <td data-label="环境">{{ d.environment }}</td>
              <td data-label="状态">
                <span class="badge" :class="drBadge(d.status)">{{ drText(d.status) }}</span>
              </td>
              <td data-label="RTO">{{ d.rtoActualMinutes ?? '—' }} / {{ d.rtoTargetMinutes }} 分</td>
              <td data-label="RPO">{{ d.rpoActualMinutes ?? '—' }} / {{ d.rpoTargetMinutes }} 分</td>
              <td data-label="执行人">{{ d.executor }}</td>
              <td data-label="复核人">{{ d.verifier || '—' }}</td>
              <td data-label="完成时间">{{ fmtTime(d.completedAt) }}</td>
              <td data-label="操作">
                <button class="btn btn--sm" type="button" @click.stop="selectDrill(d)">详情</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <div v-if="drillsTotal > drillFilter.size" class="pager">
        <button class="btn btn--sm" type="button" :disabled="drillFilter.page <= 1" @click="drillPage(-1)">
          上一页
        </button>
        <span class="sub">{{ drillFilter.page }} / {{ Math.ceil(drillsTotal / drillFilter.size) }}</span>
        <button
          class="btn btn--sm"
          type="button"
          :disabled="drillFilter.page * drillFilter.size >= drillsTotal"
          @click="drillPage(1)"
        >
          下一页
        </button>
      </div>
    </div>

    <!-- 演练详情 ------------------------------------------------------------------>
    <div v-if="selectedDrill" class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">{{ selectedDrill.drillNo }} · {{ selectedDrill.title }}</span>
        <span class="badge" :class="drBadge(selectedDrill.status)">{{ drText(selectedDrill.status) }}</span>
        <span class="spacer" />
        <span class="sub">
          执行 {{ selectedDrill.executor }} · 复核 {{ selectedDrill.verifier || '待复核' }}
        </span>
        <button
          v-if="canManageDr && selectedDrill.status === 'PLANNED'"
          class="btn btn--sm btn--primary"
          type="button"
          :disabled="actionBusy"
          @click="startDrill"
        >
          <AppIcon name="play" :size="14" /> 开始演练
        </button>
        <button
          v-if="canManageDr && (selectedDrill.status === 'PLANNED' || selectedDrill.status === 'RUNNING')"
          class="btn btn--sm"
          type="button"
          :disabled="actionBusy"
          @click="openReason('FAIL')"
        >
          判定失败
        </button>
        <button
          v-if="canManageDr && (selectedDrill.status === 'PLANNED' || selectedDrill.status === 'RUNNING')"
          class="btn btn--sm"
          type="button"
          :disabled="actionBusy"
          @click="openReason('ABORT')"
        >
          中止
        </button>
        <button
          v-if="canVerify && selectedDrill.status === 'RUNNING'"
          class="btn btn--sm btn--primary"
          type="button"
          :disabled="actionBusy || selectedDrill.executor === myName"
          :title="selectedDrill.executor === myName ? '执行人不能自己复核通过' : '由第二人复核通过'"
          @click="openComplete"
        >
          判定通过
        </button>
      </div>
      <p v-if="selectedDrill.executor === myName && selectedDrill.status === 'RUNNING'" class="hint">
        你是本次演练的执行人，不能自己判定通过；请由运营经理或其他负责人复核。
      </p>
      <TableSkeleton v-if="detailLoading" :rows="4" />
      <div v-else class="table-wrap">
        <table class="table table--compact">
          <thead>
            <tr>
              <th style="width: 60px">步骤</th>
              <th>名称</th>
              <th>必填</th>
              <th>状态</th>
              <th>证据</th>
              <th>执行人</th>
              <th>完成时间</th>
              <th v-if="canManageDr && selectedDrill.status === 'RUNNING'" style="width: 90px">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="s in selectedDrill.steps" :key="s.id">
              <td class="mono" data-primary>{{ s.stepNo }}</td>
              <td data-label="名称">{{ s.name }}</td>
              <td data-label="必填">{{ s.required ? '是' : '否' }}</td>
              <td data-label="状态"><span class="badge" :class="stepBadge(s.status)">{{ s.status }}</span></td>
              <td data-label="证据" class="ellipsis" :title="s.evidence || ''">{{ s.evidence || '—' }}</td>
              <td data-label="执行人">{{ s.actor || '—' }}</td>
              <td data-label="完成时间">{{ fmtTime(s.completedAt) }}</td>
              <td v-if="canManageDr && selectedDrill.status === 'RUNNING'" data-label="操作">
                <button class="btn btn--sm" type="button" @click="openStep(s)">登记</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <div v-if="selectedDrill.evidence || selectedDrill.note" class="drill-foot">
        <p v-if="selectedDrill.evidence"><strong>证据：</strong>{{ selectedDrill.evidence }}</p>
        <p v-if="selectedDrill.note"><strong>备注：</strong>{{ selectedDrill.note }}</p>
        <p v-if="selectedDrill.consistencyVerified" class="sub">已完成数据一致性核对</p>
      </div>
    </div>

    <!-- 策略弹窗 ------------------------------------------------------------------>
    <AppModal v-model="policyOpen" :title="policyEditing ? '编辑备份策略' : '新建备份策略'" width="820px">
      <div class="form-row">
        <label class="field f-q">
          <span class="label">编码 <span class="req">*</span></span>
          <input v-model.trim="policyForm.code" class="input" maxlength="64" :disabled="policyEditing" />
        </label>
        <label class="field f-grow">
          <span class="label">名称 <span class="req">*</span></span>
          <input v-model.trim="policyForm.name" class="input" maxlength="128" />
        </label>
      </div>
      <div class="form-row">
        <label class="field f-q">
          <span class="label">范围类型 <span class="req">*</span></span>
          <select v-model="policyForm.scopeType" class="input">
            <option value="ALL">全院</option>
            <option value="COMPONENT">组件</option>
            <option value="SCHEMA">数据库 Schema</option>
          </select>
        </label>
        <label v-if="policyForm.scopeType !== 'ALL'" class="field f-q">
          <span class="label">范围引用 <span class="req">*</span></span>
          <input v-model.trim="policyForm.scopeRef" class="input" maxlength="128" placeholder="组件编码或 schema" />
        </label>
        <label class="field f-q">
          <span class="label">备份类型 <span class="req">*</span></span>
          <select v-model="policyForm.backupType" class="input">
            <option value="FULL">全量</option>
            <option value="INCREMENTAL">增量</option>
            <option value="WAL">WAL 归档</option>
            <option value="LOGICAL">逻辑导出</option>
          </select>
        </label>
      </div>
      <div class="form-row">
        <label class="field f-q">
          <span class="label">计划（cron） <span class="req">*</span></span>
          <input v-model.trim="policyForm.scheduleCron" class="input" maxlength="64" placeholder="0 2 * * *" />
        </label>
        <label class="field f-q">
          <span class="label">保留天数</span>
          <input v-model.number="policyForm.retentionDays" class="input" type="number" min="1" max="3650" />
        </label>
        <label class="field f-q">
          <span class="label">加密</span>
          <select v-model="policyForm.encryption" class="input">
            <option value="NONE">不加密</option>
            <option value="AES256">AES-256</option>
            <option value="KMS">KMS 托管</option>
          </select>
        </label>
        <label class="field f-q">
          <span class="label">RPO（分钟） <span class="req">*</span></span>
          <input v-model.number="policyForm.rpoMinutes" class="input" type="number" min="1" max="43200" />
        </label>
      </div>
      <div class="form-row">
        <label class="field f-grow">
          <span class="label">存储目标 <span class="req">*</span></span>
          <input v-model.trim="policyForm.storageTarget" class="input" maxlength="512" placeholder="s3://bucket/path 或挂载路径" />
        </label>
        <label class="field f-q">
          <span class="label">负责人 <span class="req">*</span></span>
          <input v-model.trim="policyForm.owner" class="input" maxlength="64" />
        </label>
        <label class="field f-q">
          <span class="label">状态</span>
          <select v-model="policyForm.status" class="input">
            <option value="ACTIVE">启用</option>
            <option value="INACTIVE">停用</option>
          </select>
        </label>
      </div>
      <template #footer>
        <button class="btn" type="button" :disabled="policyBusy" @click="policyOpen = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="policyBusy" @click="submitPolicy">
          {{ policyBusy ? '保存中…' : '保存' }}
        </button>
      </template>
    </AppModal>

    <!-- 备份运行上报 -------------------------------------------------------------->
    <AppModal v-model="runOpen" title="上报备份运行" width="820px">
      <p class="hint">
        成功必须带大小/校验和/存储位置；失败必须写错误原因。同一作业重试请复用幂等键。
      </p>
      <div class="form-row">
        <label class="field f-grow">
          <span class="label">备份策略 <span class="req">*</span></span>
          <select v-model="runForm.policyCode" class="input" @change="applyPolicyDefaults">
            <option value="">请选择</option>
            <option v-for="p in activePolicies" :key="p.code" :value="p.code">{{ p.code }} · {{ p.name }}</option>
          </select>
        </label>
        <label class="field f-q">
          <span class="label">类型 <span class="req">*</span></span>
          <select v-model="runForm.backupType" class="input">
            <option value="FULL">全量</option>
            <option value="INCREMENTAL">增量</option>
            <option value="WAL">WAL 归档</option>
            <option value="LOGICAL">逻辑导出</option>
          </select>
        </label>
        <label class="field f-q">
          <span class="label">状态 <span class="req">*</span></span>
          <select v-model="runForm.status" class="input">
            <option value="SUCCESS">成功</option>
            <option value="FAILED">失败</option>
            <option value="RUNNING">进行中</option>
          </select>
        </label>
      </div>
      <div class="form-row">
        <label class="field f-grow">
          <span class="label">幂等键 <span class="req">*</span></span>
          <input v-model.trim="runForm.idempotencyKey" class="input" maxlength="128" />
        </label>
        <label class="field f-q">
          <span class="label">开始时间 <span class="req">*</span></span>
          <input v-model="runForm.startedAt" class="input" type="datetime-local" />
        </label>
        <label class="field f-q">
          <span class="label">结束时间</span>
          <input v-model="runForm.finishedAt" class="input" type="datetime-local" />
        </label>
      </div>
      <div class="form-row">
        <label class="field f-q">
          <span class="label">大小（MB）</span>
          <input v-model.number="runForm.sizeMb" class="input" type="number" min="0" step="0.01" />
        </label>
        <label class="field f-grow">
          <span class="label">校验和</span>
          <input v-model.trim="runForm.checksum" class="input" maxlength="128" placeholder="sha256:…" />
        </label>
        <label class="field f-grow">
          <span class="label">存储位置</span>
          <input v-model.trim="runForm.storageLocation" class="input" maxlength="512" />
        </label>
      </div>
      <label v-if="runForm.status === 'FAILED'" class="field">
        <span class="label">错误原因 <span class="req">*</span></span>
        <textarea v-model.trim="runForm.errorMessage" class="textarea" rows="3" maxlength="1000" />
      </label>
      <template #footer>
        <button class="btn" type="button" :disabled="runBusy" @click="runOpen = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="runBusy" @click="submitRun">
          {{ runBusy ? '上报中…' : '上报' }}
        </button>
      </template>
    </AppModal>

    <!-- 新建演练 ------------------------------------------------------------------>
    <AppModal v-model="drillOpen" title="新建灾备演练" width="760px">
      <p class="hint">未自定义步骤时按默认六步模板创建：备份可恢复性、基础设施、数据恢复、应用健康、业务冒烟、一致性核对。</p>
      <div class="form-row">
        <label class="field f-grow">
          <span class="label">标题 <span class="req">*</span></span>
          <input v-model.trim="drillForm.title" class="input" maxlength="200" placeholder="2026 Q4 全院灾备演练" />
        </label>
        <label class="field f-q">
          <span class="label">环境 <span class="req">*</span></span>
          <select v-model="drillForm.environment" class="input">
            <option value="PROD">生产</option>
            <option value="STAGING">预发</option>
            <option value="DRILL">演练环境</option>
          </select>
        </label>
      </div>
      <div class="form-row">
        <label class="field f-grow">
          <span class="label">范围 <span class="req">*</span></span>
          <input v-model.trim="drillForm.scope" class="input" maxlength="64" placeholder="ALL 或组件编码" />
        </label>
        <label class="field f-q">
          <span class="label">RTO 目标（分钟） <span class="req">*</span></span>
          <input v-model.number="drillForm.rtoTargetMinutes" class="input" type="number" min="1" max="43200" />
        </label>
        <label class="field f-q">
          <span class="label">RPO 目标（分钟） <span class="req">*</span></span>
          <input v-model.number="drillForm.rpoTargetMinutes" class="input" type="number" min="1" max="43200" />
        </label>
      </div>
      <label class="field">
        <span class="label">关联备份快照（仅成功的备份运行）</span>
        <select v-model="drillForm.backupRunId" class="input">
          <option value="">不关联</option>
          <option v-for="r in successfulRuns" :key="r.id" :value="r.id">
            {{ r.runNo }} · {{ r.policyCode }} · {{ fmtTime(r.finishedAt) }}
          </option>
        </select>
      </label>
      <label class="field">
        <span class="label">备注</span>
        <textarea v-model.trim="drillForm.note" class="textarea" rows="2" maxlength="1000" />
      </label>
      <template #footer>
        <button class="btn" type="button" :disabled="drillBusy" @click="drillOpen = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="drillBusy" @click="submitDrill">
          {{ drillBusy ? '创建中…' : '创建' }}
        </button>
      </template>
    </AppModal>

    <!-- 步骤登记 ------------------------------------------------------------------>
    <AppModal v-model="stepOpen" title="登记演练步骤" width="620px">
      <p class="hint">{{ stepForm.name }}（步骤 {{ stepForm.stepNo }}）</p>
      <div class="form-row">
        <label class="field f-q">
          <span class="label">结果 <span class="req">*</span></span>
          <select v-model="stepForm.status" class="input">
            <option value="PASSED">通过</option>
            <option value="FAILED">失败</option>
            <option value="SKIPPED" :disabled="stepForm.required">跳过（仅非必填）</option>
            <option value="PENDING">重置为待处理</option>
          </select>
        </label>
      </div>
      <label class="field">
        <span class="label">证据 <span v-if="stepForm.status === 'PASSED'" class="req">*</span></span>
        <input v-model.trim="stepForm.evidence" class="input" maxlength="1000" placeholder="日志地址 / 截图地址 / 还原结果" />
      </label>
      <label class="field">
        <span class="label">备注</span>
        <textarea v-model.trim="stepForm.note" class="textarea" rows="2" maxlength="1000" />
      </label>
      <template #footer>
        <button class="btn" type="button" :disabled="stepBusy" @click="stepOpen = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="stepBusy" @click="submitStep">
          {{ stepBusy ? '保存中…' : '保存' }}
        </button>
      </template>
    </AppModal>

    <!-- 判定通过 ------------------------------------------------------------------>
    <AppModal v-model="completeOpen" title="判定演练通过（复核）" width="720px">
      <p class="hint">
        所有必填步骤必须已通过；实测 RTO/RPO 不得超过目标；必须完成数据一致性核对并填写证据。
        复核人不能是执行人。
      </p>
      <div class="form-row">
        <label class="field f-q">
          <span class="label">实测 RTO（分钟） <span class="req">*</span></span>
          <input v-model.number="completeForm.rtoActualMinutes" class="input" type="number" min="0" max="43200" />
        </label>
        <label class="field f-q">
          <span class="label">实测 RPO（分钟） <span class="req">*</span></span>
          <input v-model.number="completeForm.rpoActualMinutes" class="input" type="number" min="0" max="43200" />
        </label>
        <label class="field f-q">
          <span class="label">数据丢失窗口（分钟）</span>
          <input v-model.number="completeForm.dataLossMinutes" class="input" type="number" min="0" max="43200" />
        </label>
      </div>
      <label class="check-row">
        <input v-model="completeForm.consistencyVerified" type="checkbox" />
        <span>已完成数据一致性核对（行数/金额/关键业务对象抽样一致）</span>
      </label>
      <label class="field">
        <span class="label">证据 <span class="req">*</span></span>
        <input v-model.trim="completeForm.evidence" class="input" maxlength="1000" placeholder="恢复日志、冒烟截图、核对报表地址" />
      </label>
      <label class="field">
        <span class="label">备注</span>
        <textarea v-model.trim="completeForm.note" class="textarea" rows="2" maxlength="1000" />
      </label>
      <template #footer>
        <button class="btn" type="button" :disabled="completeBusy" @click="completeOpen = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="completeBusy" @click="submitComplete">
          {{ completeBusy ? '提交中…' : '确认通过' }}
        </button>
      </template>
    </AppModal>

    <!-- 失败/中止原因 -------------------------------------------------------------->
    <AppModal v-model="reasonOpen" :title="reasonMode === 'FAIL' ? '判定演练失败' : '中止演练'" width="620px">
      <p class="hint">失败也是一次资产：写清楚断在哪一步，下一次演练才不会重蹈覆辙。</p>
      <label class="field">
        <span class="label">原因 <span class="req">*</span></span>
        <textarea v-model.trim="reasonText" class="textarea" rows="3" maxlength="1000" />
      </label>
      <template #footer>
        <button class="btn" type="button" :disabled="reasonBusy" @click="reasonOpen = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="reasonBusy" @click="submitReason">
          {{ reasonBusy ? '提交中…' : '确认' }}
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
  opsApi,
  type BackupPolicy,
  type BackupRun,
  type BackupRunStatus,
  type BackupType,
  type DrDrill,
  type DrDrillStep,
  type DrGate,
  type DrStepStatus,
  type DrStatus,
} from '@/api/ops'
import { errorText } from '@/api/http'
import { hasPermission, session } from '@/stores/session'
import { toast } from '@/stores/toast'

const canManageBackup = hasPermission('ops:backup:manage')
const canManageDr = hasPermission('ops:dr:manage')
const canVerify = hasPermission('ops:dr:verify')
const myName = computed(() => session.user.value?.displayName ?? '')

/* ------------------------------------------------------------------ 门禁 */
const gateForm = reactive({ scope: 'ALL', environment: 'PROD', days: 90 })
const gateResult = ref<DrGate | null>(null)
const gateLoading = ref(false)

/* ------------------------------------------------------------------ 策略 */
const policies = ref<BackupPolicy[]>([])
const policiesLoading = ref(false)
const policiesError = ref('')
const policyOpen = ref(false)
const policyBusy = ref(false)
const policyEditing = ref(false)
const policyForm = reactive({
  code: '',
  name: '',
  scopeType: 'ALL' as 'ALL' | 'COMPONENT' | 'SCHEMA',
  scopeRef: '',
  backupType: 'FULL' as BackupType,
  scheduleCron: '0 2 * * *',
  retentionDays: 30,
  encryption: 'AES256' as 'NONE' | 'AES256' | 'KMS',
  storageTarget: '',
  rpoMinutes: 15,
  owner: '',
  status: 'ACTIVE' as 'ACTIVE' | 'INACTIVE',
})

const activePolicies = computed(() => policies.value.filter((p) => p.status === 'ACTIVE'))

/* ------------------------------------------------------------------ 备份运行 */
const runs = ref<BackupRun[]>([])
const runsTotal = ref(0)
const runsLoading = ref(false)
const runsError = ref('')
const runFilter = reactive({ policyCode: '', status: '', page: 1, size: 20 })
const runOpen = ref(false)
const runBusy = ref(false)
const runForm = reactive({
  policyCode: '',
  backupType: 'FULL' as BackupType,
  status: 'SUCCESS' as BackupRunStatus,
  idempotencyKey: '',
  startedAt: '',
  finishedAt: '',
  sizeMb: null as number | null,
  checksum: '',
  storageLocation: '',
  errorMessage: '',
})
const successfulRuns = computed(() => runs.value.filter((r) => r.status === 'SUCCESS'))

/* ------------------------------------------------------------------ 演练 */
const drills = ref<DrDrill[]>([])
const drillsTotal = ref(0)
const drillsLoading = ref(false)
const drillsError = ref('')
const drillFilter = reactive({ status: '', page: 1, size: 20 })
const selectedDrill = ref<DrDrill | null>(null)
const detailLoading = ref(false)
const drillOpen = ref(false)
const drillBusy = ref(false)
const drillForm = reactive({
  title: '',
  scope: 'ALL',
  environment: 'PROD' as 'PROD' | 'STAGING' | 'DRILL',
  backupRunId: '',
  rtoTargetMinutes: 120,
  rpoTargetMinutes: 15,
  note: '',
})

const actionBusy = ref(false)

const stepOpen = ref(false)
const stepBusy = ref(false)
const stepForm = reactive({
  stepNo: 0,
  name: '',
  required: true,
  status: 'PASSED' as DrStepStatus,
  evidence: '',
  note: '',
})

const completeOpen = ref(false)
const completeBusy = ref(false)
const completeForm = reactive({
  rtoActualMinutes: 0,
  rpoActualMinutes: 0,
  dataLossMinutes: null as number | null,
  consistencyVerified: false,
  evidence: '',
  note: '',
})

const reasonOpen = ref(false)
const reasonBusy = ref(false)
const reasonMode = ref<'FAIL' | 'ABORT'>('FAIL')
const reasonText = ref('')

onMounted(async () => {
  await Promise.all([loadPolicies(), loadRuns(), loadDrills()])
  await runGate()
})

/* ------------------------------------------------------------------ 加载 */
async function loadPolicies() {
  policiesLoading.value = true
  policiesError.value = ''
  try {
    policies.value = await opsApi.backupPolicies(false)
  } catch (e) {
    policiesError.value = errorText(e)
  } finally {
    policiesLoading.value = false
  }
}

async function loadRuns() {
  runsLoading.value = true
  runsError.value = ''
  try {
    const res = await opsApi.backupRuns({
      policyCode: runFilter.policyCode || undefined,
      status: runFilter.status || undefined,
      page: runFilter.page,
      size: runFilter.size,
    })
    runs.value = res.items
    runsTotal.value = res.total
  } catch (e) {
    runsError.value = errorText(e)
  } finally {
    runsLoading.value = false
  }
}

async function loadDrills() {
  drillsLoading.value = true
  drillsError.value = ''
  try {
    const res = await opsApi.drills({
      status: drillFilter.status || undefined,
      page: drillFilter.page,
      size: drillFilter.size,
    })
    drills.value = res.items
    drillsTotal.value = res.total
    if (selectedDrill.value) {
      const fresh = res.items.find((d) => d.id === selectedDrill.value?.id)
      if (fresh) {
        await selectDrill(fresh)
      } else {
        selectedDrill.value = null
      }
    }
  } catch (e) {
    drillsError.value = errorText(e)
  } finally {
    drillsLoading.value = false
  }
}

function reloadRuns() {
  runFilter.page = 1
  loadRuns()
}

function reloadDrills() {
  drillFilter.page = 1
  loadDrills()
}

function runPage(delta: number) {
  runFilter.page = Math.max(1, runFilter.page + delta)
  loadRuns()
}

function drillPage(delta: number) {
  drillFilter.page = Math.max(1, drillFilter.page + delta)
  loadDrills()
}

async function selectDrill(drill: DrDrill) {
  selectedDrill.value = drill
  detailLoading.value = true
  try {
    selectedDrill.value = await opsApi.drill(drill.id)
  } catch (e) {
    toast.error('演练详情加载失败', errorText(e))
  } finally {
    detailLoading.value = false
  }
}

/* ------------------------------------------------------------------ 门禁 */
async function runGate() {
  if (!gateForm.scope) {
    toast.warn('请填写范围（ALL 或组件编码）')
    return
  }
  gateLoading.value = true
  try {
    gateResult.value = await opsApi.drGate(gateForm.scope, gateForm.environment, gateForm.days)
  } catch (e) {
    toast.error('门禁查询失败', errorText(e))
  } finally {
    gateLoading.value = false
  }
}

/* ------------------------------------------------------------------ 策略动作 */
function openPolicy(p?: BackupPolicy) {
  policyEditing.value = Boolean(p)
  policyForm.code = p?.code ?? ''
  policyForm.name = p?.name ?? ''
  policyForm.scopeType = p?.scopeType ?? 'ALL'
  policyForm.scopeRef = p?.scopeRef ?? ''
  policyForm.backupType = p?.backupType ?? 'FULL'
  policyForm.scheduleCron = p?.scheduleCron ?? '0 2 * * *'
  policyForm.retentionDays = p?.retentionDays ?? 30
  policyForm.encryption = p?.encryption ?? 'AES256'
  policyForm.storageTarget = p?.storageTarget ?? ''
  policyForm.rpoMinutes = p?.rpoMinutes ?? 15
  policyForm.owner = p?.owner ?? ''
  policyForm.status = p?.status ?? 'ACTIVE'
  policyOpen.value = true
}

async function submitPolicy() {
  if (!policyForm.code || !policyForm.name || !policyForm.scheduleCron || !policyForm.storageTarget || !policyForm.owner) {
    toast.warn('请填写编码、名称、计划、存储目标与负责人')
    return
  }
  if (policyForm.scopeType !== 'ALL' && !policyForm.scopeRef) {
    toast.warn('该范围类型必须填写范围引用')
    return
  }
  policyBusy.value = true
  try {
    await opsApi.upsertBackupPolicy({
      code: policyForm.code,
      name: policyForm.name,
      scopeType: policyForm.scopeType,
      scopeRef: policyForm.scopeType === 'ALL' ? undefined : policyForm.scopeRef,
      backupType: policyForm.backupType,
      scheduleCron: policyForm.scheduleCron,
      retentionDays: policyForm.retentionDays,
      encryption: policyForm.encryption,
      storageTarget: policyForm.storageTarget,
      rpoMinutes: policyForm.rpoMinutes,
      owner: policyForm.owner,
      status: policyForm.status,
    })
    policyOpen.value = false
    toast.ok('备份策略已保存', policyForm.code)
    await loadPolicies()
  } catch (e) {
    toast.error('保存失败', errorText(e))
  } finally {
    policyBusy.value = false
  }
}

/* ------------------------------------------------------------------ 备份运行动作 */
function openRun() {
  runForm.policyCode = activePolicies.value[0]?.code ?? ''
  runForm.backupType = activePolicies.value[0]?.backupType ?? 'FULL'
  runForm.status = 'SUCCESS'
  runForm.idempotencyKey = newKey()
  runForm.startedAt = toLocalInput(new Date(Date.now() - 10 * 60_000))
  runForm.finishedAt = toLocalInput(new Date())
  runForm.sizeMb = null
  runForm.checksum = ''
  runForm.storageLocation = ''
  runForm.errorMessage = ''
  runOpen.value = true
}

function applyPolicyDefaults() {
  const policy = policies.value.find((p) => p.code === runForm.policyCode)
  if (policy) {
    runForm.backupType = policy.backupType
  }
}

async function submitRun() {
  if (!runForm.policyCode) {
    toast.warn('请选择备份策略')
    return
  }
  if (!runForm.idempotencyKey || !runForm.startedAt) {
    toast.warn('请填写幂等键与开始时间')
    return
  }
  if (runForm.status === 'SUCCESS' && (!runForm.checksum || !runForm.storageLocation || runForm.sizeMb === null)) {
    toast.warn('成功的备份必须填写大小、校验和与存储位置')
    return
  }
  if (runForm.status === 'FAILED' && !runForm.errorMessage) {
    toast.warn('失败的备份必须填写错误原因')
    return
  }
  runBusy.value = true
  try {
    const saved = await opsApi.reportBackupRun({
      policyCode: runForm.policyCode,
      backupType: runForm.backupType,
      status: runForm.status,
      idempotencyKey: runForm.idempotencyKey,
      startedAt: new Date(runForm.startedAt).toISOString(),
      finishedAt: runForm.finishedAt ? new Date(runForm.finishedAt).toISOString() : undefined,
      sizeBytes: runForm.sizeMb === null ? undefined : Math.round(runForm.sizeMb * 1024 * 1024),
      checksum: runForm.checksum || undefined,
      storageLocation: runForm.storageLocation || undefined,
      errorMessage: runForm.errorMessage || undefined,
    })
    runOpen.value = false
    toast.ok('备份运行已记录', saved.runNo)
    await Promise.all([loadRuns(), loadPolicies()])
  } catch (e) {
    toast.error('上报失败', errorText(e))
  } finally {
    runBusy.value = false
  }
}

/* ------------------------------------------------------------------ 演练动作 */
function openDrill() {
  drillForm.title = ''
  drillForm.scope = 'ALL'
  drillForm.environment = 'PROD'
  drillForm.backupRunId = ''
  drillForm.rtoTargetMinutes = 120
  drillForm.rpoTargetMinutes = 15
  drillForm.note = ''
  drillOpen.value = true
}

async function submitDrill() {
  if (!drillForm.title || !drillForm.scope) {
    toast.warn('请填写标题与范围')
    return
  }
  drillBusy.value = true
  try {
    const created = await opsApi.createDrill({
      title: drillForm.title,
      scope: drillForm.scope,
      environment: drillForm.environment,
      backupRunId: drillForm.backupRunId || undefined,
      rtoTargetMinutes: drillForm.rtoTargetMinutes,
      rpoTargetMinutes: drillForm.rpoTargetMinutes,
      note: drillForm.note || undefined,
    })
    drillOpen.value = false
    toast.ok('演练已创建', created.drillNo)
    await loadDrills()
    await selectDrill(created)
  } catch (e) {
    toast.error('创建失败', errorText(e))
  } finally {
    drillBusy.value = false
  }
}

async function startDrill() {
  if (!selectedDrill.value) return
  actionBusy.value = true
  try {
    const updated = await opsApi.startDrill(selectedDrill.value.id)
    selectedDrill.value = updated
    toast.ok('演练已开始', updated.drillNo)
    await loadDrills()
  } catch (e) {
    toast.error('开始失败', errorText(e))
  } finally {
    actionBusy.value = false
  }
}

function openStep(step: DrDrillStep) {
  stepForm.stepNo = step.stepNo
  stepForm.name = step.name
  stepForm.required = step.required
  stepForm.status = step.status === 'PENDING' ? 'PASSED' : step.status
  stepForm.evidence = step.evidence ?? ''
  stepForm.note = step.note ?? ''
  stepOpen.value = true
}

async function submitStep() {
  if (!selectedDrill.value) return
  stepBusy.value = true
  try {
    const updated = await opsApi.updateDrillStep(selectedDrill.value.id, stepForm.stepNo, {
      status: stepForm.status,
      evidence: stepForm.evidence || undefined,
      note: stepForm.note || undefined,
    })
    selectedDrill.value = updated
    stepOpen.value = false
    toast.ok('步骤已登记', `步骤 ${stepForm.stepNo}`)
  } catch (e) {
    toast.error('登记失败', errorText(e))
  } finally {
    stepBusy.value = false
  }
}

function openComplete() {
  if (!selectedDrill.value) return
  completeForm.rtoActualMinutes = selectedDrill.value.rtoTargetMinutes
  completeForm.rpoActualMinutes = selectedDrill.value.rpoTargetMinutes
  completeForm.dataLossMinutes = null
  completeForm.consistencyVerified = false
  completeForm.evidence = ''
  completeForm.note = ''
  completeOpen.value = true
}

async function submitComplete() {
  if (!selectedDrill.value) return
  if (!completeForm.consistencyVerified) {
    toast.warn('请先完成数据一致性核对并勾选')
    return
  }
  if (!completeForm.evidence) {
    toast.warn('请填写演练证据')
    return
  }
  completeBusy.value = true
  try {
    const updated = await opsApi.completeDrill(selectedDrill.value.id, {
      rtoActualMinutes: completeForm.rtoActualMinutes,
      rpoActualMinutes: completeForm.rpoActualMinutes,
      dataLossMinutes: completeForm.dataLossMinutes ?? undefined,
      consistencyVerified: true,
      evidence: completeForm.evidence,
      note: completeForm.note || undefined,
    })
    selectedDrill.value = updated
    completeOpen.value = false
    toast.ok('演练已判定通过', updated.drillNo)
    await Promise.all([loadDrills(), runGate()])
  } catch (e) {
    toast.error('判定失败', errorText(e))
  } finally {
    completeBusy.value = false
  }
}

function openReason(mode: 'FAIL' | 'ABORT') {
  reasonMode.value = mode
  reasonText.value = ''
  reasonOpen.value = true
}

async function submitReason() {
  if (!selectedDrill.value) return
  if (reasonText.value.trim().length < 2) {
    toast.warn('请填写原因（至少 2 个字）')
    return
  }
  reasonBusy.value = true
  try {
    const id = selectedDrill.value.id
    const updated =
      reasonMode.value === 'FAIL'
        ? await opsApi.failDrill(id, reasonText.value.trim())
        : await opsApi.abortDrill(id, reasonText.value.trim())
    selectedDrill.value = updated
    reasonOpen.value = false
    toast.ok(reasonMode.value === 'FAIL' ? '已判定失败' : '已中止演练', updated.drillNo)
    await loadDrills()
  } catch (e) {
    toast.error('提交失败', errorText(e))
  } finally {
    reasonBusy.value = false
  }
}

/* ------------------------------------------------------------------ 展示辅助 */
function scopeText(p: BackupPolicy): string {
  if (p.scopeType === 'ALL') return '全院'
  return p.scopeType === 'COMPONENT' ? `组件 ${p.scopeRef}` : `Schema ${p.scopeRef}`
}

function runText(status: BackupRunStatus): string {
  return status === 'SUCCESS' ? '成功' : status === 'FAILED' ? '失败' : '进行中'
}

function runBadge(status: BackupRunStatus): string {
  return status === 'SUCCESS' ? 'badge--ok' : status === 'FAILED' ? 'badge--danger' : 'badge--warn'
}

function drText(status: DrStatus): string {
  const map: Record<DrStatus, string> = {
    PLANNED: '计划中',
    RUNNING: '进行中',
    PASSED: '已通过',
    FAILED: '失败',
    ABORTED: '已中止',
  }
  return map[status]
}

function drBadge(status: DrStatus): string {
  if (status === 'PASSED') return 'badge--ok'
  if (status === 'RUNNING') return 'badge--warn'
  if (status === 'FAILED') return 'badge--danger'
  return 'badge--neutral'
}

function stepBadge(status: DrStepStatus): string {
  if (status === 'PASSED') return 'badge--ok'
  if (status === 'FAILED') return 'badge--danger'
  if (status === 'SKIPPED') return 'badge--neutral'
  return 'badge--warn'
}

function fmtTime(value?: string | null): string {
  if (!value) return '—'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return date.toLocaleString('zh-CN', { hour12: false })
}

function fmtSize(bytes?: number | null): string {
  if (bytes === null || bytes === undefined) return '—'
  if (bytes >= 1024 * 1024 * 1024) return `${(bytes / (1024 * 1024 * 1024)).toFixed(2)} GB`
  if (bytes >= 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
  if (bytes >= 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${bytes} B`
}

function toLocalInput(date: Date): string {
  const pad = (n: number) => `${n}`.padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`
}

function newKey(): string {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) {
    return crypto.randomUUID()
  }
  return `bk-${Date.now()}-${Math.random().toString(16).slice(2, 10)}`
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
.gate--ok {
  border-left: 3px solid var(--positive-ink, #15803d);
}
.gate--warn {
  border-left: 3px solid var(--danger-ink, #b91c1c);
}
.gate-body {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.gate-reason {
  margin: 0;
  font-size: var(--fs-body);
}
.gate-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 14px;
  font-size: var(--fs-small);
  color: var(--muted);
}
.ellipsis {
  max-width: 220px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.pager {
  display: flex;
  align-items: center;
  gap: 10px;
  justify-content: flex-end;
  margin-top: 8px;
}
.drill-foot {
  margin-top: 10px;
  padding-top: 10px;
  border-top: 1px dashed var(--line, #e5e7eb);
  font-size: var(--fs-small);
  line-height: 1.7;
  word-break: break-all;
}
.drill-foot p {
  margin: 0;
}
.check-row {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  margin: 10px 0;
  font-size: var(--fs-body);
  line-height: 1.5;
}
</style>
