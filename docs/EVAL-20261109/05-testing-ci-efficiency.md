# MedAssist 测试体系 / CI-CD / 工程效能评测报告

- **评测日期**：2026-11-09
- **评测对象**：`<repo>`
- **评测基线**：commit `616e767`（main 分支），HEAD = `616e767 docs(eval): 系统评估 04 安全与合规`
- **方法**：静态阅读 + 轻量实测（`pytest --collect-only`、`pytest --durations`、定向 pytest 子集、`--cov` 单次全量采集、git 状态/历史核查、Helm/依赖清单核对）
- **未执行**：全量 pytest（5.5 min）、`npm run build`、Maven 构建、Helm template（本机无 helm）

---

## 总体评价

**评分：7.0 / 10**

三句话结论：

1. **测试「工程化」水平明显高于同类医疗 AI 项目**：分层清晰（单元 / 路由 / e2e / 静态架构锁 / 前端标记锁）、隔离基建完备（`tests/conftest.py` 12 个 autouse fixture）、架构与门禁反退化锁（`test_architecture.py`、`test_router_split_guard.py`、`test_ci_gates.py`）都做成了「改代码 → 忘记同步就红」的机械断言，这在国内医疗项目中罕见。
2. **但「门禁的对象错了」是本次最严重的系统性问题**：CI 的 `frontend` job 构建的是 `frontend-console`，而**实际随镜像交付给医院的前端产物是 `frontend-vue/dist`**（`Dockerfile:31` `COPY frontend-vue/dist`）。也就是说，**真正上生产的前端在 CI 里既没有 typecheck、也没有构建、也没有 lint**；同时 CI 全程不构建任何 Docker 镜像，镜像装配正确性无任何断言。
3. **测试污染生产数据的隔离缺口已被实证触发**：本次评测跑完 `tests/e2e` 后，`git status` 立即出现 `M data/prescriptions.json`（+69 行，含「主键直查验证病例」等真实临床文本），而该文件是**已被 git 跟踪的**；`data/case_archive.json` 更是**既未跟踪、也未被 .gitignore 覆盖**。医疗项目把 PHI 写回版本库的路径是敞开的。

**分项得分**

| 维度 | 得分 | 一句话依据 |
|---|---|---|
| 测试覆盖广度 | 7.5 | 1526 用例覆盖 12 个业务域，backend 覆盖率实测 88.2% |
| 测试隔离与数据安全 | 4.0 | conftest 隔离了 7 个域，但漏 `consults.json`/`prescriptions.json`/`case_archive.json`（实证污染） |
| 断言有效性 | 7.0 | 仅 2 个用例唯一断言是 `status_code == 200`，整体断言密度高 |
| CI 门禁完备性 | 6.0 | 有覆盖率/红队/Helm/迁移 lint，但漏前端真产物、镜像构建、漏洞扫描、分支保护 |
| 依赖与供应链 | 5.5 | Python 侧全量 `==` 锁定（好），但缺 PyJWT 声明、无 lock 级别的 CVE 扫描、无 SBOM |
| 测试速度 | 6.0 | 单次全量 3.5 min（实测，去 e2e）+ e2e 1.9 min；无并行、无 slow 标记 |
| 发布与变更管理 | 5.5 | 有 DDL 幂等 + 迁移 lint + 回滚顺序，但 Python 侧无版本化迁移目录、无 CHANGELOG |
| 文档与知识沉淀 | 6.5 | 台账「已做/校验/仍存边界」三段式质量很高（116 处边界如实登记），但 377KB 单文件不可检索 |
| 备份与容灾 | 7.0 | `backup_repo.ps1` 五条安全纪律写得极扎实，三次恢复演练有记录；但保留策略缺失、异地缺失 |

---

## 优点

### A1. 架构反退化锁做成了机械断言，而不是文档约定

`tests/test_architecture.py:54-79` 用正则从源码文本提取 `from backend.x import` 语句，机械断言四条依赖方向规则（core 不得依赖 api/agents、integration 不得依赖 core/agents 等）。第 5 条 `test_api_may_compose_all_layers`（L75）还反向断言正向依赖真实存在，防止「靠删 import 让违规消失」。

`tests/test_router_split_guard.py` 三条锁同样硬：
- L105 `test_medical_modules_line_ceiling`：单文件 ≤600 行，防 2275 行巨石回潮；
- L112 `test_no_from_import_of_audit_accessor`：禁止 `from …shared import get_audit_logger`（该写法会让 monkeypatch 静默失效，是 W4.3 首跑 54 红的根因）；
- L135 `test_route_snapshot_order_locked`：64 个 `(method, path)` 的**注册顺序**快照，防路由遮蔽回归。

这类「把踩过的坑变成回归锁」的实践是本项目测试体系最有价值的部分。

### A2. 覆盖率门禁不仅有，而且被反向锁死

`tests/test_ci_gates.py:31-70` 锁了四件事：CI 必须带 `--cov=backend`（L34）、阈值必须 ≥85（L37）、`-m redteam` 必须排在覆盖率全量之前（L45）、`pytest-cov` 必须 `==` 固定版本（L70）。L37 的注释写明「阈值只能上调，不能为跑绿而降」——这正面回应了「G63：配置写错 → 门禁常红 → 被忽略 → 等于没有」的历史教训。

**本次实测覆盖率**：`pytest --cov=backend --ignore=tests/e2e` → **1509 passed / 1 skipped / 215.64s / 覆盖率 88.2%（11313 statements, 1335 missing）**，高于门禁阈值 85%。

### A3. 隔离基建扎实，且带事故复盘

`tests/conftest.py` 共 12 个 autouse fixture，每个 docstring 都记录了具体的生产事故与实测症状：
- L20-21：W4.1 事故（2026-10-30）——「实测 users 17 行被清成 1 行 → 全站 401」，因此把 `PG_DATABASE` 强制指向 `medagent_test`；
- L157-169：M-29 事故复发（2026-11-06）——「`tests/test_row_level_users.py` 单跑一次即把生产用户表清成 `{}`」，补上 `auth.USERS_FILE` 隔离；
- L172-187：Windows `os.replace` 的 `WinError 32` 并发偶发失败，根因定位到 `queue.json` 共享写；
- L202-212：`/admin/audit` 10s 进程内缓存导致「单跑通过、全量跑冲突」。

这种「每条隔离规则都能追溯到一次真实事故 + 实测症状」的写法，让新人能判断**为什么**要这么隔离，而不是照抄。

### A4. 前端标记锁设计巧妙

`tests/test_frontend_syntax.py` 全文 937 行、35 个用例，用「源码文本标记锁」替代了 legacy 时期的 `node --check`。亮点：
- L72 `test_vue_dist_build_output_in_sync` 锁住「dist 随轮入库」策略（dist/index.html 存在 + assets 至少 1 个 JS + src 非空，防「dist 存在但源被误删」的伪同步）；
- L161 `test_humanize_audit_consult_events_node` 把 Vue 源里的纯函数抽出来用 node 真跑 8 个 case（不是字符串匹配，是**真实执行 + 逐字比对输出**）；
- L781 `test_kbview_xss_esc_markers_present` 做的是**反向锁**（L807-808：禁止出现 `+ f.name +` 等未转义拼接），而不是只正向断言某处有 `esc()`——反向锁能防「换个写法就绕过」。

### A5. 备份脚本把事故教训写进了代码

`scripts/backup_repo.ps1` 头部 L1-24 记录了 2026-10-30 的真实事故：**旧版本脚本先删目标再 clone，脚本 bug 导致变量解析错误，源工作树被销毁**（仅靠几分钟前的一个 bundle 救回）。当前版本五条纪律全部落地：
- L44-49：先构建到临时名 → 校验 HEAD 一致 → 才换名（绝不预删）；
- L52-53：`.git` 缺失直接 abort；
- L61-70：换名失败自动 rollback，保证「永不出现零镜像」；
- L18-19：ASCII-only 约束（PS 5.1 无 BOM UTF-8 按 ANSI 解析，中文注释会导致变量变 null，正是事故根因）；
- L20-21：退出码诚实（0 = OK，2 = 降级，1 = 硬失败）。

这是我在本次评测中见到的**工程质量最高的单个脚本**。

### A6. 迁移安全 lint 抓的是真实的生产风险

`scripts/migration_safety_lint.ps1` 针对的是「在存量大表上直接 `ADD COLUMN NOT NULL` / `SET NOT NULL` 会取 ACCESS EXCLUSIVE 锁并全表扫描 = 部署时宕机」。两条规则 + `migration_safety_baseline.txt` 基线机制（已下线的存量迁移记录在案，只拦新增），设计精准。台账 L854 还如实登记了边界：「Chart 版本递增只解决"变更可追溯"，未接入 CI 自动校验」。

### A7. 备份/容灾有三次真演练记录，不是纸面流程

`docs/DEPLOY.md`：
- L197-204：**2026-09-12** 数据恢复演练——植入双探针（文件 + PG `runtime_flags`），18 个 JSON 行数对比全部 OK，PG 9 张表 count 与基线一致；
- L217-218：**2026-10-30** Milvus 卷级备份还原——容器 6/6 Up，`run_eval.py` 9/9 与演练前一致；
- L230-232：**2026-11-08** git bundle 恢复演练——`verify` 输出 "records a complete history"，克隆体 23 passed、`--dry-run` PASS。

L234-246 还诚实列出了 bundle 恢复的**边界表**（`data/auth/users.json`、PG 业务数据、`.env.local`、模型权重都**不在** bundle 里，必须走 `restore.py` + Secret 注入），避免「以为备份了就万事大吉」。

---

## 必须优化（P0 / P1）

### P0-1｜CI 构建的前端不是交付给医院的前端

**问题**
`.github/workflows/ci.yml:126-145` 的 `frontend` job 全部在 `working-directory: frontend-console` 下执行 `npm ci` / `npm run typecheck` / `npm run build`。但：

- `docs/DEPLOY.md:36`：「Vue3 + Vite + Element Plus 单页应用（`frontend-vue/`）为**唯一前端**」；
- `docs/ARCHITECTURE.md:22`：「前端独立构建（`npm run build` → `frontend-vue/dist/`，随库提交 dist」；
- `Dockerfile:31`：`COPY frontend-vue/dist ./frontend-vue/dist` —— **镜像里装的是 frontend-vue 的产物**；
- `frontend-vue/package.json:7-11`：只有 `dev`/`build`/`preview`，**没有 `typecheck` script，也没有 `lint` script**；
- `frontend-console` 是对接 Java 网关 `/api/v2` 的独立控制台（`frontend-console/src/api/http.ts` 走 `/api/v2`），`frontend-vue/src/api/index.js` 走 `/api/v1`，两者是**不同后端的不同前端**。

