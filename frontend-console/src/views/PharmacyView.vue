<template>
  <div class="page">
    <PageHeader
      title="药房工作台"
      :sub="`开方—审核—调配三权分立：医师只开方，药师审核与发药分开授权。发药按近效期优先扣批次，退药回补原批次。当前角色 ${role}`"
    >
      <template #actions>
        <button class="btn" type="button" :disabled="loading" @click="reloadAll">
          <AppIcon name="refresh" :size="15" /> 刷新
        </button>
      </template>
    </PageHeader>

    <div class="kpi-strip">
      <KpiCard
        label="待审核"
        :value="countOf('PENDING_REVIEW')"
        unit="张"
        :meta="countOf('PENDING_REVIEW') > 0 ? '存在 BLOCK 级禁忌时必须驳回' : '审核队列已清空'"
        :tone="countOf('PENDING_REVIEW') > 0 ? 'warn' : 'default'"
      />
      <KpiCard
        label="待发药"
        :value="countOf('APPROVED')"
        unit="张"
        :meta="countOf('APPROVED') > 0 ? '已审核通过，等待窗口调配' : '没有等待调配的处方'"
        :tone="countOf('APPROVED') > 0 ? 'accent' : 'default'"
      />
      <KpiCard
        label="已发药"
        :value="countOf('DISPENSED')"
        unit="张"
        meta="服务端聚合计数，全量准确"
      />
      <KpiCard
        label="库存预警"
        :value="warnings.length"
        unit="批"
        :meta="`已过期 ${expiredCount} 批 · 近效期 ${nearExpiryCount} 批`"
        :tone="expiredCount > 0 ? 'danger' : warnings.length > 0 ? 'warn' : 'default'"
      />
    </div>

    <div class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">处方队列</span>
        <span class="badge badge--neutral">{{ filtered.length }}</span>
        <span class="spacer" />
        <label class="field queue-search">
          <span class="label">筛选</span>
          <input
            v-model.trim="keyword"
            class="input"
            placeholder="处方号 / 病案号 / 就诊号 / 医师"
            aria-label="按处方号、病案号、就诊号或医师筛选"
          />
        </label>
        <div class="seg" role="tablist" aria-label="按处方状态筛选">
          <button
            v-for="t in STATUS_TABS"
            :key="t.value || 'all'"
            class="seg-btn"
            :class="{ active: status === t.value }"
            type="button"
            @click="setStatus(t.value)"
          >
            {{ t.label }}
          </button>
        </div>
      </div>

      <TableSkeleton v-if="loading" :rows="5" />

      <LoadError
        v-else-if="loadError"
        :message="loadError"
        :retrying="loading"
        @retry="reloadAll"
      />

      <EmptyState
        v-else-if="filtered.length === 0"
        art="orders"
        title="当前筛选条件下没有处方"
        desc="处方由医师开立或临床医嘱转送生成；审核通过后进入待发药队列。可切换状态页签或清空筛选关键词。"
      />

      <div v-else class="table-wrap">
        <table class="table table--stack">
          <thead>
            <tr>
              <th>处方号</th>
              <th>患者</th>
              <th>开方</th>
              <th>计费口径</th>
              <th class="is-num">金额</th>
              <th>状态</th>
              <th style="width: 92px">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="r in filtered"
              :key="r.id"
              class="clickable"
              tabindex="0"
              @click="openDetail(r)"
              @keydown.enter="openDetail(r)"
            >
              <td class="mono" data-primary>{{ r.prescriptionNo }}</td>
              <td data-label="患者">
                <div class="is-strong mono">{{ r.patientNo }}</div>
                <div class="muted" style="font-size: var(--fs-small)">
                  {{ r.visitNo || '无关联就诊' }}
                </div>
              </td>
              <td data-label="开方">
                <div>{{ r.prescriber }}</div>
                <div class="muted" style="font-size: var(--fs-small)">
                  {{ dateTime(r.createdAt) }}
                </div>
              </td>
              <td data-label="计费口径">
                <span class="badge badge--neutral">
                  {{ r.billingMode === 'DISPENSE' ? '发药时计费' : '医嘱已计费' }}
                </span>
              </td>
              <td class="is-num is-strong" data-label="金额">{{ money(r.totalAmount) }}</td>
              <td data-label="状态">
                <StatusPill :value="r.status" :label="rxStatusLabel(r.status)" />
              </td>
              <td data-label="操作">
                <button class="btn btn--sm" type="button" @click.stop="openDetail(r)">查看</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <div class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">药品与库存</span>
        <span class="badge badge--neutral">{{ stockTab === 'drugs' ? drugs.length : warnings.length }}</span>
        <span class="spacer" />
        <template v-if="stockTab === 'drugs'">
          <label class="field stock-search">
            <span class="label">检索</span>
            <input
              v-model.trim="drugKeyword"
              class="input"
              placeholder="药品名称 / 编码 / 商品名"
              aria-label="按药品名称、编码或商品名检索"
              @keydown.enter="loadDrugs"
            />
          </label>
          <button class="btn btn--sm" type="button" :disabled="stockLoading" @click="loadDrugs">
            <AppIcon name="search" :size="13" /> 查询
          </button>
        </template>
        <span v-else class="hint">按效期升序排列；过期批次不得发出，需退货或销毁</span>
        <div class="seg" role="tablist" aria-label="药品或库存预警">
          <button
            class="seg-btn"
            :class="{ active: stockTab === 'drugs' }"
            type="button"
            @click="stockTab = 'drugs'"
          >
            药品目录
          </button>
          <button
            class="seg-btn"
            :class="{ active: stockTab === 'warnings' }"
            type="button"
            @click="stockTab = 'warnings'"
          >
            库存预警
          </button>
        </div>
      </div>

      <TableSkeleton v-if="stockLoading && stockTab === 'drugs'" :rows="4" />

      <EmptyState
        v-else-if="stockTab === 'drugs' && drugs.length === 0"
        art="orders"
        title="没有匹配的药品"
        desc="药品字典由药房维护；停用药品不再接受开方，但已开处方仍可发药。"
      />

      <EmptyState
        v-else-if="stockTab === 'warnings' && warnings.length === 0"
        art="orders"
        title="暂无近效期或已过期批次"
        desc="系统按效期阈值自动预警；发药始终按近效期优先（FEFO），过期批次不会出库。"
      />

      <div v-else-if="stockTab === 'drugs'" class="table-wrap">
        <table class="table table--stack">
          <thead>
            <tr>
              <th>药品</th>
              <th>规格</th>
              <th>剂型</th>
              <th>医保</th>
              <th class="is-num">单价</th>
              <th class="is-num">总库存</th>
              <th>状态</th>
              <th style="width: 112px">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="d in drugs" :key="d.drug.id">
              <td data-primary>
                <div class="is-strong">{{ d.drug.name }}</div>
                <div class="mono muted">
                  {{ d.drug.drugCode }}<template v-if="d.drug.tradeName"> · {{ d.drug.tradeName }}</template>
                </div>
              </td>
              <td data-label="规格">{{ d.drug.spec }}</td>
              <td data-label="剂型">{{ dosageFormLabel(d.drug.dosageForm) }}</td>
              <td data-label="医保">{{ insuranceLabel(d.drug.insuranceCategory) }}</td>
              <td class="is-num" data-label="单价">{{ money(d.drug.price) }}</td>
              <td class="is-num" data-label="总库存">
                <span :class="{ 'stock-empty': d.totalStock === 0 }">
                  {{ d.totalStock }} {{ d.drug.unit }}
                </span>
              </td>
              <td data-label="状态">
                <StatusPill :value="d.drug.enabled ? 'ACTIVE' : 'INACTIVE'" />
                <span v-if="d.drug.narcotic" class="badge badge--danger narcotic-tag">麻精</span>
              </td>
              <td data-label="操作">
                <button class="btn btn--sm" type="button" @click="openBatches(d)">批次 / 入库</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <div v-else class="table-wrap">
        <table class="table table--stack">
          <thead>
            <tr>
              <th>药品</th>
              <th>批号</th>
              <th>效期</th>
              <th class="is-num">余量</th>
              <th>状态</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="w in warnings" :key="`${w.drugCode}-${w.batchNo}`">
              <td data-primary>
                <div class="is-strong">{{ w.drugName }}</div>
                <div class="mono muted">{{ w.drugCode }}</div>
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
    </div>

    <AppModal
      v-model="detailOpen"
      title="处方明细"
      :subtitle="detail?.prescription.prescriptionNo"
      width="920px"
    >
      <TableSkeleton v-if="detailLoading" :rows="6" />

      <div v-else-if="detail" class="detail">
        <div class="detail-bar">
          <StatusPill :value="detail.prescription.status" :label="rxStatusLabel(detail.prescription.status)" />
          <span class="mono">{{ detail.prescription.prescriptionNo }}</span>
          <span class="badge badge--neutral">
            {{ detail.prescription.billingMode === 'DISPENSE' ? '发药时计费' : '医嘱已计费' }}
          </span>
          <span class="spacer" />
          <span class="muted">处方金额</span>
          <strong class="num">{{ money(detail.prescription.totalAmount) }}</strong>
        </div>

        <div class="detail-grid">
          <div class="kv"><span>患者病案号</span><strong class="mono">{{ detail.prescription.patientNo }}</strong></div>
          <div class="kv"><span>就诊号</span><strong class="mono">{{ detail.prescription.visitNo || '—' }}</strong></div>
          <div class="kv"><span>开方医师</span><strong>{{ detail.prescription.prescriber }}</strong></div>
          <div class="kv"><span>开方时间</span><strong>{{ dateTime(detail.prescription.createdAt) }}</strong></div>
          <div class="kv"><span>审核人</span><strong>{{ detail.prescription.reviewer || '—' }}</strong></div>
          <div class="kv"><span>审核时间</span><strong>{{ dateTime(detail.prescription.reviewedAt) }}</strong></div>
          <div class="kv"><span>发药日</span><strong>{{ detail.prescription.supplyDate || '—' }}</strong></div>
          <div class="kv"><span>来源医嘱</span><strong class="mono">{{ shortId(detail.prescription.sourceOrderId) }}</strong></div>
        </div>

        <div v-if="detail.prescription.rejectReason" class="notice notice--danger">
          驳回原因：{{ detail.prescription.rejectReason }}
        </div>

        <div class="detail-section">
          <div class="detail-section-title">处方明细（{{ detail.items.length }}）</div>
          <div class="table-wrap">
            <table class="table table--stack table--compact">
              <thead>
                <tr>
                  <th>药品</th>
                  <th>规格</th>
                  <th>剂量 / 频次</th>
                  <th class="is-num">天数</th>
                  <th class="is-num">数量</th>
                  <th class="is-num">单价</th>
                  <th class="is-num">金额</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="i in detail.items" :key="i.id">
                  <td data-primary>
                    <div class="is-strong">{{ i.drugName }}</div>
                    <div class="mono muted">{{ i.drugCode }}</div>
                  </td>
                  <td data-label="规格">{{ i.spec || '—' }}</td>
                  <td data-label="剂量 / 频次">
                    <div>{{ i.dosage || '—' }}</div>
                    <div class="muted" style="font-size: var(--fs-small)">
                      {{ i.frequency || '—' }}
                      <template v-if="i.dailyDose"> · 日剂量 {{ i.dailyDose }}{{ i.doseUnit || '' }}</template>
                    </div>
                  </td>
                  <td class="is-num" data-label="天数">{{ i.days ?? '—' }}</td>
                  <td class="is-num" data-label="数量">{{ i.quantity }} {{ i.unit }}</td>
                  <td class="is-num" data-label="单价">{{ money(i.unitPrice) }}</td>
                  <td class="is-num is-strong" data-label="金额">{{ money(i.amount) }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>

        <div class="detail-section">
          <div class="detail-section-title">审方记录（{{ auditView?.audits.length ?? 0 }}）</div>
          <div v-if="auditView?.blocked" class="notice notice--danger">
            存在 BLOCK 级用药禁忌：审核不可通过，请驳回并让医师修改后重开。
          </div>
          <div v-else-if="auditView?.warned" class="notice notice--warn">
            存在用药提醒：请结合患者情况判断后再决定是否通过。
          </div>
          <div v-if="!auditView || auditView.audits.length === 0" class="muted audit-empty">
            暂无审方规则命中；这不代表没有禁忌，仍需药师人工核对。
          </div>
          <div v-for="a in auditView?.audits" :key="a.id" class="audit-row">
            <span class="badge" :class="auditTone(a.level)">{{ auditLevelLabel(a.level) }}</span>
            <span class="mono">{{ a.ruleCode }}</span>
            <span class="audit-msg">{{ a.message }}</span>
            <span class="spacer" />
            <span class="muted" style="font-size: var(--fs-small)">
              {{ a.phase === 'CREATE' ? '开方评估' : '审核复评' }} · {{ dateTime(a.createdAt) }}
            </span>
          </div>
        </div>

        <div v-if="dispense" class="detail-section">
          <div class="detail-section-title">发药记录 · {{ dispense.dispense.dispenseNo }}</div>
          <div class="detail-grid">
            <div class="kv"><span>发药人</span><strong>{{ dispense.dispense.operator }}</strong></div>
            <div class="kv"><span>核对人</span><strong>{{ dispense.dispense.checker || '—' }}</strong></div>
            <div class="kv"><span>发药时间</span><strong>{{ dateTime(dispense.dispense.dispensedAt) }}</strong></div>
            <div class="kv">
              <span>计费状态</span>
              <strong :class="{ 'billing-bad': billingAbnormal }">{{ billingLabel(dispense.dispense.billingStatus) }}</strong>
            </div>
          </div>

          <div class="table-wrap">
            <table class="table table--stack table--compact">
              <thead>
                <tr>
                  <th>批号</th>
                  <th>效期</th>
                  <th class="is-num">发药量</th>
                  <th class="is-num">已退</th>
                  <th class="is-num">金额</th>
                  <th style="width: 88px">操作</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="it in dispense.items" :key="it.id">
                  <td class="mono" data-primary>{{ it.batchNo }}</td>
                  <td data-label="效期">{{ date(it.expireDate) }}</td>
                  <td class="is-num" data-label="发药量">{{ it.quantity }}</td>
                  <td class="is-num" data-label="已退">{{ it.returnedQuantity }}</td>
                  <td class="is-num" data-label="金额">{{ money(it.amount) }}</td>
                  <td data-label="操作">
                    <button
                      v-if="canReturn && it.quantity > it.returnedQuantity"
                      class="btn btn--sm"
                      type="button"
                      @click="openReturn(it)"
                    >
                      退药
                    </button>
                    <span v-else class="muted">—</span>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>

          <div v-if="dispense.returns.length > 0" class="returns">
            <div v-for="r in dispense.returns" :key="r.id" class="return-row">
              <span class="mono">{{ dateTime(r.createdAt) }}</span>
              <span>退回 {{ r.quantity }}</span>
              <span class="muted">{{ r.reason }}</span>
              <span class="spacer" />
              <span class="muted">{{ r.operator }}</span>
            </div>
          </div>
        </div>
      </div>

      <template #footer>
        <span class="hint footer-hint">审核与发药分开授权：医师只能开方，药师不能修改处方内容</span>
        <span class="spacer" />
        <button
          v-if="dispense && billingAbnormal && canDispense"
          class="btn"
          type="button"
          :disabled="busy"
          @click="retryBilling"
        >
          计费重试
        </button>
        <template v-if="detail?.prescription.status === 'PENDING_REVIEW' && canReview">
          <button class="btn" type="button" :disabled="busy" @click="openReject">驳回</button>
          <button class="btn btn--primary" type="button" :disabled="busy" @click="approve">通过审核</button>
        </template>
        <button
          v-if="detail?.prescription.status === 'APPROVED' && canDispense"
          class="btn btn--primary"
          type="button"
          :disabled="busy"
          @click="openDispense"
        >
          发药
        </button>
        <button class="btn" type="button" @click="detailOpen = false">关闭</button>
      </template>
    </AppModal>

    <AppModal
      v-model="rejectOpen"
      title="驳回处方"
      :subtitle="detail?.prescription.prescriptionNo"
      width="480px"
    >
      <label class="field">
        <span class="label">驳回理由 <span class="req">*</span></span>
        <textarea
          v-model.trim="rejectReason"
          class="input"
          rows="3"
          maxlength="256"
          placeholder="例如：剂量超过日剂量上限，请调整后重开"
        />
        <span class="hint">理由会回显给开方医师，必须写清可执行的修改方向。</span>
      </label>
      <template #footer>
        <button class="btn" type="button" @click="rejectOpen = false">取消</button>
        <button
          class="btn btn--danger"
          type="button"
          :disabled="busy || !rejectReason"
          @click="submitReject"
        >
          确认驳回
        </button>
      </template>
    </AppModal>

    <AppModal
      v-model="dispenseOpen"
      title="调配发药"
      :subtitle="detail?.prescription.prescriptionNo"
      width="520px"
    >
      <div class="stack">
        <div class="notice notice--warn">
          发药将按近效期优先扣减批次库存；一张处方只能发一次药，扣减与发药同事务。
        </div>
        <div v-if="narcoticItems.length > 0" class="notice notice--danger">
          含麻醉/精神药品：{{ narcoticItems.map((i) => i.drugName).join('、') }}。必须双人核对并登记专册。
        </div>
        <label class="field">
          <span class="label">
            核对人
            <span v-if="narcoticItems.length > 0" class="req">*</span>
            <span v-else class="hint">（可选）</span>
          </span>
          <input
            v-model.trim="checker"
            class="input"
            maxlength="64"
            placeholder="第二核对人姓名或工号"
          />
          <span class="hint">核对人不得与发药人相同；麻醉/精神药品未核对无法发药。</span>
        </label>
      </div>
      <template #footer>
        <button class="btn" type="button" @click="dispenseOpen = false">取消</button>
        <button
          class="btn btn--primary"
          type="button"
          :disabled="busy || (narcoticItems.length > 0 && !checker)"
          @click="submitDispense"
        >
          确认发药
        </button>
      </template>
    </AppModal>

    <AppModal
      v-model="returnOpen"
      title="退药回补"
      :subtitle="returnItem ? `${returnItem.batchNo} · 效期 ${date(returnItem.expireDate)}` : ''"
      width="520px"
    >
      <div v-if="returnItem" class="stack">
        <div class="return-info">
          <span class="muted">可退数量</span>
          <strong class="num">{{ returnItem.quantity - returnItem.returnedQuantity }}</strong>
        </div>
        <label class="field">
          <span class="label">退回数量 <span class="req">*</span></span>
          <input
            v-model.number="returnQty"
            class="input num"
            type="number"
            min="1"
            :max="returnItem.quantity - returnItem.returnedQuantity"
          />
          <span class="hint">不得超过已发未退数量；退回库存回补到原批次，不新建批次。</span>
        </label>
        <label class="field">
          <span class="label">退药原因 <span class="req">*</span></span>
          <textarea
            v-model.trim="returnReason"
            class="input"
            rows="2"
            maxlength="256"
            placeholder="例如：医嘱停用，病区退回"
          />
        </label>
        <span class="hint">本次退药单号（幂等键）：<span class="mono">{{ returnId }}</span>，重复提交不会重复回补。</span>
      </div>
      <template #footer>
        <button class="btn" type="button" @click="returnOpen = false">取消</button>
        <button
          class="btn btn--primary"
          type="button"
          :disabled="busy || !canSubmitReturn"
          @click="submitReturn"
        >
          确认退药
        </button>
      </template>
    </AppModal>

    <AppModal
      v-model="batchOpen"
      title="批次库存"
      :subtitle="currentDrug ? `${currentDrug.drug.name} · ${currentDrug.drug.spec}` : ''"
      width="760px"
    >
      <TableSkeleton v-if="batchesLoading" :rows="4" height="40px" />
      <div v-else-if="currentDrug" class="detail">
        <div class="detail-bar">
          <span class="badge badge--neutral">
            总库存 {{ currentDrug.totalStock }} {{ currentDrug.drug.unit }}
          </span>
          <span class="mono">{{ currentDrug.drug.drugCode }}</span>
          <span class="spacer" />
          <span class="hint">发药按近效期优先（FEFO），过期批次不会发出</span>
        </div>

        <div v-if="batches.length === 0" class="muted">该药品暂无批次库存，请先入库登记。</div>
        <div v-else class="table-wrap">
          <table class="table table--stack table--compact">
            <thead>
              <tr>
                <th>批号</th>
                <th>效期</th>
                <th class="is-num">余量</th>
                <th>供应商</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="b in batches" :key="b.id">
                <td class="mono" data-primary>{{ b.batchNo }}</td>
                <td data-label="效期">{{ date(b.expireDate) }}</td>
                <td class="is-num" data-label="余量">{{ b.quantity }} {{ currentDrug.drug.unit }}</td>
                <td data-label="供应商">{{ b.supplier || '—' }}</td>
              </tr>
            </tbody>
          </table>
        </div>

        <div v-if="canManageStock" class="detail-section">
          <div class="detail-section-title">入库登记</div>
          <div class="receive-form">
            <label class="field">
              <span class="label">批号 <span class="req">*</span></span>
              <input v-model.trim="receiveForm.batchNo" class="input" maxlength="64" />
            </label>
            <label class="field">
              <span class="label">效期 <span class="req">*</span></span>
              <input v-model="receiveForm.expireDate" class="input" type="date" />
            </label>
            <label class="field">
              <span class="label">数量 <span class="req">*</span></span>
              <input v-model.number="receiveForm.quantity" class="input num" type="number" min="1" />
            </label>
            <label class="field grow">
              <span class="label">供应商</span>
              <input v-model.trim="receiveForm.supplier" class="input" maxlength="128" />
            </label>
            <button
              class="btn btn--primary"
              type="button"
              :disabled="busy || !receiveValid"
              @click="submitReceive"
            >
              入库
            </button>
          </div>
          <span class="hint">同一药品同一批号只能入库一次；重复入库请核对批号后补录。</span>
        </div>
        <div v-else class="hint">当前账号没有入库权限（需 pharmacy:stock:manage）。</div>
      </div>
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
import { pharmacyApi } from '@/api'
import { errorText } from '@/api/http'
import type {
  DispenseItem,
  DispenseView,
  DrugBatch,
  DrugView,
  Prescription,
  PrescriptionAuditView,
  PrescriptionView,
  StockWarning,
} from '@/api/types'
import { hasPermission, session } from '@/stores/session'
import { toast } from '@/stores/toast'
import { date, dateTime, money, todayIso } from '@/utils/format'

const STATUS_TABS = [
  { value: 'PENDING_REVIEW', label: '待审核' },
  { value: 'APPROVED', label: '待发药' },
  { value: 'DISPENSED', label: '已发药' },
  { value: 'REJECTED', label: '已驳回' },
  { value: '', label: '全部' },
]

const role = session.roleLabel
const canReview = computed(() => hasPermission('pharmacy:prescription:review'))
const canDispense = computed(() => hasPermission('pharmacy:dispense:execute'))
const canReturn = computed(() => hasPermission('pharmacy:dispense:return'))
const canManageStock = computed(() => hasPermission('pharmacy:stock:manage'))

const loading = ref(false)
const busy = ref(false)
/** 加载失败文案：非空时展示错误态而非空态，避免把故障误报为"没有数据"。 */
const loadError = ref('')
const rows = ref<Prescription[]>([])
/** 服务端聚合的各状态计数（不受列表 200 条上限影响）。 */
const counts = ref<Record<string, number>>({})
const keyword = ref('')
const status = ref('PENDING_REVIEW')

const warnings = ref<StockWarning[]>([])
const drugs = ref<DrugView[]>([])
const drugKeyword = ref('')
const stockTab = ref<'drugs' | 'warnings'>('drugs')
const stockLoading = ref(false)

const expiredCount = computed(() => warnings.value.filter((w) => w.expired).length)
const nearExpiryCount = computed(() => warnings.value.length - expiredCount.value)

function countOf(value: string): number {
  return counts.value[value] ?? 0
}

const filtered = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  if (!kw) return rows.value
  return rows.value.filter((r) =>
    [r.prescriptionNo, r.patientNo, r.visitNo, r.prescriber].some((v) =>
      (v ?? '').toLowerCase().includes(kw),
    ),
  )
})

