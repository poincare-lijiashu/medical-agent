<!-- 占位视图（轮1）：轮2/3 逐步替换为真实助手/管理视图；导航 key 从路由 meta 读取 -->
<template>
  <div>
    <div class="shead">
      <div>
        <h2>{{ title }}</h2>
        <p>迁移占位视图 · 功能将在轮2/轮3 交付</p>
      </div>
    </div>
    <div class="page-body">
      <el-empty description="该视图尚未迁移：过渡期请使用 legacy 前端（/）完成对应业务操作" />
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { NAV_ITEMS } from '../constants/nav'

const route = useRoute()
const title = computed(() => NAV_ITEMS[route.meta.nav]?.t || '视图')
</script>
