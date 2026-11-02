<!-- 自绘临床图标集 v3：24×24 网格、1.5 描边、圆角端点。
     语义优先：医嘱、术式、麻精、病案、手术间都有专属字形；
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
  { size: 18, stroke: 1.5 },
)

const ICONS: Record<string, string> = {
  /* ---------- 临床语义 ---------- */
  orders:
    '<path d="M8 4h8a1.5 1.5 0 0 1 1.5 1.5V19a1.5 1.5 0 0 1-1.5 1.5H8A1.5 1.5 0 0 1 6.5 19V5.5A1.5 1.5 0 0 1 8 4Z"/><path d="M9.5 4v-.8A1.2 1.2 0 0 1 10.7 2h2.6a1.2 1.2 0 0 1 1.2 1.2V4"/><path d="M9.5 9h5M9.5 12.5h5M9.5 16h3"/>',
  surgery:
    '<path d="M4.5 20.5 14 11l3.5 3.5-9.5 9.5H4.5v-3.5Z"/><path d="M14 11l2.2-2.2a2.2 2.2 0 0 1 3.1 0l.4.4a2.2 2.2 0 0 1 0 3.1L17.5 14.5"/><path d="M13.5 5.5 15 4l5 5-1.5 1.5"/>',
  scalpel:
    '<path d="M4 20l4.5-1 11-11a2.1 2.1 0 0 0-3-3l-11 11L4 20Z"/><path d="M12.5 6.5 17 11"/>',
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
  stethoscope:
    '<path d="M5 3v5a4 4 0 0 0 8 0V3"/><path d="M3.5 3H7M11 3h3.5"/><path d="M9 15v1.5a4.5 4.5 0 0 0 9 0V14"/><circle cx="18" cy="12" r="2"/>',
  syringe:
    '<path d="M14 3.5 20.5 10"/><path d="M17 6.5 8.5 15l-3.5 1 1-3.5L14.5 4"/><path d="M9 12.5l2.5 2.5"/><path d="M5.5 18.5 4 20"/>',
  pill:
    '<rect x="3.6" y="9" width="16.8" height="6" rx="3" transform="rotate(-45 12 12)"/><path d="M8.5 8.5l7 7"/>',
  monitor:
    '<rect x="3" y="4.5" width="18" height="12" rx="1.5"/><path d="M8 20h8M12 16.5V20"/><path d="M6 11h3l1.5-3 2 5 1.5-2h4"/>',
  heart:
    '<path d="M12 20s-7.5-4.6-7.5-9.6A4.4 4.4 0 0 1 12 7a4.4 4.4 0 0 1 7.5 3.4C19.5 15.4 12 20 12 20Z"/>',
  activity: '<path d="M3 12h3.5l2.2-5.5L13 17.5l2.4-5.5H21"/>',
  thermometer:
    '<path d="M10 13.5V5a2 2 0 1 1 4 0v8.5a4 4 0 1 1-4 0Z"/><path d="M12 17.5v-6"/>',
  bed: '<path d="M3 19V7"/><path d="M3 12h15a3 3 0 0 1 3 3v4"/><path d="M3 19h18"/><circle cx="7.5" cy="9.5" r="1.8"/>',
  building:
    '<path d="M4.5 20.5V5.5A1.5 1.5 0 0 1 6 4h7a1.5 1.5 0 0 1 1.5 1.5v15"/><path d="M14.5 9.5H18a1.5 1.5 0 0 1 1.5 1.5v9.5"/><path d="M3 20.5h18"/><path d="M7.5 8h4M7.5 11.5h4M7.5 15h4"/>',
  door: '<path d="M5 20.5V4.5A1.5 1.5 0 0 1 6.5 3h8A1.5 1.5 0 0 1 16 4.5v16"/><path d="M3.5 20.5h17"/><circle cx="12.8" cy="12" r=".9" fill="currentColor" stroke="none"/>',
  hospital:
    '<path d="M4 20.5V6a2 2 0 0 1 2-2h12a2 2 0 0 1 2 2v14.5"/><path d="M3 20.5h18"/><path d="M12 7.5v6M9 10.5h6"/><path d="M9.5 20.5v-3h5v3"/>',
  baby: '<circle cx="12" cy="8" r="3"/><path d="M6.5 20c.6-4 2.8-6 5.5-6s4.9 2 5.5 6"/><path d="M9 5.5c.8-1 1.8-1.5 3-1.5s2.2.5 3 1.5"/>',
  users:
    '<circle cx="9" cy="8.5" r="3"/><path d="M3 20c.5-3.6 2.8-5.5 6-5.5s5.5 1.9 6 5.5"/><path d="M16 5.6a3 3 0 0 1 0 5.8"/><path d="M17.5 14.8c2.2.6 3.6 2.4 4 5.2"/>',
  user: '<circle cx="12" cy="8.5" r="3.5"/><path d="M5 20.2c.7-3.4 3.5-5.4 7-5.4s6.3 2 7 5.4"/>',
  'id-card':
    '<rect x="3" y="5" width="18" height="14" rx="1.5"/><circle cx="8.5" cy="11" r="2"/><path d="M5.5 16c.4-1.6 1.6-2.5 3-2.5s2.6.9 3 2.5"/><path d="M14 10h4M14 13h3"/>',

  /* ---------- 操作与界面 ---------- */
  search: '<circle cx="10.5" cy="10.5" r="6"/><path d="M15 15 20 20"/>',
  filter: '<path d="M3.5 5h17l-6.5 7.6V20l-4-2v-5.4L3.5 5Z"/>',
  sliders: '<path d="M4 7h9M17 7h3M4 12h3M11 12h9M4 17h7M15 17h5"/><circle cx="15" cy="7" r="2"/><circle cx="9" cy="12" r="2"/><circle cx="13" cy="17" r="2"/>',
  calendar:
    '<rect x="3.5" y="5" width="17" height="15.5" rx="1.5"/><path d="M3.5 9.5h17"/><path d="M8 3v4M16 3v4"/><path d="M7.5 13h3M13.5 13h3M7.5 16.5h3"/>',
  clock: '<circle cx="12" cy="12" r="8.5"/><path d="M12 7.5V12l3 2"/>',
  refresh: '<path d="M19 12a7 7 0 1 1-2.2-5.1"/><path d="M19 4.5V9h-4.5"/>',
  'rotate-ccw': '<path d="M5 12a7 7 0 1 0 2.2-5.1"/><path d="M5 4.5V9h4.5"/>',
  download: '<path d="M12 4v10"/><path d="M8 10.5 12 14.5l4-4"/><path d="M5 19.5h14"/>',
  upload: '<path d="M12 15V5"/><path d="M8 8.5 12 4.5l4 4"/><path d="M5 19.5h14"/>',
  print: '<path d="M7 8V4h10v4"/><rect x="4" y="8" width="16" height="7.5" rx="1.5"/><path d="M7 13.5h10V20H7v-6.5Z"/>',
  copy: '<rect x="8.5" y="8.5" width="11.5" height="11.5" rx="1.5"/><path d="M15.5 8.5V5A1.5 1.5 0 0 0 14 3.5H5A1.5 1.5 0 0 0 3.5 5v9A1.5 1.5 0 0 0 5 15.5h3.5"/>',
  edit: '<path d="M4.5 19.5h4L19 9a2.1 2.1 0 0 0-3-3L5.5 16.5v3Z"/><path d="M14.5 6.5 17.5 9.5"/>',
  trash: '<path d="M4.5 6.5h15"/><path d="M9 6.5V4h6v2.5"/><path d="M6.5 6.5 7.5 20h9l1-13.5"/><path d="M10 10v6M14 10v6"/>',
  plus: '<path d="M12 5v14M5 12h14"/>',
  minus: '<path d="M5 12h14"/>',
  x: '<path d="M6 6l12 12M18 6 6 18"/>',
  check: '<path d="M5 12.5l4.5 4.5L19 7"/>',
  'check-circle': '<circle cx="12" cy="12" r="8.5"/><path d="M8 12.2l2.6 2.6L16 9.5"/>',
  'x-circle': '<circle cx="12" cy="12" r="8.5"/><path d="M9 9l6 6M15 9l-6 6"/>',
  warning:
    '<path d="M12 3.8 21 19.5H3L12 3.8Z"/><path d="M12 9.5v4.2"/><circle cx="12" cy="16.6" r=".9" fill="currentColor" stroke="none"/>',
  alert: '<circle cx="12" cy="12" r="8.5"/><path d="M12 7.8v4.6"/><circle cx="12" cy="16" r=".9" fill="currentColor" stroke="none"/>',
  info: '<circle cx="12" cy="12" r="8.5"/><path d="M12 11.2v5"/><circle cx="12" cy="8.2" r=".9" fill="currentColor" stroke="none"/>',
  help: '<circle cx="12" cy="12" r="8.5"/><path d="M9.6 9.4a2.5 2.5 0 1 1 3.4 2.4c-.7.3-1 .8-1 1.5v.4"/><circle cx="12" cy="16.6" r=".9" fill="currentColor" stroke="none"/>',
  lock: '<rect x="5" y="10" width="14" height="10" rx="1.5"/><path d="M8.5 10V7.5a3.5 3.5 0 0 1 7 0V10"/>',
  unlock: '<rect x="5" y="10" width="14" height="10" rx="1.5"/><path d="M8.5 10V7.5a3.5 3.5 0 0 1 6.7-1.4"/>',
  key: '<circle cx="8" cy="12" r="4"/><path d="M12 12h9"/><path d="M18 12v3M15.5 12v2"/>',
  shield: '<path d="M12 3.2 19.5 6v6c0 4.4-3 7.6-7.5 8.9C7.5 19.6 4.5 16.4 4.5 12V6L12 3.2Z"/><path d="M9 12.2l2.2 2.2 3.8-4"/>',
  eye: '<path d="M2.5 12S6 5.5 12 5.5 21.5 12 21.5 12 18 18.5 12 18.5 2.5 12 2.5 12Z"/><circle cx="12" cy="12" r="3"/>',
  'eye-off':
    '<path d="M4 4l16 16"/><path d="M9.9 5.8A9.9 9.9 0 0 1 12 5.5c6 0 9.5 6.5 9.5 6.5a17 17 0 0 1-2.6 3.4"/><path d="M6.1 7.9A16.6 16.6 0 0 0 2.5 12S6 18.5 12 18.5c1.2 0 2.3-.2 3.3-.7"/><path d="M10 10a2.8 2.8 0 0 0 4 4"/>',
  logout:
    '<path d="M14.5 8V5.5A1.5 1.5 0 0 0 13 4H6a1.5 1.5 0 0 0-1.5 1.5v13A1.5 1.5 0 0 0 6 20h7a1.5 1.5 0 0 0 1.5-1.5V16"/><path d="M10 12h10"/><path d="M16.5 8.5 20 12l-3.5 3.5"/>',
  settings:
    '<circle cx="12" cy="12" r="3"/><path d="M19.4 14.2a1.7 1.7 0 0 0 .3 1.9l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.9-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.9a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.9.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.9 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.9a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.9l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.9.3H10a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.9a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.9-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.9V10a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.9a1.7 1.7 0 0 0-1.5 1Z"/>',
  bell: '<path d="M18 9a6 6 0 1 0-12 0c0 4-1.6 5.2-2.2 5.8h16.4C19.6 14.2 18 13 18 9Z"/><path d="M9.8 18.5a2.4 2.4 0 0 0 4.4 0"/>',
  dots: '<circle cx="5.5" cy="12" r="1.3" fill="currentColor" stroke="none"/><circle cx="12" cy="12" r="1.3" fill="currentColor" stroke="none"/><circle cx="18.5" cy="12" r="1.3" fill="currentColor" stroke="none"/>',
  'panel-left': '<rect x="3.5" y="4" width="17" height="16" rx="1.5"/><path d="M9.5 4v16"/>',
  'panel-right': '<rect x="3.5" y="4" width="17" height="16" rx="1.5"/><path d="M14.5 4v16"/>',
  columns: '<rect x="3.5" y="4" width="17" height="16" rx="1.5"/><path d="M9.5 4v16M14.5 4v16"/>',
  grid: '<rect x="3.5" y="3.5" width="7" height="7" rx="1"/><rect x="13.5" y="3.5" width="7" height="7" rx="1"/><rect x="3.5" y="13.5" width="7" height="7" rx="1"/><rect x="13.5" y="13.5" width="7" height="7" rx="1"/>',
  list: '<path d="M8 6h13M8 12h13M8 18h13"/><circle cx="4" cy="6" r="1" fill="currentColor" stroke="none"/><circle cx="4" cy="12" r="1" fill="currentColor" stroke="none"/><circle cx="4" cy="18" r="1" fill="currentColor" stroke="none"/>',
  layers: '<path d="M12 3.5 21 8l-9 4.5L3 8l9-4.5Z"/><path d="M3 12.5 12 17l9-4.5"/><path d="M3 16.8 12 21.3l9-4.5"/>',
  archive: '<rect x="3.5" y="4" width="17" height="4" rx="1"/><path d="M5 8v11.5A1.5 1.5 0 0 0 6.5 21h11a1.5 1.5 0 0 0 1.5-1.5V8"/><path d="M10 12h4"/>',
  file: '<path d="M6 3.5h8L19 8.5V20.5H6V3.5Z"/><path d="M14 3.5v5h5"/>',
  'file-text':
    '<path d="M6 3.5h8L19 8.5V20.5H6V3.5Z"/><path d="M14 3.5v5h5"/><path d="M9 12h7M9 15.5h7M9 8.5h2.5"/>',
  folder: '<path d="M3.5 6.5A1.5 1.5 0 0 1 5 5h4l2 2.5h8A1.5 1.5 0 0 1 20.5 9v9A1.5 1.5 0 0 1 19 19.5H5A1.5 1.5 0 0 1 3.5 18V6.5Z"/>',
  clipboard:
    '<rect x="5" y="4.5" width="14" height="16" rx="1.5"/><path d="M9 4.5V3h6v1.5"/><path d="M8.5 10h7M8.5 13.5h7M8.5 17h4"/>',
  link: '<path d="M10 14a4 4 0 0 0 5.7 0l2.8-2.8a4 4 0 1 0-5.7-5.7L11.5 6.8"/><path d="M14 10a4 4 0 0 0-5.7 0l-2.8 2.8a4 4 0 1 0 5.7 5.7l1.3-1.3"/>',
  external: '<path d="M14 4.5h5.5V10"/><path d="M19.5 4.5 11 13"/><path d="M18 14v5.5H4.5V6H10"/>',
  'arrow-right': '<path d="M4.5 12h15"/><path d="M14 6.5 19.5 12 14 17.5"/>',
  'arrow-left': '<path d="M19.5 12h-15"/><path d="M10 6.5 4.5 12 10 17.5"/>',
  'arrow-up': '<path d="M12 19.5v-15"/><path d="M6.5 10 12 4.5 17.5 10"/>',
  'arrow-down': '<path d="M12 4.5v15"/><path d="M6.5 14 12 19.5 17.5 14"/>',
  'chevron-down': '<path d="M6 9.5 12 15l6-5.5"/>',
  'chevron-right': '<path d="M9.5 6 15 12l-5.5 6"/>',
  'chevron-left': '<path d="M14.5 6 9 12l5.5 6"/>',
  'chevrons-left': '<path d="M13 6 7.5 12 13 18"/><path d="M18 6 12.5 12 18 18"/>',
  'chevrons-right': '<path d="M11 6 16.5 12 11 18"/><path d="M6 6 11.5 12 6 18"/>',
  maximize: '<path d="M9 4.5H4.5V9"/><path d="M15 4.5h4.5V9"/><path d="M15 19.5h4.5V15"/><path d="M9 19.5H4.5V15"/>',
  minimize: '<path d="M4.5 9H9V4.5"/><path d="M19.5 9H15V4.5"/><path d="M19.5 15H15V19.5"/><path d="M4.5 15H9V19.5"/>',
  play: '<path d="M7 4.8 19 12 7 19.2V4.8Z"/>',
  pause: '<path d="M8.5 4.5v15M15.5 4.5v15"/>',
  send: '<path d="M20.5 3.5 3.5 10l7 2.5 2.5 7 7.5-16Z"/><path d="M10.5 12.5 20.5 3.5"/>',
  inbox: '<path d="M3.5 12.5 6 4.5h12l2.5 8"/><path d="M3.5 12.5h5l1.5 2.5h4l1.5-2.5h5v6a1.5 1.5 0 0 1-1.5 1.5h-13a1.5 1.5 0 0 1-1.5-1.5v-6Z"/>',
  package: '<path d="M12 3.5 20.5 8v8L12 20.5 3.5 16V8L12 3.5Z"/><path d="M3.5 8 12 12.5 20.5 8"/><path d="M12 12.5v8"/>',
  truck: '<path d="M3.5 6.5h10v9h-10z"/><path d="M13.5 9.5H17l3 3v3h-6.5"/><circle cx="7" cy="18" r="1.8"/><circle cx="17" cy="18" r="1.8"/>',
  wallet: '<rect x="3.5" y="6" width="17" height="13" rx="1.5"/><path d="M3.5 9.5h17"/><circle cx="16.5" cy="14.5" r="1.1" fill="currentColor" stroke="none"/>',
  'credit-card': '<rect x="3" y="5.5" width="18" height="13" rx="1.5"/><path d="M3 10h18"/><path d="M6.5 14.5h4"/>',
  receipt: '<path d="M5.5 3.5h13v17l-2.2-1.5-2.2 1.5-2.1-1.5-2.2 1.5-2.3-1.5V3.5Z"/><path d="M9 8h6M9 11.5h6M9 15h3.5"/>',
  scan: '<path d="M4 9V5.5A1.5 1.5 0 0 1 5.5 4H9"/><path d="M15 4h3.5A1.5 1.5 0 0 1 20 5.5V9"/><path d="M20 15v3.5a1.5 1.5 0 0 1-1.5 1.5H15"/><path d="M9 20H5.5A1.5 1.5 0 0 1 4 18.5V15"/><path d="M4 12h16"/>',
  tag: '<path d="M4 4h7l9 9-7 7-9-9V4Z"/><circle cx="8.5" cy="8.5" r="1.3"/>',
  bookmark: '<path d="M6 3.5h12v17L12 16.5 6 20.5v-17Z"/>',
  star: '<path d="M12 3.5l2.6 5.4 5.9.8-4.3 4.2 1 5.9-5.2-2.8-5.2 2.8 1-5.9-4.3-4.2 5.9-.8L12 3.5Z"/>',
  pin: '<path d="M9 3.5h6l-1 5 3 3h-10l3-3-1-5Z"/><path d="M12 11.5V20"/>',
  'map-pin': '<path d="M12 21s6.5-6 6.5-11a6.5 6.5 0 1 0-13 0C5.5 15 12 21 12 21Z"/><circle cx="12" cy="10" r="2.4"/>',
  phone: '<path d="M6.5 3.5h3l1.5 4-2 1.5a11 11 0 0 0 5 5l1.5-2 4 1.5v3a2 2 0 0 1-2 2C10 18.5 5.5 14 5.5 5.5a2 2 0 0 1 1-2Z"/>',
  mail: '<rect x="3" y="5" width="18" height="14" rx="1.5"/><path d="m3.5 6.5 8.5 6 8.5-6"/>',
  globe: '<circle cx="12" cy="12" r="8.5"/><path d="M3.5 12h17"/><path d="M12 3.5c2.3 2.4 3.4 5.2 3.4 8.5S14.3 18.1 12 20.5c-2.3-2.4-3.4-5.2-3.4-8.5S9.7 5.9 12 3.5Z"/>',
  database: '<ellipse cx="12" cy="6" rx="7.5" ry="2.8"/><path d="M4.5 6v6c0 1.5 3.4 2.8 7.5 2.8s7.5-1.3 7.5-2.8V6"/><path d="M4.5 12v6c0 1.5 3.4 2.8 7.5 2.8s7.5-1.3 7.5-2.8v-6"/>',
  server: '<rect x="3.5" y="4" width="17" height="6" rx="1.2"/><rect x="3.5" y="14" width="17" height="6" rx="1.2"/><path d="M7 7h.01M7 17h.01"/><circle cx="7" cy="7" r=".9" fill="currentColor" stroke="none"/><circle cx="7" cy="17" r=".9" fill="currentColor" stroke="none"/>',
  terminal: '<rect x="3" y="4.5" width="18" height="15" rx="1.5"/><path d="m7 9 3 3-3 3M12.5 15H17"/>',
  branch: '<circle cx="6" cy="5.5" r="2"/><circle cx="6" cy="18.5" r="2"/><circle cx="18" cy="9" r="2"/><path d="M6 7.5v9"/><path d="M18 11a7 7 0 0 1-7 7H8"/>',
  code: '<path d="m8 8-4 4 4 4M16 8l4 4-4 4"/><path d="m13.5 5-3 14"/>',
  spark: '<path d="M12 3.5 13.8 9l5.7 1.8-5.7 1.8L12 18l-1.8-5.4L4.5 10.8 10.2 9 12 3.5Z"/><path d="M18.5 16.5 19.2 18.8 21.5 19.5 19.2 20.2 18.5 22.5 17.8 20.2 15.5 19.5 17.8 18.8 18.5 16.5Z"/>',
  pulse: '<path d="M3 12h4l2.2-5.5L13 17.5l2.4-5.5H21"/>',
  sun: '<circle cx="12" cy="12" r="4"/><path d="M12 2.5v2M12 19.5v2M2.5 12h2M19.5 12h2M5.2 5.2l1.4 1.4M17.4 17.4l1.4 1.4M18.8 5.2l-1.4 1.4M6.6 17.4l-1.4 1.4"/>',
  moon: '<path d="M20 14.5A8.5 8.5 0 0 1 9.5 4a8.5 8.5 0 1 0 10.5 10.5Z"/>',
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
