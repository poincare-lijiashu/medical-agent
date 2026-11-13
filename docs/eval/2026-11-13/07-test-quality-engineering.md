# E7 测试与质量工程 评测报告

- 评测人：评测子智能体（批次 4，域 E7）
- 评测时间：2026-11-13
- 评测范围：`platform/**/src/test`（Java 272 文件）、`tests/**`（Python 242 测试文件 + 4 辅助文件）、`tests/e2e/**`（4 文件 16 用例）、`.github/workflows/ci.yml`（11 个 job）、`scripts/migration_safety_lint.ps1` + `scripts/migration_safety_baseline.txt`、`scripts/e2e/**`（48 个冒烟/演练脚本）、`frontend-console/**`（Vue 135 文件，无单测）、`eval/`（AI 评测基线）
- 方法与工具：
  - 资产规模：`Get-ChildItem` 递归统计 + 全文正则统计 `@Test`/`def test_`/断言调用
  - 断言质量：Grep 恒真模式（`assertTrue(True)`/`assert x is not None` 单独出现/`fail()`）、逐文件断言计数
  - 运行证据（只读/无副作用）：
    - `python -m pytest --collect-only -q` → 2155 collected（6.31s）
    - `python -m pytest -q -m redteam` → **23 passed**（7.23s）
    - `python -m pytest -q --cov=backend` → **2138 passed / 16 failed / 1 skipped**（503.89s）；`coverage report` → **TOTAL 14387 stmts / 1925 miss / 87%**
    - `python -m pytest -q -m e2e --timeout=180` → **16 failed**（254.40s）
    - `python -m ruff check backend tests scripts` → **2 errors（F401），exit 1**
    - `powershell -File scripts\migration_safety_lint.ps1` → `scanned=307 known=11 new=0 / PASS`，exit 0（执行前已 Read 确认脚本只读文件、不写库、不写文件）
    - `frontend-console\node_modules\.bin\vue-tsc.cmd --noEmit` → exit 0（无输出即通过）
    - GitHub API：`gh api repos/<github-owner>/medical-agent/actions/runs` → 最近一次 CI 运行 **2026-09-15**，本仓本地 HEAD 为 **0ef22398 / 2026-11-13**
  - 只读约束遵守：未修改任何产品代码/配置/迁移/构建产物；唯一写入为本报告文件

---

## 1. 评分总表

