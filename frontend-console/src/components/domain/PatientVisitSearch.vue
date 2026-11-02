<!-- 通用"患者 → 就诊"选择器：手术类页面共用一个上下文入口。 -->
<template>
  <div class="picker card">
    <div class="picker-row">
      <div class="picker-search">
        <AppIcon name="search" :size="16" class="muted" />
        <input
          v-model="keyword"
          class="picker-input"
          placeholder="姓名或病案号，回车检索"
          aria-label="检索患者"
          @keydown.enter="search"
          @input="onInput"
        />
        <span class="kbd">Enter</span>
      </div>
      <button class="btn btn--sm" type="button" :disabled="searching" @click="search">检索</button>
      <span class="spacer" />
      <div v-if="patient" class="picked">
        <strong>{{ patient.name }}</strong>
        <span class="muted">{{ genderLabel(patient.gender) }} · {{ ageOf(patient.birthDate) }}</span>
        <span class="mono muted">{{ patient.patientNo }}</span>
      </div>
    </div>

    <div v-if="results.length" class="results">
      <button v-for="p in results" :key="p.id" class="result" type="button" @click="choose(p)">
        <span class="result-name">{{ p.name }}</span>
        <span class="muted">{{ genderLabel(p.gender) }} · {{ ageOf(p.birthDate) }}</span>
        <span class="mono muted">{{ p.patientNo }}</span>
        <span class="spacer" />
        <AppIcon name="chevron-right" :size="14" class="muted" />
      </button>
    </div>

    <div v-if="patient" class="visits">
      <span class="visits-label">就诊</span>
      <button
        v-for="v in visits"
        :key="v.id"
        class="visit-chip"
        :class="{ active: visit?.id === v.id }"
        type="button"
        @click="chooseVisit(v)"
      >
        <span class="mono">{{ v.visitNo }}</span>
        <span>{{ deptName(v.deptId) }}</span>
        <StatusPill :value="v.status" />
      </button>
      <span v-if="visits.length === 0" class="muted" style="font-size: var(--fs-12)">
        该患者暂无就诊记录
      </span>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import AppIcon from '@/components/ui/AppIcon.vue'
import StatusPill from '@/components/ui/StatusPill.vue'
import { clinicalApi, empiApi } from '@/api'
import { errorText } from '@/api/http'
import type { Dept, Patient, Visit } from '@/api/types'
import { toast } from '@/stores/toast'
import { ageOf, genderLabel } from '@/utils/format'

const emit = defineEmits<{ select: [{ patient: Patient; visit: Visit }] }>()

const keyword = ref('')
const results = ref<Patient[]>([])
const searching = ref(false)
const patient = ref<Patient | null>(null)
const visits = ref<Visit[]>([])
const visit = ref<Visit | null>(null)
const departments = ref<Dept[]>([])
let timer: number | undefined

function onInput() {
  window.clearTimeout(timer)
  if (!keyword.value.trim()) {
    results.value = []
    return
  }
  timer = window.setTimeout(search, 350)
}

async function search() {
  const kw = keyword.value.trim()
  if (!kw) return
  searching.value = true
  try {
    const looksLikeNo = /^[A-Za-z]{1,4}\d{2,}$/.test(kw)
    const res = looksLikeNo ? await empiApi.searchByNo(kw) : await empiApi.searchByName(kw)
    results.value = res.items
    if (res.items.length === 0) toast.info('未找到患者')
  } catch (e) {
    toast.error('检索失败', errorText(e))
  } finally {
    searching.value = false
  }
}

async function choose(p: Patient) {
  patient.value = p
  results.value = []
  keyword.value = p.name + ' · ' + p.patientNo
  try {
    visits.value = await clinicalApi.visits(p.id)
    const first = visits.value.find((v) => v.status === 'IN_TREATMENT') ?? visits.value[0]
    if (first) chooseVisit(first)
  } catch (e) {
    toast.error('就诊加载失败', errorText(e))
  }
}

function chooseVisit(v: Visit) {
  visit.value = v
  if (patient.value) emit('select', { patient: patient.value, visit: v })
}

function deptName(id: string) {
  return departments.value.find((d) => d.id === id)?.name ?? id.slice(0, 8)
}

onMounted(async () => {
  try {
    departments.value = await clinicalApi.departments()
  } catch {
    /* 科室名失败不影响检索 */
  }
})
</script>

<style scoped>
.picker {
  padding: var(--s3) var(--s4);
  display: flex;
  flex-direction: column;
  gap: var(--s3);
}

.picker-row {
  display: flex;
  align-items: center;
  gap: var(--s2);
  flex-wrap: wrap;
}

.picker-search {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 340px;
  max-width: 100%;
  height: 34px;
  padding: 0 10px;
  background: var(--sunken);
  border: 1px solid var(--line);
  border-radius: var(--r-md);
}

.picker-search:focus-within {
  border-color: var(--accent);
  background: var(--panel);
  box-shadow: var(--focus);
}

.picker-input {
  flex: 1;
  min-width: 0;
  border: 0;
  background: none;
  outline: none;
  font-size: var(--fs-13);
}

.picked {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: var(--fs-13);
}

.kbd {
  font-family: var(--font-mono);
  font-size: 10px;
  color: var(--ink-3);
  border: 1px solid var(--line-strong);
  border-radius: var(--r-xs);
  padding: 1px 4px;
  background: var(--panel);
}

.results {
  display: flex;
  flex-direction: column;
  max-height: 220px;
  overflow: auto;
  border-top: 1px solid var(--line);
  padding-top: var(--s2);
}

.result {
  display: flex;
  align-items: center;
  gap: var(--s3);
  padding: 7px 8px;
  border: 0;
  background: none;
  border-radius: var(--r-sm);
  cursor: pointer;
  text-align: left;
  font-size: var(--fs-13);
}

.result:hover {
  background: var(--panel-3);
}

.result-name {
  font-weight: 500;
  min-width: 64px;
}

.visits {
  display: flex;
  align-items: center;
  gap: var(--s2);
  flex-wrap: wrap;
  border-top: 1px solid var(--line);
  padding-top: var(--s3);
}

.visits-label {
  font-size: var(--fs-12);
  color: var(--ink-3);
}

.visit-chip {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  padding: 4px 9px;
  border: 1px solid var(--line);
  background: var(--panel);
  border-radius: var(--r-pill);
  cursor: pointer;
  font-size: var(--fs-12);
  color: var(--ink-2);
}

.visit-chip:hover {
  border-color: var(--line-strong);
}

.visit-chip.active {
  border-color: var(--accent-line);
  background: var(--accent-weak);
  color: var(--accent);
}
</style>
