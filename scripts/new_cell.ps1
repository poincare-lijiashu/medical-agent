<#
.SYNOPSIS
  本地开通一个演示 Cell（ADR-001 P1 / §282）：独立 PostgreSQL + 全量迁移 + 控制面台账登记。

.DESCRIPTION
  一院一 Cell 在本地怎么验证："再起一个 PG、把 15 个服务的迁移跑一遍、把库坐标登记到控制面"。
  这不是模拟：数据库是独立实例（独立端口/独立数据卷/独立账号），与一院数据物理隔离。

  脚本只做开通，不启动业务进程；验证二院登录时按需启动对应服务（示例见输出末尾）。

.EXAMPLE
  powershell -NoProfile -ExecutionPolicy Bypass -File scripts\new_cell.ps1
  powershell -NoProfile -ExecutionPolicy Bypass -File scripts\new_cell.ps1 -Code tenant-003 -Name "演示三院" -Port 5435 -InstanceCode MEDASSIST-HOSP-C-0001

.NOTES
  依赖：Docker Desktop 已启动；platform/*/target 下已 package 出 jar。
  控制面库（登记台账）默认取本机 5433 容器 medagent_pg。
#>
param(
    [string]$Code = "tenant-002",
    [string]$Name = "演示二院",
    [string]$InstanceCode = "MEDASSIST-HOSP-B-0001",
    [int]$Port = 5434,
    [string]$Container = "medagent_pg_tenant002",
    [string]$ControlContainer = "medagent_pg",
    [string]$DbUser = "medagent",
    [string]$DbPassword = "medagent123",
    [string]$CellEndpoint = "http://127.0.0.1:8180",
    [switch]$SkipMigrate
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot

# 带 Flyway 迁移脚本的服务（gateway/logs 无库；新增服务时同步这里）
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
            $match = Select-String -Path $file -Pattern "^\s*$key\s*=\s*(.+)$" |
                Select-Object -First 1
            if ($match) { return $match.Matches[0].Groups[1].Value.Trim().Trim('"').Trim("'") }
        }
    }
    if (Test-Path env:$key) { return (Get-Item env:$key).Value }
    return ""
}

# ---------------------------------------------------------------- 前置检查
Write-Step "前置检查"
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { Fail "未找到 docker，请先启动 Docker Desktop" }
docker ps | Out-Null
if ($LASTEXITCODE -ne 0) { Fail "docker 不可用（Docker Desktop 未启动？）" }
$missing = @()
$plain = @()
foreach ($svc in $services) {
    $jar = Get-ChildItem "$root\platform\$svc\target\platform-$svc-*.jar" -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notlike "*sources*" } | Select-Object -First 1
    if (-not $jar) { $missing += $svc; continue }
    # 可执行包必须含 BOOT-INF/：mvn package 被中断（或 jar 被运行中的服务占用）时会留下普通 jar，
    # 这种包没有主清单，直接 java -jar 会报"没有主清单属性"。
    $hasBoot = (jar tf $jar.FullName 2>$null | Select-String -SimpleMatch "BOOT-INF/" -Quiet)
    if (-not $hasBoot) { $plain += $svc }
}
if ($missing.Count -gt 0) {
    Fail ("缺少 jar：{0}。请先执行 mvn -f platform/pom.xml -DskipTests package" -f ($missing -join ", "))
}
if ($plain.Count -gt 0) {
    Fail ("以下服务的 jar 不是可执行包（缺 BOOT-INF）：{0}。`n       通常是因为该服务正在运行、jar 被占用导致 repackage 失败：请先停止对应服务再 mvn package。" -f ($plain -join ", "))
}
Write-Ok "docker 与 15 个服务的可执行包就绪"

$jwt = Get-EnvValue "AUTH_JWT_SECRET"
$internal = Get-EnvValue "INTERNAL_API_TOKEN"
if (-not $jwt) { $jwt = "medassist-cell-local-dev-only-secret-please-override-0123456789" }
if (-not $internal) { $internal = "medassist-dev-internal-token" }

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
        # 原生命令写 stderr 时 PS 5 会抛 NativeCommandError（即使退出码为 0），
        # 这里临时放宽，用退出码判断迁移成败。
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

# ---------------------------------------------------------------- 3. 控制面登记
Write-Step "登记租户到控制面台账（$ControlContainer）"
$sql = @"
INSERT INTO auth_svc.tenant
    (code, instance_code, name, edition_code, status, cell_endpoint, db_host, db_name, region, created_by)
VALUES
    ('$Code', '$InstanceCode', '$Name', 'ENTERPRISE', 'ACTIVE', '$CellEndpoint', '127.0.0.1:$Port', 'medagent', 'local', 'new_cell.ps1')
ON CONFLICT (code) DO UPDATE
   SET instance_code = EXCLUDED.instance_code,
       name          = EXCLUDED.name,
       status        = EXCLUDED.status,
       cell_endpoint = EXCLUDED.cell_endpoint,
       db_host       = EXCLUDED.db_host,
       db_name       = EXCLUDED.db_name,
       updated_at    = now();
"@
# 中文经 base64 传输，绕开 Windows 控制台编码把 SQL 弄成乱码
$b64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($sql))
docker exec $ControlContainer bash -lc "echo $b64 | base64 -d | psql -U $DbUser -d medagent -v ON_ERROR_STOP=1" | Out-Null
if ($LASTEXITCODE -ne 0) { Fail "控制面登记失败（确认 $ControlContainer 在运行，且 auth 已迁移到 V96）" }
Write-Ok "已登记 $Code（db_host=127.0.0.1:$Port）"

# ---------------------------------------------------------------- 4. 摘要
$schemas = (docker exec $Container psql -U $DbUser -d medagent -t -A -c `
        "SELECT count(DISTINCT table_schema) FROM information_schema.tables WHERE table_name='flyway_schema_history'")
Write-Step "完成"
Write-Host "    租户      $Code / $Name"
Write-Host "    实例码    $InstanceCode"
Write-Host "    数据库    127.0.0.1:$Port (容器 $Container)"
Write-Host "    已迁移域  $schemas 个 schema"
Write-Host ""
Write-Host "    启动该 Cell 的身份与入口（本地验证用）：" -ForegroundColor Yellow
Write-Host "      `$env:PG_PORT='$Port'; `$env:TENANT_DEFAULT_CODE='$Code'; `$env:TENANT_INSTANCE_CODE='$InstanceCode'"
Write-Host "      java -jar platform\auth\target\platform-auth-0.1.0-SNAPSHOT.jar --server.port=8181 --spring.profiles.active=dev"
Write-Host "      `$env:TENANT_ROUTING_ENABLED='true'; `$env:TENANT_HOSTS='hospital-b.example=$Code'"
Write-Host "      java -jar platform\gateway\target\platform-gateway-0.1.0-SNAPSHOT.jar --server.port=8180 --spring.profiles.active=dev"
Write-Host ""
Write-Host "    二院库目前没有任何账号：登录会被拒（这正是一院/二院数据隔离的证明）。"