| # | 评分项 | 权重 | 得分(0-5) | 证据等级 | 关键证据（路径:行号 / 命令输出） |
|---|---|---|---|---|---|
| 7.1 | 测试资产规模与模块分布（风险匹配度） | 13% | 3 | A+B | Java main 713 / test 272（比 0.38），surefire 报告 `Tests run: 2388, Failures: 0, Errors: 0, Skipped: 0`（272 份报告，2026-11-13 19:05）；Python `tests/` 242 测试文件 / 2155 用例；模块比值：clinical 0.43、pharmacy 0.44、tech 0.50、insurance 0.50、**empi 0.20 / license 0.21 / fhir 0.21 / release 0.33 / masterdata 0.30** 偏低 |
| 7.2 | 单元测试质量（断言强度/边界/参数化） | 13% | 3 | A+B | Java 平均 2.76 断言/测试方法（6581/2388），Python 3.62（7593/2096）；**0 个 @Test 文件零断言**（含 Mockito `verify`/`assertThatCode` 后）；**全仓 0 处 `@ParameterizedTest`/`@ValueSource`/`@CsvSource`**（Grep 0 命中）——边界覆盖靠手写重复用例，无参数化驱动 |
| 7.3 | 契约测试与前后端 API 一致性 | 12% | 4 | A | `tests/test_console_java_api_contract.py:150-171` 静态解析 Java `@RequestMapping` + 网关 `StripPrefix` → 实测 **962 条 Java 路由 / 455 个前端调用 / 0 未匹配**；`test_console_ai_api_contract.py:55-60` → **59 个 AI 前端路径 vs 156 条 `/api/v1` 路由**；19 个 `*_contract.py` + 37 个 `test_console_*.py` 前端静态回归锁 |
| 7.4 | 迁移脚本安全（lint + baseline + 可回滚） | 12% | 3 | A+B | `scripts/migration_safety_lint.ps1:39-41` 两类规则（SET NOT NULL / ADD COLUMN NOT NULL 无 DEFAULT）；实测 `scanned=307 known=11 new=0`；11 条 baseline（`migration_safety_baseline.txt:14-24`）与 307 文件中 22 处 SET NOT NULL 的 11 个文件精确对应；**但 0 个迁移含 DROP COLUMN / DROP TABLE / RENAME / TRUNCATE，Flyway 无 undo 脚本 → DDL 不可回滚** |
| 7.5 | 集成测试与真实栈冒烟 | 12% | 3 | A+B | Java 真库 IT：3 个 `*IT.java`（clinical/auth/insurance，`@Tag("postgres")` + Testcontainers + Flyway 真实迁移），surefire 报告 `Tests run: 3, Failures: 0`（2026-11-05 13:27 实跑）；Python 侧无真库 CI job；`scripts/e2e/` 48 个冒烟脚本 **未进任何 CI job** |
| 7.6 | 端到端与浏览器测试 | 12% | 1 | A | 16 个 Playwright 用例（`tests/e2e/`），实测 **`-m e2e` 16/16 全红**：全部 `TimeoutError: waiting for locator("input[placeholder=\"用户名\"]")`；根因：登录页 placeholder 已改为「工号或院内账号…」（`frontend-console/src/views/LoginView.vue:27`），e2e 选择器自 `b051a91b`(2026-11-10) 起即陈旧；CI e2e job `continue-on-error: true`（`ci.yml:369`）→ 红了也不阻断 |
| 7.7 | CI/CD 门禁有效性 | 14% | 2 | A | `.github/workflows/ci.yml` 11 个 job（pytest/lint/vuln-scan/postgres-it/migration-lint/platform/frontend/image-build/helm/eval-baseline/e2e）设计完整，但：**① 远端 CI 最近一次运行 2026-09-15，本地 HEAD 已前进 739 个提交**，`ci.yml` 2026-10-30 才首次入库；**② 实测 ruff 门禁在当前 HEAD 即为红（2 个 F401）**；**③ e2e job `continue-on-error: true`**；**④ vuln-scan 两个步骤均 `continue-on-error: true`**；**⑤ 分支保护状态无法取证（gh 403）** |
| 7.8 | 静态分析与依赖治理 | 10% | 2 | A+B | Python：`ruff.toml` 只选 `["E9","F"]`（正确性规则，基线清零，可阻断）；**Java 侧 JaCoCo 仅在 pluginManagement 声明版本，从未绑定执行（`platform/pom.xml:134-138`）→ Java 覆盖率完全无度量**；**无 checkstyle/spotbugs/pmd**；**前端无 eslint/prettier 配置、无 vitest、0 个前端单测文件**；`vue-tsc --noEmit` 实测通过；`requirements-dev.txt` 固定 ruff==0.14.4 / pip-audit==2.9.0 |
| 7.9 | 缺陷管理与质量度量 | 6% | 1 | C+B | 无缺陷库/逃逸率/缺陷分类度量（全仓 Grep `缺陷逃逸|escaped.defect|缺陷密度` 仅命中 `docs/COMMERCIAL-READINESS-100-PLAN.md:239,318` 计划条目与 `docs/HANDOFF.md` 经验条）；`ops_incident`/`ops_postmortem`（`V2__oncall_incident.sql:54,107`）具备 severity/root_cause/corrective_actions 结构，但**属生产事件管理，不是缺陷管理** |
| 7.10 | 测试数据与合成数据治理 | 5% | 4 | A+B | ① 测试库物理隔离：`tests/conftest.py:20-21` 强制 `PG_DATABASE=medagent_test`（2026-10-30「users 表被清成 1 行 → 全站 401」事故后整改）；② 11 个 autouse fixture 把 users/consults/prescriptions/case_archive/audit/runtime_flags 等全部锁到 tmp_path（`conftest.py:99-262`）；③ e2e 数据显式标记 `[e2e]`（`test_vue_parity.py:281,295-296`）+ `scripts/cleanup_test_data.py` 幂等清理（幂等条件 A–F）；④ `.gitignore:10-19` 隔离 data/auth、data/audit、data/review、consults/prescriptions/case_archive；⑤ tracked `data/` 44 文件 PHI 扫描 0 命中 |

**权重合计**：13+13+12+12+12+12+14+10+6+5 = 109 → 归一化后各项权重见下表计算。

**归一化权重**：7.1=11.9%、7.2=11.9%、7.3=11.0%、7.4=11.0%、7.5=11.0%、7.6=11.0%、7.7=12.8%、7.8=9.2%、7.9=5.5%、7.10=4.6%（合计 100%）

---

## 2. 域加权得分与结论

**计算方式**：Σ(归一化权重 × 得分)

| 评分项 | 归一化权重 | 得分 | 加权贡献 |
|---|---|---|---|
| 7.1 资产规模与分布 | 11.9% | 3 | 0.357 |
| 7.2 单元测试质量 | 11.9% | 3 | 0.357 |
| 7.3 契约测试与 API 一致性 | 11.0% | 4 | 0.440 |
| 7.4 迁移脚本安全 | 11.0% | 3 | 0.330 |
| 7.5 集成测试与真实栈冒烟 | 11.0% | 3 | 0.330 |
| 7.6 E2E 与浏览器测试 | 11.0% | 1 | 0.110 |
| 7.7 CI/CD 门禁有效性 | 12.8% | 2 | 0.256 |
| 7.8 静态分析与依赖治理 | 9.2% | 2 | 0.184 |
| 7.9 缺陷管理与质量度量 | 5.5% | 1 | 0.055 |
| 7.10 测试数据与合成数据治理 | 4.6% | 4 | 0.184 |
| **合计** | **100%** | — | **2.603** |

### **E7 域加权得分：2.60 / 5（换算百分制 52.1 / 100）**

**一句话结论**：测试**资产**与**契约锁**已达主流基线（Java 2388 项全绿、Python 2155 项、87% 覆盖率、前后端 API 契约 455 调用零漂移、测试数据隔离与合成标记机制成熟），但**门禁从未在真实 CI 上执行过**——远端 CI 停留在 2026-09-15（本地已前进 739 提交）、ruff 门禁在当前 HEAD 即为红、16 个 E2E 用例 100% 陈旧失效且被 `continue-on-error` 掩盖、E2E job 本身也从不阻断，形成"**写得很完整的门禁，但从未真正把守过一次主干**"的结构性缺口。

