// 评测 P0-5：frontend-vue 此前没有任何 lint 门禁（P0-4 的 403 处内联样式、风格漂移无人拦）。
// 规则集刻意只取"正确性/易错点"：
//   - js.configs.recommended（no-undef / no-unused-vars / no-dupe-keys …）
//   - eslint-plugin-vue flat/essential（模板解析错误、v-for key、计算属性副作用 …）
//   - vuejs-accessibility 中"真影响键盘/读屏"的规则（点击元素可聚焦、输入框有标签、aria 合法）
// 不含格式类规则（缩进/属性排序/单双引号）——那些交给格式化工具，避免一次性 700+ 条噪音。
import js from '@eslint/js'
import pluginVue from 'eslint-plugin-vue'
import vueA11y from 'eslint-plugin-vuejs-accessibility'
import globals from 'globals'

export default [
  { ignores: ['dist/**', 'node_modules/**', 'tests/**', 'vite.config.js', 'eslint.config.js'] },
  js.configs.recommended,
  ...pluginVue.configs['flat/essential'],
  {
    files: ['**/*.vue'],
    plugins: { 'vuejs-accessibility': vueA11y },
    rules: {
      // 真可访问性问题：div/span 挂 @click 必须可聚焦 + 键盘可触发；输入框必须有标签
      'vuejs-accessibility/click-events-have-key-events': 'error',
      'vuejs-accessibility/no-static-element-interactions': 'error',
      'vuejs-accessibility/form-control-has-label': 'error',
      'vuejs-accessibility/anchor-has-content': 'error',
      'vuejs-accessibility/no-autofocus': 'error',
      'vuejs-accessibility/no-distracting-elements': 'error',
      'vuejs-accessibility/mouse-events-have-key-events': 'error',
      'vuejs-accessibility/role-has-required-aria-props': 'error',
      'vuejs-accessibility/aria-props': 'error',
      'vuejs-accessibility/aria-role': 'error',
      'vuejs-accessibility/aria-unsupported-elements': 'error',
      // label-has-for 对 Element Plus 自定义组件误报（label 包住 el-input 时静态无法关联），
      // 由 form-control-has-label + Playwright 键盘走查覆盖。
      'vuejs-accessibility/label-has-for': 'off',
    },
  },
  {
    files: ['**/*.{js,vue}'],
    languageOptions: {
      ecmaVersion: 2022,
      sourceType: 'module',
      globals: { ...globals.browser },
    },
    rules: {
      // 未使用变量按"错误"拦（能抓出改名残留），但函数参数/捕获异常不追究
      'no-unused-vars': ['error', { args: 'none', caughtErrors: 'none', ignoreRestSiblings: true }],
      'no-empty': ['error', { allowEmptyCatch: true }],
      // 视图组件是单词名（OverviewView 等已多词；保留能力以防未来新增）
      'vue/multi-word-component-names': 'off',
    },
  },
]
