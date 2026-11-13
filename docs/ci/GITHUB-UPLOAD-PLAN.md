# 上传 GitHub 计划书（含 LLM Key 与个人隐私专项排查）

| 项 | 内容 |
|---|---|
| 版本 | v2.0（2026-11-14） |
| 适用仓库 | `medical_agent`（铭医 MedAssist） |
| 当前 HEAD | 见 `git log -1`（历史已于 2026-11-14 重写，831 个提交） |
| 上传目标 | GitHub **私有仓库**（先私有，验收后再评估是否公开） |
| 当前状态 | **未上传**。仓库无远端（`git remote -v` 为空） |
| 结论 | 可上传。工作树与历史均已通过密钥/隐私双专项排查（§3、§4） |

---

## 1. 一句话结论

工作树与**完整 Git 历史**已扫描：无真实 LLM Key、无真实个人隐私、无真实患者数据；
历史中的 26 个含病例原文的提交已用 `git filter-repo` 彻底移除；文档中的本机路径、
主机名、个人账号、真实口令已全部脱敏。**可以上传到私有仓库。**

上传后仍需你在 GitHub 后台手动开启 4 项防护（§6），并注意 §7 的两条待决策项。

---

## 2. 代码规模（实测，2026-11-14）

| 类型 | 文件 | 行数 |
|---|---:|---:|
| Java | 991 | 167,470 |
| Vue | 135 | 76,252 |
| Python | 408 | 63,831 |
| Markdown | 173 | 49,650 |
| TypeScript | 43 | 17,786 |
| SQL | 312 | 16,168 |
| JSON | 37 | 12,133 |
| PowerShell | 65 | 11,987 |
| YAML | 109 | 11,941 |
| 其他（CSS/XML/JS/字体等） | 约 310 | 约 6,800 |

- 跟踪文件 **2,563** 个，合计约 **43.2 万行**
- Git 打包体积 **73.58 MiB**（重写历史后）
- 新增展示资产：`docs/showcase/screenshots/` 50 张 PNG（约 15.6 MB）+ `docs/showcase/index.html`（62 KB）

---

## 3. 专项排查一：LLM API Key

### 3.1 扫描范围与方法

| 范围 | 方法 | 结果 |
|---|---|---|
| 工作树全部跟踪文件 | `git grep -E 'sk-[A-Za-z0-9_-]{16,}'` | **0 命中** |
| 工作树全部跟踪文件 | `git grep -E 'AKIA[0-9A-Z]{16}|ghp_[A-Za-z0-9]{20,}|xox[baprs]-|AIza[0-9A-Za-z_-]{30,}'` | **0 命中** |
| 工作树全部跟踪文件 | `git grep -E 'eyJ[A-Za-z0-9_-]{20,}\.[A-Za-z0-9_-]{20,}\.'`（JWT 全文） | **0 命中** |
| 完整 Git 历史 | `git log --all -S 'sk-…'` | 3 个提交命中，**全部为测试假值**（下表） |
| 完整 Git 历史 | `git log --all -S 'AUTH_JWT_SECRET=[A-Za-z0-9]'` | **0 命中** |
| 完整 Git 历史 | `git log --all -- .env.local data/llm_providers.json data/license` | **0 命中**（从未入库） |

### 3.2 历史中命中的 3 处假值明细

| 提交 | 内容 | 性质 |
|---|---|---|
| `be138959` | `PLAIN = "sk-live-0123456789abcdef"` | 字段加密单测夹具 |
| `19f67b30` | `"api_key": "sk-drill-FAKE-KEY-FOR-ROTATION-DRILL"` | 密钥轮换演练脚本 |
| `fdf01a17` | `"api_key": "sk-invalid-fault-drill-000"` | 故障注入测试 |

> 这 3 处**不会泄露任何真实凭据**，但可能触发 GitHub Push Protection 的"疑似密钥"提示。
> 处理方式见 §5.3（**不要**为了让扫描通过而改测试数据）。

### 3.3 真实 Key 的实际存放位置（全部在库外）

| 位置 | 保护方式 |
|---|---|
| `.env.local` | `.gitignore` 排除，历史零命中 |
| `data/llm_providers.json` | `.gitignore` 排除；存储侧为 AES-256-GCM 密文（`enc:v2:<keyId>:<payload>`），密钥环在 `data/field_keys.json`（同样排除） |
| 数据库 `llm_providers.api_key` | 同上密文；密钥材料不进库 |
| 授权签发私钥 `data/license/` | `.gitignore` 排除，历史零命中 |