---

## 3. 做得好的（Top 5，带证据）

### 3.1 契约测试做到了"机器可验 + 能真失败"（A 级）
`tests/test_console_java_api_contract.py` 不是靠人肉核对清单，而是**静态解析 Java 控制器注解 + 网关 `StripPrefix` 规则还原真实路径**，再逐条比对前端调用：

```
实测（python -c 复现）：
java routes parsed: 962
frontend calls: 455
matched frontend calls: 455 unmatched: 0
ai frontend paths: 59
backend /api/v1 routes: 156
```

它还有**自检护栏**（`:136-140`）：若解析器失效（路由 < 500 或前端调用 < 350）立即失败——这解决了"正则失效 → 测试变绿"这一类契约测试的通病。`test_console_ai_api_contract.py:57` 同样有 `len(paths) >= 40` 护栏。

### 3.2 错误码契约测试是真断言，不是摆设（A 级）
`platform/auth/src/test/java/com/medassist/auth/web/ExceptionMappingContractTest.java` 遍历 `ErrorCode.values()` 枚举全量断言 HTTP 状态，并反向锁定"业务错误码不得映射为 5xx"（`:65-77`）。其被测实现 `GlobalExceptionHandler.statusOf`（`:25-45`）是带 `default -> BAD_REQUEST` 的 switch——**新增错误码漏配分支时该测试会立刻变红**，正是它存在的理由。8 个模块各自持有域内副本（MD5 各不相同，适配各域 ErrorCode 子集），共享同一"全量遍历 + 不允许 null"的不变式。

### 3.3 门禁配置本身被"锁"起来防止被静默删除（A 级）
`tests/test_ci_gates.py` 把 CI 配置当成被测对象：`--cov-fail-under` 阈值不得低于 85（`:37`）、`-m redteam` 必须排在覆盖率全量之前（`:45-46`）、redteam 用例总数 ≥ 20 且必须真实存在（`:56-64`）、ruff 必须真实执行且规则集必须是 E9+F（`:87-90`）、e2e 必须有独立 job 且 4 个 e2e 文件必须带 `pytestmark = pytest.mark.e2e`（`:126-139`）。实测 **25 passed**。这类"配置锁"是防止"门禁被悄悄摘掉"的罕见工程实践。

### 3.4 测试数据隔离与合成标记机制成熟（A 级）
这是本域**最扎实**的一块，三层防护：

1. **库级隔离**：`tests/conftest.py:20-21` 强制 `PG_DATABASE=medagent_test`，注释记录了 2026-10-30 的真实事故（"users 17 行被清成 1 行 → 全站 401"）。
2. **文件级隔离**：11 个 autouse fixture 把 users/consults/prescriptions/case_archive/audit/queue/runtime_flags/agent 域 JSON 全部重定向到 `tmp_path`（`conftest.py:99-262`），每条 fixture 注释都写明"哪次事故导致的"。
3. **标记 + 兜底清理**：e2e 处方统一携带 `[e2e]` 标记（`test_vue_parity.py:281,295-296`），`scripts/cleanup_test_data.py` 按 A–F 六条幂等条件兜底清理，并明文记录历史遗留（"50 条残留占演示库 54%"）。

实测 `.gitignore:10-19` 精确隔离 `data/auth|audit|review/`、`consults.json`、`prescriptions.json`、`case_archive.json`；tracked 的 44 个 `data/` 文件 PHI 扫描 **0 命中**。

**A 级旁证（失败路径下仍自清理）**：本次评测实际执行了 16 个 E2E 用例且**全部失败**（见 L3），其中 `test_parity_rx_reject_and_out_of_dict_flow` 会通过 API 真实创建一条带 `[e2e]` 标记的处方（`test_vue_parity.py:294-300`）。跑完后复核：`data/prescriptions.json` 共 14 条、`[e2e]` 标记 **0 条**、药名「测试」的字典外处方 **0 条**；`data/review/queue.json` 1099 条中 `[e2e]` 标记 **0 条**。即 `finally: _purge_created_prescriptions(created_ids)`（`test_vue_parity.py:285-286`）在用例**失败**时依然正确执行——标记 + teardown 机制经真实失败路径验证有效，不是纸面设计。

### 3.5 迁移安全 lint 机制闭环且真的有效（A 级）
`scripts/migration_safety_lint.ps1` 是我本次执行过的**唯一真正有规则、有 baseline、有退出码的静态门禁**：

```
scanned=307 known=11 new=0
PASS no new blocking NOT NULL patterns   (exit 0)
```

关键设计到位：① 只扫 `db/migration` 目录并排除 `target/`（`:44-46`，避免把 307 份构建产物副本算进去——我独立复核确认：`db/migration` 下共 614 个 .sql，排除 target 后正好 307）；② baseline 按**文件路径**而非语句记录，只豁免存量、新增即红（`:52-60`）；③ 我用 Python 独立复算确认——307 文件中 22 处 SET NOT NULL 分布在 **11 个文件**，与 baseline 的 11 条**精确一一对应**；④ `ADD COLUMN NOT NULL` 无 DEFAULT 的文件数独立复算为 **0**，规则 R2 当前无违规。

---

## 4. 失分点与最小整改动作