/** 切换状态页签：列表由服务端按状态过滤，需重新加载。 */
function setStatus(value: string) {
  status.value = value
  void load()
}

async function load() {
  loading.value = true
  try {
    // 计数走服务端聚合（准确、不受 200 条上限影响）；列表只取当前状态页签。
    const [summary, list, warn] = await Promise.all([
      pharmacyApi.prescriptionSummary(),
      pharmacyApi.prescriptions(status.value || undefined, 200),
      pharmacyApi.stockWarnings(),
    ])
    counts.value = summary
    rows.value = list
    warnings.value = warn
    loadError.value = ''
  } catch (e) {
    toast.error('药房数据加载失败', errorText(e))
    rows.value = []
    warnings.value = []
    counts.value = {}
    // 失败必须与"没有数据"区分：空态会让药师误判为无待办
    loadError.value = errorText(e)
  } finally {
    loading.value = false
  }
}

async function loadDrugs() {
  stockLoading.value = true
  try {
    drugs.value = await pharmacyApi.drugs(drugKeyword.value || undefined, undefined, 200)
  } catch (e) {
    toast.error('药品目录加载失败', errorText(e))
    drugs.value = []
  } finally {
    stockLoading.value = false
  }
}

function reloadAll() {
  void Promise.all([load(), loadDrugs()])
}

