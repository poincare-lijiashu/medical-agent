<!-- 自绘线性图标集：24×24 网格、1.6 描边、圆角端点。
     不使用第三方图标库，避免与产品视觉语言割裂。 -->
<script setup lang="ts">
import { computed } from 'vue'

const props = withDefaults(
  defineProps<{
    name: string
    size?: number
    stroke?: number
    /** 装饰性图标默认 aria-hidden；有语义时传 label */
    label?: string
  }>(),
  { size: 18, stroke: 1.6 },
)

const ICONS: Record<string, string> = {
  orders:
    '<path d="M8 4h8a1.5 1.5 0 0 1 1.5 1.5V19a1.5 1.5 0 0 1-1.5 1.5H8A1.5 1.5 0 0 1 6.5 19V5.5A1.5 1.5 0 0 1 8 4Z"/><path d="M9.5 4V3.2A1.2 1.2 0 0 1 10.7 2h2.6a1.2 1.2 0 0 1 1.2 1.2V4"/><path d="M9.5 9h5M9.5 12.5h5M9.5 16h3"/>',
  surgery:
    '<path d="M4.5 20.5 14 11l3.5 3.5-9.5 9.5H4.5v-3.5Z"/><path d="M14 11l2.2-2.2a2.2 2.2 0 0 1 3.1 0l.4.4a2.2 2.2 0 0 1 0 3.1L17.5 14.5"/><path d="M13.5 5.5 15 4l5 5-1.5 1.5"/>',
  charges:
    '<path d="M5.5 3.5h13v17l-2.2-1.5-2.2 1.5-2.1-1.5-2.2 1.5-2.3-1.5V3.5Z"/><path d="M9 8h6M9 11.5h6M9 15h3.5"/>',
  narcotic:
    '<path d="M9 3h6M10.5 3v4.2L6.6 15a3.4 3.4 0 0 0 3 5h4.8a3.4 3.4 0 0 0 3-5l-3.9-7.8V3"/><path d="M7.6 14.5h8.8"/>',
  preop:
    '<path d="M6 3v5a4 4 0 0 0 8 0V3"/><path d="M4 3h4M12 3h4"/><path d="M10 15v2.5a3.5 3.5 0 0 0 7 0V15"/><circle cx="17" cy="13" r="2.2"/>',
  qc:
    '<path d="M6 3.5h8.5L19 8v12.5H6V3.5Z"/><path d="M14 3.5V8h5"/><path d="M9.2 14.2l1.9 1.9 3.8-4"/>',
  kb:
    '<path d="M4.5 5.2A2.2 2.2 0 0 1 6.7 3H19v15.5H6.7a2.2 2.2 0 0 0 0 4.4H19"/><path d="M8 7.5h7M8 11h5"/>',
  search: '<circle cx="10.5" cy="10.5" r="6"/><path d="M15 15 20 20"/>',
  bell: '<path d="M18 9a6 6 0 1 0-12 0c0 4-1.6 5.2-2.2 5.8h16.4C19.6 14.2 18 13 18 9Z"/><path d="M9.8 18.5a2.4 2.4 0 0 0 4.4 0"/>',
  'chevron-down': '<path d="M6 9.5 12 15l6-5.5"/>',
  'chevron-right': '<path d="M9.5 6 15 12l-5.5 6"/>',
  'chevron-left': '<path d="M14.5 6 9 12l5.5 6"/>',
  check: '<path d="M5 12.5l4.5 4.5L19 7"/>',
  warning: '<path d="M12 3.8 21 19.5H3L12 3.8Z"/><path d="M12 9.5v4.2"/><circle cx="12" cy="16.6" r="0.9" fill="currentColor" stroke="none"/>',
  alert: '<circle cx="12" cy="12" r="8.5"/><path d="M12 7.8v4.6"/><circle cx="12" cy="16" r="0.9" fill="currentColor" stroke="none"/>',
  info: '<circle cx="12" cy="12" r="8.5"/><path d="M12 11.2v5"/><circle cx="12" cy="8.2" r="0.9" fill="currentColor" stroke="none"/>',
  x: '<path d="M6 6l12 12M18 6 6 18"/>',
  plus: '<path d="M12 5v14M5 12h14"/>',
  refresh: '<path d="M19 12a7 7 0 1 1-2.2-5.1"/><path d="M19 4.5V9h-4.5"/>',
  filter: '<path d="M3.5 5h17l-6.5 7.6V20l-4-2v-5.4L3.5 5Z"/>',
  download: '<path d="M12 4v10"/><path d="M8 10.5 12 14.5l4-4"/><path d="M5 19.5h14"/>',
  print: '<path d="M7 8V4h10v4"/><rect x="4" y="8" width="16" height="7.5" rx="1.5"/><path d="M7 13.5h10V20H7v-6.5Z"/>',
  lock: '<rect x="5" y="10" width="14" height="10" rx="2"/><path d="M8.5 10V7.5a3.5 3.5 0 0 1 7 0V10"/>',
  shield: '<path d="M12 3.2 19.5 6v6c0 4.4-3 7.6-7.5 8.9C7.5 19.6 4.5 16.4 4.5 12V6L12 3.2Z"/><path d="M9 12.2l2.2 2.2 3.8-4"/>',
  user: '<circle cx="12" cy="8.5" r="3.5"/><path d="M5 20.2c.7-3.4 3.5-5.4 7-5.4s6.3 2 7 5.4"/>',
  logout: '<path d="M14.5 8V5.5A1.5 1.5 0 0 0 13 4H6a1.5 1.5 0 0 0-1.5 1.5v13A1.5 1.5 0 0 0 6 20h7a1.5 1.5 0 0 0 1.5-1.5V16"/><path d="M10 12h10"/><path d="M16.5 8.5 20 12l-3.5 3.5"/>',
  settings:
    '<circle cx="12" cy="12" r="3"/><path d="M19.4 14.2a1.7 1.7 0 0 0 .3 1.9l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.9-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.9a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.9.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.9 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.9a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.9l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.9.3H10a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.9a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.9-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.9V10a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.9a1.7 1.7 0 0 0-1.5 1Z"/>',
  sun: '<circle cx="12" cy="12" r="4"/><path d="M12 2.5v2M12 19.5v2M2.5 12h2M19.5 12h2M5.2 5.2l1.4 1.4M17.4 17.4l1.4 1.4M18.8 5.2l-1.4 1.4M6.6 17.4l-1.4 1.4"/>',
  moon: '<path d="M20 14.5A8.5 8.5 0 0 1 9.5 4a8.5 8.5 0 1 0 10.5 10.5Z"/>',
  pill: '<rect x="3.6" y="9" width="16.8" height="6" rx="3" transform="rotate(-45 12 12)"/><path d="M8.5 8.5l7 7"/>',
  clock: '<circle cx="12" cy="12" r="8.5"/><path d="M12 7.5V12l3 2"/>',
  'arrow-right': '<path d="M4.5 12h15"/><path d="M14 6.5 19.5 12 14 17.5"/>',
  external: '<path d="M14 4.5h5.5V10"/><path d="M19.5 4.5 11 13"/><path d="M18 14v5.5H4.5V6H10"/>',
  play: '<path d="M7 4.8 19 12 7 19.2V4.8Z"/>',
  trash: '<path d="M4.5 6.5h15"/><path d="M9 6.5V4h6v2.5"/><path d="M6.5 6.5 7.5 20h9l1-13.5"/><path d="M10 10v6M14 10v6"/>',
  edit: '<path d="M4.5 19.5h4L19 9a2.1 2.1 0 0 0-3-3L5.5 16.5v3Z"/><path d="M14.5 6.5 17.5 9.5"/>',
  dots: '<circle cx="5.5" cy="12" r="1.4" fill="currentColor" stroke="none"/><circle cx="12" cy="12" r="1.4" fill="currentColor" stroke="none"/><circle cx="18.5" cy="12" r="1.4" fill="currentColor" stroke="none"/>',
  'panel-left': '<rect x="3.5" y="4" width="17" height="16" rx="2"/><path d="M9.5 4v16"/>',
  pulse: '<path d="M3 12h4l2.2-5.5L13 17.5l2.4-5.5H21"/>',
  eye: '<path d="M2.5 12S6 5.5 12 5.5 21.5 12 21.5 12 18 18.5 12 18.5 2.5 12 2.5 12Z"/><circle cx="12" cy="12" r="3"/>',
  'eye-off':
    '<path d="M4 4l16 16"/><path d="M9.9 5.8A9.9 9.9 0 0 1 12 5.5c6 0 9.5 6.5 9.5 6.5a17 17 0 0 1-2.6 3.4"/><path d="M6.1 7.9A16.6 16.6 0 0 0 2.5 12S6 18.5 12 18.5c1.2 0 2.3-.2 3.3-.7"/><path d="M10 10a2.8 2.8 0 0 0 4 4"/>',
  building: '<path d="M4.5 20.5V5.5A1.5 1.5 0 0 1 6 4h7a1.5 1.5 0 0 1 1.5 1.5v15"/><path d="M14.5 9.5H18a1.5 1.5 0 0 1 1.5 1.5v9.5"/><path d="M3 20.5h18"/><path d="M7.5 8h4M7.5 11.5h4M7.5 15h4"/>',
  bed: '<path d="M3 19V7"/><path d="M3 12h15a3 3 0 0 1 3 3v4"/><path d="M3 19h18"/><circle cx="7.5" cy="9.5" r="1.8"/>',
}

const markup = computed(() => ICONS[props.name] ?? ICONS.info)
</script>

<template>
  <svg
    :width="size"
    :height="size"
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    :stroke-width="stroke"
    stroke-linecap="round"
    stroke-linejoin="round"
    :aria-hidden="label ? undefined : 'true'"
    :role="label ? 'img' : undefined"
    :aria-label="label"
    class="app-icon"
    v-html="markup"
  />
</template>

<style scoped>
.app-icon {
  flex: none;
  display: block;
}
</style>
