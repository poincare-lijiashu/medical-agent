# MedAssist 项目评测报告（整合版）

> 评审：GLM-5.3（第三视角）+ DeepSeek（实证复核，重跑测试/取证代码），2026-09-11
> 结论一致度：高——70 分定位相同；分歧点 3 处已裁定。

## 一、总评

**70 / 100 · 科室级试点 deployable**。工程纪律、审计、双控超出同规模项目平均；距商用（85+）差三件实事：医学内容审校、合规专项、E2E+压测+恢复演练。

## 二、实测证据（两模型共识，非声称）

| 项 | 数据 |
|---|---|
| 全量测试 | 659 passed / 0 failed（346s 实测重跑） |
| 提交史 | 42+ 条语义化提交，演进脉络清晰 |
| 权限矩阵 | `require_role` 依赖层统一 403+中文文案 |
| 双控 | 处方状态机全路径拦截；rewrite/submit 仅本人；reviewer 留痕 |
| 密钥安全 | `.gitignore` 实证含 .env.local + llm_providers.json——API key 不入库 |
| 代码体积 | 前端 2836 行/197KB 单文件；backend/core 28 模块（pg_store 58KB 最重） |

## 三、做对了的（共识）

职责与双控（服务端强制，不信任前端）· 审计全链（JSONL+PG 双写+人话映射）· 数据工程（原子写/JSON 兜底/备份/归属表）· 幻觉防线（候选池约束=0 幻觉+规则硬校验分级）· 可扩展预埋（Redis Store/LLM provider 抽象/三级部署）· 测试纪律（标记锁/TDD/角色矩阵）。

## 四、问题清单（合并去重，按严重度）

### 🔴 P1 · 医学内容未经专家审校（最大风险敞口，评分 40）
342 药/318 规则/指南要点全部 AI 初稿。免责声明是盾不是正确性保障。**商用红线：执业药师全量审校规则库（高危 135 条优先）+抽查字典。**

### 🟠 P2 · 部署前必须实测的三个缺口
1. **无压测**——"科室级够用"是推算非实测；需 locust 压三热点（检索/开药/阅片）记录 QPS 基线
2. **备份未演练恢复**——backup.py 只测了命令序列；备份=可恢复才算数，需一次清库→还原→健康检查演练
3. **无 E2E**——标记锁保"代码在"不保"点击流通"；至少一条 Playwright 冒烟全链路

### 🟠 P3 · 医疗合规未系统化
PII 脱敏有，但缺等保测评、医疗数据分级分类、知情同意流程、审计保留年限策略。上真医院前需法务专项。

### 🟡 P4 · 小项（DeepSeek 新发现）
- LLM key 运行期热更新→client 缓存失效未测
- 无 pytest-timeout（死锁测试会挂满超时）
- bge-m3 CUDA 依赖——无 GPU 部署 CPU 慢 10 倍+
- 环境耦合：Milvus/PG 跨机迁移依赖 P2 恢复演练

### 🟡 P5 · 前端单文件（裁定：技术债真实但可快速缓解）
GLM 判"长期债"，DeepSeek 复核"分区结构+标记锁尚可，ESM 拆分 1-2 天"。**P5**：单人维护可忍，多人协作前必拆。

## 五、分维度评分

| 维度 | 分 | 说明 |
|---|---|---|
| 工程层（权限/审计/数据/双控） | 85 | 超出同类 |
| 测试完备性 | 75 | 单元强，E2E/压测/超时弱 |
| 医学内容 | 40 | 最大敞口 |
| **综合** | **70** | 科室级试点 |

## 六、行动清单（按性价比）

1. locust 压测三热点+基线数字进 DEPLOY（部署决策硬数据）
2. Playwright 冒烟一条（登录→开药→提交→签发）
3. LLM key 热更新失效测试（小）
4. 药师审校流程启动（高危 135 条规则优先）——**上线硬门槛**
5. 前端 ESM 拆分（可后置）
6. restore 演练一次（清库→还原→验证）

## 七、对抗性测试建议（给后续评测）

越权矩阵穷举（doctor 调药师端点/自审双控）· 提示注入（病例文本/图片 OCR 埋指令）· 幻觉攻击（诱导开字典外药）· 并发竞态（双窗口同审/同提）· 降级演练（停 PG/停 Milvus/断 key——验证不裸 500）· 边界值（11 图/5001 字/超长药名——422 中文文案）。

## 八、行动项执行状态（2026-09-12 更新）

