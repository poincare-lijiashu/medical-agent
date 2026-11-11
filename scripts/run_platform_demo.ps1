# 本地演示启动脚本：Java 医院业务微服务矩阵（platform/*）
#
# 用途：本机演示用，一条命令拉起 8080-8089 全部 Java 服务（含数据库迁移）。
# 纪律：
#   - 只用于本地演示。生产走 Helm（deploy/helm/medassist-platform），不用本脚本。
#   - 密钥优先取 .env.local 的 AUTH_JWT_SECRET（与 Python AI 中台同一密钥，
#     这是"一次登录访问两边"的前提）；未配置时回落本地演示值并告警。
#   - 启动失败不静默：哪个服务没起来就打印它的日志尾部和失败原因，脚本退出码非 0。
#
# 用法：
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\run_platform_demo.ps1
#   （加 -Stop 停止本脚本启动的全部服务）

param(
    [switch]$Stop,
    [string]$Root = ""
)

$ErrorActionPreference = "Stop"
if (-not $Root) {
    $Root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
}
Set-Location $Root

$logDir = Join-Path $Root "logs\platform"
$pidFile = Join-Path $logDir "pids.txt"
New-Item -ItemType Directory -Force -Path $logDir | Out-Null

# 服务清单：名称 / 模块目录 / 端口 / 启动顺序（auth 最先：其余服务与网关依赖它签发/校验）
$services = @(
    @{ name = "auth";      port = 8081 },
    @{ name = "empi";      port = 8082 },
    @{ name = "clinical";  port = 8083 },
    @{ name = "pharmacy";  port = 8084 },
    @{ name = "tech";      port = 8085 },
    @{ name = "emr";       port = 8086 },
    @{ name = "insurance"; port = 8087 },
    @{ name = "kb";        port = 8088 },
    @{ name = "material";  port = 8089 },
    @{ name = "fhir";      port = 8090 },
    @{ name = "gateway";   port = 8080 }
)

function Stop-All {
    if (-not (Test-Path $pidFile)) {
        Write-Host "未找到 pid 文件，跳过停止"
        return
    }
    Get-Content $pidFile | ForEach-Object {
        $id = $_.Trim()
        if ($id) {
            try { Stop-Process -Id ([int]$id) -Force -ErrorAction SilentlyContinue; Write-Host "已停止 pid=$id" } catch {}
        }
    }
    Remove-Item $pidFile -Force -ErrorAction SilentlyContinue
}

function Test-Port([int]$Port) {
    # 比 Test-NetConnection 快一个量级（后者要跑完整网络诊断，单次约 1 秒）
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $iar = $client.BeginConnect("127.0.0.1", $Port, $null, $null)
        if (-not $iar.AsyncWaitHandle.WaitOne(300)) { return $false }
        $client.EndConnect($iar)
        return $true
    } catch {
        return $false
    } finally {
        $client.Close()
    }
}

if ($Stop) {
    Stop-All
    exit 0
}

# ---------- 运行环境 ----------
$env:MEDASSIST_ENV = "dev"                 # 必须显式声明：缺省按生产处理会拒绝启动
$env:JWT_ISSUER = "medassist-auth"
# FHIR Bundle 的 fullUrl/self 必须是**对外**地址：经网关时服务端看到的 Host 是内网地址，
# 不配置会把链接写成 127.0.0.1:8090（对端拿到后无法回查）。
$env:FHIR_BASE_URL = "http://127.0.0.1:8080/api/v2/fhir"

# 影像底座（第 260 批）：Orthanc/OHIF 属于可选 profile，未启动时不改 PACS 配置
# （tech 服务照常启动，调阅接口返回 503 而不是假装"没有影像"）。
# 启动：docker compose --profile imaging up -d orthanc ohif
if (Test-Port 8042) {
    if (-not $env:PACS_BASE_URL) {
        $env:PACS_BASE_URL = "http://127.0.0.1:8042/dicom-web"
    }
    if (-not $env:PACS_VIEWER_URL) {
        # OrthancOHIF 插件自带 OHIF，深链格式与独立 OHIF 一致
        $env:PACS_VIEWER_URL = "http://127.0.0.1:8042/ohif/viewer"
    }
}
if (Test-Port 4242) {
    if (-not $env:PACS_DIMSE_ENABLED) { $env:PACS_DIMSE_ENABLED = "true" }
    if (-not $env:PACS_DIMSE_HOST) { $env:PACS_DIMSE_HOST = "127.0.0.1" }
    if (-not $env:PACS_DIMSE_PORT) { $env:PACS_DIMSE_PORT = "4242" }
    if (-not $env:PACS_DIMSE_CALLED_AE) { $env:PACS_DIMSE_CALLED_AE = "MEDAGENT" }
    if (-not $env:PACS_DIMSE_MOVE_DESTINATIONS) { $env:PACS_DIMSE_MOVE_DESTINATIONS = "MEDAGENT" }
}

# 密钥治理（第 129 批）：本脚本**不再内置任何演示密钥字面量**。
# 两个值都必须来自 .env.local（或当前进程环境变量），缺失即 fail-fast——
# 静默回落到仓库里的公开值，等于把"能伪造任意身份/调用内部接口"的能力写进版本库。
$envFile = Join-Path $Root ".env.local"