**证据**
```
git ls-files frontend-console/dist  →  0 个文件（未入库，不随镜像交付）
git ls-files frontend-vue/dist     →  3 个文件（已入库，随镜像交付）
```
`backend/`、`deploy/` 全目录 grep `frontend-console` → **无任何匹配**，说明它不是 Python 侧产物。

**影响**
真正的生产前端（`frontend-vue`）在 CI 里**零校验**：改了 `frontend-vue/src/*.vue` 导致构建失败、产物与 src 不同步、`dist` 忘记重建入库，CI 全绿。`tests/test_frontend_syntax.py` 只锁了 35 个字符串标记，不验证「能构建」。这是「CI 全绿但部署即白屏」的直接通路。

**建议做法**（预估 1 人日）
1. 在 `frontend-vue/package.json` 增加 `"typecheck": "vue-tsc --noEmit"`（需加 `typescript` + `vue-tsc` devDependency，与 `frontend-console` 对齐）；
2. CI `frontend` job 拆为两个 step 或两个 job：`frontend-vue`（typecheck + build + **构建产物 diff 断言**：build 后 `dist/assets` 的 hash 文件名应与已入库 `dist` 一致，不一致即红）与 `frontend-console`（现状保留）；
3. `frontend-vue` 的构建产物同步断言可直接进 `tests/test_frontend_syntax.py`（当前 L72 只断言「dist 存在」，应升级为「dist 与 src 同步」——比对构建产物内嵌的源码 hash 或直接在 CI 里 build 后 `git diff --exit-code frontend-vue/dist`）。

---

### P0-2｜CI 完全没有构建 Docker 镜像，镜像装配正确性零断言

**问题**
`.github/workflows/ci.yml` 全文 grep `docker build` / `Dockerfile` → **0 匹配**。CI 的 7 个 job（pytest / postgres-it / migration-lint / platform / frontend / helm / eval-baseline）没有一个构建镜像。

**证据**
`Dockerfile` 中有多个只有构建镜像才能暴露的错误点：
- L19 `COPY requirements.txt ./` → L25 `RUN pip install -r requirements.txt`：若 `requirements.txt` 漏声明某个 import（见 P1-4 实测 PyJWT 缺失），**镜像启动时才会 `ModuleNotFoundError`**，CI 的 pytest job 却因为本机环境已装好而全绿；
- L27 `COPY backend ./backend` → L29 `COPY frontend-vue/dist ./frontend-vue/dist`：`frontend-vue/dist` 若未入库（被 `.gitignore` 的 `dist/` 通配误伤），`COPY` 直接失败；
- L41 `HEALTHCHECK ... urlopen('http://127.0.0.1:8001/healthz')`：健康检查路径写错不会被任何测试发现；
- L47 `ENTRYPOINT` 指向 `deploy/docker/entrypoint.sh`（L33 拷贝），该脚本内的 SkyWalking 挂载逻辑无测试。

**影响**
「代码在 CI 里全绿 → 镜像构建失败 → 部署现场才发现」是典型的产线事故模式。当前 `requirements.txt` 与 `import` 已经不一致（见 P1-4），这个缺口是**已知的、正在生效的**风险而非假设。

**建议做法**（预估 1.5 人日）
1. CI 新增 `image` job：`docker build -t medassist-ai-core:test .`（可用 `--build-arg` 指向国内镜像源，Dockerfile L8-9 已预留）；
2. 构建后跑一个**镜像内冒烟**：`docker run --rm -d -p 8001:8001` → 轮询 `/healthz` → `docker exec` 验证 `python -c "import backend.main"` → 验证 `frontend-vue/dist/index.html` 存在于镜像内 → 清理；
3. 断言脚本放进仓库（如 `scripts/image_smoke.sh`），CI 调用而非内联，保持本地可复跑。

---

### P0-3｜e2e 测试会把含真实临床文本的处方写回「已被 git 跟踪的」生产数据文件

**问题（本次评测实证触发）**
本次评测运行 `pytest tests/e2e` 后，git 工作区立刻被污染：

```
$ pytest -q tests/e2e          → 16 passed in 113.27s
$ git status --short
 M data/prescriptions.json      ← +69 行，含真实临床文本
?? data/case_archive.json
```

`git diff data/prescriptions.json` 显示新增了 3 条处方，字段如 `"case_text": "主键直查验证病例，患者主诉发热三天，需要规范用药。"`、`"name": "华法林"`、`"doctor": "doctor01"`。

**根因链**
1. `data/prescriptions.json` 与 `data/consults.json` **被 git 跟踪**（`git ls-files` 确认；`data/consults.json` 已进 12 个 commit，`data/prescriptions.json` 已进 16 个 commit），且内容是**真实临床数据**（`data/consults.json` 内含「65岁男性，右下后牙自发性跳痛…空腹血糖9.8mmol/L」等完整病史 + AI 意见）；
2. `tests/conftest.py` 的隔离清单覆盖了 `runtime_flags`（L76）、`case_archive`（L146）、`users`（L156）、`review/queue`（L172）、`audit`（L172 内）、`consult_metrics`（L190）、`audit 聚合缓存`（L202）、agent 域 JSON（L95），**但没有覆盖 `consults.CONSULTS_FILE` 与 `prescriptions.PRESCRIPTIONS_FILE`**；
3. `tests/test_consults.py:26-29`、`test_prescriptions.py:60`、`test_prompt_injection.py:89-92` 等是**逐文件** monkeypatch 这两个路径的——只要有一个测试文件漏了，就写穿到真实文件；
4. e2e 走的是**真实服务进程**（`tests/e2e/test_vue_parity.py:41` `BASE_URL = "http://127.0.0.1:8001"`），服务的 `PRESCRIPTIONS_FILE` 就是 `data/prescriptions.json`——`conftest.py` 的 monkeypatch 对已在运行的独立服务进程**完全无效**；
5. e2e 自己的 teardown `_purge_created_prescriptions`（`test_vue_parity.py:238-270`）只清理**带 `[e2e]` 标记**的记录，而上面那 3 条是历史遗留的**无标记**残留，永远清不掉。

**补充事实**
`data/case_archive.json` 是 `git status` 里的 `??`（未跟踪），且 **`git check-ignore` 确认它未被 .gitignore 覆盖**——任何人执行一次 `git add -A` 就会把它提交进版本库。

**影响**
- **PHI 泄漏进版本库**：`data/consults.json`（393KB，12 个 commit）与 `data/prescriptions.json`（16 个 commit）已经在 git 历史里，含完整临床叙述。这意味着即使现在修复，历史提交仍需 `git filter-repo` + 强推 + 通知所有克隆方——而 `origin` 指向 `<backup-dir>\medical_agent_20261030.bundle`（**remote 就是那个 2026-10-30 的 bundle 文件**，不是网络远端），副本扩散面未知。
- **`.gitignore` 规则不对称**：`.gitignore` 已排除 `data/auth/`、`data/audit/`、`data/review/`（明确标注「用户隐私数据」），却**没排除 `data/consults.json` / `data/prescriptions.json` / `data/case_archive.json` / `data/departments.json` / `data/drug_dict.json`**——`git check-ignore` 对这五个文件全部返回「未忽略」。这是规则的**遗漏**，不是有意设计（`drug_dict.json`/`drug_rules.json` 是种子产物确实该入库，`departments.json` 是科室字典确实该入库，但 `consults.json`/`prescriptions.json`/`case_archive.json` 是**业务实例数据**，性质完全不同）。

**建议做法**（预估 1 人日 + 1 次历史清理）
1. **立即**：把 `data/consults.json`、`data/prescriptions.json`、`data/case_archive.json` 加入 `.gitignore`，并 `git rm --cached` 取消跟踪；`data/departments.json`、`data/drug_dict.json`、`data/drug_rules.json` 保留跟踪（是种子/字典产物），但需在 `.gitignore` 里加注释说明「为何这 3 个例外」。
2. **立即**：给 `data/case_archive.json` 加 ignore 规则（当前是 `??`，最容易被误提交）。
3. **短期**：`conftest.py` 补两个 autouse fixture，把 `consults.CONSULTS_FILE` / `prescriptions.PRESCRIPTIONS_FILE` 指向 `tmp_path`，与既有的 `_isolate_users_file`（L156）同模式——这样逐文件 monkeypatch 不再是「必须记住的纪律」，而是「基础设施默认兜底」。
4. **中期**：历史 PHI 清理（`git filter-repo --path data/consults.json --path data/prescriptions.json --invert-paths`），需评估 `origin`（bundle 文件）的处理方式，并同步 `scripts/backup_repo.ps1` 产出的新 bundle。
5. e2e teardown 的清理口径应从「`[e2e]` 标记」扩展到「按会话 ID + 时间窗」，避免无标记残留永久累积。

---

### P0-4｜CI 门禁没有覆盖前端真产物、无 lint/typecheck、无漏洞扫描、无分支保护

**问题**
逐项核查 `.github/workflows/ci.yml`（275 行，7 个 job）后，缺失项如下（详细表见下方「CI 门禁现状表」）：

| 缺失门禁 | 现状证据 |
|---|---|
| Python lint（ruff/flake8） | 仓库无 `ruff.toml`/`.flake8`；`grep -r "ruff\|flake8"` 无命中 |
| Python type check（mypy/pyright） | 无 `mypy.ini`/`.mypy.ini`/`pyproject.toml` |
| 前端 lint | `frontend-vue`/`frontend-console` 均无 eslint 配置（`Get-ChildItem -Recurse -Filter ".eslintrc*"` 只命中 `node_modules` 内部文件） |
| 前端 `frontend-vue` typecheck | `frontend-vue/package.json` 无 `typecheck` script |
| 依赖漏洞扫描 | 无 `pip-audit`/`safety`/`npm audit`/`osv-scanner` 任何引用 |
| SBOM / 依赖清单导出 | 无 `cyclonedx`/`syft`/`pip freeze` artifact |
| 镜像构建 + 镜像内断言 | 见 P0-2 |
| 分支保护 / CODEOWNERS | `.github/` 下**只有 `workflows/ci.yml` 一个文件**——无 CODEOWNERS、无 PR 模板、无 ISSUE 模板 |
| pre-commit 钩子 | 无 `.pre-commit-config.yaml` |
| 覆盖率报告 artifact | `ci.yml:80` 只输出 `--cov-report=term-missing:skip-covered` 到日志，无 XML/JSON artifact，无 PR diff 覆盖 |

**影响**
- 「Python 代码风格/类型」无强制约束，`tests/test_architecture.py` 只锁**依赖方向**，不锁**代码形态**（行长、裸 except、类型注解）。项目里 `except Exception` 用了 `# noqa: BLE001` 注释做人工标注，但没有机器校验，等价于「靠自觉」；
- 供应链风险无扫描：`requirements.txt` 里 `mcp>=1.8,<2`（唯一非精确锁定）、`apache-skywalking==1.3.0`（2024 年版本）、`pillow==12.3.0`、`biopython==1.84` 等均无 CVE 状态跟踪；
- 无分支保护意味着：**任何人可以直接 push 到 main**——而 git log 显示 main 上是「一批一提交」的连续 feat 提交，没有 PR 痕迹。

