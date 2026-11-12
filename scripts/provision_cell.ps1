<#
.SYNOPSIS
  一键开通一个演示 Cell（§284 / ADR-001 P3）：独立 PostgreSQL + 全量迁移 + 控制面开通编排。

.DESCRIPTION
  与 new_cell.ps1 的区别：
    * new_cell.ps1 是"离线/受限环境"的手工路径（直接写两张台账）；
    * 本脚本走**控制面开通 API**（/auth/tenants/provisions），执行与生产完全相同的流水线：
      校验规格 → 登记租户 → 探测 Cell 结构 → Cell 内登记租户 → 创建首个管理员
      → 授权检查 → 激活 → 健康快照。
  一次性管理员口令由服务端生成，只在开通成功时打印一次。

.EXAMPLE
  powershell -NoProfile -ExecutionPolicy Bypass -File scripts\provision_cell.ps1
  powershell -NoProfile -ExecutionPolicy Bypass -File scripts\provision_cell.ps1 -Code tenant-003 -Port 5435

.NOTES
  依赖：Docker Desktop；platform/*/target 已 package；主演示栈 8080 网关 + 8081 auth 在线；
        ops01（PLATFORM_OPERATOR）账号可用。
#>
param(
    [string]$Code = "tenant-003",
    [string]$Name = "演示三院",
    [string]$InstanceCode = "MEDASSIST-HOSP-C-0001",
    [int]$Port = 5435,
    [string]$Container = "medagent_pg_tenant003",
    [string]$DbUser = "medagent",
    [string]$DbPassword = "medagent123",
    [string]$CellEndpoint = "http://127.0.0.1:8184",
    [string]$Gateway = "http://127.0.0.1:8080",
    [string]$Username = "ops01",
    [string]$Password = "Ops@2026abc",
    [string]$AdminUsername = "admin01",
    [string]$AdminDisplayName = "三院管理员",
    [switch]$RequireLicense,
    [switch]$SkipMigrate,
    [string]$OutDir = "artifacts/provisioning"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot

$services = @(
    "auth", "empi", "clinical", "pharmacy", "material", "tech", "emr", "insurance",
    "kb", "fhir", "masterdata", "migration", "ops", "release", "license"
)

function Write-Step([string]$text) { Write-Host "==> $text" -ForegroundColor Cyan }
function Write-Ok([string]$text) { Write-Host "    OK  $text" -ForegroundColor Green }
function Fail([string]$text) { Write-Host "[FAIL] $text" -ForegroundColor Red; exit 1 }

function Get-EnvValue([string]$key) {
    foreach ($file in @("$root\.env.local", "$root\.env")) {
        if (Test-Path $file) {
            $match = Select-String -Path $file -Pattern "^\s*$key\s*=\s*(.+)$" | Select-Object -First 1
            if ($match) { return $match.Matches[0].Groups[1].Value.Trim().Trim('"').Trim("'") }
        }
    }
    if (Test-Path env:$key) { return (Get-Item env:$key).Value }
    return ""
}

function Call([string]$method, [string]$path, [string]$token, $body, [int]$timeoutSec = 60) {
    $headers = @{}
    if ($token) { $headers["Authorization"] = "Bearer " + $token }
    $params = @{
        Method = $method; Uri = ($Gateway + "/api/v2" + $path); Headers = $headers;
        UseBasicParsing = $true; TimeoutSec = $timeoutSec
    }
    if ($body -ne $null) {
        $params["ContentType"] = "application/json; charset=utf-8"
        # 显式 UTF-8：PS 5.1 对字符串体默认按 Latin-1 发送，中文医院名会变成 "?"
        $params["Body"] = [Text.Encoding]::UTF8.GetBytes(($body | ConvertTo-Json -Depth 8))
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

# ---------------------------------------------------------------- 前置检查
Write-Step "前置检查"
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { Fail "未找到 docker，请先启动 Docker Desktop" }
docker ps | Out-Null
if ($LASTEXITCODE -ne 0) { Fail "docker 不可用（Docker Desktop 未启动？）" }
$missing = @()
foreach ($svc in $services) {
    $jar = Get-ChildItem "$root\platform\$svc\target\platform-$svc-*.jar" -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notlike "*sources*" } | Select-Object -First 1
    if (-not $jar) { $missing += $svc; continue }
    if (-not (jar tf $jar.FullName 2>$null | Select-String -SimpleMatch "BOOT-INF/" -Quiet)) {
        Fail ("$svc 的 jar 不是可执行包（缺 BOOT-INF）：请先停止该服务再 mvn package")
    }
}
if ($missing.Count -gt 0) {
    Fail ("缺少 jar：{0}" -f ($missing -join ", "))
}
Write-Ok "docker 与 15 个服务的可执行包就绪"

# ---------------------------------------------------------------- 1. 独立数据库
Write-Step "创建独立 Cell 数据库（$Container · 127.0.0.1:$Port）"
$exists = (docker ps -a --filter "name=^/$Container$" --format "{{.Names}}")
if ($exists) {
    $running = (docker ps --filter "name=^/$Container$" --format "{{.Names}}")
    if (-not $running) { docker start $Container | Out-Null }
    Write-Ok "容器已存在，已确保启动（数据卷保留）"
} else {
    docker run -d --name $Container `
        -e "POSTGRES_USER=$DbUser" -e "POSTGRES_PASSWORD=$DbPassword" -e "POSTGRES_DB=medagent" `
        -p "127.0.0.1:$Port`:5432" -v "$($Container)_data:/var/lib/postgresql/data" `
        postgres:15-alpine | Out-Null
    if ($LASTEXITCODE -ne 0) { Fail "容器启动失败" }
    Write-Ok "容器已创建（数据卷 $($Container)_data）"
}
$ready = $false
$prevEap = $ErrorActionPreference
$ErrorActionPreference = "SilentlyContinue"
for ($i = 0; $i -lt 40; $i++) {
    docker exec $Container pg_isready -U $DbUser -d medagent *> $null
    if ($LASTEXITCODE -eq 0) { $ready = $true; break }
    Start-Sleep -Milliseconds 1500
}
$ErrorActionPreference = $prevEap
if (-not $ready) { Fail "数据库 60 秒内未就绪：docker logs $Container" }
Write-Ok "数据库就绪"

# ---------------------------------------------------------------- 2. 全量迁移
if (-not $SkipMigrate) {
    Write-Step "迁移 15 个服务到该 Cell（一次性 Job 模式，与 Helm Hook 一致）"
    $jwt = Get-EnvValue "AUTH_JWT_SECRET"
    $internal = Get-EnvValue "INTERNAL_API_TOKEN"
    if (-not $jwt) { $jwt = "medassist-cell-local-dev-only-secret-please-override-0123456789" }
    if (-not $internal) { $internal = "medassist-dev-internal-token" }
    $env:PG_HOST = "127.0.0.1"
    $env:PG_PORT = "$Port"
    $env:PG_DATABASE = "medagent"
    $env:PG_USER = $DbUser
    $env:PG_PASSWORD = $DbPassword
    $env:SPRING_PROFILES_ACTIVE = "dev"
    $env:JWT_SECRET = $jwt
    $env:INTERNAL_API_TOKEN = $internal

    foreach ($svc in $services) {
        $jar = Get-ChildItem "$root\platform\$svc\target\platform-$svc-*.jar" |
            Where-Object { $_.Name -notlike "*sources*" } | Select-Object -First 1
        $prevEap = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        $out = & java -jar $jar.FullName --medassist.migrate-only=true --server.port=0 `
            --spring.flyway.enabled=true 2>&1 | Out-String
        $code = $LASTEXITCODE
        $ErrorActionPreference = $prevEap
        if ($code -ne 0) {
            ($out -split "`r?`n") | Select-Object -Last 12 | ForEach-Object { Write-Host "      $_" }
            Fail "$svc 迁移失败（见上方日志）"
        }
        Write-Ok "$svc migrated"
    }
} else {
    Write-Step "跳过迁移（-SkipMigrate）"
}

# ---------------------------------------------------------------- 3. 通过 API 开通
Write-Step "登录平台运营账号（$Username）"
$login = Call "POST" "/auth/login" $null @{ username = $Username; password = $Password }
if ($login.code -ne 200) { Fail ("登录失败：HTTP " + $login.code + " " + $login.raw) }
$token = $login.body.data.accessToken
Write-Ok "已登录"

Write-Step "提交开通任务（控制面编排）"
$startedAt = Get-Date
$body = @{
    tenantCode = $Code
    instanceCode = $InstanceCode
    name = $Name
    editionCode = "ENTERPRISE"
    dbHost = "127.0.0.1:$Port"
    dbName = "medagent"
    cellEndpoint = $CellEndpoint
    region = "local"
    quotas = @{ maxBeds = 500; maxUsers = 800; maxStorageGb = 200; maxAiCallsPerDay = 5000 }
    contactName = "三院信息科"
    notes = "scripts/provision_cell.ps1 一键开通"
    initialAdmin = @{ username = $AdminUsername; displayName = $AdminDisplayName; employeeNo = "" }
    requireLicense = [bool]$RequireLicense
}
$r = Call "POST" "/auth/tenants/provisions" $token $body 120
if ($r.code -ne 200) { Fail ("开通请求失败：HTTP " + $r.code + " " + $r.raw) }
$job = $r.body.data
$elapsed = [Math]::Round(((Get-Date) - $startedAt).TotalSeconds, 1)

Write-Host ""
Write-Host ("任务 {0} · 结论 {1} · 耗时 {2}s" -f $job.id, $job.status, $elapsed) -ForegroundColor White
foreach ($step in $job.steps) {
    $mark = switch ($step.status) {
        "SUCCEEDED" { "[OK]  " }
        "SKIPPED" { "[SKIP]" }
        default { "[FAIL]" }
    }
    $line = "  {0} {1,-16} {2,6}ms" -f $mark, $step.code, $step.durationMs
    if ($step.error) { $line += " :: " + $step.error }
    elseif ($step.detail.reason) { $line += " :: " + $step.detail.reason }
    Write-Host $line
}
Write-Host ""
if ($job.oneTimeAdminPassword) {
    Write-Host "一次性管理员口令（仅显示一次，请立即转交）：" -ForegroundColor Yellow
    Write-Host ("    {0} / {1}" -f $AdminUsername, $job.oneTimeAdminPassword) -ForegroundColor Yellow
}

# ---------------------------------------------------------------- 4. 证据
if (-not (Test-Path $OutDir)) { New-Item -ItemType Directory -Path $OutDir -Force | Out-Null }
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$evidence = [ordered]@{
    generatedAt = (Get-Date).ToString("o")
    adr = "ADR-001 Section 7 P3 开通自动化"
    tenant = $Code; instanceCode = $InstanceCode; db = "127.0.0.1:$Port/$DbUser"
    elapsedSeconds = $elapsed
    job = $job
}
$path = Join-Path $OutDir ("provision-" + $stamp + ".json")
($evidence | ConvertTo-Json -Depth 12) | Out-File -FilePath $path -Encoding utf8
Write-Host "证据：$path"

if ($job.status -ne "SUCCEEDED") {
    Write-Host "开通未完成。修复失败原因后可继续：" -ForegroundColor Yellow
    Write-Host ("  POST " + $Gateway + "/api/v2/auth/tenants/provisions/" + $job.id + "/resume")
    exit 1
}
exit 0