// ---------------------------------------------------------------- 处方详情

const detailOpen = ref(false)
const detailLoading = ref(false)
const detail = ref<PrescriptionView | null>(null)
const auditView = ref<PrescriptionAuditView | null>(null)
const dispense = ref<DispenseView | null>(null)

async function fetchDetail(id: string) {
  const [view, audit, dispensed] = await Promise.all([
    pharmacyApi.prescription(id),
    pharmacyApi.prescriptionAudit(id),
    pharmacyApi.dispenseOfPrescription(id),
  ])
  detail.value = view
  auditView.value = audit
  dispense.value = dispensed ?? null
}

async function openDetail(row: Prescription) {
  detailOpen.value = true
  detailLoading.value = true
  detail.value = null
  auditView.value = null
  dispense.value = null
  try {
    await fetchDetail(row.id)
  } catch (e) {
    toast.error('处方明细加载失败', errorText(e))
    detailOpen.value = false
  } finally {
    detailLoading.value = false
  }
}

async function refreshDetail() {
  const id = detail.value?.prescription.id
  if (!id) return
  try {
    await fetchDetail(id)
  } catch (e) {
    toast.error('刷新处方明细失败', errorText(e))
  }
}

// ---------------------------------------------------------------- 审核

const rejectOpen = ref(false)
const rejectReason = ref('')