### 3.4 截图专项（新增，50 张）

50 张界面截图中，**2 张是「AI 模型配置」页**（`23-ai-model-config`、`24-ai-command-palette`）。
逐张打开核验结果：

- 「API 密钥」输入框为**空**，显示的是占位提示「本地 / 无鉴权服务可空；保存后不可回显」；
- 「密钥加解密盘点」面板只显示统计数（密钥总数 4 / 当前密钥 0 / 明文遗留 4 / 旧版 v1 0）与
  版本分布 `plaintext × 4`，**不含任何密钥材料**（代码设计即"只统计 keyId，不含密钥材料"）；
- 其余 48 张截图无密钥输入框或密钥展示。

**结论：截图中无 API Key 泄露。**

---

## 4. 专项排查二：个人隐私

### 4.1 文档全量审计

159 个 Markdown 文件分两批**逐个打开通读**（`docs/` 全部子目录、`deploy/`、`platform/README.md`、根目录 README/HANDOFF），发现并处理：

| 类别 | 具体发现 | 处理结果 |
|---|---|---|
| **真实口令** | `docs/HANDOFF.md` 记录了另一项目容器账号 `ljs/<redacted-password>` | 已删除 + 历史重写 |
| **主机名** | `<host>`（3 处：L3 证据包 + 2 份性能报告） | → `<host>` |
| **个人 GitHub 账号** | `poincare-lijiashu`（5 处，含一个已公开仓库地址） | → `<github-owner>` |
| **本机路径** | `d:\workspace_AI\...`、`D:\software_work\...`、`C:\backup_repos`、临时代理端口 | → `<repo>` / `<backup-dir>` / 删除；脚本改为从脚本位置推导 |
| **凭据明文** | 演示口令、开发库口令、带口令 DSN、开发 JWT 密钥 | → `<demo-password>` / `<dev-password>` / `<dev-jwt-secret>` |
| **内网地址** | `192.0.2.13`、`192.0.2.20` | → 文档保留网段 `192.0.2.x` |

复核（`git grep`）：`<redacted-password>` / `<host>` / `poincare-lijiashu` / 本机绝对路径 —— **均为 0 命中**。

### 4.2 患者数据

| 位置 | 状态 |
|---|---|
| 工作树 `data/consults.json` / `prescriptions.json` / `case_archive.json` | **未跟踪**（`.gitignore` 排除） |
| **历史中的这三个文件** | **26 个提交包含真实病例原文**（现病史、会诊意见），已在历史重写中彻底移除 |
| `data/auth/users.json`（账号与口令哈希） | 未跟踪，历史零命中 |
| `data/audit/audit.jsonl`（含 PHI） | 未跟踪，历史零命中 |
| `data/review/`（审核队列含病历） | 未跟踪，历史零命中 |
| 数据库中的患者数据 | 在 PG 容器内，不随仓库上传 |

历史重写验证：`git log --all -- data/consults.json data/prescriptions.json data/case_archive.json` → **0**。

### 4.3 截图中的人物标识（50 张逐张核验）

| 类型 | 出现内容 | 判定 |
|---|---|---|
| 患者标识 | `P2026…`、`Gxx Patient xxxxx`、`IC2026…`、哈希化 ID | 合成脱敏 |
| 签署人 | `LI SI (SPOUSE)`、`李然絮` | 合成测试数据（CA 签署用例构造值） |
| 账号 | `admin01`、`doctor01`、`qc01`、`ops01` | 演示账号 |
| 临时账号 | `e2e_pwd_221140`、`ui_pwd_check` | **脚本自动生成**：`scripts/e2e/password_lifecycle_suite.py:120` 生成 `e2e_pwd_{stamp}`；`ui_pwd_check` 是浏览器冒烟账号 |
| 租户管理员电话 | `13800000000` | 合成测试号（`13800000000` 为标准测试号） |
| 浏览器工具栏 | 搜索词与书签（医学/基因编辑类新闻） | 经你确认不属隐私范畴，保留 |

**结论：截图中无真实患者信息与真实个人信息。**

### 4.4 二进制与第三方素材