| 编号 | 失分点 | 严重度 | 影响 | 最小整改动作 | 预估工作量 |
|---|---|---|---|---|---|
| **L1** | **CI 门禁从未对当前代码执行过**：远端 `<github-owner>/medical-agent` 的 Actions 最近一次运行是 **2026-09-15T11:09**，而本地 HEAD 为 `0ef22398 / 2026-11-13`，两者相差 **739 个提交**；`ci.yml` 本身 2026-10-30 才首次入库（`git log --diff-filter=A`）。远端 `ci.yml` 只有 2610 字节（一个 pytest job），本地已 17193 字节（11 个 job）——**本地那套门禁从未在 GitHub Actions 上跑过一次** | **严重** | 全部 11 个 CI job 的"门禁"属性在证据上均为 **未验证**。项目自述文档亦承认（`docs/AGENT-RUNTIME-20261105.md:2731`"只在本地按 CI 命令验证，未在 GitHub Actions 真机跑"）。这是本域所有其他失分点的根因 | ① 把 `<backup-dir>\medical_agent_20261030.bundle` 换成真实 GitHub 远端并推送 `main`；② 打通 GitHub Actions runner；③ 在 `Settings → Branches` 开启 `main` 的 required checks（本次 gh API 返回 403，无法确认是否已开——须人工核实）；④ 推送后立即观察首个全绿 run 并把 run URL 归档进 `docs/eval/2026-11-13/` | 0.5 人日（含一次 push + runner 打通 + 首绿观察） |
| **L2** | **ruff 门禁在当前 HEAD 即为红**：实测 `python -m ruff check backend tests scripts` → `Found 2 errors`（`tests/test_backup_dr_contract.py:16` F401 `re`、`tests/test_deploy_path_consistency.py:16` F401 `os`），exit 1。`git status` 确认两文件无未提交改动 → **红灯存在于已提交状态**。由于 CI 从未跑（L1），此红灯从未被发现 | **严重** | "阻断级问题 = 0"的验收口径（W10.2）当前**不成立**；CI 一旦接上，第一次 push 就会红 | 删除两个未使用的 import（`re` / `os`），确认 `ruff check` exit 0；把 ruff 纳入 pre-commit（`requirements-dev.txt` 已固定 ruff==0.14.4，无需装包） | 0.5 小时 |
| **L3** | **16 个 E2E 用例 100% 失效**：实测 `pytest -q -m e2e` → `16 failed`，全部报 `TimeoutError: waiting for locator("input[placeholder=\"用户名\"]")`。根因：登录页 placeholder 已于 `8a15d46e`(2026-11-02) 改为「工号或院内账号…」（`frontend-console/src/views/LoginView.vue:27`）、密码框改为「输入登录密码…」（`:47`），而 4 个 e2e 文件（`test_vue_assist.py:55-57`、`test_vue_admin.py:63-65`、`test_vue_parity.py:76-78`、`test_smoke_flow.py:68-70`）仍用旧选择器。已验证当前 dist 产物 `LoginView-DvlqBSRV.js` 中**不含**"用户名"、含"工号或院内账号" | **严重** | 关键临床链路（开方→药师复核→QC→管理台）的浏览器级回归**全部失守**；且 e2e 是唯一能验证"前端真能跑起来"的门禁，失效后前端改动无任何自动防线 | ① 把 4 个 e2e 文件的登录选择器改为 `input[name="username"]` / `input[name="password"]`（用稳定属性而非 placeholder 文案）；② 本地重跑 `pytest -m e2e` 确认 16/16 通过；③ 把 `continue-on-error: true`（`ci.yml:369`）摘掉改为阻断，或至少在 E2E 连续 2 周全绿后再摘（注释原本的计划就是"一个周期后转强制"） | 1 人日 |
| **L4** | **Java 侧无覆盖率度量、无静态分析**：JaCoCo 只在 `platform/pom.xml:134-138` 的 `pluginManagement` 里声明了版本，**从未绑定到 build 执行**（无 `prepare-agent`、无 `jacoco:report` goal）；全仓无 checkstyle / spotbugs / pmd 配置。相比之下 Python 侧有 85% 覆盖率硬门禁 + ruff E9/F 阻断 | **高** | 713 个 Java 主代码文件、2388 个测试方法的覆盖率**完全不可知**；"核心域覆盖率 ≥85%"（W10.1）只在 Python 侧成立，Java 侧无从核验。CI 的 `platform` job 只跑 `mvn package`（编译+单测），不产覆盖率 | 在 `platform/pom.xml` 的 `<build><plugins>` 中绑定 jacoco `prepare-agent` + `report`，并在 `platform` job 后加一步 `mvn jacoco:report` + 按模块阈值（先取当前基线值，勿凭空定 85）；JaCoCo `check` goal 阈值先设"不高于当前实测值"，随整改上调 | 1 人日 |
| **L5** | **前端零单测、零 lint**：全仓 0 个 `*.spec.ts` / `*.test.ts` / `vitest.config.*`；`frontend-console/` 下无 eslint / prettier 配置（Glob 0 命中）；`package.json` scripts 只有 `dev/build/preview/typecheck`。前端质量仅靠 `vue-tsc --noEmit`（实测 exit 0）与 37 个 Python 写的**静态文本锁**（正则匹配 .vue 源码字符串，如 `test_console_load_states.py:55-66` 断言 `loadErr.value =` 存在）。静态文本锁对格式重排极度敏感，且不覆盖运行时行为 | **高** | 135 个 Vue 文件的**行为**零自动验证（只有编译期类型 + 字符串匹配）；`docs/reviews/20261102-B-工程质量.md:191` 已自认："utils/format.ts、utils/surgery.ts（纯函数、最该有单测）零覆盖" | ① 引入 vitest（devDependency），先把 `utils/format.ts`、`utils/surgery.ts` 两个纯函数模块补上单测；② 加 `eslint` + `npm run lint` 进 CI frontend job；③ 保留现有 Python 静态锁作为"架构约束"，但把可被 vitest 覆盖的字符串断言迁到 vitest | 3 人日 |
| **L6** | **e2e job 与 vuln-scan job 均不阻断**：`ci.yml:369`（e2e `continue-on-error: true`）、`ci.yml:132,135`（pip-audit / npm audit 均 `continue-on-error: true`）。原注释写明"先以可观测不阻断上线一个周期……一个周期后按实际结果决定哪些级别切强制"——**该周期已过去两个月，状态未切换** | **高** | 供应链漏洞（pip-audit/npm-audit）与 E2E 全部是"只报告不阻断"，商用交付前无法满足"阻断级问题 = 0"的评审口径 | ① pip-audit 切为阻断（存量 CVE 先升依赖或按 severity 分级，`--ignore-vuln` 需登记理由）；② npm audit `--audit-level=high` 切阻断；③ e2e 随 L3 修复后切阻断 | 1 人日（依赖 L3） |
| **L7** | **Java 侧零参数化测试**：全仓 Grep `@ParameterizedTest|@ValueSource|@MethodSource|@CsvSource|@EnumSource` → **0 命中**。2388 个测试方法全为手写单值用例，边界/多值场景靠复制粘贴 | 中 | 边界值（空/null/超长/边界分页/并发）易漏；用例体积膨胀（Java test 272 文件 / 6581 次断言中约 2.76 断言/方法） | 优先对错误码映射（`ExceptionMappingContractTest`）、分页、权限矩阵三类引入 `@ParameterizedTest` + `@EnumSource`/`@CsvSource`；每类 1 个模板即可覆盖 N 个值 | 2 人日 |
| **L8** | **迁移不可回滚、baseline 只覆盖 NOT NULL 一类风险**：307 个迁移中 **0 个** 含 `DROP COLUMN`/`DROP TABLE`/`RENAME`/`TRUNCATE`（现状是安全的），但也 **0 个**含回滚脚本（Flyway 不生成 undo）→ 已上线的 DDL 变更无法逆向；lint 规则只覆盖 R1/R2 两类，不覆盖索引并发创建、列类型变更、大批量 UPDATE | 中 | 未来一旦出现"加列并回填百万行"的迁移，线上无回退手段；lint 规则集偏窄 | ① 为高风险变更建立"前向兼容 + 灰度 + 监控"模板（删列分两次：先停止写入 → 下个版本再删）；② lint 增加 `CREATE INDEX`（非 CONCURRENTLY）与 `ALTER COLUMN TYPE` 两条规则；③ 运维侧准备"按备份点回滚"的 runbook（`scripts/restore.py` 已存在，需演练） | 1.5 人日 |
| **L9** | **无缺陷库 / 无逃逸率 / 无缺陷分类度量**：全仓 Grep `缺陷逃逸|escaped.defect|缺陷密度|缺陷台账` → 仅命中计划条目 `docs/COMMERCIAL-READINESS-100-PLAN.md:239,318`（"缺陷逃逸率逐季下降，最终 <5%"）与 `docs/HANDOFF.md` 的经验条目。`ops_incident`/`ops_postmortem`（`V2__oncall_incident.sql:54,107`）虽有 severity/root_cause/corrective_actions 结构，但那是**生产事件**管理，不承载开发期缺陷 | 中 | W10.3"缺陷逃逸与根因"无实现载体；评审无法回答"本季度逃逸了多少缺陷、根因分布如何" | ① 复用 `ops_incident` 表，新增 `defect_stage` 字段区分 `FOUND_IN_DEV/FOUND_IN_TEST/ESCAPED_TO_PROD`；② 定义逃逸率 = ESCAPED_TO_PROD / (全部缺陷)；③ 每季度出一次分布报告 | 2 人日 |
| **L10** | **48 个真实栈冒烟脚本未进任何 CI job**：`scripts/e2e/` 下 48 个脚本（`clinical_*_smoke.ps1` 20 个、`pharmacy_*` 5 个、`emr_*` 2 个、`insurance_*` 4 个、`tech_*` 4 个、`empi_`、`kb_`、`internal_*`、`db_guardrails.ps1`、`cell_isolation_suite.py`、`tenant_lifecycle_suite.py`、`password_lifecycle_suite.py`、`backup_restore_drill.py` 等）覆盖了大量真实链路，但 `ci.yml` 全文 Grep `scripts/e2e` → **0 命中**；仅 `scripts/release_check.py`（版本一致性）与 `scripts/migration_safety_lint.ps1` 被 CI 调用 | 中 | 手术排台/知情同意/术前访视/麻醉复苏/毒麻药品/DRG 对账等高价值链路的端到端验证**完全依赖人工执行**，无法回归 | ① 挑 3–5 条最关键链路（如 `db_guardrails.ps1`、`pharmacy_narcotic_smoke.ps1`、`insurance_drg_smoke.ps1`）接入 CI nightly job；② 其余纳入"发布前人工执行清单"，在 `docs/DEPLOY.md` 记录执行记录与结果 | 2 人日 |
| **L11** | **测试资产分布与风险不完全匹配**：Java test/main 比值——`clinical 0.43`（173 主文件，核心临床域最大）、`pharmacy 0.44`、`tech 0.50` 尚可，但 `empi 0.20`（15/3）、`license 0.21`（14/3）、`fhir 0.21`（29/6）、`masterdata 0.30`（27/8）、`release 0.33`（21/7）偏低。`auth` 模块 89 个主文件中 21 个是 service、10 个是 repo，但只有 13 个测试文件落在 service/repo 包（`TokenRepository`/`UserRepository`/`RbacRepository`/`AuditRepository`/`CampusRepository` 等 10 个仓储类**无对应测试**） | 中 | EMPI（患者主索引）、License（授权计费）、FHIR（互操作标准）三个高业务风险域测试密度低于全平台均值；auth 的持久层正确性依赖 mock，无法发现 SQL 漂移 | ① 对 empi/license/fhir 补齐 service 层测试（各 3–5 个文件，对齐 clinical 的 0.43 均值）；② auth 的 10 个 repository 至少给 TokenRepository / UserRepository / RbacRepository 补真库 IT（Testcontainers 基础设施已存在，`ClinicalRepositoryPostgresIT` 可直接照抄） | 3 人日 |
| **L12** | **Python 侧无真库 CI job**：`ci.yml` 的 pytest job 有 `postgres:15-alpine` service 与 `medagent_test` 隔离库（`:39-74`），但 `pg_store.py` 是 1887 行的数据真源、实测覆盖率 87%（241 行未覆盖，多为降级分支），且 `test_pg_repo.py` 大量使用**假连接池**。Java 侧有 `postgres-it` 真库门禁，Python 侧没有对等物 | 中 | 数据层真实 SQL 语义（CAS、部分唯一索引、行锁并发）只在 Java 侧被真库验证，Python AI 中台的数据真源无此保障 | 在 pytest job 后加一个 `pytest-postgres-it` job：用 service 里的真 PG + 一个 `PG_REAL=1` 开关的测试集，跑 `tests/test_pg_*.py` 的真库分支（该批文件已存在，只缺"真库模式"的 CI 编排） | 2 人日 |
| **L13** | **CI 失败样本不可追溯**：pytest job 虽上传 `reports/junit.xml` + `coverage.xml` artifact（`ci.yml:84-91`），但仓库内 `reports/` 目录**无任何历史 junit/coverage 产物**（实测仅有 `reports/perf/slo-capacity-*.json/md` 两组）。因 L1，artifact 从未真实产生 | 低 | 无法回答"哪次提交开始掉覆盖率" | L1 打通后，把每次 CI 的 `pytest-reports` artifact 保留期从 14 天延长到 90 天并定期归档到 `reports/ci/<date>/` | 0.5 人日 |