**建议做法**（预估 2-3 人日）
1. 加 `ruff`（配置极简：`E,F,I,B,UP` + line-length=100），CI 加一步 `ruff check backend tests scripts`；先 `--exit-zero` 跑一轮出基线清单，再切强制；
2. `frontend-vue` 加 `vue-tsc` typecheck（与 `frontend-console` 对齐，见 P0-1）；
3. CI 加 `pip-audit -r requirements.txt` 与 `npm audit --audit-level=high`（两个前端目录都跑），先设为 `continue-on-error: true` 观察一个周期；
4. 加 `.github/CODEOWNERS` + 仓库设置里开分支保护（main 必须 PR + 必须过 CI）；这是唯一能保证「CI 门禁真的被遵守」的机制——**当前所有门禁在技术上都是可绕过的**；
5. `pytest --junitxml` + `pytest-cov --cov-report=xml` 作为 artifact 上传，便于回溯。

---

### P1-1｜`requirements.txt` 缺 `PyJWT` 声明（实测 import 与声明不一致）

**问题**
`backend/core/auth.py:21` `import jwt`，`L64`/`L70` 使用 `jwt.PyJWTError`。但 `requirements.txt`（50 行）**全文没有 PyJWT**。

**证据**
```
requirements.txt  →  无 "jwt" / "PyJWT" 任何匹配
pip show PyJWT    →  Name: PyJWT, Version: 2.13.0   ← 本机环境里装着，但 requirements 没声明
```

**影响**
- 全新环境按 `requirements.txt` 装完后，`import backend.core.auth` 直接 `ModuleNotFoundError: No module named 'jwt'`——**而 CI 的 pytest job 永远发现不了**，因为 CI 的 `pip install -r requirements.txt` 后本机恰好有 PyJWT（或被其他包间接带入，`pip list` 有 202 个包，环境不干净）。
- 这正是 P0-2（镜像构建无断言）会捕获、但当前没有门禁捕获的问题。**两个缺口叠加 = 生产镜像启动即崩**。

**建议做法**（0.5 人日）
1. `requirements.txt` 增加 `PyJWT==2.13.0`（与本机已装版本一致，锁精确版本）；
2. 加一条 CI 断言（可放在 `tests/test_ci_gates.py` 同款模式）：扫描 `backend/` + `scripts/` 的 `import X` 顶层名，与 requirements 的分发名做映射比对，缺一个即红——这是**一次性的机械化补齐**，比逐个手查可靠；
3. 顺带清理 `pip list` 里的 202 个包与 requirements 的差集，确认 CI 用的干净环境能跑通。

---

### P1-2｜测试隔离缺口：`consults` / `prescriptions` / `case_archive` 未纳入 autouse 兜底

**问题**
`tests/conftest.py` 已有 8 个 autouse 隔离 fixture（覆盖 runtime_flags / case_archive / users / review+audit / agent stores / consult_metrics / audit cache / pg runtime），但**`consults.CONSULTS_FILE` 与 `prescriptions.PRESCRIPTIONS_FILE` 没有对应的 autouse 兜底**——目前靠 8 个测试文件各自 monkeypatch（`test_consults.py:27`、`test_prescriptions.py:60`、`test_prompt_injection.py:89`、`test_mdt_images.py:35`、`test_row_level_review.py:165`、`test_e2e_api_smoke.py:41`、`test_pg_repo.py:614` 等）。

**影响**
与 P0-3 同源：任何**新增**测试文件若忘记 monkeypatch，就会写入真实 `data/consults.json` / `data/prescriptions.json`。这两个文件恰恰是**已被 git 跟踪**的，所以后果直接进版本库。而 conftest 里已有的先例（`_isolate_users_file` L156 的 docstring 明确写「此前 conftest 未覆盖 `auth.USERS_FILE`…单跑一次即把生产用户表清成 `{}`」）证明**同类事故已经发生过一次并复发过一次**——现在轮到 consults/prescriptions。

**建议做法**（0.5 人日）
在 `conftest.py` 补一个 autouse fixture（与 `_isolate_users_file` 同款形态）：
```python
@pytest.fixture(autouse=True)
def _isolate_consults_and_prescriptions(tmp_path, monkeypatch):
    from backend.core import consults as consults_mod
    from backend.core import prescriptions as rx_mod
    monkeypatch.setattr(consults_mod, "CONSULTS_FILE", str(tmp_path / "consults.json"))
    monkeypatch.setattr(rx_mod, "PRESCRIPTIONS_FILE", str(tmp_path / "prescriptions.json"))
    yield
```
各测试文件里已有的 monkeypatch 会覆盖它（后写生效），语义不变。

---

### P1-3｜e2e 与单测混跑，单个 e2e 用例可吃掉 63 秒；无 slow/e2e marker，无法分层并行

**问题**
- `pytest.ini` 只注册了 **1 个 marker**（`redteam`，L8）；**没有 `slow`、没有 `e2e`、没有 `integration`** marker；
- e2e 目录（`tests/e2e/`，4 个文件 + `_ratelimit.py`，49KB）**与 154 个单测文件在同一层级被 pytest 收集**（`tests/e2e/__init__.py` 存在，所以不是 rootdir 隔离）；
- 限流等待被内嵌在测试逻辑里：`tests/e2e/_ratelimit.py:20` `WINDOW_WAIT_MS = 61_000`（60 次/分钟滑动窗口 + 1s 余量）。

**实测耗时分布**（本次评测 `--durations` 实测）

| 用例 | 耗时 | 说明 |
|---|---|---|
| `tests/e2e/test_vue_parity.py::test_agent_console_cards_visible` | **63.11s** | 撞限流 → 等一个完整窗口 |
| `tests/e2e/test_vue_parity.py::test_parity_rx_reject_and_out_of_dict_flow` | 10.96s | |
| `tests/e2e/test_smoke_flow.py::test_smoke_doctor_prescribe_then_pharmacist_approve` | 5.57s | |
| `tests/e2e/test_vue_admin.py::test_vue_admin_admin_panels` | 4.26s | |
| `tests/e2e/` **整个目录** | **113.27s** | 16 个用例 |
| 单测（去 e2e，含 coverage） | **215.64s** | 1509 passed / 1 skipped |
| 单测最慢用例 | 3.67s | `test_kb_ingest.py::test_scanned_pdf_without_text_layer_gives_explicit_error` |

单例 63.11s ≈ 全量的 19%，且**发生在 CI 与本地混跑的同一个 pytest 进程里**。

**影响**
- CI 的 pytest job（`ci.yml:80`）跑的是**不区分层级的全量**，意味着 CI 里也会偶发 63s 空等；
- e2e 依赖**外部运行中的 8001 服务**（`test_smoke_flow.py:37-43` `_service_up()` 不通就 skip）。在 CI 里 pytest job **没有起服务**，所以 16 个 e2e 用例在 CI 中**全部 skip**——CI 门禁事实上**从未真正执行过浏览器链路**；
- 无法分层并行：`pytest-xdist` 未安装（`pip show pytest-xdist` 无输出），且没有 marker 可用于 `-m "not e2e"` 分片；
- 无 marker 意味着 `pytest -m "not slow"` 不可用，新人无法快速跑「核心 5 分钟套件」。

**建议做法**（预估 1.5 人日）
1. `pytest.ini` 注册 marker：`e2e`（需外部服务/浏览器）、`slow`（>5s）、`integration`（需真库）；
2. `tests/e2e/*.py` 顶部加 `pytestmark = pytest.mark.e2e`；
3. CI 拆两个 job：`pytest-unit`（`-m "not e2e"`，**必须全过**）与 `pytest-e2e`（`-m e2e`，需起服务：起 uvicorn → 等 `/healthz` → 跑 → 收服务）；这样浏览器链路首次真正进入 CI；
4. 装 `pytest-xdist`，unit job 用 `-n auto`（**注意**：需先确认 `_isolate_*` fixture 与 PG 单例 `pg_store._pool` 在多进程下的隔离——当前 conftest 只做了**进程内**复位，多进程需每进程独立库名，如 `medagent_test_w{worker}`）；
5. 限流等待从「等 61s」改为「**测试期临时抬高 `RATE_LIMIT_PER_MIN`**」（`backend/config.py:154`），用 `monkeypatch.setattr(settings, "rate_limit_per_min", 10000)`——把 63s 变成 0.1s，同时**测出真实的限流逻辑**（限流本身由 `test_rate_limit_identity.py` 7 个用例专门覆盖，不需要靠 e2e 碰撞来验证）。

---

### P1-4｜全量 pytest 5.5 分钟是「本地全跑」，CI 与本地职责未分离；CI 重复消耗在低价值环节

**问题**
项目迭代节奏是「改代码 → 定向测试 → **全量 pytest** → 写台账 → git add 具体文件提交 → 跑 backup_repo.ps1」。全量 5.5 分钟是**每次提交的前置成本**，而 CI 又独立跑一遍同等内容。

**CI 与本地的重复与浪费**

| 环节 | 本地 | CI | 重复? |
|---|---|---|---|
| 全量 pytest | ✅ 3.5min（去 e2e） | ✅ `ci.yml:80` | **完全重复** |
| 红队用例 | 含在全量内 | ✅ `ci.yml:76` 独立先跑（22 个） | **重复跑两次**（设计如此，合理——为快速失败） |
| 覆盖率采集 | 本地不采 | ✅ `--cov=backend` | 不重复 |
| e2e | ✅ 113s（需手动起服务） | ❌ 全 skip（无服务） | **CI 缺失** |
| `npm run build` | 手动 | ✅ 只对 frontend-console | **CI 目标错**（P0-1） |
| Helm lint/template | 需手动 helm | ✅ `ci.yml:157-168` | 不重复 |
| Docker build | 手动 | ❌ 无 | **CI 缺失**（P0-2） |

CI 的 job 拆分（7 个 job 并行）本身设计良好——`timeout-minutes` 分别 30/30/5/20/15/10/40，`concurrency` 取消过期运行（`ci.yml:27-29`），每周一 02:00 UTC 定时跑全量 + eval（`ci.yml:24-25`）。**问题不在 CI 结构，而在 CI 检查的对象集合不完整**（P0-1/P0-2/P0-4）。

**建议做法**（预估 1 人日）
1. 本地提交前只需跑**定向用例 + `pytest -m "not e2e"` 快速套件**（预估 <60s），全量留给 CI；但需提供一个 `scripts/verify.sh` 之类的本地入口封装「定向 → 快速 → 全量」三级；
2. CI 的 eval-baseline job（`ci.yml:182-275`，40 min 上限，含 milvus/etcd/minio 4 个 service + 种子灌库 + 起 uvicorn + `run_eval.py`）是**极高成本低频次**的门禁——它只在 `workflow_dispatch`/`schedule` 跑，定位正确，但 40 分钟的服务编排应当拆出独立 workflow 以免拖慢常规 CI 的队列。

