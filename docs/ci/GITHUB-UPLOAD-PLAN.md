# 上传 GitHub 计划书（含密钥 / 个人信息专项排查）

| 项 | 内容 |
|---|---|
| 编写日期 | 2026-11-14 |
| 适用仓库 | `<repo>` |
| 当前 HEAD | `1e474061`（main，829 个提交） |
| 上传目标 | GitHub（建议先 **私有仓库**，验收后再决定是否公开） |
| 结论 | **不能直接 `git push`**：工作树已干净，但**历史里有 26 个提交包含病例原文**，必须先清理历史 |

---

## 1. 代码规模（实测，2026-11-14）

统计口径：`git ls-files`（只统计将上传的文件），行数按物理行累计。

### 1.1 按语言

| 类型 | 文件数 | 行数 | 说明 |
|---|---:|---:|---|
| Java | 991 | 167,470 | 16 个 Spring Boot 服务 + common |
| Vue | 135 | 76,252 | 控制台（含 `dist` 源码映射外的单文件组件） |
| Python | 408 | 63,831 | AI 中台 / 脚本 / 测试 |
| Markdown | 173 | 49,650 | 设计与运维文档 |
| TypeScript | 43 | 17,786 | 前端 API 与类型 |
| SQL | 312 | 16,168 | Flyway 迁移（多 schema） |
| JSON | 37 | 12,133 | 字典 / 评测集 / KB 任务 |
| PowerShell | 65 | 11,987 | 部署、门禁、演练脚本 |
| YAML | 109 | 11,941 | K8s / Helm / CI / 可观测性 |
| CSS | 91 | 2,452 | 样式 |
| XML | 28 | 1,669 | POM / 配置 |
| JavaScript | 109 | 560 | 少量脚本 |
| 其他（HTML/sh/字体等） | 约 20 | 约 250 | — |

**汇总**

- 业务源代码（Java + Vue + Python + TS + SQL + CSS + JS + XML + SH）：**约 34.6 万行**
- 配置与运维脚本（YAML + PowerShell）：**约 2.4 万行**
- 文档（Markdown）：**约 5.0 万行**
- 合计（含 JSON/HTML 等）：**约 43.2 万行 / 2,563 个跟踪文件**
- Git 打包体积：**73.58 MiB**（含 829 个提交历史；前端 `dist` 已跟踪约 2.8 MB）

---

## 2. 本次红线扫描结果（真实执行，非推测）

### 2.1 LLM API Key

| 检查项 | 结果 | 证据 |
|---|---|---|
| 工作树中真实 Key（`sk-` + 20 位以上） | **未发现** | `git grep -E 'sk-[A-Za-z0-9_-]{16,}'` 仅命中假值 |
| 云厂商 / GitHub Token（AKIA/ghp_/xox/AIza） | **未发现** | `git grep` 零命中 |
| `AUTH_JWT_SECRET=<值>` 形式的硬编码 | **未发现** | `git log -G 'AUTH_JWT_SECRET=[A-Za-z0-9]'` 零命中 |
| 历史提交中的 `sk-` 形态字符串 | 3 个提交，**全部为测试假值** | `be138959`：`sk-live-0123456789abcdef`（单测夹具）<br>`19f67b30`：`sk-drill-FAKE-KEY-FOR-ROTATION-DRILL`（密钥轮换演练）<br>`fdf01a17`：`sk-invalid-fault-drill-000`（故障注入） |
| 密钥真实存放位置 | 本地 `.env.local`、`data/llm_providers.json`（AES-GCM 密文） | 均已被 `.gitignore` 排除；`git log --all -- .env.local data/llm_providers.json` 零命中 |

> 结论：**不需要轮换任何真实 Key**；上表中 3 个假值可能触发 GitHub push protection 的"疑似密钥"提示，
> 处理方式见 §5.4（先保留、命中后再加 allowlist，不建议为绕过扫描改测试）。

### 2.2 个人信息 / 患者隐私

| 检查项 | 结果 | 证据 |
|---|---|---|
| 当前工作树中的病例原文（`consults` / `prescriptions` / `case_archive`） | **未跟踪**（仅本地磁盘） | `.gitignore` 明确排除；`git ls-files` 无这三个文件 |
| **历史提交中的病例原文** | **高风险：26 个提交包含**，最后一次删除于 `f75dd040` | `git log --all -- data/consults.json data/prescriptions.json data/case_archive.json` → 26 条；`git show f75dd040^:data/consults.json` 可见完整现病史/会诊意见 |
| `data/auth/users.json`（账号与口令哈希） | **未跟踪**，历史零命中 | `data/auth/` 在 `.gitignore` |
| `data/audit/audit.jsonl`（含 PHI 的审计） | **未跟踪**，历史零命中 | `data/audit/` 在 `.gitignore` |
| 身份证号 / 手机号 | 仅命中**测试夹具与文档示例**（`1101011990…`、`138xxxx`、`张主任`） | 无法对应真实自然人；无真实患者姓名/证件 |
| 已跟踪的 `data/` 文件 | 44 个：科室/药品字典、指南文本、KB 任务、评测结果 | 均非患者数据；其中 `data/kb/tasks/*.json` 含教材文件名 |
| 授权私钥目录 `data/license/` | 未跟踪，历史零命中 | `git log --all -- data/license` 空 |

