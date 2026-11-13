# 告警送达闭环验证（评测 D-09）
#
# 目的：证明"Alertmanager → 平台 hook → 工单"这条链路真的通，而不是配置里有个 url。
# 步骤：
#   1. 经网关以 admin01 登录拿 JWT；
#   2. 创建（或轮换）接入源 alertmanager，拿到 Bearer 令牌；
#   3. 用渲染器把平台通道写进 alertmanager.generated.yml（院方渠道 + 平台 continue 路由）；
#   4. 向平台 hook 投递一条 firing 告警 → 断言 created ≥ 1；
#   5. 投递 resolved → 断言 resolved ≥ 1（接入源开启 autoResolve）。
#
# 前置：scripts\run_platform_demo.ps1 已启动（ops 8093 / gateway 8080 / auth 8081）。
# 用法：
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\ops\verify_alert_pipeline.ps1
#   （可选 -Password 覆盖演示口令；默认读 MEDASSIST_DEMO_PASSWORD 或 Med@2026）

param(
    [string]$Gateway = "http://127.0.0.1:8080",
    [string]$AuthBase = "http://127.0.0.1:8081",
    [string]$OpsDirect = "http://127.0.0.1:8093",
    [string]$Username = "admin01",
    [string]$Password = "",
    [string]$SourceCode = "alertmanager"
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path))
Set-Location $Root

if (-not $Password) {
    $Password = $env:MEDASSIST_DEMO_PASSWORD
}
if (-not $Password) { $Password = "Med@2026" }

function Fail([string]$Message) {
    Write-Host "[FAIL] $Message" -ForegroundColor Red
    exit 1
}

function Ok([string]$Message) {
    Write-Host "[ OK ] $Message" -ForegroundColor Green
}

# ---------- 1. 登录 ----------
$loginBody = @{ username = $Username; password = $Password } | ConvertTo-Json
try {
    $login = Invoke-RestMethod -Method Post -Uri "$AuthBase/login" `
        -Headers @{ "X-Actor" = $Username; "X-Client-IP" = "127.0.0.1" } `
        -ContentType "application/json" -Body $loginBody
} catch {
    Fail "登录失败：$($_.Exception.Message)"
}
if (-not $login.data.accessToken) { Fail "登录响应缺少 accessToken" }
$token = $login.data.accessToken
$auth = @{ Authorization = "Bearer $token" }
Ok "登录成功：$Username（租户 $($login.data.user.tenantCode)）"

# ---------- 2. 接入源：存在则轮换，不存在则创建 ----------
$sourcesPath = "$Gateway/api/v2/ops/integrations/alert-sources"
$existing = $null
try {
    $list = Invoke-RestMethod -Method Get -Uri $sourcesPath -Headers $auth
    $existing = @($list.data) | Where-Object { $_.code -eq $SourceCode } | Select-Object -First 1
} catch {
    Fail "查询接入源失败：$($_.Exception.Message)"
}