---

## 5. 证据缺口清单（无法取证/未覆盖）

| # | 缺口 | 原因 | 影响 |
|---|---|---|---|
| G1 | **分支保护（required checks）状态未知** | `gh api repos/.../branches/main/protection` → `HTTP 403 Resource not accessible by integration`；且该仓库最后 push 在 2026-09-15，与当前代码无关联 | 无法判定"合并是否必须过 CI"。`tests/test_ci_gates.py:114-123` 自己也承认"分支保护本身是仓库设置项，代码侧只能提供所有者声明与模板" |
| G2 | **CI job 的历史通过/失败曲线无本地证据** | 远端 Actions 仅有 9 次运行（2026-09-12 ~ 2026-09-15），对应的是**旧版** ci.yml（2610 字节，仅 pytest job） | 无法用趋势证明"门禁是否稳定/常红被忽略"。`docs/AGENT-RUNTIME-20261105.md:2731` 记录了同类历史教训（G63：配置写错 → 门禁常红 → 被忽略 → 等于没有） |
| G3 | **Java 测试未在本次评测中实跑** | 硬约束禁止 `mvn test/build`。取而代之使用 **surefire 报告产物**（272 份 txt，2026-11-13 19:05，`Tests run: 2388, Failures: 0, Errors: 0, Skipped: 0`）作为 A 级证据 | surefire 报告反映最近一次本地运行全绿，但**不能证明 CI 环境**下同样全绿（依赖不同 JDK/镜像拉取） |
| G4 | **postgres-it 三个 IT 的最近一次实跑是 2026-11-05** | surefire 报告时间戳 `LoginThrottlePostgresIT.txt` / `ClinicalRepositoryPostgresIT.txt` / `RealtimeSettlementPostgresIT.txt` 均为 2026-11-05 13:27（需 Docker + 本地 postgres:15-alpine 镜像） | 真库 SQL 门禁已 8 天未实跑；当前代码此后有 739 个提交级演进，IT 可能已漂移 |
| G5 | **前端构建产物 `frontend-console/dist` 随轮入库（206 个文件被 git 跟踪）** | `git ls-files frontend-console/dist` = 206；`tests/test_frontend_hosting.py:54` 断言"控制台产物应随轮入库" | E2E 跑的是**入库的旧产物**而非源码现编结果。本次已验证入库产物与当前源码的登录 placeholder 一致（均为"工号或院内账号…"），故 E2E 失败不是产物陈旧所致，而是**选择器本身陈旧** |
| G6 | **AI 评测基线（eval-baseline job）未实跑** | 依赖 `QWEN_API_KEY` secret + Milvus + PG + 外网拉取，属外部依赖 | `scripts/run_eval.py` 的"9/9"门槛未在本次评测中独立验证 |
| G7 | **缺陷逃逸率无任何历史数据** | 系统内不存在缺陷库 | L9 的最小整改缺少基线值，无法设定"逐季下降"的目标线 |
| G8 | **未评估前端测试基础设施的引入成本** | 本次为只读评测，未做依赖安装/可行性验证 | L5 的 3 人日估算是经验值，实际取决于是否允许引入 vitest 依赖 |
| G9 | **`scripts/e2e/` 48 个脚本的实际可执行性未验证** | 需完整 Java 服务矩阵（8081–8095）+ PG + 演示数据全起，执行代价过高 | L10 中"哪些脚本当前还能跑"未逐一确认；脚本本身还有已知踩坑记录（`docs/HANDOFF.md:1578-1599` 记录了 PowerShell 5.1 ANSI 读取中文串导致断言必失败等 6 类坑） |