---

### P1-5｜Python 侧无版本化迁移目录，DDL 靠「幂等 ALTER 堆叠」

**问题**
Python 侧（AI 中台）的 schema 变更全部内联在 `backend/core/pg_store.py` 里，采用 `CREATE TABLE IF NOT EXISTS` + `ALTER TABLE ... ADD COLUMN IF NOT EXISTS` 的幂等模式。实测该文件里的 DDL 语句：

```
L393-429   CREATE INDEX IF NOT EXISTS × 15（review_queue / prescriptions / consults / case_archive / audit_log）
L484       CREATE TABLE IF NOT EXISTS audit_log
L487       CREATE TABLE IF NOT EXISTS users
L491       ALTER TABLE users ADD COLUMN IF NOT EXISTS dept text NOT NULL DEFAULT ''
L492-518   CREATE TABLE IF NOT EXISTS review_queue + ALTER TABLE ADD COLUMN IF NOT EXISTS × 6
           （images / sources / … / resubmit_of / attempt / meta）
```

**正面**：这套模式**确实幂等**，且 `tests/test_pg_indexes.py`、`test_pg_repo.py` 有覆盖。

**问题**
1. **无版本表、无迁移目录、无顺序保证**。Java 侧有 Flyway（`deploy/helm/medassist-platform/values.yaml:229-239` `migration:` 段 + `templates/job-migrate-*.yaml` 的 PreSync Hook），Python 侧**完全没有对等物**——`pg_store.init()` 里那 30 条 DDL 每次启动都跑一遍，靠 `IF NOT EXISTS` 保证不重复；
2. **无法回答「生产库当前 schema 版本是多少」**——这是排障与回滚时的第一问题；
3. **无法做「迁移审计」**——`scripts/migration_safety_lint.ps1` 只扫 `platform/**/db/migration/*.sql`（**Java 侧 SQL**），Python 侧的 `ALTER TABLE` 完全在其视野之外。`scripts/migration_safety_baseline.txt` 里的基线也只针对 Java 迁移文件；
4. **`L491` 的 `ADD COLUMN IF NOT EXISTS dept text NOT NULL DEFAULT ''`** 恰恰是 `migration_safety_lint.ps1` R2 规则要拦的形态（`NOT NULL` 无默认值）——只不过因为它在 Python 侧、且带了 `DEFAULT ''`，lint 扫不到。

**建议做法**（预估 2 人日）
1. 引入轻量 schema 版本表（`CREATE TABLE IF NOT EXISTS schema_migrations(version TEXT PRIMARY KEY, applied_at TIMESTAMPTZ)`），把 `pg_store.init()` 的 DDL 拆成有序的版本化步骤（如 `001_init` / `002_review_queue_images` / `003_consult_subtables`），每步一个函数，执行后写版本行；
2. 把 `migration_safety_lint.ps1` 的扫描范围扩到 Python 侧（正则匹配 `backend/core/pg_store.py` 中的 `ALTER TABLE ... ADD COLUMN` 形态），或把 Python DDL 移到 `backend/core/migrations/*.sql` 后复用同一 lint；
3. Helm `values.yaml` 的 `migration:` 段目前只管 Java 的 Flyway——需要为 Python AI 中台补一个等价的 PreSync Job（`ai-core` 的 `strategy: Recreate` 已就位，迁移前置不会与滚动发布冲突）。

---

### P1-6｜`scripts/backup_repo.ps1` 无保留策略，本地已积压 95 个 bundle / 1.59 GB

**问题**
`scripts/backup_repo.ps1` 生成的 bundle 是**按天命名**的（`medical_agent_$stamp.bundle`，L78），脚本里**没有任何删除旧 bundle 的逻辑**（grep `Remove-Item.*bundle|keep|retain|prune` 只命中临时文件清理 L83/L87/L97）。

**实测**
```
<backup-dir>\*.bundle  →  95 个文件，合计 1588.8 MB
最旧：medical_agent_20261030.bundle (16.1MB)
最新：medical_agent_20261109.bundle (27.7MB)
```
从 2026-10-30 到 2026-11-09 仅 11 天就累积 95 个（含大量一次性 `_p0`/`_p1`/`_round3fixN`/`_r5fix` 命名的临时包——这些是每批次手工改 `-Dst` 名的产物）。

**其他备份机制对比**

| 机制 | 保留策略 | 验证 |
|---|---|---|
| `scripts/backup_repo.ps1` bundle | **无**（95 个累积） | ✅ `git bundle verify` + 克隆校验（L91-95） |
| `scripts/backup.py` 业务备份 | ✅ 保留最近 7 份（`tests/test_backup.py:139` `test_rotate_backups_keeps_latest_seven`） | ✅ 9 个用例，含 checksum |
| `scripts/backup_milvus.ps1` | ✅ 默认保留最近 3 份（`docs/DEPLOY.md:210`） | ✅ 2026-10-30 演练 |

**异地缺失**：所有备份（`<backup-dir>\`、`data/backups/`）**都在 C 盘同一台机器上**。`docs/DEPLOY.md:400` 已诚实登记这个边界：「或要求异地容灾」列为待办。

**影响**
- 磁盘无上限增长（11 天 1.59 GB，年化约 52 GB）；
- **单点失效未覆盖**：机器故障/磁盘损坏时，代码备份与业务数据备份同时丢失。

**建议做法**（预估 1 人日）
1. `backup_repo.ps1` 增加保留策略：`$KeepBundles = 14`（保留 14 天），删除超出部分（需注意 L20-21「绝不预删」纪律：应先构建新 bundle、验证通过、再删超期的旧 bundle）；
2. 补一份异地方案：至少加一条 `robocopy`/rclone 增量同步到 NAS 或另一台机器，并**写一个恢复验证脚本**（不只验证「传过去了」，要验证「能 clone + 能跑测试」——`docs/DEPLOY.md:222-228` 已有现成的验证命令模板可复用）；
3. 清理历史一次性 bundle（95 → 14），释放约 1.4 GB。

---

### P1-7｜台账 377KB 单文件不可检索；`check_docs.ps1` / `refresh_baseline.ps1` 被文档引用但不存在

**问题**
1. `docs/AGENT-RUNTIME-20261105.md` **376.7 KB**，按 `###` 标题统计仅 **29 个章节**，但「仍存边界」出现 **116 次**——125 个批次的信息压在 29 个 heading 下（说明大量批次是追加在同一 heading 下而非独立章节）。文档**没有目录（TOC）**，也没有批次索引表。检索一个批次（如「第 87 批做了什么」）只能全文 Ctrl+F；
2. `docs/OPERATIONS.md:174` 写「改环境后刷新机器基线（`refresh_baseline.ps1` → `check_docs.ps1` = `CONSISTENCY_OK`）」——但 `scripts/` 下**这两个脚本都不存在**（`Get-ChildItem scripts -Filter "*check_docs*"` / `"*refresh_baseline*"` 均无输出）。这是一条**指向不存在工具的操作手册指令**，新人照做必然卡住。

**正面**：台账的「已做 / 校验 / 仍存边界」三段式（116 处「仍存边界」）质量很高，是罕见的行为自觉。举两例说明其价值：
- L854：「Chart 版本递增只解决"变更可追溯"，未接入 CI 自动校验（改模板忘记改版本仍可能发生）」——**主动指出了自己体系的缺口**；
- L902：「`phi.py` 仍是正则实现，只覆盖结构化标识与上下文锚定的姓名/住址，不识别……」——如实标注能力边界，不夸大。

**另有一处文档与代码不同步的实证**：`docs/OPERATIONS.md:90` 与 L94 写「当前 **21 条**规则全部依赖它」、「本地 / 院区 compose（**21 条规则**生效）」，但 `deploy/prometheus/medassist-agent-ops.rules.yml` 实测有 **25 条** `- alert:`（含 4 条 PG 查询/写入指标规则：`MedAssistAgentPgQueryErrors` / `PgQuerySlow` / `PgWriteErrors` / `PgWriteSlow`）。这是「运维手册数字未随规则集更新」的实证。

**建议做法**（预估 1.5 人日）
1. 台账**保持单文件**（历史连续性有价值），但**在文件头加一张批次索引表**（批次号 → 标题 → 行号 → 涉及文件），生成脚本化（`scripts/gen_ledger_index.py`），随台账一起提交；
2. 或按 20 批次一卷拆分为 `docs/ledger/AGENT-RUNTIME-{PART}.md` + 根目录 `INDEX.md`；
3. **要么补上 `check_docs.ps1` / `refresh_baseline.ps1`，要么改 `OPERATIONS.md:174` 删掉这句**（当前状态是指向幽灵脚本，比不写更糟）；
4. 修 `OPERATIONS.md:90/L94` 的「21 条」→ 25 条；建议加一条测试（`tests/test_deploy_observability.py` 已有 `test_helm_prometheus_rule_matches_native_rules`，可扩展为「文档中声明的规则数 == yml 实际规则数」）防再次漂移。

---

### P1-8｜发布与变更管理：版本号散落四处、无 CHANGELOG、无 release 产物清单

**问题**
1. **无 CHANGELOG.md、无 VERSION 文件**（`Get-ChildItem -Filter "CHANGELOG*"` / `"VERSION*"` 均无输出）；
2. **版本号有 4 个来源且互不一致**：

| 位置 | 值 |
|---|---|
| `backend/config.py:16` `app_version` | `"1.0.0"` |
| `frontend-vue/package.json:4` | `"0.1.0"` |
| `frontend-console/package.json:4` | `"0.1.0"` |
| `deploy/helm/.../values.yaml` `gateway.image.tag` 等 8 处 | `"0.1.0-SNAPSHOT"` |
| `deploy/helm/.../values.yaml` `aiCore.image.tag` | `"0.1.0"` |

   后端 `app_version=1.0.0` **硬编码在代码里**且从不更新——`/healthz` 或 `/metrics` 若暴露版本，暴露的永远是 1.0.0；
3. **Python 侧无发布产物校验**：`scripts/release/release_gate.ps1`（3.3KB）存在且 `docs/DEPLOY.md:431` 记录了它的用途，但 **CI 里没有调用它**（grep `release_gate` 在 ci.yml 中 0 匹配）——它是纯手工脚本。

**影响**
- 无法从「版本号」推断「包含了哪些变更」；`0.1.0-SNAPSHOT` 在 `values.yaml` 里出现 8 次，改版本要改 8 处，无一致性断言；
- 部署到医院的镜像 tag 与后端自报版本对不上，排障时无法确定现场跑的是哪版。