function openReject() {
  rejectReason.value = ''
  rejectOpen.value = true
}

async function approve() {
  if (!detail.value) return
  busy.value = true
  try {
    const view = await pharmacyApi.reviewPrescription(detail.value.prescription.id, true)
    toast.ok('审核通过', `${view.prescription.prescriptionNo} 已进入待发药队列`)
    await refreshDetail()
    await load()
  } catch (e) {
    toast.error('审核未通过', errorText(e))
  } finally {
    busy.value = false
  }
}

async function submitReject() {
  if (!detail.value || !rejectReason.value) return
  busy.value = true
  try {
    const view = await pharmacyApi.reviewPrescription(
      detail.value.prescription.id,
      false,
      rejectReason.value,
    )
    toast.ok('已驳回', `${view.prescription.prescriptionNo} 已退回开方医师`)
    rejectOpen.value = false
    await refreshDetail()
    await load()
  } catch (e) {
    toast.error('驳回失败', errorText(e))
  } finally {
    busy.value = false
  }
}

// ---------------------------------------------------------------- 发药

const dispenseOpen = ref(false)
const checker = ref('')

/** 药品字典里标记为麻精的明细（字典未加载时为空，由服务端兜底拒绝）。 */
const narcoticItems = computed(() => {
  const narcoticCodes = new Set(
    drugs.value.filter((d) => d.drug.narcotic).map((d) => d.drug.drugCode),
  )
  return (detail.value?.items ?? []).filter((i) => narcoticCodes.has(i.drugCode))
})

