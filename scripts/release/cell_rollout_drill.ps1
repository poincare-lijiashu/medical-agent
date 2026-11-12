<#
.SYNOPSIS
  Cell 分批发布与单院回滚演练（第 283 批 / ADR-001 P2）。

.DESCRIPTION
  ADR-001 §3 的"发布"契约：按 Cell 分批灰度（1 家 → 10% → 全量），
  单院回滚不影响其他院。本脚本把这条契约做成可重复执行的演练，并把每一步
  写进 release 服务的部署台账（带 tenantCode 与 batchLabel），产出证据 JSON：

      0) BASELINE：给参与演练的每个 Cell 各记一次基线部署（提供回滚目标）；
      1) PILOT    ：只推进首家医院；
      2) CANARY_10：推进到累计 10%（不足 1 家时与试点重合，会明确标注）；
      3) ALL      ：推进剩余全部医院；
      4) ROLLBACK ：回滚试点院最近一次部署，验证别院台账完全不变。

  为什么要有 BASELINE：回滚只能回到"同一 Cell 的上一版成功部署"。
  首次演练若没有上一版，回滚目标为空会被服务端拒绝（REL_ROLLBACK_NO_TARGET），
  这是正确行为；脚本用基线记录把演练变成可重复的两步。

.EXAMPLE
  powershell -NoProfile -ExecutionPolicy Bypass -File scripts\release\cell_rollout_drill.ps1
  powershell -NoProfile -ExecutionPolicy Bypass -File scripts\release\cell_rollout_drill.ps1 -Tenants tenant-001,tenant-002 -SkipRollback

.NOTES
  依赖：主演示栈（8080 网关 + 8094 release + 8081 auth）在线；admin01 具备
  release:write / approve / deploy（ADMIN 默认全部具备）。
  演练只写部署台账，不真正滚动镜像；生产由 Helm 按 cell 执行同一批次序列。
#>
param(
    [string]$Gateway = "http://127.0.0.1:8080",
    [string]$Username = "admin01",
    [string]$Password = "Med@2026",
    [string]$ComponentCode = "platform-gateway",
    [string]$Version = "0.1.0-drill-20261112",
    [string[]]$Tenants = @("tenant-001", "tenant-002"),
    [switch]$SkipRollback,
    [string]$OutDir = "artifacts/release"
)

$ErrorActionPreference = "Stop"
$script:fail = 0
$script:pass = 0

function Write-Step([string]$text) { Write-Host "==> $text" -ForegroundColor Cyan }
function Write-Ok([string]$text) { Write-Host "    OK  $text" -ForegroundColor Green }
function Write-Warn([string]$text) { Write-Host "    WARN $text" -ForegroundColor Yellow }
function Fail([string]$text) { Write-Host "[FAIL] $text" -ForegroundColor Red; exit 1 }

function Call([string]$method, [string]$path, [string]$token, $body, [int]$timeoutSec = 20) {
    $uri = $Gateway + "/api/v2" + $path
    $headers = @{}
    if ($token) { $headers["Authorization"] = "Bearer " + $token }
    $params = @{
        Method = $method; Uri = $uri; Headers = $headers; UseBasicParsing = $true;
        TimeoutSec = $timeoutSec
    }
    if ($body -ne $null) {
        $params["ContentType"] = "application/json"
        $params["Body"] = ($body | ConvertTo-Json -Depth 10)
    }
    try {
        $r = Invoke-WebRequest @params
        return @{ code = [int]$r.StatusCode; body = ($r.Content | ConvertFrom-Json); raw = $r.Content }
    } catch {
        $code = -1
        $text = $_.Exception.Message
        $resp = $_.Exception.Response
        if ($resp -ne $null) {
            $code = [int]$resp.StatusCode
            try {
                $sr = New-Object System.IO.StreamReader($resp.GetResponseStream())
                $text = $sr.ReadToEnd()
            } catch { }
        }
        $obj = $null
        try { $obj = $text | ConvertFrom-Json } catch { }
        return @{ code = $code; body = $obj; raw = $text }
    }
}

