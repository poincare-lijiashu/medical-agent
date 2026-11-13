<template>
  <div class="page">
    <PageHeader
      title="灾备演练"
      sub="备份策略与运行 · 演练步骤与 RTO/RPO · 四眼复核 · 生产发布门禁"
    >
      <template #actions>
        <router-link class="btn" to="/release">发布管理</router-link>
      </template>
    </PageHeader>

    <div class="banner">
      <AppIcon name="warning" :size="16" />
      <span>
        生产部署（非回滚）必须存在有效期内的灾备演练通过记录，且复核人不能是执行人；
        演练过期即视为没有演练，发布会被拒绝。
      </span>
    </div>

    <OpsDrPanel />
  </div>
</template>

<script setup lang="ts">
import AppIcon from '@/components/ui/AppIcon.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import OpsDrPanel from '@/components/domain/ops/OpsDrPanel.vue'
</script>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.banner {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 14px;
  border-radius: 8px;
  background: var(--warn-bg, #fef3c7);
  color: var(--warn-ink, #92400e);
  font-size: var(--fs-body);
  line-height: 1.5;
}
</style>