function Get-EnvValue([string]$Name) {
    # 进程环境变量优先（CI/容器注入），其次 .env.local
    $v = [Environment]::GetEnvironmentVariable($Name)
    if ($v) { return $v.Trim() }
    if (Test-Path $envFile) {
        $m = Select-String -Path $envFile -Pattern "^$Name=(.+)$"
        if ($m) { return $m.Matches[0].Groups[1].Value.Trim() }
    }
    return ""
}

# JWT 密钥：与 Python AI 中台共用，才能做到"一次登录、两边都认"
$secret = Get-EnvValue "AUTH_JWT_SECRET"
if (-not $secret) {
    Write-Host "[FAIL] 未配置 AUTH_JWT_SECRET（.env.local 或环境变量）。" -ForegroundColor Red
    Write-Host "       该密钥必须与 Python AI 中台一致，否则无法一次登录；且禁止使用仓库内的演示值。" -ForegroundColor Red
    exit 1
}
$env:JWT_SECRET = $secret

# 服务间内部令牌：/internal/** 端点 fail-closed 校验，缺失即拒绝。
# 同上：必须由 .env.local/环境变量提供，不在脚本里写死。
$internal = Get-EnvValue "INTERNAL_API_TOKEN"
if (-not $internal) {
    Write-Host "[FAIL] 未配置 INTERNAL_API_TOKEN（.env.local 或环境变量）。" -ForegroundColor Red
    Write-Host "       各 Java 服务与 AI 中台必须使用同一个值，否则内部调用与身份同步会被拒。" -ForegroundColor Red
    exit 1
}
$env:INTERNAL_API_TOKEN = $internal

# EMPI 字段加密与查重盐：**不注入环境变量**，让各服务 application-dev.yml 的
# 开发默认值生效——数据库里已有的患者密文就是用它加密的，换值会导致无法解密。
# 生产必须由 Secret 注入 EMPI_FIELD_KEY / EMPI_HASH_SALT（缺失即拒绝启动）。

# ---------- 启动 ----------
$pids = @()
$failed = @()

foreach ($s in $services) {
    $jar = Join-Path $Root "platform\$($s.name)\target\platform-$($s.name)-0.1.0-SNAPSHOT.jar"
    if (-not (Test-Path $jar)) {
        Write-Host "[SKIP] $($s.name)：未找到 $jar（先执行 mvn -f platform/pom.xml -DskipTests package）" -ForegroundColor Red
        $failed += $s.name
        continue
    }
    # 端口已在监听 → 视为已在运行，不重复启动
    if (Test-Port $s.port) {
        Write-Host "[RUN ] $($s.name) 已在 $($s.port) 监听，跳过"
        continue
    }

    $out = Join-Path $logDir "$($s.name).out.log"
    $err = Join-Path $logDir "$($s.name).err.log"
    # 清空旧日志，避免误判历史错误
    if (Test-Path $out) { Remove-Item $out -Force }
    if (Test-Path $err) { Remove-Item $err -Force }

    $p = Start-Process -FilePath "java" `
        -ArgumentList "-jar", "`"$jar`"", "--spring.profiles.active=dev" `
        -RedirectStandardOutput $out -RedirectStandardError $err `
        -PassThru -WindowStyle Hidden
    $pids += $p.Id
    Write-Host "[BOOT] $($s.name) pid=$($p.Id) → $($s.port) 等待就绪..."

    # 最多等 90 秒
    $ready = $false
    for ($i = 0; $i -lt 90; $i++) {
        Start-Sleep -Milliseconds 1000
        if ($p.HasExited) { break }
        if (Test-Port $s.port) { $ready = $true; break }
    }

    if (-not $ready) {
        Write-Host "[FAIL] $($s.name) 未能监听 $($s.port)（进程已退出=$($p.HasExited)）" -ForegroundColor Red
        if (Test-Path $out) {
            Write-Host "------ $($s.name) 日志尾部 ------" -ForegroundColor Red
            Get-Content $out -Tail 25 | ForEach-Object { Write-Host $_ }
        }
        if (Test-Path $err) {
            $e = Get-Content $err -Tail 15
            if ($e) { Write-Host "------ stderr ------" -ForegroundColor Red; $e | ForEach-Object { Write-Host $_ } }
        }
        $failed += $s.name
    } else {
        Write-Host "[ OK ] $($s.name) 就绪 → http://127.0.0.1:$($s.port)" -ForegroundColor Green
    }
}

$pids | Out-File -Encoding ascii $pidFile

Write-Host ""
if ($failed.Count -gt 0) {
    Write-Host "启动失败的服务：$($failed -join ', ')" -ForegroundColor Red
    Write-Host "已写 pid 文件：$pidFile（-Stop 可全部停止）"
    exit 1
}

Write-Host "全部服务已就绪。网关入口：http://127.0.0.1:8080" -ForegroundColor Green
Write-Host "停止：powershell -NoProfile -ExecutionPolicy Bypass -File scripts\run_platform_demo.ps1 -Stop"

# 常驻心跳：Java 子进程挂在当前 PowerShell 下，父进程一退出它们就会被回收。
# 这里保持脚本存活（配合 run_in_background 跑），Ctrl+C / 关闭终端时子进程一并结束。
Write-Host "脚本常驻中（保持服务存活）。本终端不要关闭。"
while ($true) {
    Start-Sleep -Seconds 60
    $alive = @($pids | Where-Object { Get-Process -Id $_ -ErrorAction SilentlyContinue })
    if ($alive.Count -eq 0) {
        Write-Host "[WARN] 所有 Java 子进程已退出，脚本退出。" -ForegroundColor Yellow
        break
    }
}
exit 0
