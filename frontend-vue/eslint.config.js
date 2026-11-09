// 评测 P0-5：frontend-vue 此前没有任何 lint 门禁（P0-4 的 403 处内联样式、风格漂移无人拦）。
// 规则集刻意只取"正确性/易错点"：
//   - js.configs.recommended（no-undef / no-unused-vars / no-dupe-keys …）
//   - eslint-plugin-vue flat/essential（模板解析错误、v-for key、计算属性副作用 …）
// 不含格式类规则（缩进/属性排序/单双引号）——那些交给格式化工具，避免一次性 700+ 条噪音。
import js from '@eslint/js'
import pluginVue from 'eslint-plugin-vue'
import globals from 'globals'

export default [
  { ignores: ['dist/**', 'node_modules/**', 'tests/**', 'vite.config.js', 'eslint.config.js'] },
  js.configs.recommended,
  ...pluginVue.configs['flat/essential'],
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
