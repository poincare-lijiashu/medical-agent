<template>
  <div class="page">
    <PageHeader
      title="医保目录与报盘"
      :sub="`医保目录维护与院内收费映射、报盘批次生成/回执/撤销/报文下载。目录导入先预览再落库，报文下载单独授权并留痕。当前角色 ${role}`"
    >
      <template #actions>
        <router-link class="btn" to="/insurance">
          <AppIcon name="wallet" :size="15" /> 返回结算工作台
        </router-link>
      </template>
    </PageHeader>

    <div class="card tabs-card">
      <div class="seg" role="tablist" aria-label="医保运维分区">
        <button
          class="seg-btn"
          :class="{ active: tab === 'catalog' }"
          type="button"
          @click="tab = 'catalog'"
        >
          目录与映射
        </button>
        <button
          class="seg-btn"
          :class="{ active: tab === 'export' }"
          type="button"
          @click="tab = 'export'"
        >
          报盘批次
        </button>
        <button
          class="seg-btn"
          :class="{ active: tab === 'grouping' }"
          type="button"
          @click="tab = 'grouping'"
        >
          DRG/DIP 分组
        </button>
      </div>
      <span class="spacer" />
      <span class="hint">
        {{
          tab === 'catalog'
            ? '省局目录按医保编码 UPSERT，内容未变不写库'
            : tab === 'export'
              ? '已回执批次不可撤销，须走省局冲销流程'
              : '院内模拟预分组，不替代省局正式分组与结算'
        }}
      </span>
    </div>

    <InsuranceCatalogPanel v-if="tab === 'catalog'" />
    <InsuranceExportPanel v-else-if="tab === 'export'" />
    <InsuranceGroupingPanel v-else />
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import AppIcon from '@/components/ui/AppIcon.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import InsuranceCatalogPanel from '@/components/domain/InsuranceCatalogPanel.vue'
import InsuranceExportPanel from '@/components/domain/InsuranceExportPanel.vue'
import InsuranceGroupingPanel from '@/components/domain/InsuranceGroupingPanel.vue'
import { session } from '@/stores/session'

const role = session.roleLabel
const tab = ref<'catalog' | 'export' | 'grouping'>('catalog')
</script>

<style scoped>
.tabs-card {
  display: flex;
  align-items: center;
  gap: var(--s3);
  padding: var(--s2) var(--s3);
  flex-wrap: wrap;
}
</style>
