# Vue3 前端迁移 · 执行与回退手册

> 决策：2026-09-12 用户拍板路径 B（Vue3 + Vite + Element Plus 重写前端，后端零改动）。
> 回退基线：git tag **`v-legacy-frontend`**（= a1a4c34，700 tests，vanilla 前端最后完好状态）。

## 路线（4 轮，每轮独立提交、独立可回退）

| 轮 | 内容 | 交付物 |
|---|---|---|
| 1 | Vite 脚手架 + 登录/导航/布局壳 + Element Plus + api 客户端（token 拦截器）+ 后端挂载 /app | frontend-vue/ 可登录可导航，dist 构建通过 |
| 2 | 核心助手视图：文献/影像/MDT 会诊/开药工作台+我的处方（交互最重先啃） | /app 全助手视图可用 |
| 3 | 管理视图：审核中心/病例库/质控/知识库/审计/字典规则管理/LLM 配置/数据面板 + 角标轮询 | /app 功能对齐 legacy |
| 4 | 切换默认入口（/=新应用）+ 删 legacy + 标记锁重写 + Playwright 适配 + DEPLOY 构建步骤 | 单一前端，全量回归绿 |

## 保存与回退策略（用户要求：随时可回退）

1. **每轮一个 commit**——任何轮次后 `git revert`/`git reset` 均可回到上一轮完好态。
2. **dist 构建产物随轮提交**——回退不需要 node 环境，checkout 即可用。
3. **legacy 前端保留到轮 4**——迁移全程 `/`=legacy 可用，`/app`=新应用并行验证；轮 4 才切换。
4. **一键整体回退**（放弃 Vue3 回到 vanilla）：
   ```
   git checkout v-legacy-frontend -- frontend backend/main.py tests docs/DEPLOY.md
   git commit -m "revert: rollback to legacy frontend (v-legacy-frontend)"
   ```
   （frontend-vue/ 目录删除即可；后端 API 七百测试背书，从未被本轮改动触碰。）
5. **轮内回退**：每轮验收清单（构建过/Playwright 过/pytest 过）不满足则不合并，工作区 `git checkout -- .` 即弃。

## 不变项（安全边界）

- 后端 API 契约零改动（700 后端测试锁定）；仅 main.py 增加 /app 静态挂载（轮 1）与切换（轮 4）。
- Playwright E2E 在轮 4 前始终指向 legacy（= 主入口不变即测试不破）。
- 免责声明、双控、审计语义全部在后端——前端重写不影响合规属性。

## 技术选型锁定

Vue3 + Vite + Element Plus + vue-router + pinia + axios；npm registry 用 npmmirror（网络受限环境保安装成功）。