> **唯一阻塞项**：历史中的病例原文。Git 历史是可被任何人 `git log -p` 翻出来的，
> 只在最新提交删除文件**不等于**数据已消失。

### 2.3 其他需要在上传前决定的内容

| 项 | 现状 | 建议 |
|---|---|---|
| `data/kb/sample_guideline.pdf` | 已跟踪，**18.75 MB** | 若为第三方指南原文，公开仓库有版权风险；建议移出并改为下载脚本 |
| `data/kb_guidelines_v2/*.md`（27 篇） | 已跟踪 | 指南要点摘录，需确认可再分发；否则改为院内部署资产 |
| `data/kb/tasks/*.json` | 已跟踪，含教材名（如《病理学（第10版）》） | 无原文内容，可保留；如担心版权联想可移出 |
| `frontend-console/dist/`（206 个文件） | 已跟踪（既定策略：免 node 部署） | 保留；但每次前端改动都会产生大 diff，可在 README 说明 |
| 演示口令 `<demo-password>`、`<dev-password>` | 出现在 CI、docs、compose 默认值 | 公库可保留（仅演示环境），但在 README 用醒目段落声明"必须改密" |
| **本机绝对路径** | `docs/` 中约 10 处出现 `d:\workspace_AI\...`（评测报告、评审记录、HANDOFF） | **建议替换为相对链接**：只是工作区路径，不涉及个人身份，但会暴露内部目录结构 |
| `docs/` 中的内部评审与运维记录 | 已跟踪（约 5 万行） | 含内部主机名/路径与历史缺陷，公开前建议抽检 `docs/reviews/`、`docs/eval/` |

---

## 3. 上传方案（二选一）

### 方案 A（推荐）：清理历史后上传

适用于希望保留提交历史、且可能公开仓库。

1. 用 `git filter-repo` 删除敏感路径（会重写全部提交 ID）；
2. 全量复扫（工作树 + 历史）；
3. 推送新历史到 GitHub。

代价：所有历史 commit hash 变化；已有本地备份 bundle 仍保留旧历史，**不要**再推旧 bundle。

### 方案 B：重建单一初始提交

适用于"不需要历史、只要代码"的场景（最彻底、最快）。

```powershell
# 在临时目录生成不含历史的新仓库（保留工作树内容）
git clone --no-hardlinks . ..\medical_agent_clean
cd ..\medical_agent_clean
Remove-Item .git -Recurse -Force
git init -b main
git add -A
git commit -m "chore: initial import (history sanitized)"
```

代价：丢失 829 个提交的演进记录（若团队依赖 `git blame`，不推荐）。

> 无论 A/B，**都必须先做 §4 的第 0 步备份**。

---

## 4. 执行步骤（可直接照做）

### 第 0 步：备份（必须）

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\backup_repo.ps1
# 产物：<backup-dir>\medical_agent_<日期>.bundle（含旧历史，仅本地保存）
```

### 第 1 步：确认工作区干净、服务已停

```powershell
git status --short          # 必须为空
powershell -File scripts\run_platform_demo.ps1 -Stop
```

### 第 2 步：工作树密钥扫描（三重）

```powershell
# 2.1 仓库自带规则
git grep -n -I -E 'sk-[A-Za-z0-9_-]{16,}|AKIA[0-9A-Z]{16}|ghp_[A-Za-z0-9]{20,}' -- .

# 2.2 gitleaks（推荐安装：winget install gitleaks）
gitleaks detect --source . --no-git --redact -v

# 2.3 detect-secrets（可选，生成基线便于后续增量）
pip install detect-secrets
detect-secrets scan --all-files > .secrets.baseline
```

预期：只命中 §2.1 的测试假值与演示口令；出现其他命中一律先停下处理。

### 第 3 步：历史清理（方案 A）

```powershell
pip install git-filter-repo

# 3.1 删除病例原文（三个文件出现在 26 个提交中）
git filter-repo --force ^
  --path data/consults.json ^
  --path data/prescriptions.json ^
  --path data/case_archive.json ^
  --invert-paths