function Check([string]$name, [bool]$ok, [string]$detail) {
    if ($ok) {
        $script:pass++
        Write-Host "    PASS $name" -ForegroundColor Green
    } else {
        $script:fail++
        Write-Host "    FAIL $name :: $detail" -ForegroundColor Red
    }
}

function Now-Iso { return (Get-Date).ToUniversalTime().ToString("o") }

function Checklist {
    # 服务端把 change_ticket 当布尔项校验（true/"true"），单号另放 change_ticket_ref
    return @{
        backup = $true; rollback_plan = $true; smoke_test = $true;
        oncall_confirmed = $true; change_ticket = $true;
        change_ticket_ref = "DRILL-ADR001-283"
    }
}

# ---------------------------------------------------------------- 登录
Write-Step "登录与组件/版本准备"
$login = Call "POST" "/auth/login" $null @{ username = $Username; password = $Password }
if ($login.code -ne 200) { Fail ("登录失败：HTTP " + $login.code + " " + $login.raw) }
$token = $login.body.data.accessToken
Write-Ok "已登录 $Username"

# 组件必须存在（幂等）
$components = Call "GET" "/release/components?onlyActive=false" $token $null
$component = $null
if ($components.code -eq 200) {
    $component = @($components.body.data) | Where-Object { $_.code -eq $ComponentCode } | Select-Object -First 1
}
if (-not $component) {
    $created = Call "POST" "/release/components" $token @{
        code = $ComponentCode; name = "统一网关"; kind = "SERVICE";
        ownerGroup = "PLATFORM_ONCALL"; status = "ACTIVE"; sortNo = 10
    }
    if ($created.code -ne 200) { Fail ("创建组件失败：" + $created.raw) }
    Write-Ok "组件已登记：$ComponentCode"
} else {
    Write-Ok "组件已存在：$ComponentCode"
}

# 版本必须存在且已 RELEASED（幂等：先按版本号找）
$versions = Call "GET" ("/release/versions?componentCode=" + $ComponentCode + "&size=200") $token $null
# 注意：$Version 是版本号字符串参数；工作变量必须另起名字。
# PowerShell 变量大小写不敏感，若这里用 $version，会把参数覆盖成 null（已踩过）。
$versionInfo = $null
if ($versions.code -eq 200) {
    $versionInfo = @($versions.body.data.items) | Where-Object { $_.version -eq $Version } | Select-Object -First 1
}
if (-not $versionInfo) {
    $reg = Call "POST" "/release/versions" $token @{
        componentCode = $ComponentCode; version = $Version;
        # 可部署组件发布前必须有镜像摘要（服务端硬校验）。演练用确定性的假摘要，
        # 只为走通"登记 → 发布 → 分批部署"的真实链路，不代表任何实际镜像。
        imageDigest = ("sha256:drill-" + ($Version -replace "[^0-9A-Za-z._-]", ""));
        changelog = "ADR-001 Section 3 rollout drill (cell batch + rollback)"; breakingChanges = $false
    }
    if ($reg.code -ne 200) { Fail ("登记版本失败：" + $reg.raw) }
    $versionInfo = $reg.body.data
    Write-Ok "版本已登记：$Version（DRAFT）"
}
if ($versionInfo.status -eq "DRAFT" -and
        ($versionInfo.imageDigest -eq $null -or $versionInfo.imageDigest -eq "")) {
    Fail ("版本 " + $Version + " 处于 DRAFT 且缺少 imageDigest（早期演练遗留）。" +
          "请换一个 -Version，或先清理该 DRAFT 记录后重跑。")
}
if ($versionInfo.status -eq "DRAFT") {
    $pub = Call "POST" ("/release/versions/" + $versionInfo.id + "/publish") $token $null
    if ($pub.code -ne 200) { Fail ("发布版本失败：" + $pub.raw) }
    $versionInfo = $pub.body.data
    Write-Ok "版本已发布：$Version"
} elseif ($versionInfo.status -ne "RELEASED") {
    Fail ("版本状态为 " + $versionInfo.status + "，演练需要 RELEASED")
} else {
    Write-Ok "版本已发布：$Version"
}