---

## 6. 外部依赖项（不由代码关闭）

| # | 外部项 | 说明 | 需谁完成 |
|---|---|---|---|
| E1 | **GitHub Actions runner / 分支保护配置** | L1 的核心前置。仓库当前 `origin` 指向本地 bundle 文件 `<backup-dir>\medical_agent_20261030.bundle`（16MB，2026-10-30），不是 GitHub 远端 | 平台/运维团队：换远端 + 推 main + 配 required checks |
| E2 | **`QWEN_API_KEY` / `QWEN_BASE_URL` secret** | `ci.yml:322-323` 引用，缺失则 `eval-baseline` job 的 AI 评测维度不可用 | 院方/平台：Secrets 配置 |
| E3 | **GitHub Packages / 容器镜像仓库凭证** | `image-build` job 只 build 不 push（`ci.yml:227 push: false`），如需交付镜像需 registry 凭证 | 运维 |
| E4 | **院方真实环境联调** | `docs/COMMERCIAL-READINESS-100-PLAN.md:467` 明确 "HL7 v2 ADT/ORM/ORU 联调通过——院方联调通过后勾选"，属软件外的医院侧依赖 | 院方信息科 + 厂商 |
| E5 | **第三方安全扫描工具的 CVE 情报库** | pip-audit/npm audit 依赖上游 advisory 库；离线环境下可能漏报 | 运维网络策略 |
| E6 | **Testcontainers 依赖的 Docker + postgres:15-alpine 镜像** | `postgres-it` job 与本地 IT 均需；`ci.yml:162-163` 显式 pre-pull 以适配离线环境 | CI runner 环境 |