**建议做法**（预估 1.5 人日）
1. **单一版本源**：以 git tag 为准（如 `v2026.11.09`），CI 从 tag 注入：
   - `backend/config.py` 的 `app_version` 改为从环境变量/构建期注入（`APP_VERSION`），默认 `"dev"`；
   - `values.yaml` 的 8 处 tag 改为 `{{ .Chart.AppVersion }}`（`Chart.yaml` 的 `appVersion` 单一来源）；
2. 加 `CHANGELOG.md`，用 git tag 自动生成（`git log --pretty`），或至少在每个 release tag 时人工补「用户可见变更 + 迁移步骤 + 回滚步骤」；
3. `release_gate.ps1` 接入 CI（作为 deploy 前的手动触发 workflow `workflow_dispatch`），并把「网关健康 + 登录 + 读接口 200 + 未带令牌 401」这几项改为可在 CI 复跑的脚本版本。

---

### P1-9｜settings 新增字段与 Helm values 无同步机制（已发现 1 处实际漂移）

**问题**
`backend/config.py` 有 **约 90 个 Settings 字段**（L15-233）。这些字段要生效，运维必须能通过 K8s 注入。但 `deploy/helm/medassist-platform/values.yaml` 的 `aiCore.env` 只有 **3 个**（L645-649）：

```yaml
aiCore:
  env:
    APP_ENV: production
    EMBED_DEVICE: cpu
    PUBMED_ENABLED: "true"
```

**已发现的一处实际漂移**：`backend/config.py:73` 新增了 `consult_opinions_pending: bool = False`（第 125 批的「收件箱 pending 读路径可切子表」开关，注释明确写「切换前必须确认 `scripts/backfill_consult_opinions.py --check` 无差异…因此默认关闭、由运维显式开启」）。但 **Helm values 里没有 `CONSULT_OPINIONS_PENDING` 这个环境变量**——运维在 K8s 上**无法开启该开关**，除非改 Chart 模板再发版。开关存在但不可达。

同类高价值开关还有 `pg_primary`（L68，PG 真源翻转的灰度总开关）、`consult_opinions_pending`（L73）、`qc_auto_pass`/`qc_auto_sign`/`qc_auto_sign_full`（L190-197，有 runtime_flags 运行时切换通道所以不算漂移）。

**现有校验**：`tests/` 中只有 `test_audit_archive_cronjob.py:16` 与 `test_grafana_helm_assets.py:29` 引用了 `values.yaml`，**没有**「settings 字段 ↔ Helm values 暴露面」的对照测试。

**建议做法**（预估 1 人日）
1. 加测试：对 `backend/config.py` 的 Settings 字段分类，断言「标记为『运维需可调』的字段」在 `values.yaml` 的 `aiCore.env` 或 `external.*` 中有对应项。分类清单可以先硬编码一张白名单（避免过度自动化），随新增开关一起维护；
2. 或更轻量：加一个 `docs/OPERATIONS.md` 的「运维可调开关清单」表格 + 一条测试断言清单里的每个 `settings` 字段名在文档/values 中出现；
3. 立即修复 `consult_opinions_pending` 的漂移（values.yaml 补 `CONSULT_OPINIONS_PENDING: "false"`）。

---

### P1-10｜依赖供应链：无 lock 级别锁定、无 CVE 扫描、npm 侧全为 `^` 范围

**问题**

**Python 侧（好）**：`requirements.txt` 34 个依赖几乎全部 `==` 精确锁定，且带了两处高质量注释：
- L18-21：torch 不固化，注明「GPU 服务器: pip install torch --index-url … cu121；CPU 服务器: … cpu」（按部署矩阵二选一）；
- L37-39：`nacos-sdk-python==3.2.0` 并说明「2.0.11 连接/心跳"成功"但服务端查不到实例（静默失败，2026-10-30 实测），3.2.0 正常」——**把一次真实排障结论固化成了版本约束**；
- L44-46：`--no-binary apache-skywalking` + 注释「1.3.0 的 PyPI wheel 缺失 skywalking/protocol 生成代码（实测 ModuleNotFoundError）」。

**问题**：
1. **唯一的非精确锁定**：`mcp>=1.8,<2`（L27）——范围锁在 CI 上会随时间漂移（CI 每次 `pip install -r requirements.txt` 都可能装到新的 1.x）；
2. **`torch` 完全不锁**（L18-21），CI 里 `pip install torch --index-url .../cpu`（`ci.yml:62`）装的是**当时最新**——torch 2.x 的大版本间有 ABI/行为变化，而 CI 每次重装都是新的；
3. **无 CVE 扫描**：CI 无 `pip-audit`/`safety`/`osv-scanner`；
4. **npm 侧全部 `^` 范围**：`frontend-vue/package.json` 6 个依赖 + 2 个 devDep 全是 `^`（axios `^1.8.0`、element-plus `^2.9.11`、pinia `^3.0.2`、vue `^3.5.13`、vue-router `^4.5.0`、`@vitejs/plugin-vue` `^5.2.3`、vite `^6.3.0`）。**虽有 `package-lock.json`**（lockfileVersion 3，frontend-vue 147 条 / frontend-console 106 条），但 CI 用的是 `npm ci`（`ci.yml:141`）——`npm ci` 严格按 lock 安装，**这一点是正确的**。风险在于：本地开发用 `npm install` 会静默更新 lock 并可能引入新版本，且 `^` 允许 minor 自动升级；
5. **无 SBOM**：无 `cyclonedx`/`syft`/`pip freeze` artifact，医疗项目交付通常要求 SBOM；
6. **未维护/老旧依赖**：`apache-skywalking==1.3.0`（2024 年）、`biopython==1.84`、`pillow==12.3.0`、`pymupdf==1.24.14`——PyMuPDF 与 Pillow 历史上 CVE 较多（如 Pillow 的 CVE-2023-44271/50447、PyMuPDF 的 CVE-2023-36464），无扫描即无感知。

**建议做法**（预估 1.5 人日）
1. `mcp>=1.8,<2` → 收敛为 `mcp==1.x.y`（当前实际安装版本以本机为准）；
2. CI 加 `pip-audit -r requirements.txt --strict`（先 `continue-on-error: true` 跑一个周期产出基线，再转强制）；npm 侧加 `npm audit --audit-level=high`；
3. 产出 SBOM：CI 里 `pip freeze > requirements.lock.txt` + `npx @cyclonedx/cyclonedx-npm` 导出 JSON，上传 artifact；
4. 考虑引入 `pip-tools`（`requirements.in` + 编译出的 `requirements.lock.txt`）分离「直接声明」与「传递锁定」。

---

### P1-11｜前端无组件级单测，全部靠「静态标记锁 + 人工浏览器」

**问题**
`tests/test_frontend_syntax.py`（35 用例）+ `tests/test_frontend_agent_markers.py`（20 用例）+ `tests/test_vue_shell.py`（9 用例）合计 64 个前端相关用例，但**没有一个是「跑 Vue 组件」的测试**——全部是**对源码文本做字符串包含断言**。

台账已如实登记这一点：
- L981：「`AskView` 仍是"标记测试 + 人工浏览器"验证，没有组件级单测」；
- L1009：「前端仍无组件级单测，以上为静态标记 + `npm run build` + 人工浏览器验证」。

**影响**
静态标记锁有一个结构性弱点：**它锁的是「存在性」而非「行为」**。例如 `test_frontend_syntax.py:413-415`：
```python
shell = (VUE_SRC / "layouts" / "AppShell.vue").read_text(encoding="utf-8")
assert shell.count("badges.refreshRxBadge()") >= 2, "登录首刷与离开 rx 恢复均须刷新角标"
assert "badges.poll()" in shell, "60s 定时轮询应挂载于布局壳"
```
这只能证明「字符串出现了 ≥2 次」，**不能证明**「登录后确实刷新了一次、离开 rx 后确实恢复、60s 轮询确实挂载且没内存泄漏」。函数被改名/挪进别处/被注释掉，标记可能仍在（甚至更糟：注释里也能通过断言）。

同理，L344-382 的 `test_phase3_rx_frontend_markers_present` 锁了 30+ 个标记字符串，但「高危联用阻断条」的**实际阻断行为**（`submitDisabled` 是否真的在有高危时禁用提交）没有任何自动化验证。

**建议做法**（预估 3 人日）
1. 引入 `vitest` + `@vue/test-utils`（Vue 3 官方方案），优先覆盖**纯逻辑单元**（不需 DOM）：
   - `utils/audit.js` 的 `humanizeAudit`——目前是「抽源码 → node 跑」（`test_frontend_syntax.py:161-212`），迁到 vitest 后可获得**逐 case 断言 + 覆盖率 + IDE 补全 + 失败定位**，是一次纯收益的迁移；
   - `utils/assist.js` 的 `esc()`/`fmtTs()`/`md()`；
   - `stores/badges.js` 的 `refreshRxBadge`/`refreshReviewBadge`/`poll`（用 mock http 断言请求参数与状态更新，这是当前 L408-411 用「抽函数体 → grep 字符串」代替的真实行为断言）；
2. 再上 `@vue/test-utils` 组件测试（`RxView` 的高危阻断、`KbView` 的 esc 包裹）；
3. e2e（Playwright）继续承担跨页链路，组件测试承担分支逻辑——**两者职责分离**。

---

### P1-12｜`.gitignore` 的 PHI 排除规则不完整且不对称

**问题**
`.gitignore` 已有明确的 PHI 红线段落（`# ===== 医疗项目红线：密钥 / 隐私 / 大文件，严禁入库 =====`），覆盖 `data/auth/`、`data/audit/`、`data/review/`、`data/backups/`、`data/llm_providers.json`。但**未覆盖业务实例数据文件**。

**实测 `git check-ignore -v` 结果**

| 文件 | 是否被忽略 | 是否被 git 跟踪 | 内容性质 | 判定 |
|---|---|---|---|---|
| `data/auth/users.json` | ✅ 已忽略 | 否 | 密码哈希 + 账户 | 正确 |
| `data/audit/` | ✅ 已忽略 | 否 | 审计含 PHI | 正确 |
| `data/review/queue.json` | ✅ 已忽略 | 否 | 审核队列含病历 | 正确 |
| `data/consults.json` | ❌ **未忽略** | ✅ **已跟踪**（12 commits） | 会诊记录含完整病史 | **应忽略** |
| `data/prescriptions.json` | ❌ **未忽略** | ✅ **已跟踪**（16 commits） | 处方含病例文本 | **应忽略** |
| `data/case_archive.json` | ❌ **未忽略** | ❌ 未跟踪（`??`） | 病例归档 | **应忽略（最易误提交）** |
| `data/drug_dict.json` | ❌ 未忽略 | ✅ 已跟踪 | 药品字典（种子产物） | 正确（保持跟踪） |
| `data/drug_rules.json` | ❌ 未忽略 | ✅ 已跟踪 | 相互作用规则（种子产物） | 正确（保持跟踪） |
| `data/departments.json` | ❌ 未忽略 | ✅ 已跟踪 | 科室字典 | 正确（保持跟踪） |