function openDispense() {
  checker.value = ''
  dispenseOpen.value = true
}

async function submitDispense() {
  if (!detail.value) return
  const needChecker = narcoticItems.value.length > 0
  const who = checker.value.trim()
  if (needChecker && !who) {
    toast.error('需要核对人', '麻醉/精神药品必须双人核对')
    return
  }
  if (who && who === session.user.value?.username) {
    toast.error('核对人不合法', '核对人不能是当前发药人')
    return
  }
  busy.value = true
  try {
    const result = await pharmacyApi.dispensePrescription(
      detail.value.prescription.id,
      who || undefined,
    )
    toast.ok('发药成功', `${result.dispense.dispenseNo} · 扣减 ${result.items.length} 个批次`)
    dispenseOpen.value = false
    await refreshDetail()
    await load()
  } catch (e) {
    toast.error('发药失败', errorText(e))
  } finally {
    busy.value = false
  }
}

// ---------------------------------------------------------------- 退药

const returnOpen = ref(false)
const returnItem = ref<DispenseItem | null>(null)
const returnQty = ref(1)
const returnReason = ref('')
const returnId = ref('')

function newId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (ch) => {
    const r = (Math.random() * 16) | 0
    const v = ch === 'x' ? r : (r & 0x3) | 0x8
    return v.toString(16)
  })
}