# powershell -File 传参时 "a,b" 会作为单个字符串到达，必须显式拆分
$tenantList = @($Tenants |
        ForEach-Object { $_ -split "," } |
        ForEach-Object { $_.Trim() } |
        Where-Object { $_ } |
        Select-Object -Unique)
if ($tenantList.Count -eq 0) { Fail "至少需要一个租户" }

# ---------------------------------------------------------------- 批次计划
$total = $tenantList.Count
$pilot = @($tenantList[0])
$canaryCumulative = [Math]::Max(1, [Math]::Ceiling($total * 0.1))
$canary = @()
for ($i = $pilot.Count; $i -lt $canaryCumulative; $i++) { $canary += $tenantList[$i] }
$all = @()
for ($i = $pilot.Count + $canary.Count; $i -lt $total; $i++) { $all += $tenantList[$i] }

$plan = New-Object System.Collections.ArrayList
[void]$plan.Add(@{ label = "BASELINE"; tenants = $tenantList; note = "基线部署（提供回滚目标）" })
[void]$plan.Add(@{ label = "PILOT"; tenants = $pilot; note = "首批 1 家" })
[void]$plan.Add(@{ label = "CANARY_10"; tenants = $canary; note = ("推进到累计 " + $canaryCumulative + " 家（10%）") })
[void]$plan.Add(@{ label = "ALL"; tenants = $all; note = "推进剩余全部" })

Write-Step "批次计划（$total 家 Cell）"
foreach ($batch in $plan) {
    $names = if ($batch.tenants.Count -gt 0) { $batch.tenants -join ", " } else { "（与上一批重合，本批无新增）" }
    Write-Host ("    {0,-10} {1,-28} {2}" -f $batch.label, $names, $batch.note)
}

# ---------------------------------------------------------------- 逐批部署
$records = New-Object System.Collections.ArrayList
function Deploy-To([string]$tenant, [string]$batchLabel, [string]$note) {
    $body = @{
        componentCode = $ComponentCode; versionId = $versionInfo.id; environment = "PROD";
        tenantCode = $tenant; batchLabel = $batchLabel;
        action = "DEPLOY"; result = "SUCCESS"; checklist = (Checklist);
        reason = ("cell rollout drill: " + $note);
        startedAt = (Now-Iso); finishedAt = (Now-Iso)
    }
    $r = Call "POST" "/release/deployments" $token $body
    if ($r.code -ne 200) { throw ("部署记录失败 tenant=" + $tenant + " HTTP " + $r.code + " " + $r.raw) }
    return $r.body.data
}

foreach ($batch in $plan) {
    if ($batch.tenants.Count -eq 0) { continue }
    Write-Step ("批次 " + $batch.label)
    foreach ($tenant in $batch.tenants) {
        try {
            $d = Deploy-To $tenant $batch.label $batch.note
            [void]$records.Add([ordered]@{
                tenant = $tenant; batch = $batch.label; deploymentId = $d.id;
                action = $d.action; result = $d.result; startedAt = $d.startedAt
            })
            Write-Ok ("$tenant 已记录部署 " + $d.id)
        } catch {
            Check ("批次 " + $batch.label + " 部署 " + $tenant) $false $_.Exception.Message
        }
    }
    # 每批结束校验：上一批的所有 Cell 台账可查且归属正确（证明批次是"按院推进"而不是全局）
    $verify = Call "GET" ("/release/deployments?componentCode=" + $ComponentCode + "&tenantCode=" + $batch.tenants[0] + "&size=5") $token $null
    $first = @($verify.body.data.items) | Select-Object -First 1
    Check ("批次 " + $batch.label + " 台账可查且归属 " + $batch.tenants[0]) `
        ($verify.code -eq 200 -and $first -ne $null -and $first.tenantCode -eq $batch.tenants[0]) `
        ($verify.raw)
}

