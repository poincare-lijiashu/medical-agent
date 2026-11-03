<!-- 状态徽标：颜色永远配文字（视力/色觉障碍与打印场景都不能只靠颜色）。 -->
<template>
  <span class="badge" :class="`badge--${tone}`">
    <span class="dot" aria-hidden="true" />
    {{ label }}
  </span>
</template>

<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{ value?: string | null; label?: string }>()

type Tone = 'ok' | 'warn' | 'danger' | 'accent' | 'neutral'

const MAP: Record<string, { label: string; tone: Tone }> = {
  // 就诊
  REGISTERED: { label: '待接诊', tone: 'warn' },
  IN_TREATMENT: { label: '接诊中', tone: 'accent' },
  FINISHED: { label: '已结束', tone: 'ok' },
  CANCELLED: { label: '已取消', tone: 'neutral' },
  // 住院
  ADMITTED: { label: '待入科', tone: 'warn' },
  IN_HOSPITAL: { label: '在院', tone: 'accent' },
  DISCHARGED: { label: '已出院', tone: 'neutral' },
  PATIENT_ACTIVE: { label: '有效档案', tone: 'ok' },
  ACTIVE: { label: '启用', tone: 'ok' },
  MAINTENANCE: { label: '维护中', tone: 'warn' },
  INACTIVE: { label: '停用', tone: 'neutral' },
  // 医嘱
  PLACED: { label: '已下达', tone: 'accent' },
  CHARGED: { label: '已计费', tone: 'ok' },
  STOPPED: { label: '已停嘱', tone: 'neutral' },
  REJECTED: { label: '已驳回', tone: 'danger' },
  // 收费
  UNPAID: { label: '未结算', tone: 'warn' },
  PAID: { label: '已结算', tone: 'ok' },
  VOID: { label: '已作废', tone: 'neutral' },
  // 病案首页
  DRAFT: { label: '草稿', tone: 'warn' },
  SUBMITTED: { label: '已提交', tone: 'accent' },
  ARCHIVED: { label: '已归档', tone: 'ok' },
  // 手术
  SCHEDULED: { label: '已排台', tone: 'accent' },
  PLANNED: { label: '已计划', tone: 'warn' },
  IN_PROGRESS: { label: '进行中', tone: 'accent' },
  COMPLETED: { label: '已完成', tone: 'ok' },
  NOT_REQUIRED: { label: '无需审批', tone: 'neutral' },
  PENDING: { label: '待审批', tone: 'warn' },
  APPROVED: { label: '已放行', tone: 'ok' },
  // 麻精专册
  PENDING_RETURN: { label: '待回收空安瓿', tone: 'warn' },
  RETURNED: { label: '已回收', tone: 'ok' },
  // 护理执行
  EXECUTED: { label: '已执行', tone: 'ok' },
  REFUSED: { label: '患者拒绝', tone: 'warn' },
  OMITTED: { label: '未执行', tone: 'danger' },
  // 会诊
  REQUESTED: { label: '待受理', tone: 'warn' },
  ACCEPTED: { label: '会诊中', tone: 'accent' },
  // 知识库
  READY: { label: '已索引', tone: 'ok' },
  INDEXING: { label: '索引中', tone: 'warn' },
  FAILED: { label: '索引失败', tone: 'danger' },
  // 药房
  PENDING_REVIEW: { label: '待审核', tone: 'warn' },
  DISPENSED: { label: '已发药', tone: 'ok' },
  PARTIAL_RETURNED: { label: '部分退回', tone: 'warn' },
  EXPIRED: { label: '已过期', tone: 'danger' },
  NEAR_EXPIRY: { label: '近效期', tone: 'warn' },
  // 通用
  SIGNED: { label: '已签署', tone: 'ok' },
  FIT: { label: '可耐受', tone: 'ok' },
  FIT_WITH_CAUTION: { label: '谨慎评估', tone: 'warn' },
  UNFIT: { label: '不宜手术', tone: 'danger' },
}

const meta = computed(() => {
  const key = (props.value ?? '').toUpperCase()
  return MAP[key] ?? { label: props.label ?? props.value ?? '—', tone: 'neutral' as Tone }
})
const label = computed(() => props.label ?? meta.value.label)
const tone = computed(() => meta.value.tone)
</script>

<style scoped>
.dot {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: currentColor;
  opacity: 0.85;
}
</style>
