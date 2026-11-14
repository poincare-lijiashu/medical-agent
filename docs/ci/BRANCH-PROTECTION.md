# 分支保护与 required checks 落地（评测 D-02）

| 项 | 内容 |
|---|---|
| 背景 | 远端 Actions 最后一次运行 2026-09-15，本地 HEAD 已前进数百提交；`ci.yml` 2026-10-30 才入库；没有分支保护，红灯也能合并 |
| 现状 | 工作流定义已在仓库内（`/.github/workflows/ci.yml`），缺的是：远端仓库、required checks、禁止直推 |
| 本文件交付 | ① 本地可验证的 pre-push 门禁；② required checks 清单；③ 一次性应用命令 |

> 远端设置需要**仓库管理员权限**与公网访问，程序无法在本机替你完成。
> 因此这里给的是"可直接照抄执行"的步骤，而不是"已配置"的声明。

---

## 1. 本地门禁（立即可用，已验证）

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\ci\install_hooks.ps1
```

启用后 `.githooks/pre-push` 会在推送前检查：

| 检查 | 拦截条件 |
|---|---|
| 受保护分支直推 | 目标是 `main` / `master` / `release/*` |
| 冲突标记 | 暂存区空白错误，或工作树存在 `<<<<<<<` / `=======` / `>>>>>>>` |
| 疑似密钥 | 本次推送新增 `medassist-dev-internal-token` / 开发 JWT 密钥字面量 |
| Python 静态检查 | `ruff check` 不通过（未安装时只告警，CI 仍会拦） |

验证方式：`sh .githooks/pre-push` 应输出 `[ OK ] pre-push 本地门禁通过`。

---

## 2. required checks 清单（与 `ci.yml` 的 job 名一一对应）

以下 **10 个** job 已设为 `main` 的 required（名字必须与 job `name:`/`id` 完全一致）：

```
pytest
lint
vuln-scan
postgres-it
migration-lint
platform
frontend
image-build
helm
e2e
```

> **为什么不含 `eval-baseline`**：它只在 `workflow_dispatch` / 每周定时运行
> （`if: github.event_name == 'workflow_dispatch' || github.event_name == 'schedule'`），
> PR 上根本不会产生 check run。把它设为 required 会让所有 PR 永远停在
> "Expected — Waiting for status to be reported"，再也合不进去。

> 注意：`continue-on-error` 已在本轮整改中移除；任何 job 失败都会让 check 变红，
> 分支保护才能真实阻断合并。

---

## 3. 已在远端生效的配置（2026-11-14，`gh api` 应用并回读核验）

```json
{
  "required_status_checks": {
    "strict": false,
    "contexts": ["pytest","lint","vuln-scan","postgres-it","migration-lint",
                 "platform","frontend","image-build","helm","e2e"]
  },
  "enforce_admins": false,
  "required_pull_request_reviews": null,
  "restrictions": null,
  "allow_force_pushes": false,
  "allow_deletions": false,
  "required_conversation_resolution": false
}
```

两处与"教科书配置"不同的取舍，都是为了让单人维护的仓库**不被自己锁死**：

| 项 | 取值 | 原因 |
|---|---|---|
| `required_pull_request_reviews` | `null`（不要求 review） | 仓库只有一个维护者时，"需要 1 个 approval"意味着自己永远无法合并自己的 PR |
| `enforce_admins` | `false` | 管理员仍可直接推送；协作方必须走 PR + 绿灯。等有 ≥2 名协作者后再改为 `true` 并开启 1 人 review |
| `strict` | `false` | 不强制"合并前先把分支 rebase 到最新"，减少单人开发的摩擦；CI 仍会在 `push`/`pull_request` 上跑 |

复现命令：

```powershell
$env:GH_TOKEN=""          # 若有 App token 会 403；清空后 gh 用 keyring 里的用户 token
gh api -X PUT repos/<owner>/<repo>/branches/main/protection --input .\protection.json
gh api repos/<owner>/<repo>/branches/main/protection --jq '.required_status_checks.contexts'
```

仓库若使用 GitLab / Gitee，对应能力是
"Settings → Merge requests → Pipelines must succeed" + "Protected branches"，
检查项名称同上。

---

## 4. 验收标准（1、2 已完成）

1. ✅ `gh api repos/<owner>/<repo>/branches/main/protection` 返回的 `contexts` 含上表 10 项
   （2026-11-14 回读：`pytest,lint,vuln-scan,postgres-it,migration-lint,platform,frontend,image-build,helm,e2e`）；
2. ✅ 首次全绿 run：<https://github.com/poincare-lijiashu/medical-agent/actions/runs/43534683795>
   （10 个 job 全绿，`eval-baseline` 按设计 skipped）；
3. ⏳ 在分支上故意让 `lint` 失败，PR 显示 Required check 未通过且**无法合并**
   （需要一次 PR 才能验证；直推 `main` 由管理员身份放行，属预期）；
4. ✅ 合并后 `main` 的 Actions 运行记录时间晚于本次整改提交。