**影响**
与 P0-3 同源。`.gitignore` 的规则**不完整**（漏 3 个），且**不完整的方式是危险的方向**——漏掉的恰好是带患者数据的文件。

**建议做法**（0.5 人日，与 P0-3 第 1 条合并执行）
```gitignore
# 会诊/处方/病例归档：业务实例数据，含真实临床文本，严禁入库
data/consults.json
data/prescriptions.json
data/case_archive.json
```
并加注释说明与 `data/drug_dict.json` 等「种子产物」的区分理由，避免后人误删这两类。

---

## 建议优化（P2 / P3）

### P2-1｜`pytest.ini` 全局 `--timeout=300` 与「快失败」目标冲突

`pytest.ini:4` `addopts = --timeout=300 --timeout_method=thread`。300s（5 分钟）的单用例超时对一个测试套件来说过于宽松——**任何单用例挂死都会占满 5 分钟**，而当前 e2e 单例最慢 63.11s。建议分档：单元 60s、e2e 180s，用 `@pytest.mark.timeout(...)` 就近声明（pytest-timeout 支持 marker 级覆盖）。

### P2-2｜`tests/e2e/_ratelimit.py` 的 61 秒等待逻辑本身值得单测

`_ratelimit.py` 有 82 行、包含「登录阶段 429 → 等窗口重放」「首刷观察窗 → reload 最多 2 轮」「只对确认限流的情况重试，其他失败原样抛出」三段逻辑，注释写得很清楚（**不吞真实回归**是正确设计）。但它**自己没有任何测试**——`tests/e2e/` 的 16 个用例里没有一个针对 `_ratelimit.py`。这段「防假红」的代码本身若失效（误判 429 / 吞掉真错误），会导致**所有 e2e 假绿**。建议用 `unittest.mock` 造一个假 `page` 对象，测 `_watch_window_has_429` 的返回逻辑与 `_banner_says_rate_limited` 的异常兜底（L33-34 的 `except Exception: return False`）。

### P2-3｜`test_router_split_guard.py` 的 600 行护栏可考虑收紧或分档

当前 `LINE_CEILING = 600`（L22）。实测 `backend/api/v1/medical/` 下各模块行数分布可核，但 600 是经验值——建议用测试反推一个「基于历史 P95 × 1.2」的动态阈值，或至少在断言失败信息里给出该文件的行数与「建议按哪几个域拆分」的提示（L107-109 目前只报错 offenders 字典）。

### P2-4｜`scripts/` 30 个脚本中仅 7 个被测试引用，运维脚本缺测试

实测被测试引用的脚本：`backup.py`、`restore.py`、`eval_metrics.py`、`loadtest.py`、`mcp_server.py`、`purge_drug_review_items.py`、`sync_grafana_dashboards.py`（7 个）。**未被任何测试覆盖的 23 个**包括：`archive_audit_log.py`（有独立测试 `test_audit_archive.py` 但脚本路径未直接引用）、`backfill_audit_action.py`、`backfill_consult_opinions.py`（**有生产开关依赖它**——`backend/config.py:71` 明确说「切换前必须确认 `scripts/backfill_consult_opinions.py --check` 无差异」）、`migrate_json_to_pg.py`、`migrate_kb_hybrid.py`、`seed_kb*.py`（4 个）、`fault_drill.py`、`drill_key_rotation.py`、`agent_go_live_check.py`、`smoke_all.py`、`verify_*.py` 等。

**特别值得补测的 3 个**（都直接连生产开关）：
1. `scripts/backfill_consult_opinions.py --check`（`backend/config.py:71` 明确引用为切换前置条件，但脚本无测试 → **开关的前置校验本身不可信**）；
2. `scripts/archive_audit_log.py`（`values.yaml:724-745` 的 `auditArchive` CronJob 依赖它，默认 dry-run + 事务回滚，语义关键）；
3. `scripts/fault_drill.py` / `drill_key_rotation.py`（`docs/KEY_ROTATION_DRILL.md` 有手册，但脚本无自动化验证）。

### P2-5｜`scripts/migration_safety_lint.ps1` 的规则覆盖可扩展

当前只覆盖 R1（`SET NOT NULL`）与 R2（`ADD COLUMN NOT NULL` 无默认值）。常见的另外三类 PG 迁移风险未覆盖：**① 阻塞式 `CREATE INDEX`（应用 `CONCURRENTLY`）**；**② `ALTER TABLE ... SET DEFAULT` 在大表上的回写**；**③ `DROP COLUMN` / `DROP TABLE`（不可逆）**。建议补这三条规则。

### P2-6｜`backend/core/main.py` 与 `agent_router.py` 覆盖率偏低

实测 `backend/main.py` 58.9%（166/282 statements）、`backend/api/v1/agent/agent_router.py` 81.4%（613/753）。`main.py` 未覆盖部分主要是 lifespan（模型预加载、调度器启动）——**这正是 conftest 刻意规避的**（`tests/conftest.py:15` 关闭快照调度、各测试不进 lifespan），属合理取舍，但意味着「启动路径」只有 e2e 覆盖。`agent_router.py` 的 140 行未覆盖含 A2A/流式等端点，建议按路由补齐。

### P2-7｜`nacos_registry.py` 与 `offline_guard.py` 零覆盖

实测 `backend/core/nacos_registry.py` **0%**（86 statements）、`backend/core/offline_guard.py` **0%**（23 statements）。两者都是「部署形态相关」的模块（Nacos 注册 / 离网守卫），当前默认关闭所以测试可跳过——但这意味着**一旦在生产开启 `NACOS_ENABLED=true` 或部署到离网环境，这两段代码从未被执行过**。建议补「配置开启/关闭」两条路径的单元测试（用 mock SDK），成本低（109 行）。

### P2-8｜`reranker.py` 覆盖率 26.2%，可考虑把模型加载部分排除出覆盖率统计

`backend/core/reranker.py` 11/42（26.2%）——未覆盖的是 BGE-reranker 权重加载与 `FlagEmbedding` 调用（需要真实模型文件，测试环境不可得）。同理 `embedder.py` 61.1%。建议在 `requirements.txt:33-34` 旁增加 `.coveragerc`（或 `pyproject.toml` 的 `[tool.coverage]`），对 `backend/core/{embedder,reranker}.py` 与 `backend/main.py` 的 lifespan 设 `omit`/`exclude_lines`，让 85% 阈值反映**可测逻辑**的真实水位，而不是被模型加载稀释。当前 88.2% 的实测值已高于阈值，所以这不会造成门禁失败，但**阈值的信息含量会因此提高**。

### P2-9｜`docs/HANDOFF.md`（167KB）应纳入与台账同等的检索治理

`docs/` 下文档规模：台账 376.7KB、`HANDOFF.md` 167.5KB、`REVIEW-20261103-MULTIAGENT.md` 135.2KB、`CLINICAL-CORE-ROADMAP.md` 132KB、`DEPLOY.md` 62.6KB。**5 份文档超过 100KB**，全部无目录。新人上手成本主要来自这里。建议至少为 `HANDOFF.md` 与 `DEPLOY.md` 加目录（DEPLOY.md 62.6KB 已有章节结构，加 TOC 成本很低）。

### P2-10｜补一份「本地开发环境一致性」文档与自检脚本

本机实际可用环境是 `python`（pytest 8.3.4），而 `python` 指向 `python` **无 pytest**（`No module named pytest`）。`requirements.txt` 也未指定 Python 版本（只有 `Dockerfile:3` `FROM python:3.11-slim` 与 CI 的 `python-version: "3.11"`）。建议：
1. `requirements.txt` 顶部加注释明确 `Python 3.11`；
2. 新增 `python_requires` 或 `.python-version`；
3. 加一个 `scripts/check_env.py`，检查关键包版本与 Python 版本，不符即退出——避免新人花半小时排查「为什么 pytest 装不上」。

### P3-1｜`platform/`（Java，645 文件 / 118286 行）的 CI 覆盖可核实

实测 `platform/` 有 **189 个 `*Test*.java` 文件**。`docs/DEPLOY.md:439` 称「后端 `mvn package`（170 个测试类 / 1606 用例）」——**189 vs 170** 有差异（可能是「测试类」与「含测试的类」口径不同，也可能是文档过期）。CI 有两个 Java job：`postgres-it`（`ci.yml:98`，`mvn -Ppostgres-it -pl clinical,auth,insurance -am test`，真库 Testcontainers）与 `platform`（`ci.yml:124`，`mvn package`）。`ci.yml:83-85` 的注释记录了一个真实教训：「此前 `*IT` 类因 surefire 默认只收 `*Test` 而从未执行（Tests run: 0 同样 BUILD SUCCESS）」——**这个坑已被识别并修复，值得肯定**。建议核实 189/170 差异并把准确数字写回文档。

### P3-2｜`tests/test_go_live_check.py` 的 fake 覆盖率可作为「外部依赖桩」参考范式

`test_go_live_check.py` 用 `fake(base, path, method=..., token=..., body=..., timeout=..., cookie=...)` 形式的桩（L155/L175/L192/L223/L247），签名与真实 HTTP 客户端一致——这是**好桩的范式**：桩签名跟着真实接口走，接口变了桩就编译报错。当前项目里 1141 处 monkeypatch 大多是「替换模块属性」，与真实签名无关联，接口变更时不会失败。

### P3-3｜CI 定时任务可加「依赖/基础镜像更新」巡检

`ci.yml:24-25` 每周一 02:00 UTC 跑 pytest + eval。可在同一 cron 增加 `pip list --outdated` 与 `npm outdated` 的 artifact 产出（不自动升级，只报告），把「依赖该升了」从「没人知道」变成「有据可查」。

### P3-4｜`tests/test_architecture.py` 可补一条「测试代码不得 import 生产内部实现细节」的规则

当前该文件只管生产代码的依赖方向（L54-79）。测试侧大量直接 import 内部模块（`from backend.core import pg_store as _pg_store`），若生产内部结构大改，测试会大面积破裂。加一条「测试只应 import 公开 API 层（backend.main.app / backend.api.*）与明确标注的 core 单测白名单」的规则，可在重构前给出预警。

---

## 测试覆盖矩阵

> 用例数来自 `pytest --collect-only -q` 实测（1526 collected，2.83s）。
> 「真实逻辑覆盖」= 该域用例中**不依赖 monkeypatch 替换核心决策点**的比例（AST 扫描 `monkeypatch.setattr` / `unittest.mock` 后人工判定）。
> 覆盖率 = 本次 `--cov=backend` 实测（88.2% / 11313 stmts / 1335 missing，去 e2e）。