| 文件 | 处置 |
|---|---|
| `data/kb/sample_guideline.pdf`（18.75 MB） | **已停止跟踪** + 加入 `.gitignore`；本机文件保留 |
| `docs/qa/audit-export-2026-09-07.xlsx` | 已解包核验：仅事件类型/日期/队列计数，无 PHI → 保留 |
| `docs/skills-audit/`（68 个文件，约 742 KB） | 上游仓库**逐字复制**且未随附 LICENSE → **私有仓可留，转公开前必须处理**（§7） |
| `frontend-console/dist/`（206 个文件） | 构建产物，按既定策略入库（免 node 部署） |
| 字体 `woff2/woff` | IBM Plex（SIL OFL），保留 |

---

## 5. 上传执行步骤

### 5.1 第 0 步：备份（必须，且已完成）

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\backup_repo.ps1 -Dst "C:\backup_repos"
# → BACKUP OK ... HEAD=<当前提交>
```

> ⚠️ **历史重写前的旧 bundle（`C:\backup_repos\medical_agent_2026111[0-3].bundle` 等）仍含病例原文**，
> 它们只用于本地灾难恢复，**不要上传、分享或推送到任何远端**。

### 5.2 第 1 步：工作区与远端

```powershell
git status --short              # 必须为空
git log --oneline -3
git remote -v                   # 当前为空（filter-repo 已移除旧 origin）
```

### 5.3 第 2 步：创建私有仓库并推送

```powershell
# GitHub 上先建 **私有** 仓库（不要勾选自动生成 README/.gitignore/LICENSE）
git remote add origin https://github.com/<owner>/<repo>.git
git push -u origin main
```

推送后如被 Push Protection 拦下（仅可能是 §3.2 的 3 处测试假值）：

1. 在 GitHub 上确认告警是 **测试假值**（看文件名与行号，对照 §3.2 表）；
2. 选择 **"Allow"** 并添加 allowlist 条目（路径级），**不要**为了绕过扫描去改测试代码；
3. 重新推送。

### 5.4 第 3 步：推送后立即开启平台防护

| 项 | 操作 | 验证 |
|---|---|---|
| Secret scanning | Settings → Code security → 开启 | 能看到扫描结论 |
| Push protection | 同上 → 开启 | 后续推送自动拦截 |
| Dependabot | Settings → Advanced → 开启 alerts + security updates | 出现依赖告警 |
| 分支保护 | `main` 要求 11 个 required checks + 1 人 review + 禁 force push | 见 `docs/ci/BRANCH-PROTECTION.md` |

11 个 required checks（必须与 `ci.yml` 的 job 名完全一致）：
`pytest` `lint` `vuln-scan` `postgres-it` `migration-lint` `platform` `frontend` `image-build` `helm` `eval-baseline` `e2e`

### 5.5 第 4 步：干净克隆验收（必做）

在**新目录**做，不要用当前工作区：

```powershell
git clone https://github.com/<owner>/<repo>.git ..\verify_clone
cd ..\verify_clone

# 6.1 敏感文件复查（期望空）
git ls-files | Select-String -Pattern "consults|prescriptions|case_archive|users\.json|\.env\.local|llm_providers"

# 6.2 历史复查（期望空）
git log --all --oneline -- data/consults.json data/prescriptions.json data/case_archive.json
git log --all --oneline -S '<redacted-password>'
git log --all --oneline -S '<host>'

# 6.3 密钥复查（期望 0）
git grep -E 'sk-[A-Za-z0-9_-]{16,}' -- .

