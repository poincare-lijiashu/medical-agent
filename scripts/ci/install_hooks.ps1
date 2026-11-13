# 安装仓库自带的 Git 钩子（评测 D-02）
#
# 为什么需要：.githooks/pre-push 只有在 core.hooksPath 指向它时才生效。
# 新克隆的仓库默认不启用，等于门禁只写在文档里。
#
# 用法：
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\ci\install_hooks.ps1
#   powershell ... -File scripts\ci\install_hooks.ps1 -Uninstall

param([switch]$Uninstall)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path))
Set-Location $Root

if ($Uninstall) {
    git config --unset core.hooksPath
    Write-Host "已取消 core.hooksPath（恢复默认 .git/hooks）"
    exit 0
}

if (-not (Test-Path ".githooks\pre-push")) {
    Write-Host "[FAIL] 未找到 .githooks\pre-push" -ForegroundColor Red
    exit 1
}

# Git for Windows 自带 sh，钩子脚本按 POSIX sh 编写
git config core.hooksPath .githooks
$current = git config --get core.hooksPath
if ($current -ne ".githooks") {
    Write-Host "[FAIL] core.hooksPath 未生效（当前：$current）" -ForegroundColor Red
    exit 1
}

Write-Host "[ OK ] 已启用 core.hooksPath=.githooks" -ForegroundColor Green
Write-Host "钩子内容：受保护分支直推拦截 / 冲突标记 / 疑似密钥 / ruff"
Write-Host "临时绕过：git push --no-verify（请只在明确知道原因时使用）"