| 业务域 | 测试文件数 | 用例数 | 真实逻辑覆盖 | 风险等级 | 关键依据 / 缺口 |
|---|---|---|---|---|---|
| **agent（智能体域）** | 24 | 352 | 中（约 60%） | **中** | 最大域。`test_agent_control_plane.py`(47) + `test_agent_bots.py`(33) + `test_agent_skills.py`(24) 为主；但 LLM 决策点普遍被 mock（`test_consult.py:35` `_provider` 替换 `consult.resolve_provider`）。`bot_runtime.py` 82.1%、`bot_teams.py` 82.2%。**A2A / 流式端点未充分覆盖** |
| **consults（会诊/MDT）** | 10 | 167 | 中（约 55%） | **中** | `test_consult.py`(46) + `test_consult_search.py`(27) + `test_consults.py`(25) + `test_consult_metrics.py`(23)。**核心 Agent 决策点被整段 mock**：`test_consults.py:50-61` `_mock_team` 替换 `mdt.pick_departments` / `mdt.dept_briefs` ——「组队+定向意见」的真实逻辑（prompt 构造、护栏、科室同步锁）从未被端到端验证 |
| **prescriptions（开药/药物）** | 12 | 118 | **高**（约 80%） | 低 | `test_prescriptions.py`(35) + `test_drug_pharm_review.py`(12) + `test_drug_dict.py`(8) + `test_drug_rules_review.py`(9)。走 TestClient + 真实路由 + 真实 JSON 存储，仅 mock LLM。`prescriptions.py` 覆盖率 90%+ |
| **review（审核）** | 11 | 110 | **高**（约 80%） | 低 | `test_review_flow.py` + `test_review_permission.py`(12) + `test_review_images.py`(15) + `test_review_single_row_write.py`(14)。双控矩阵（审核人≠提交人）、行级隔离、权限矩阵都有真实断言 |
| **pg_store（数据层）** | 9 | 113 | **高**（约 85%） | 低 | `test_pg_repo.py`(64) 是最大单文件。覆盖 CAS、部分唯一索引、分页、SQL 拼接。**但 `pg_store.py` 自身 89.1%，未覆盖的 175 行含降级分支**。缺真库并发验证（依赖 Java 侧 Testcontainers `postgres-it` job，Python 侧无对应） |
| **kb（知识库）** | 11 | 84 | 中（约 65%） | 中 | `test_kb_ingest.py`(26) + `test_kb_structured_chunk.py`(10) + `test_kb_ocr.py`(9)。`medical_kb.py` 77.2%、`kb_ingest.py` 89.3%。**OCR/嵌入模型路径依赖真实模型文件，测试用假文件** → 真实 OCR 准确率无自动化保障 |
| **audit（审计）** | 10 | 76 | **高**（约 85%） | 低 | `test_audit_filters.py`(20) + `test_medical_audit.py`(13) + `test_audit_payload.py`(9) + `test_audit_archive.py`(8)。`medical_audit.py` 87.4%。**归档 CronJob 的 25 条 Prometheus 规则与文档数字已漂移（21 vs 25）** |
| **auth（鉴权/安全）** | 12 | 69 | **高**（约 80%） | 低 | `test_security.py`(16, redteam) + `test_auth_cookie.py`(7) + `test_jwt_rotation.py`(5) + `test_prompt_injection.py`(6, redteam) + `test_password.py`(3)。红队 22 用例 CI 独立门禁。**唯一断言是 `status_code==200` 的 2 个用例之一在此域**（`test_auth_cookie.py:62`） |
| **qc（质控）** | 7 | 49 | 中（约 70%） | **中** | `test_qc_automation.py`(10) + `test_qc_category.py`(8) + `test_qc_role.py`(8) + `test_qc_resubmit.py`(5)。**三档分流的核心判定（确定性硬伤识别 / ≥0.85 自动归档）依赖 LLM 判定，测试用假 provider** → 「自动驳回」是否误伤真实病历无自动化保障 |
| **case_archive（病例归档）** | 2 | 25 | 中（约 60%） | **中** | `test_case_archive.py`(12) + `test_pg_case_archive.py`(13)。**归档字段的 PHI 脱敏（`patient_ref`）验证依赖具体实现**；`data/case_archive.json` 是未忽略且未跟踪的高危文件（P0-3） |
| **rate_limit（限流）** | 1 | 7 | **高**（100%） | 中 | `test_rate_limit_identity.py` 7 用例覆盖可信代理 IP 提取、429 + Retry-After。**但域名只有 1 个文件 7 用例，且真实 60/min 滑动窗口的并发语义未被压测**——e2e 里靠「撞限流 → 等 61s」被动暴露（`test_vue_parity.py::test_agent_console_cards_visible` 63.11s） |
| **前端（Vue3）** | 4 | 64 | **低**（标记锁，非行为验证） | **高** | `test_frontend_syntax.py`(35) + `test_frontend_agent_markers.py`(20) + `test_vue_shell.py`(9)。**全部是对源码文本的字符串包含断言**，无组件级测试（台账 L981/L1009 已自认）。唯一真执行的是 `humanizeAudit` 的 node 子进程测试（L161-212） |
| **e2e（Playwright）** | 4 | 16 | **高**（真浏览器真服务） | **高** | `test_vue_parity.py`(10) + `test_vue_admin.py`(4) + `test_smoke_flow.py`(1) + `test_vue_assist.py`(1)。**真链路、可信度高；但 ① CI 中全 skip（pytest job 不起服务）② 污染生产数据（P0-3）③ 单例 63s 限流空等 ④ 无 marker，无法分层** |
| **架构/运维/eval（其他）** | 45 | 347 | 高 | 低 | `test_architecture.py`(5) + `test_router_split_guard.py`(3) + `test_ci_gates.py`(5) + `test_deploy_observability.py`(6) + `test_grafana_*.py`(12) + `test_backup.py`(9) + `test_restore.py`(5)。**静态锁质量最高的一组**，但也最易被「改快照」绕过 |

### 覆盖率实测明细（`--cov=backend`，1509 passed / 1 skipped / 215.64s）

| 项 | 实测值 |
|---|---|
| 覆盖文件数 | 95（= backend 全部 .py） |
| statements | 11313 |
| missing | 1335 |
| **总覆盖率** | **88.2%**（CI 门禁阈值 85%，余量 3.2pt） |
| **零覆盖文件** | **2 个**：`backend/core/nacos_registry.py`（0/86）、`backend/core/offline_guard.py`（0/23） |
| 覆盖 <40% 且 ≥20 stmts | 3 个：nacos_registry 0%、offline_guard 0%、reranker 26.2% |
| 覆盖 <70% 且 ≥40 stmts | `backend/main.py` 58.9%（166/282）、`backend/core/embedder.py` 61.1% |
| 桩/mock 密度 | 154 个顶层测试文件中 **123 个含 mocking**，共 **1141 处** `monkeypatch.setattr`/`unittest.mock`/`@patch`（约 7.4 处/文件） |
| mock 密度 Top-5 | `test_pg_repo.py` 93、`test_consults.py` 29、`test_milvus_timeout_guard.py` 29、`test_kb_ingest.py` 28、`test_review_images.py` 28 |
| 唯一断言为 `status_code == 200` 的用例 | **2 个**（`test_auth_cookie.py:62`、`test_qc_route.py:55`）——占 1526 的 0.13%，断言质量总体良好 |

---

## CI 门禁现状表

> 逐行核查 `.github/workflows/ci.yml`（275 行，7 个 job）+ 仓库配置实测。

| 检查项 | 是否存在 | 覆盖范围 | 缺口 / 证据 |
|---|---|---|---|
| **pytest 全量** | ✅ | `ci.yml:80`，`--cov=backend --cov-fail-under=85` | — |
| **覆盖率阈值门禁** | ✅ | 85%，`tests/test_ci_gates.py:31-37` 反向锁「不得低于 85」 | 实测 88.2%，余量 3.2pt；无 XML/JSON artifact、无 PR diff 覆盖 |
| **红队独立门禁** | ✅ | `ci.yml:76` `-m redteam`，**排在覆盖率全量之前**（先快后慢） | 实测 **22 个 redteam 用例**（`pytest -m redteam --collect-only`）；`test_ci_gates.py:56` 锁「≥20 条」防删空 |
| **marker 注册** | ✅ | `pytest.ini:7-8` 仅注册 `redteam` | **无 `slow`/`e2e`/`integration` marker** → 无法分层并行 |
| **隔离测试库** | ✅ | `ci.yml:65-72` 建 `medagent_test`；`conftest.py:20-21` 强制指向 | — |
| **PG service 容器** | ✅ | `ci.yml:37-48` postgres:15-alpine，端口 5433 | — |
| **真库集成测试（Java）** | ✅ | `ci.yml:98` `mvn -Ppostgres-it -pl clinical,auth,insurance -am test`（Testcontainers） | **Python 侧无对应真库 job** |
| **真库集成测试（Python）** | ❌ | — | `pg_store.py` 175 行未覆盖，含 CAS/唯一索引/行锁并发分支 |
| **迁移安全 lint** | ✅ | `ci.yml:103-110` `migration_safety_lint.ps1` | **只扫 `platform/**/db/migration/*.sql`（Java 侧）**；Python 侧 `pg_store.py` 的 `ALTER TABLE` 完全不在视野 |
| **Java 编译 + 单测** | ✅ | `ci.yml:112-124` `mvn -f platform/pom.xml package`（20 min 上限） | — |
| **前端 typecheck** | ⚠️ **部分** | `ci.yml:142-143` `npm run typecheck`，**仅 frontend-console** | **`frontend-vue`（= 唯一生产前端）无 typecheck script**，`frontend-vue/package.json:7-11` 只有 dev/build/preview |
| **前端 build** | ⚠️ **部分** | `ci.yml:144-145` `npm run build`，**仅 frontend-console** | **`frontend-vue/dist`（= 镜像实际内容）从不在 CI 构建** |
| **前端 lint（eslint）** | ❌ | — | 仓库无 eslint 配置（`Get-ChildItem -Recurse -Filter ".eslintrc*"` 只命中 node_modules 内部） |
| **Python lint（ruff/flake8）** | ❌ | — | 无 `ruff.toml`/`.flake8`/`setup.cfg` |
| **Python type check（mypy）** | ❌ | — | 无 `mypy.ini`/`.mypy.ini`/`pyproject.toml` |
| **pre-commit 钩子** | ❌ | — | 无 `.pre-commit-config.yaml` |
| **依赖漏洞扫描** | ❌ | — | 无 `pip-audit`/`safety`/`osv-scanner`/`npm audit` |
| **SBOM 产出** | ❌ | — | 无 `cyclonedx`/`syft`/`pip freeze` artifact |
| **依赖一致性校验** | ❌ | — | **`backend/core/auth.py:21` `import jwt` 但 requirements.txt 无 PyJWT**（P1-1） |
| **Docker 镜像构建** | ❌ | — | `ci.yml` 全文 grep `docker build` / `Dockerfile` → **0 匹配**（P0-2） |
| **镜像内断言（healthz/产物存在）** | ❌ | — | `Dockerfile:41` HEALTHCHECK 路径无测试 |
| **Helm lint** | ✅ | `ci.yml:156-157` `helm lint deploy/helm/medassist-platform` | — |
| **Helm 三环境渲染** | ✅ | `ci.yml:158-168` dev/staging/prod 各 `helm template` + `test -s` | **只断言「文件非空」，不校验资源数量/字段值**；无 `kubeconform`/`kubeval` schema 校验 |
| **Helm 渲染断言（测试侧）** | ⚠️ **部分** | `test_grafana_helm_assets.py:133` `helm template` + ConfigMap 断言 | **本机无 helm → 该用例 skip**（`@pytest.mark.skipif(shutil.which("helm") is None)`）；CI 有 helm 但 pytest job 未装 helm → 本地与 CI 覆盖互补（CI 的 helm job 只 `test -s`，不做内容断言） |
| **Compose 配置校验** | ✅ | `ci.yml:169-180` 三套 compose `config -q` | — |
| **评测基线门禁（eval）** | ✅ | `ci.yml:182-275` `run_eval.py` 门槛 9/9，仅 `workflow_dispatch`/`schedule` | 40 min 上限 + 4 个 service 容器，成本高但定位正确（低频） |
| **发布后冒烟门禁** | ❌ | `scripts/release/release_gate.ps1` 存在（3.3KB） | **CI 中 0 匹配** → 纯手工脚本 |
| **分支保护 / CODEOWNERS** | ❌ | `.github/` 下**仅 `workflows/ci.yml` 一个文件** | **无 CODEOWNERS、无 PR 模板**；技术上所有门禁均可绕过（P0-4） |
| **并发取消** | ✅ | `ci.yml:27-29` `concurrency` + `cancel-in-progress: true` | — |
| **定时全量** | ✅ | `ci.yml:24-25` 每周一 02:00 UTC pytest + eval | — |
| **job 超时上限** | ✅ | 30/30/5/20/15/10/40 min，均已设置 | — |