# 6.4 可运行性（按 README）
copy ..\medical_agent\.env.example .env.local    # 填入本地密钥
mvn -f platform/pom.xml -DskipTests package
python -m uvicorn backend.main:app --host 127.0.0.1 --port 8001
powershell -File scripts\run_platform_demo.ps1
# 打开 http://127.0.0.1:8001 登录并核对驾驶舱
```

---

## 6. 上传后 24 小时内的动作清单

- [ ] 确认 Secret scanning 与 Push protection 已开启
- [ ] 确认分支保护生效（推一个故意失败的 PR 验证会被拦）
- [ ] 观察首个 Actions run 是否 11 个 job 全绿；不绿就先修再谈合并
- [ ] 把首个全绿 run 的 URL 归档到 `docs/eval/2026-11-13/`
- [ ] 在 `docs/ci/GITHUB-UPLOAD-PLAN.md` §10 记录实际上传时间、仓库地址与 run 链接

---

## 7. 仍待你决策的两项（不阻塞私有仓库上传）

| # | 事项 | 现状 | 建议 |
|---|---|---|---|
| 1 | `docs/skills-audit/`（68 个文件 / 742 KB） | 上游仓库逐字复制正文，未随附 LICENSE | 私有仓保留；**转公开前**只留 `manifest.json` + 索引摘要，或补齐各上游 LICENSE/NOTICE |
| 2 | `docs/reviews/`、`docs/EVAL-*/`、`docs/eval/`（约 6,000 行内部负面评审） | 已脱敏，但含"可被利用的越权路径"等定性结论与未公开路线图 | 私有仓保留；**转公开前**需商务/法务确认是否披露 |

---

## 8. 真实密钥万一泄露的补救顺序

1. **先轮换**（厂商控制台作废并重建），不要在仓库里"删掉就算"；
2. 再清理历史（`git filter-repo`）并强推；
3. 在 GitHub 删除旧分支/Tag/Release 附件，联系 GitHub Support 清理缓存；
4. 记录事件：暴露窗口、轮换时间、影响范围。

> 另有一件**本仓库管不了**的事：`ljs/<redacted-password>` 属于另一个项目 `ai_dev` 的容器账号，
> 本仓库已删除并重写历史，但**该口令曾暴露过**，请在 `ai_dev` 项目里改密。

---

## 9. 展示资产（本次新增）

| 路径 | 内容 |
|---|---|
| `docs/showcase/index.html` | 单文件展示页：hero + 统计 + 架构图 + 12 行能力矩阵 + 50 张截图画廊（点击放大、键盘可达）+ 质量与边界 + 文档地图；深浅色自适应；Mermaid 三镜像回退 |
| `docs/showcase/screenshots/*.png` | 50 张截图（已裁掉浏览器工具栏，1600px 宽，约 15.6 MB） |
| `docs/showcase/manifest.json` | 截图清单（文件名 / 业务域 / 标题 / 说明） |
| `docs/showcase/README-DRAFT.md` | 可直接替换根 README 的展示稿 |
| `docs/showcase/GITHUB-SHOWCASE.md` | 设计规范：视觉系统、页面结构、仓库主页设置、素材清单、发布节奏 |
| `docs/showcase/assets/hero.svg` | 1280×340 矢量首屏（Social preview 可由它导出 PNG） |
| `docs/architecture/ARCHITECTURE.md` | 7 张 Mermaid 架构图（上下文 / 服务地图 / 医嘱链路 / AI 链路 / 数据 / 部署 / 安全纵深） |

> 截图来源为本机演示环境，已裁掉浏览器工具栏；患者标识为合成数据。

---

## 10. 执行记录

| 日期 | 动作 | 结果 |
|---|---|---|
| 2026-11-14 | 文档全量审计（159 个 Markdown） | 6 类敏感项已处理 |
| 2026-11-14 | 文档脱敏（45 个文件改写） | 复核 0 命中 |
| 2026-11-14 | `sample_guideline.pdf` 停止跟踪 | `.gitignore` 已加 |
| 2026-11-14 | `git filter-repo` 历史重写（831 提交） | 病例/口令/主机名/账号历史均 0 |
| 2026-11-14 | 50 张截图核验与导入 | 无真实 Key、无真实隐私 |
| 2026-11-14 | 展示页浏览器验收（3 轮） | 50/50 图加载、8 项问题全修 |
| — | **创建 GitHub 私有仓库并推送** | ⏳ 待你指示后执行 |
| — | 开启 Secret scanning / Push protection / Dependabot / 分支保护 | ⏳ 推送后手动执行 |

---

## 11. 诚实边界

- 隐私判定基于**模式匹配 + 人工逐份审计**，无法保证 100% 覆盖（例如图片里的手写内容、
  非常规格式的编码串）；当前 50 张截图已逐张人工核验。
- 历史重写**已执行且不可逆**：全部 commit ID 已变化；旧 bundle 仍含旧历史，不要外传。
- 若真实 Key 曾写进任何**聊天工具、工单、邮件**，GitHub 清理不影响那些副本，需一并轮换。
- 「上传后一切正常」只能以**干净克隆里的实测**为依据——请执行 §5.5 第 6.4 步。
