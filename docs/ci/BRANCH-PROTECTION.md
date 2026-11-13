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

以下 11 个 job 必须全部设为 required（名字必须与 job `name:`/`id` 完全一致）：

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
eval-baseline
e2e
```

> 注意：`continue-on-error` 已在本轮整改中移除；任何 job 失败都会让 check 变红，
> 分支保护才能真实阻断合并。

---

## 3. 一次性应用（管理员执行）

```powershell
# 0) 准备：gh auth login（需 repo/admin 权限）
gh auth status

# 1) 确认远端存在（若还没有远端，先建仓库再执行后续步骤）
git remote -v

# 2) 应用保护规则（repository 用 owner/repo 替换）
$repo = "owner/repo"
gh api -X PUT "repos/$repo/branches/main/protection" --input - <<'JSON'
{
  "required_status_checks": {
    "strict": true,
    "contexts": ["pytest","lint","vuln-scan","postgres-it","migration-lint",
                 "platform","frontend","image-build","helm","eval-baseline","e2e"]
  },
  "enforce_admins": true,
  "required_pull_request_reviews": {
    "required_approving_review_count": 1,
    "dismiss_stale_reviews": true,
    "require_code_owner_reviews": false
  },
  "restrictions": null,
  "allow_force_pushes": false,
  "allow_deletions": false,
  "required_conversation_resolution": true
}
JSON

# 3) 核对是否生效
gh api "repos/$repo/branches/main/protection" --jq '.required_status_checks.contexts'
```

仓库若使用 GitLab / Gitee，对应能力是
"Settings → Merge requests → Pipelines must succeed" + "Protected branches"，
检查项名称同上。

---

## 4. 验收标准

1. `gh api repos/<owner>/<repo>/branches/main/protection` 返回的 `contexts` 包含上表 11 项；
2. 在分支上故意让 `lint` 失败，PR 显示 Required check 未通过且**无法合并**；
3. 直接 `git push origin main` 被远端与本地各拦一次；
4. 合并后 `main` 的 Actions 运行记录时间晚于本次整改提交。

> 在 3、4 完成前，`SUMMARY.md` 中 D-02 只能标记为"本地门禁已生效、远端待管理员执行"，
> 不能标记为已闭环。