# 3.2 如决定移出大文件/版权材料，追加：
# --path data/kb/sample_guideline.pdf --invert-paths

# 3.3 验证：历史中应完全找不到这些路径
git log --all --oneline -- data/consults.json data/prescriptions.json data/case_archive.json
# 期望输出为空

# 3.4 历史复扫（必须重跑，filter-repo 会重写对象）
gitleaks detect --source . --redact -v

# 3.5 绝对路径脱敏（可选但建议）：把文档里的本机路径改成相对链接
git grep -l 'workspace_AI' -- docs
# 逐个把 file:///d:/workspace_AI/... 换成相对路径（如 01-architecture.md）
```

> `filter-repo` 会删除 `origin` 远端配置，属正常现象；下一步重新添加。

### 第 4 步：创建远端并推送

```powershell
# 4.1 先在 GitHub 建**私有**仓库（不要勾选自动生成 README）
git remote add origin https://github.com/<owner>/<repo>.git

# 4.2 小步推送，便于失败时快速回滚
git push -u origin main
```

### 第 5 步：上传后立即开启平台侧防护

在 GitHub 仓库 Settings 中完成（或按 `docs/ci/BRANCH-PROTECTION.md` 用 `gh api`）：

1. **Secret scanning** + **Push protection**：开启；
2. **Dependabot alerts / security updates**：开启；
3. **分支保护**：`main` 要求 11 个 required checks（pytest / lint / vuln-scan / postgres-it /
   migration-lint / platform / frontend / image-build / helm / eval-baseline / e2e）+
   1 人 review + 禁止 force push；
4. **Actions 权限**：默认只读 `GITHUB_TOKEN`。

### 第 6 步：验收（在全新目录做，不用当前目录）

```powershell
git clone https://github.com/<owner>/<repo>.git ..\verify_clone
cd ..\verify_clone
# 6.1 敏感文件复查
git ls-files | Select-String -Pattern "consults|prescriptions|case_archive|users\.json|\.env\.local"
# 期望：空
# 6.2 历史复查
git log --all --oneline -- data/consults.json data/prescriptions.json data/case_archive.json
# 期望：空
# 6.3 可运行性（按 README）
#    - 复制 .env.example 为 .env.local 并填入本地密钥
#    - mvn -f platform/pom.xml -DskipTests package
#    - powershell -File scripts\run_platform_demo.ps1
#    - 登录 http://127.0.0.1:8080 验证驾驶舱
```

### 第 7 步：若发现真实密钥已上传（补救顺序）

1. **先轮换密钥**（在厂商控制台作废并重建），不要在仓库里"删掉就算"；
2. 再清理历史（`filter-repo`）并强推；
3. 在 GitHub 上删除旧分支/Tag/Release 附件，联系 GitHub Support 清理缓存；
4. 记录事件：时间、暴露窗口、轮换时间、影响范围（写入 `docs/` 安全事件记录）。

---

## 5. 需要你确认的 4 个决策

| # | 决策 | 选项 | 默认建议 |
|---|---|---|---|
| 1 | 历史处理 | A 清理历史 / B 单一初始提交 | **A**（保留演进记录） |
| 2 | 仓库可见性 | 私有 / 公开 | **先私有**，验收后再公开 |
| 3 | 版权材料 | 保留 `sample_guideline.pdf` 与 27 篇指南摘录 / 移出 | **移出 PDF**（18.75MB 且版权不明）；摘录保留 |
| 4 | 前端 `dist` | 继续入库 / 改由 CI 产物发布 | **继续入库**（现策略免 node 部署），在 README 注明 |

---

## 6. 功能可用性验证（本次实测）

| 项 | 结果 |
|---|---|
| 17 个入口（8080–8095 + 8001） | 全部监听 `127.0.0.1` |
| 控制台登录 / 驾驶舱 / 值班 / 预算 / 指挥台 | 通过（本批整改后复验） |
| 关键接口冒烟：`/ops/dashboard`、`/ops/golive/cockpit`、`/mdm/quality/dashboard`、`/clinical/wards` | 均 `code=0` |
| Java 单元测试（common/ops/masterdata/kb） | BUILD SUCCESS |
| 前端 `npm run typecheck` | 通过（无输出即无错误） |
| **Python 全量非 e2e 测试** | **2140 passed, 1 skipped**（203 秒） |
| 告警 → 平台 → 工单 → 自动恢复 | 通过（`scripts/ops/verify_alert_pipeline.ps1`） |

> 本轮全量回归发现并修复了 2 个**因前批改动引入的测试契约失效**（不是放宽断言）：
> ①`test_ai_governance` 仍按旧的 4 项 readiness 断言，未覆盖新增的 `runtimeModelMatchesRegistry`；
> ②`test_ci_gates` 只认 `pytestmark = pytest.mark.e2e` 单值写法，不接受合法的列表写法。
> 修复后全量 2140 项通过。

> 说明："一切功能正常"只能以**可复现的测试与冒烟证据**表述；无法承诺"零 bug"。
> 上传后仍建议按 §4 第 6 步在干净克隆里再跑一遍，避免"本地能跑、克隆不能跑"。

---

## 7. 验收标准（缺一不可）

1. `git ls-files` 与 `git log --all` 中均无病例原文 / 用户表 / 审计日志 / `.env.local`；
2. `gitleaks` 在**工作树与历史**两个模式下均无真实密钥命中；
3. GitHub 的 Secret scanning 与 Push protection 已开启，且最近一次 push 未被拦截；
4. `main` 的 11 个 required checks 全部为绿色且被设为必需；
5. 全新克隆目录能按 README 启动并登录；
6. 本计划书与 `docs/ci/BRANCH-PROTECTION.md` 一起入库，作为可复核依据。

---

## 8. 诚实边界

- 历史重写**已执行**（见 §9），该操作不可逆：全部 commit ID 已变化；
  旧备份 bundle 仍含旧历史，**不要**把旧 bundle 推送到任何远端；
- 私有信息判定依赖模式匹配 + 人工审计，无法保证 100% 覆盖（例如图片/PDF 中的文字）；
- 若曾把真实 Key 写进过任何**聊天工具、工单、邮件**，GitHub 清理不影响那些副本，需一并轮换。

---

## 9. 执行结果（2026-11-14，已完成）

用户确认按推荐方案执行（保留演进 + 先私有 + 移出 PDF + dist 继续入库）。已完成：

| 步骤 | 结果 | 验证 |
|---|---|---|
| 文档逐份审计 | 159 个 Markdown 分两批全量审计（含 `docs/` 全部子目录、`deploy/`、`platform/README.md`） | 发现 1 个真实口令、3 类个人标识、6 类内部路径、若干演示口令 |
| 文档脱敏 | 45 个文件被改写：本机路径→`<repo>`/`<backup-dir>`、主机名→`<host>`、个人 GitHub 账号→`<github-owner>`、真实口令→`<redacted-password>`、演示/开发口令→`<demo-password>`/`<dev-password>`、内网地址→`192.0.2.x` | `git grep` 复核：`<redacted-password>`/`<host>`/`poincare-lijiashu`/本机路径 均为 0 命中 |
| 示例 PDF 移出 | `data/kb/sample_guideline.pdf`（18.75MB）停止跟踪并加入 `.gitignore`，本机文件保留 | `git log --all -- data/kb/sample_guideline.pdf` → 0 |
| 历史重写 | `git filter-repo`：移除 4 类路径 + 替换 16 类标识字符串；**831 个提交**全部重写 | 病例文件历史 0；PDF 历史 0；真实口令/主机名/账号历史 0 |
| 远端 | `filter-repo` 已移除旧 `origin`（本地 bundle），当前**无远端**，等待创建私有仓库后再添加 | `git remote -v` 为空 |

### 9.1 必须你本人完成的一件事

审计发现 `docs/HANDOFF.md` 曾记录**另一个项目**（`ai_dev`）的容器账号口令 `ljs/<redacted-password>`。
本仓库已删除该文本并重写历史，但**该口令已经暴露过**，请在 `ai_dev` 项目里改密——
这不受本仓库控制，也无法由本仓库验证。

### 9.2 仍待你决策（不阻塞私有仓库上传）

| 项 | 现状 | 影响 |
|---|---|---|
| `docs/skills-audit/`（68 个文件） | 含 10 个上游仓库的**逐字复制正文**，且未随附 LICENSE | 私有仓无碍；**转公开前**建议只保留 `manifest.json` 与摘要索引 |
| `docs/reviews/`、`docs/EVAL-*/`、`docs/eval/`（约 6,000 行内部负面评审） | 已脱敏（无个人信息），但含"可被利用的漏洞"等定性结论 | 私有仓可留；公开前需商务/法务确认 |
| 演示口令 | 文档中统一为 `<demo-password>`，README 与 CI 保留可运行值 | 公库可接受，但必须在部署检查表保留"上线改密"否定项 |

### 9.3 上传时的最短路径

```powershell
git remote add origin https://github.com/<owner>/<repo>.git
git push -u origin main
```

推送后按 §4 第 5 步开启 Secret scanning / Push protection / 分支保护 / Dependabot。