function openReturn(item: DispenseItem) {
  returnItem.value = item
  returnQty.value = item.quantity - item.returnedQuantity
  returnReason.value = ''
  // 幂等键在打开弹窗时固定：双击/重试都复用同一个值，避免重复回补批次库存
  returnId.value = newId()
  returnOpen.value = true
}

const returnMax = computed(() => {
  const it = returnItem.value
  return it ? it.quantity - it.returnedQuantity : 0
})

const canSubmitReturn = computed(
  () =>
    Boolean(returnItem.value) &&
    Boolean(returnReason.value) &&
    returnQty.value >= 1 &&
    returnQty.value <= returnMax.value,
)

async function submitReturn() {
  if (!dispense.value || !returnItem.value || !returnReason.value) return
  busy.value = true
  try {
    const result = await pharmacyApi.returnDrug(dispense.value.dispense.id, {
      dispenseItemId: returnItem.value.id,
      quantity: returnQty.value,
      reason: returnReason.value,
      returnId: returnId.value,
    })
    dispense.value = result
    toast.ok('退药完成', '批次库存已按原批次回补')
    returnOpen.value = false
    await refreshDetail()
    await load()
  } catch (e) {
    toast.error('退药失败', errorText(e))
  } finally {
    busy.value = false
  }
}