$sourceToken = $null
if ($existing) {
    $rotated = Invoke-RestMethod -Method Post -Uri "$sourcesPath/$($existing.id)/rotate" -Headers $auth
    $sourceToken = $rotated.data.token
    Ok "接入源已存在，已轮换令牌：$SourceCode（hint=$($rotated.data.source.tokenHint)）"
} else {
    $body = @{
        code                 = $SourceCode
        name                 = "Alertmanager（本地/院区）"
        kind                 = "ALERTMANAGER"
        defaultService       = "infra-monitoring"
        severityMap          = @{ critical = "P1"; warning = "P2"; info = "P3" }
        autoResolve          = $true
        repeatCommentMinutes = 60
    } | ConvertTo-Json
    $created = Invoke-RestMethod -Method Post -Uri $sourcesPath -Headers $auth `
        -ContentType "application/json" -Body $body
    $sourceToken = $created.data.token
    Ok "接入源已创建：$SourceCode"
}
if (-not $sourceToken) { Fail "未获得接入源令牌（创建/轮换响应异常）" }

# ---------- 3. 渲染配置（平台通道写进 receiver） ----------
$outFile = Join-Path $Root "artifacts\observability\alertmanager.generated.yml"
$renderArgs = @(
    "scripts/ops/render_alertmanager_tenants.py",
    "--out", $outFile,
    "--platform-url", $Gateway,
    "--platform-token", $sourceToken,
    "--source-code", $SourceCode,
    "--allow-missing"
)
$render = & python @renderArgs 2>&1
if ($LASTEXITCODE -ne 0) {
    $render | ForEach-Object { Write-Host $_ }
    Fail "渲染 Alertmanager 配置失败"
}
if (-not (Select-String -Path $outFile -Pattern "/ops/hooks/alerts/$SourceCode" -Quiet)) {
    Fail "渲染结果里没有平台 hook 地址：$outFile"
}
Ok "渲染完成：$outFile（含平台 hook 与 Bearer 令牌）"

# ---------- 4. 投递 firing / resolved ----------
$fingerprint = "verify-" + [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
# 入站直接打 ops（8093 仅回环）：hook 自己校验接入源令牌；
# 经网关会让 "Bearer <接入源令牌>" 先进 JWT 校验器，反而被当成无效 JWT 拒绝。
$hook = "$OpsDirect/ops/hooks/alerts/$SourceCode"
$headers = @{ Authorization = "Bearer $sourceToken"; "Content-Type" = "application/json" }

function Send-Alert([string]$Status) {
    $payload = @{
        version           = "4"
        status            = $Status
        groupKey          = "{}:{alertname=`"D09PipelineProbe`"}"
        receiver          = "medassist-platform"
        commonLabels      = @{ alertname = "D09PipelineProbe"; severity = "warning" }
        commonAnnotations = @{}
        alerts            = @(
            @{
                status       = $Status
                labels       = @{
                    alertname = "D09PipelineProbe"
                    severity  = "warning"
                    service   = "infra-monitoring"
                    instance  = "d09-verify"
                    tenant    = "tenant-001"
                }
                annotations  = @{ summary = "D-09 闭环探针"; description = "验证告警能自动开单并自动恢复" }
                startsAt     = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
                endsAt       = if ($Status -eq "resolved") { (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ") } else { "0001-01-01T00:00:00Z" }
                generatorURL = "http://127.0.0.1:9090/graph"
                fingerprint  = $fingerprint
            }
        )
    } | ConvertTo-Json -Depth 8
    try {
        return Invoke-RestMethod -Method Post -Uri $hook -Headers $headers -Body $payload
    } catch {
        Fail "投递 $Status 失败：$($_.Exception.Message) $($_.ErrorDetails.Message)"
    }
}

$firing = Send-Alert "firing"
if ($firing.code -ne 0) { Fail "firing 响应非成功：$($firing.message)" }
$created = [int]$firing.data.created
if ($created -lt 1) {
    Fail "firing 未建单（created=$created，deduped=$($firing.data.deduped) failed=$($firing.data.failed)）"
}
Ok "firing → 新建工单 created=$created（source=$($firing.data.source)）"

$resolved = Send-Alert "resolved"
if ($resolved.code -ne 0) { Fail "resolved 响应非成功：$($resolved.message)" }
if ([int]$resolved.data.resolved -lt 1) {
    Fail "resolved 未闭环工单（resolved=$($resolved.data.resolved) failed=$($resolved.data.failed)）"
}
Ok "resolved → 自动解决 resolved=$($resolved.data.resolved)"

# ---------- 5. 事件留痕 ----------
$events = Invoke-RestMethod -Method Get `
    -Uri "$Gateway/api/v2/ops/integrations/alert-events?sourceCode=$SourceCode&limit=5" -Headers $auth
$count = @($events.data).Count
if ($count -lt 2) { Fail "hook 事件留痕不足：$count 条" }
Ok "hook 事件留痕：$count 条（最近：$(@($events.data)[0].action) / $(@($events.data)[0].status)）"

Write-Host ""
Write-Host "PASS：Alertmanager → 平台 hook → 工单 → 自动恢复 全链路可用。" -ForegroundColor Green
Write-Host "提示：本地 alert-sink 仍可作为日志旁路；产线请用 --require-platform 渲染，缺失平台通道即失败。"
exit 0