---

## 7. 复现命令

> 全部为只读/无副作用命令；在仓库根 `<repo>` 执行。
> Python 解释器：`python`

```powershell
# 【S1】测试资产规模：Java main/test 文件数与模块比值
Get-ChildItem platform -Directory | ForEach-Object {
  $m = @(Get-ChildItem $_.FullName -Recurse -Filter *.java -File | Where-Object { $_.FullName -notmatch '\\src\\test\\' }).Count
  $t = @(Get-ChildItem $_.FullName -Recurse -Filter *.java -File | Where-Object { $_.FullName -match '\\src\\test\\' }).Count
  [PSCustomObject]@{Module=$_.Name; Main=$m; Test=$t; Ratio=[math]::Round($t/$m,2)}
} | Sort-Object Module | Format-Table -AutoSize

# 【S2】Java 最近一次真实单测结果（surefire 报告产物，272 份）
Get-ChildItem platform -Recurse -Directory -Filter surefire-reports | ForEach-Object {
  Get-ChildItem $_.FullName -Filter *.txt -File
} | Select-String -Pattern 'Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+)' |
  ForEach-Object { $_.Matches[0].Groups[1..4].Value -join '/' } | Measure-Object | Format-Table Count

# 【S3】Python 用例收集（只收集不执行）
& "python" -m pytest --collect-only -q
#  → 2155 tests collected in 6.31s

# 【S4】红队门禁（CI 同款命令）
& "python" -m pytest -q -m redteam
#  → 23 passed, 2132 deselected in 7.23s

# 【S5】全量 + 覆盖率（CI 同款门禁，耗时约 8 分钟）
& "python" -m pytest -q --cov=backend --cov-report=term:skip-covered --cov-fail-under=0 tests/
& "python" -m coverage report
#  → 16 failed, 2138 passed, 1 skipped in 503.89s
#  → TOTAL  14387  1925  87%

# 【S6】E2E 现状（本次核心发现：16/16 全红）
& "python" -m pytest -q -m e2e --timeout=180
#  → 16 failed（全部 TimeoutError: waiting for locator("input[placeholder=\"用户名\"]")）

# 【S7】Python 静态门禁（当前 HEAD 即为红）
& "python" -m ruff check backend tests scripts
#  → F401 tests/test_backup_dr_contract.py:16 're'；F401 tests/test_deploy_path_consistency.py:16 'os'；exit 1

# 【S8】前端类型检查（CI 同款）
cd frontend-console; .\node_modules\.bin\vue-tsc.cmd --noEmit; Write-Output "exit=$LASTEXITCODE"
#  → exit=0（无输出）

# 【S9】迁移安全 lint（只读扫描；已确认脚本不写库不写文件）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\migration_safety_lint.ps1
#  → scanned=307 known=11 new=0 / PASS no new blocking NOT NULL patterns

# 【S10】CI 门禁"从未跑过当前代码"的取证
git log -1 --format="%H %ci %s"                      # 本地 HEAD 0ef22398 2026-11-13
gh api repos/<github-owner>/medical-agent/actions/runs --jq '.workflow_runs[0] | {created_at, conclusion}'
                                                     # → 2026-09-15T11:09:00Z（落后 739 个提交）
gh api repos/<github-owner>/medical-agent/contents/.github/workflows/ci.yml --jq '.size'
                                                     # → 2610（远端旧版）vs 本地 17193
gh api repos/<github-owner>/medical-agent/branches/main/protection
                                                     # → HTTP 403（分支保护不可取证）

# 【S11】Java 覆盖率/静态分析缺失的取证
Select-String -Path platform\pom.xml -Pattern 'prepare-agent|jacoco'   # → 只有 pluginManagement 里的版本号，无 goal
Get-ChildItem platform -Recurse -Include 'checkstyle*.xml','spotbugs*.xml' -ErrorAction SilentlyContinue  # → 空

# 【S12】前端零单测/零 lint 的取证
Get-ChildItem frontend-console -Recurse -Include '*.spec.ts','*.test.ts','vitest.config.*' -ErrorAction SilentlyContinue | Where-Object { $_.FullName -notmatch node_modules }  # → 空
Get-ChildItem . -Recurse -Force -Include '.eslintrc*','eslint.config.*' -ErrorAction SilentlyContinue | Where-Object { $_.FullName -notmatch 'node_modules|\.git' }  # → 空

# 【S13】E2E 选择器陈旧的取证
Select-String -Path 'tests/e2e/*.py' -Pattern 'placeholder="用户名"'   # → 4 文件 12 处
Select-String -Path frontend-console/src/views/LoginView.vue -Pattern 'placeholder='  # → :27 工号或院内账号…  :47 输入登录密码…

# 【S14】冒烟脚本未进 CI 的取证
Select-String -Path .github/workflows/ci.yml -Pattern 'scripts/e2e|smoke_all'   # → 0 命中
(Get-ChildItem scripts/e2e -File).Count                                       # → 48

# 【S15】测试数据隔离与 PHI 的取证
Select-String -Path tests/conftest.py -Pattern 'PG_DATABASE|tmp_path' | Select-Object -First 5
Select-String -Path .gitignore -Pattern 'data/auth/|data/audit/|data/review/|consults.json|prescriptions.json'
Select-String -Path data -Pattern '\b\d{17}[\dXx]\b' -Recurse   # → 0 命中
```