const billingAbnormal = computed(() => {
  const s = dispense.value?.dispense.billingStatus
  return s === 'PENDING' || s === 'CHARGE_FAILED' || s === 'REFUND_FAILED'
})

async function retryBilling() {
  if (!dispense.value) return
  busy.value = true
  try {
    const result = await pharmacyApi.retryBilling(dispense.value.dispense.id)
    dispense.value = result
    toast.ok('计费重试完成', '收费域已按幂等键处理，重复调用不会重复记账')
  } catch (e) {
    toast.error('计费重试失败', errorText(e))
  } finally {
    busy.value = false
  }
}

// ---------------------------------------------------------------- 批次与入库

const batchOpen = ref(false)
const currentDrug = ref<DrugView | null>(null)
const batches = ref<DrugBatch[]>([])
const batchesLoading = ref(false)
const receiveForm = reactive({
  batchNo: '',
  expireDate: todayIso(),
  quantity: 1,
  supplier: '',
})

const receiveValid = computed(
  () => receiveForm.batchNo.trim().length > 0 && receiveForm.expireDate.length > 0 && receiveForm.quantity >= 1,
)

async function openBatches(drug: DrugView) {
  currentDrug.value = drug
  batchOpen.value = true
  resetReceiveForm()
  batchesLoading.value = true
  try {
    batches.value = await pharmacyApi.batches(drug.drug.id)
  } catch (e) {
    toast.error('批次加载失败', errorText(e))
    batches.value = []
  } finally {
    batchesLoading.value = false
  }
}