| # | 行动项 | 状态 | 提交 |
|---|---|---|---|
| 1 | 负载基线 | ✅ scripts/loadtest.py 实测 16 并发：p95 全端点 <110ms、零错误，基线入 DEPLOY | `8ec0a31` |
| 3 | LLM key 热更新 | ✅ 库层缓存失效缺口实锤并修复（路由层钩子绕过场景）+4 测 | `8ec0a31` |
| 4 | 药师审校工作流 | ✅ 工具链完成（批量标记/覆盖率/筛选/高危排前）；**实际医学审校待人工执行** | `d644884` |
| 6 | restore 演练 | ✅ restore.py + **真实演练通过**（双探针清除/18 JSON 计数一致/PG 保真），记录入 DEPLOY | `392647d` |
| 2 | E2E 冒烟 | ✅ Playwright 真浏览器全链路 PASSED（2.55s）+ API 等价冒烟 | `51fab05` |
| 5 | 前端拆分 | ✅ index.html 2906→484 行 + 7 个 JS 模块（行数守恒 2427=2427，标记锁全保留，Playwright PASSED）；ESM 转换留作增量轮（决策记录在 ARCHITECTURE.md） | `f32031f` |
| P3 | 合规法务专项 | ⏸ 非编程项，需法务/合规介入 | — |
| 补 | 提示注入对抗测试 | ✅ 硬校验防线未被绕过（6 用例：幻觉丢弃/规则阻断/权限语义均不受注入文本影响） | `fdf01a1` |
| 补 | 真故障注入 | ✅ 三场景实跑 PASS（PG 停机 JSON 兜底/Milvus 停机降级+2 项 DR 发现/LLM key 失效降级），记录入 DEPLOY | `fdf01a1` |
| 补 | Milvus 停机挂起修复 | ✅ 检索 RPC 20s 看门狗+客户端重建（DR 发现②固化） | `f32031f` |

测试基线演进：659 → **700 passed**。

## 九、Vue3 前端迁移（2026-09-12 完成，4 轮）

**决策**：用户拍板路径 B（Vue3+Vite+Element Plus 重写，后端零改动）。**回退基线：git tag `v-legacy-frontend`**（= a1a4c34），手册：docs/plans/vue3_migration.md。

| 轮 | 交付 | 提交 |
|---|---|---|
| 1 | Vite 脚手架+登录壳+/app 挂载（与 legacy 并行） | `e1f7f4e` |
| 2 | 核心助手视图（文献 SSE 流式/影像压缩/MDT/开药工作台全语义） | `9e0d2c7` |
| 3 | 管理视图（审核中心/字典审校/知识库/审计/LLM 配置/角标轮询） | `b5157d6` |
| 4 | 切换默认入口+legacy 移除+标记锁重写+Playwright 适配 | `daf757f` |

**终态**：707 passed · Playwright 6/6 · `/` 直出 Vue 应用 · legacy 已删（tag 永久可回退，checkout 三处无需 node）。**整体回退一条命令**：`git checkout v-legacy-frontend -- frontend backend/main.py tests && git commit`。

## 十、DeepSeek 终评整改（2026-09-12，e67aa1a · 759 passed）

用户实测通过后由 DeepSeek 出全项目终评（87/100，只读）。核实 3 高危属实并全部整改：

| 级别 | 发现 | 处置 |
|---|---|---|
| 🔴 | MDT 传图 `image_url` 未归一 `data:` scheme → SSRF（VL 提供商服务端拉内网） | ✅ `enforce_data_url` 四层收口（HTTP/MCP/存储兜底/mdt） |
| 🟡 | KbView `v-html=kbMsg` 渲染未消毒文件名（全项目唯一裸 v-html）+ token 在 localStorage | ✅ esc() 全拼接收口+标记锁（localStorage 迁移登记 backlog） |
| 🟡 | run_eval.py 硬编码默认凭据 | ✅ 强制环境变量 |
| 🟡 | admin base_url 无内网黑名单 → SSRF+key 外发 | ✅ 私网黑名单+LLM_ALLOW_PRIVATE_HOSTS 开关 |
| 🟡 | kb_ingest doc_tag 未清洗拼 Milvus filter；异步线程无上限 | ✅ _safe_tag 统一+Semaphore(2) |
| 🟡 | docker-compose 弱口令+PG 端口全接口暴露 | ✅ 127.0.0.1 绑定+密码 env 化（下次重建容器生效） |
| 🟢 | qc float 500 / rewrite_query 不截断 / 死代码 / 配置文档漂移 / 假池 T5 覆盖错觉 | ✅ 全修+真实 PG 集成冒烟（skipif）补验 |

**登记 backlog**：PG 多实例全表重写竞态（单写者约束）、localStorage→httpOnly cookie、eval 判分改 LLM 判分、_load_json/原子写/路径解析三处重复去重、medical_router.py（2000+行）按域拆分、consults.json 测试隔离缺口。

测试基线演进：659 → **759 passed**。