---

## 附：域结论一句话（供 SUMMARY.md 引用）

> **E7 = 2.60 / 5（52.1/100）**。测试资产的**规模与契约锁质量**已达主流基线（Java 2388 项 / Python 2155 项全绿或近全绿、覆盖率 87%、前后端 API 契约 455 调用零漂移、测试数据三重隔离与 `[e2e]` 合成标记成熟），但**门禁的"把守"功能在证据上从未成立**：CI 远端停留在 739 个提交之前（2026-09-15）、ruff 门禁在当前 HEAD 即为红、16 个 E2E 100% 陈旧失效且被 `continue-on-error` 掩盖、Java 侧覆盖率与静态分析完全缺位、前端 135 个 Vue 文件零单测零 lint、无缺陷库与逃逸率度量。**整改第一优先级不是补测试，而是让门禁真正跑起来并变红一次（L1+L2+L3，约 2 人日）**，否则后续所有测试投入都无法证明其价值。

---

## 附二：本次评测的只读合规声明

| 项 | 状态 |
|---|---|
| 修改产品代码 / 配置 / 迁移 / 构建产物 | **无**。`git status --porcelain` 仅显示 `?? docs/eval/`（本报告目录） |
| 执行的命令 | 全部为只读或无副作用：`pytest --collect-only`、`pytest -m redteam`、`pytest --cov`（写 `.coverage` 临时文件，评测后已删除；`.coverage` 本身已被 `.gitignore:75` 忽略）、`coverage report`、`ruff check`、`migration_safety_lint.ps1`（执行前已 Read 确认只读文件、不写库不写文件）、`vue-tsc --noEmit`（noEmit，无输出产物）、`git log/show/ls-files`、`gh api`（只读查询） |
| 未执行 | `mvn test` / `mvn package` / `mvn -Ppostgres-it`、完整 `pytest` 的覆盖率门禁强制模式（用 `--cov-fail-under=0` 只取数不改阈值）、`pip install` / `npm install`、`git commit` / `git push` |
| 测试数据副作用 | E2E 失败运行后复核 `data/prescriptions.json`、`data/review/queue.json`：`[e2e]` 标记残留 **0 条**（详见 §3.4 A 级旁证）；`.coverage` 临时产物已清理 |
| 唯一写入文件 | `docs/eval/2026-11-13/07-test-quality-engineering.md` |