function resetReceiveForm() {
  receiveForm.batchNo = ''
  receiveForm.expireDate = todayIso()
  receiveForm.quantity = 1
  receiveForm.supplier = ''
}

async function submitReceive() {
  if (!currentDrug.value) return
  busy.value = true
  try {
    await pharmacyApi.receiveStock(currentDrug.value.drug.id, {
      batchNo: receiveForm.batchNo,
      expireDate: receiveForm.expireDate,
      quantity: receiveForm.quantity,
      supplier: receiveForm.supplier || undefined,
    })
    toast.ok('入库成功', `${currentDrug.value.drug.name} · 批号 ${receiveForm.batchNo}`)
    resetReceiveForm()
    batches.value = await pharmacyApi.batches(currentDrug.value.drug.id)
    await Promise.all([load(), loadDrugs()])
  } catch (e) {
    toast.error('入库失败', errorText(e))
  } finally {
    busy.value = false
  }
}

// ---------------------------------------------------------------- 展示映射

function rxStatusLabel(value: string): string | undefined {
  if (value === 'APPROVED') return '待发药'
  return undefined
}

function dosageFormLabel(value: string): string {
  const map: Record<string, string> = {
    TABLET: '片剂',
    CAPSULE: '胶囊',
    INJECTION: '注射剂',
    GRANULE: '颗粒',
    LIQUID: '液体',
    OINTMENT: '膏剂',
    OTHER: '其他',
  }
  return map[value] ?? value
}

function insuranceLabel(value: string | null): string {
  const map: Record<string, string> = { A: '甲类', B: '乙类', C: '丙类', NONE: '自费' }
  return value ? map[value] ?? value : '—'
}

function auditLevelLabel(level: string): string {
  const map: Record<string, string> = { BLOCK: '禁用', WARN: '提醒', INFO: '提示' }
  return map[level] ?? level
}

function auditTone(level: string): string {
  const map: Record<string, string> = {
    BLOCK: 'badge--danger',
    WARN: 'badge--warn',
    INFO: 'badge--neutral',
  }
  return map[level] ?? 'badge--neutral'
}

function billingLabel(status: string): string {
  const map: Record<string, string> = {
    NONE: '不发药计费',
    PENDING: '计费中',
    CHARGED: '已计费',
    CHARGE_FAILED: '计费失败（可重试）',
    REFUND_PENDING: '退款中',
    REFUND_FAILED: '退款失败（可重试）',
  }
  return map[status] ?? status
}

function shortId(value: string | null): string {
  return value ? `${value.slice(0, 8)}…` : '—'
}

onMounted(() => {
  void Promise.all([load(), loadDrugs()])
})
</script>

<style scoped>
.queue-search,
.stock-search {
  width: 210px;
}

.narcotic-tag {
  margin-left: 6px;
}

.stock-empty {
  color: var(--danger);
}

.billing-bad {
  color: var(--danger);
}

.clickable {
  cursor: pointer;
}

.detail {
  display: flex;
  flex-direction: column;
  gap: var(--s5);
}

.detail-bar {
  display: flex;
  align-items: center;
  gap: var(--s3);
  padding-bottom: var(--s3);
  border-bottom: 1px solid var(--line);
  flex-wrap: wrap;
}

.detail-bar strong {
  font-size: var(--fs-18);
}

.detail-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: var(--s3) var(--s5);
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
  color: var(--ink);
  font-weight: 500;
}

.detail-section {
  border-top: 1px solid var(--line);
  padding-top: var(--s3);
}

.detail-section-title {
  font-size: var(--fs-13);
  font-weight: 600;
  margin-bottom: var(--s2);
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

.audit-empty {
  padding: var(--s2) 0;
}

.audit-row {
  display: flex;
  align-items: baseline;
  gap: var(--s3);
  padding: 7px 0;
  border-bottom: 1px dashed var(--line);
  font-size: var(--fs-12);
  color: var(--ink-2);
}

.audit-msg {
  flex: 1;
  min-width: 0;
}

.returns {
  margin-top: var(--s2);
}

.return-row {
  display: flex;
  align-items: baseline;
  gap: var(--s3);
  padding: 6px 0;
  border-bottom: 1px dashed var(--line);
  font-size: var(--fs-12);
  color: var(--ink-2);
}

.return-info {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  padding: var(--s3) var(--s4);
  background: var(--sunken);
  border-radius: var(--r-md);
}

.return-info strong {
  font-size: var(--fs-18);
}

.receive-form {
  display: flex;
  align-items: flex-end;
  gap: var(--s3);
  flex-wrap: wrap;
}

.receive-form .field {
  width: 150px;
}

.receive-form .field.grow {
  flex: 1;
  min-width: 160px;
  width: auto;
}

.footer-hint {
  margin-right: auto;
  text-align: left;
}

@media (max-width: 900px) {
  .detail-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