---

## 效能指标表

> 实测值均为本次评测亲自运行所得；「商业级基线」取自主流工程实践的通行目标。

| 指标 | 实测值 | 商业级基线 | 差距 |
|---|---|---|---|
| **后端单元测试覆盖率** | **88.2%**（11313 stmts / 1335 missing） | 85–90% | ✅ **达标**（CI 门禁 85%，余量 3.2pt） |
| **零覆盖的生产模块** | **2 个**（nacos_registry 86 stmts、offline_guard 23 stmts） | 0 | ⚠️ 差 2 个（109 行生产代码从未执行） |
| **测试用例总数** | 1526（collect-only 2.83s） | — | ✅ 量级充足 |
| **单文件最大用例数** | 64（`test_pg_repo.py`） | ≤50 | ⚠️ 略超（单文件 93 处 mock，宜拆分） |
| **全量 pytest 耗时（去 e2e）** | **215.64s**（3.5 min，1509 passed，含 coverage 采集） | ≤2 min | ⚠️ 差 1.75x |
| **e2e 耗时** | **113.27s**（1.9 min，16 用例） | — | ⚠️ 单例最慢 **63.11s**（撞 60/min 限流空等 61s） |
| **全量 pytest 耗时（含 e2e）** | ≈ 5.5 min（与项目记录一致） | ≤3 min | ⚠️ 差 1.8x；且 CI pytest job 不起服务 → e2e 全 skip |
| **测试并行度** | **1（无 xdist）**，`pip show pytest-xdist` 无输出 | 4–8 worker | ❌ **完全缺失**，且无 marker 可分片 |
| **可分层的 marker 数** | **1**（仅 `redteam`） | ≥3（unit/e2e/slow/integration） | ❌ 差 2+ |
| **CI pytest job 超时上限** | 30 min | — | ✅ 充裕 |
| **CI job 数 / 并行度** | 7 job 全并行 | — | ✅ 结构良好 |
| **CI e2e 实际执行率** | **0%（16 用例全 skip，pytest job 不起 8001 服务）** | 100% | ❌ **浏览器链路从未在 CI 跑过** |
| **CI 镜像构建** | **无** | 每次 PR 构建 + 冒烟 | ❌ **完全缺失** |
| **CI 交付前端构建覆盖** | frontend-console ✅ / **frontend-vue ❌**（= 生产产物） | 100% | ❌ **生产前端零 CI 校验** |
| **Python lint / type check** | 无 | ruff + mypy 强制 | ❌ 缺失 |
| **前端 lint** | 无 | eslint 强制 | ❌ 缺失 |
| **依赖漏洞扫描** | 无 | pip-audit + npm audit | ❌ 缺失 |
| **SBOM 产出** | 无 | 每次发布附 SBOM | ❌ 缺失 |
| **requirements 精确锁定率** | 33/34 精确（`mcp>=1.8,<2` + `torch` 不锁） | 100%（含间接依赖 lock） | ⚠️ 差 2 处；npm 侧靠 `npm ci` + lockfile 规避 ✅ |
| **import 与声明一致性** | **不一致**（`PyJWT` 缺失） | 100% | ❌ 差 1 个关键依赖（会导致镜像启动崩溃） |
| **lock 文件** | Python ❌ / npm ✅ ×2（lockfileVersion 3） | 两侧都有 | ⚠️ Python 侧缺 |
| **Docker 镜像健康检查** | ✅ `Dockerfile:41` HEALTHCHECK `/healthz` | 有 | ✅ 但**无测试验证**（无镜像构建 job） |
| **Helm 渲染断言强度** | `test -s`（非空）+ 1 个测试侧 ConfigMap 断言（本机 skip） | schema 校验 + 关键字段值断言 | ⚠️ 弱 |
| **迁移版本化** | Java ✅（Flyway + PreSync Hook + 安全 lint）；**Python ❌**（幂等 DDL 堆叠，无版本表） | 两侧均有 | ❌ Python 侧缺失 |
| **数据库回滚方案** | ✅ 明确（`docs/DEPLOY.md:444`：helm rollback → 等 readiness → 重跑 release_gate；「迁移只前进不回滚」） | 有 | ✅ 有文档有顺序；Python 侧迁移回滚仍无脚本 |
| **配置变更同步机制** | **无自动机制**（settings ~90 字段 vs `aiCore.env` 3 项；已发现 `consult_opinions_pending` 不可达） | 自动校验 | ❌ 缺 1 处实际漂移 |
| **版本号一致性** | 4 个来源不一致（config 1.0.0 / 两个 package.json 0.1.0 / values.yaml 8 处 SNAPSHOT） | 单一版本源 | ❌ 缺 1 项 |
| **CHANGELOG** | ❌ 无 | 有（自动生成） | ❌ 缺失 |
| **测试隔离 autouse fixture 数** | **8 个**（runtime_flags / case_archive / users / review+audit / agent stores / consult_metrics / audit cache / pg runtime） | 全域覆盖 | ⚠️ **漏 consults / prescriptions**（实证污染，P0-3） |
| **测试污染生产数据** | **实证发生**（e2e 跑完 `M data/prescriptions.json`，+69 行真实临床文本） | 0 | ❌ **最高优先级差距** |
| **被 git 跟踪的 PHI 文件** | **2 个**（`data/consults.json` 12 commits、`data/prescriptions.json` 16 commits） | 0 | ❌ 需历史清理 |
| **备份保留策略** | bundle **无**（95 个 / 1.59 GB）；业务备份 ✅ 保留 7；Milvus ✅ 保留 3 | 全链路有 | ❌ 代码备份缺 |
| **备份异地** | ❌ 全在 `<backup-dir>`（同机同盘） | ≥1 份异地 | ❌ 缺（`docs/DEPLOY.md:400` 已登记为待办） |
| **恢复演练记录** | ✅ **3 次有日期有数据**（2026-09-12 数据 / 2026-10-30 Milvus / 2026-11-08 bundle） | 定期演练 | ✅ **优秀** |
| **备份完整性校验** | ✅ `git bundle verify` + 克隆后跑测试（`docs/DEPLOY.md:222-228`） | 有 | ✅ 优秀 |
| **台账可检索性** | 376.7 KB / 29 个 `###` 章节 / **无 TOC** / 116 处「仍存边界」 | 有索引 | ⚠️ 单文件不可检索 |
| **文档规模** | 5 份 >100KB（台账 376.7 / HANDOFF 167.5 / REVIEW 135.2 / ROADMAP 132 / DEPLOY 62.6） | 结构化 | ⚠️ 新人上手成本高 |
| **文档与代码同步度** | 发现 3 处实证漂移：`OPERATIONS.md` 规则数 21 vs 实际 25；`check_docs.ps1`/`refresh_baseline.ps1` 被引用但不存在；`DEPLOY.md:439` Java 测试类 170 vs 实测 189 文件 | 零漂移 | ❌ 差 3 处 |
| **CI 分支保护** | ❌ 无 CODEOWNERS、无 PR 模板 | 强制 PR + 必须过 CI | ❌ **所有门禁技术上可绕过** |
| **返工成本（全量 pytest）** | 3.5 min/次（本地全跑）+ 1.9 min e2e | 分层后 <1 min | ⚠️ 差 3x |

---

## 附：评测过程中的关键实测命令与输出

```powershell
# 用例分布（1526 collected in 2.83s）
python -m pytest --collect-only -q -p no:cacheprovider

# 覆盖率实测（1509 passed, 1 skipped in 215.64s；88.2%）
python -m pytest -q --cov=backend --cov-report=json:.coverage_eval.json --ignore=tests/e2e

# e2e 实测（16 passed in 113.27s；单例最慢 63.11s = 撞 60/min 限流）
python -m pytest -q --durations=15 tests/e2e

# 数据污染实证
python -m pytest -q tests/e2e
git status --short
#  →  M data/prescriptions.json     ← +69 行，含真实临床文本
#  ?? data/case_archive.json        ← 未跟踪且未被 .gitignore 覆盖

# gitignore 缺口
git check-ignore -v data/consults.json data/prescriptions.json data/case_archive.json
#  → 三者均返回「未忽略」；前两者已被 git 跟踪

# 依赖缺口
grep -n "jwt\|PyJWT" requirements.txt        #  → 无匹配
grep -n "import jwt" backend/core/auth.py    #  → L21 有

# CI 缺口
grep -n "docker build\|Dockerfile" .github/workflows/ci.yml     #  → 0 匹配
grep -n "frontend-vue\|frontend-console" .github/workflows/ci.yml #  → 仅 frontend-console
```

> **注**：评测过程中 `data/prescriptions.json` 被 e2e 运行修改，评测结束后已用 `git checkout -- data/prescriptions.json` 还原，未留任何改动。覆盖率临时文件 `.coverage_eval.json` / `.coverage` 已清理。本次评测**未修改任何生产代码、测试代码、CI 配置或脚本**。