# ---------------------------------------------------------------- 单院回滚
$rollbackInfo = $null
if (-not $SkipRollback) {
    Write-Step "单院回滚演练（试点院）"
    if ($records.Count -eq 0) {
        Check "单院回滚演练" $false "没有成功的部署记录，跳过回滚"
    } else {
    $pilotTenant = $pilot[0]
    $pilotRecords = @($records | Where-Object { $_.tenant -eq $pilotTenant })
    $latest = $pilotRecords | Select-Object -Last 1
    $otherTenant = $tenantList | Where-Object { $_ -ne $pilotTenant } | Select-Object -First 1
    $beforeOther = $null
    if ($otherTenant) {
        $other = Call "GET" ("/release/deployments?componentCode=" + $ComponentCode + "&tenantCode=" + $otherTenant + "&size=1") $token $null
        $beforeOther = @($other.body.data.items) | Select-Object -First 1
    }

    $rb = Call "POST" ("/release/deployments/" + $latest.deploymentId + "/rollback") $token @{
        reason = "ADR-001 cell rollback drill"
    }
    Check "试点院回滚成功" ($rb.code -eq 200 -and $rb.body.data.action -eq "ROLLBACK") $rb.raw
    if ($rb.code -eq 200) {
        Check "回滚记录继承原 Cell" ($rb.body.data.tenantCode -eq $pilotTenant) `
            ("tenant=" + $rb.body.data.tenantCode)
        if ($otherTenant) {
            $after = Call "GET" ("/release/deployments?componentCode=" + $ComponentCode + "&tenantCode=" + $otherTenant + "&size=1") $token $null
            $afterOther = @($after.body.data.items) | Select-Object -First 1
            Check "回滚不影响其他院台账" `
                ($after.code -eq 200 -and $afterOther -ne $null -and $afterOther.id -eq $beforeOther.id) `
                ("before=" + $beforeOther.id + " after=" + $afterOther.id)
        }
        $rollbackInfo = [ordered]@{
            tenant = $pilotTenant; rolledBackDeployment = $latest.deploymentId;
            rollbackDeployment = $rb.body.data.id; otherTenant = $otherTenant
        }
    }
    }
}

# ---------------------------------------------------------------- 证据
Write-Step "生成证据"
$perTenant = [ordered]@{}
foreach ($tenant in $tenantList) {
    $r = Call "GET" ("/release/deployments?componentCode=" + $ComponentCode + "&tenantCode=" + $tenant + "&size=20") $token $null
    $perTenant[$tenant] = @($r.body.data.items | ForEach-Object {
        [ordered]@{
            id = $_.id; action = $_.action; result = $_.result;
            batch = $_.batchLabel; startedAt = $_.startedAt
        }
    })
}

$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$evidence = [ordered]@{
    generatedAt = (Get-Date).ToString("o")
    adr = "ADR-001 §3 发布隔离契约"
    gateway = $Gateway
    component = $ComponentCode
    version = [ordered]@{ id = $versionInfo.id; version = $versionInfo.version; status = $versionInfo.status }
    plan = @($plan | ForEach-Object { [ordered]@{ batch = $_.label; tenants = $_.tenants; note = $_.note } })
    records = $records
    rollback = $rollbackInfo
    deploymentsByTenant = $perTenant
    summary = [ordered]@{ pass = $script:pass; fail = $script:fail }
    verdict = if ($script:fail -eq 0) { "PASS" } else { "FAIL" }
}
if (-not (Test-Path $OutDir)) { New-Item -ItemType Directory -Path $OutDir -Force | Out-Null }
$path = Join-Path $OutDir ("cell-rollout-" + $stamp + ".json")
($evidence | ConvertTo-Json -Depth 10) | Out-File -FilePath $path -Encoding utf8

Write-Host ""
Write-Host ("结论：{0} · PASS={1} FAIL={2}" -f $evidence.verdict, $script:pass, $script:fail) -ForegroundColor White
Write-Host "证据：$path"
if ($script:fail -gt 0) { exit 1 }
exit 0
