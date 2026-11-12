<#
.SYNOPSIS
  单 Cell 故障注入（第 283 批 / ADR-001 P2）：验证"一院出事，别院不抖"。

.DESCRIPTION
  对**可丢弃的演示 Cell**（默认 tenant-002）注入三类故障，同时持续采样**主演示院**
  （tenant-001，8080 网关）的延迟与错误率，产出可提交的证据 JSON：

    1) CPU 打满：在目标 Cell 的数据库容器内压满单核；
    2) 进程被杀：kill -9 目标 Cell 的 auth 进程（按端口精确识别，避免误杀）；
    3) 数据库停机：docker stop 目标 Cell 的独立 PostgreSQL。

  判定口径（与 ADR-001 §3 契约一致）：
    * 进程隔离 / 数据隔离：故障期间主院错误率为 0，P99 相对基线波动 ≤ 5%；
    * CPU 隔离：本地单机共享内核，只能作为参考值（生产由每 Cell 的 ResourceQuota /
      cgroup 保证），默认不计入通过条件，可用 -EnforceCpu 强制纳入。

  安全护栏（刻意写死，避免"演练脚本误伤生产"）：
    * 目标容器必须匹配 medagent_pg_tenant<N>，绝不能是 medagent_pg；
    * 目标 auth 端口必须在 8100-8999，绝不能落在主演示栈 8080-8095；
    * 只有命令行包含 platform-auth 且端口匹配的进程才会被 kill。

.EXAMPLE
  powershell -NoProfile -ExecutionPolicy Bypass -File scripts\chaos_cell.ps1
  powershell -NoProfile -ExecutionPolicy Bypass -File scripts\chaos_cell.ps1 -BaselineSeconds 30 -FaultSeconds 45

.NOTES
  依赖：Docker Desktop；主演示栈（8080 网关 + 8081 auth）在线；二院 Cell 已由 new_cell.ps1 开通。
  脚本结束时会把被停掉的数据库容器重新拉起；被杀掉的二院 auth 进程不会自动恢复
  （它是一次性验证实例，恢复命令见输出末尾）。
#>
param(
    [string]$CellAUrl = "http://127.0.0.1:8080",
    [string]$CellBUrl = "http://127.0.0.1:8180",
    [string]$Username = "admin01",
    [string]$Password = "Med@2026",
    [string]$TargetContainer = "medagent_pg_tenant002",
    [int]$TargetAuthPort = 8181,
    [int]$BaselineSeconds = 20,
    [int]$FaultSeconds = 30,
    [int]$SampleIntervalMs = 500,
    [switch]$SkipCpu,
    [switch]$EnforceCpu,
    [string]$OutDir = "artifacts/chaos"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$script:fail = 0

function Write-Step([string]$text) { Write-Host "==> $text" -ForegroundColor Cyan }
function Write-Ok([string]$text) { Write-Host "    OK  $text" -ForegroundColor Green }
function Write-Warn([string]$text) { Write-Host "    WARN $text" -ForegroundColor Yellow }
function Fail([string]$text) { Write-Host "[FAIL] $text" -ForegroundColor Red; exit 1 }

function CallAbs([string]$method, [string]$uri, [string]$token, $body, [int]$timeoutSec = 5) {
    $headers = @{}
    if ($token) { $headers["Authorization"] = "Bearer " + $token }
    $params = @{
        Method = $method; Uri = $uri; Headers = $headers; UseBasicParsing = $true;
        TimeoutSec = $timeoutSec
    }
    if ($body -ne $null) {
        $params["ContentType"] = "application/json"
        $params["Body"] = ($body | ConvertTo-Json -Depth 8)
    }
    try {
        $r = Invoke-WebRequest @params
        return @{ code = [int]$r.StatusCode; raw = $r.Content }
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
        return @{ code = $code; raw = $text }
    }
}

function Login([string]$baseUrl, [string]$user, [string]$pass) {
    $r = CallAbs "POST" "$baseUrl/api/v2/auth/login" $null @{ username = $user; password = $pass }
    if ($r.code -ne 200) { return $null }
    try { return ($r.raw | ConvertFrom-Json).data.accessToken } catch { return $null }
}

function Percentile($sorted, [double]$p) {
    if ($sorted.Count -eq 0) { return 0 }
    $idx = [Math]::Ceiling($p / 100.0 * $sorted.Count) - 1
    if ($idx -lt 0) { $idx = 0 }
    if ($idx -ge $sorted.Count) { $idx = $sorted.Count - 1 }
    return [Math]::Round([double]$sorted[$idx], 1)
}

function Stats($samples) {
    if ($samples.Count -eq 0) {
        return [ordered]@{ count = 0; errors = 0; errorRate = 0; p50 = 0; p95 = 0; p99 = 0; max = 0 }
    }
    $ms = @($samples | ForEach-Object { [double]$_.ms } | Sort-Object)
    $errors = @($samples | Where-Object { $_.code -ne 200 }).Count
    return [ordered]@{
        count     = $samples.Count
        errors    = $errors
        errorRate = [Math]::Round(100.0 * $errors / $samples.Count, 2)
        p50       = Percentile $ms 50
        p95       = Percentile $ms 95
        p99       = Percentile $ms 99
        max       = [Math]::Round($ms[$ms.Count - 1], 1)
    }
}

function Sample([int]$seconds, [string]$token) {
    $list = New-Object System.Collections.ArrayList
    $deadline = (Get-Date).AddSeconds($seconds)
    while ((Get-Date) -lt $deadline) {
        $sw = [System.Diagnostics.Stopwatch]::StartNew()
        $r = CallAbs "GET" "$CellAUrl/api/v2/auth/me" $token $null
        $sw.Stop()
        [void]$list.Add([pscustomobject]@{
            ms = [Math]::Round($sw.Elapsed.TotalMilliseconds, 1); code = $r.code
        })
        Start-Sleep -Milliseconds $SampleIntervalMs
    }
    return $list
}

function DeltaP99([double]$baseline, [double]$phase) {
    if ($baseline -le 0) { return 0 }
    return [Math]::Round(100.0 * ($phase - $baseline) / $baseline, 2)
}

# ---------------------------------------------------------------- 护栏
Write-Step "安全护栏"
if ($TargetContainer -eq "medagent_pg") { Fail "禁止对主演示库 medagent_pg 做故障注入" }
if ($TargetContainer -notmatch '^medagent_pg_tenant\d+$') {
    Fail "目标容器必须是 medagent_pg_tenant<N>（当前：$TargetContainer）"
}
if ($TargetAuthPort -ge 8080 -and $TargetAuthPort -le 8095) {
    Fail "目标端口落在主演示栈（8080-8095）：$TargetAuthPort"
}
$running = (docker ps --filter "name=^/$TargetContainer$" --format "{{.Names}}")
if (-not $running) { Fail "目标容器未运行：$TargetContainer（先执行 scripts\new_cell.ps1）" }
Write-Ok "目标 = $TargetContainer · auth 端口 $TargetAuthPort（一次性 Cell，允许破坏）"

# ---------------------------------------------------------------- 基线
Write-Step "登录主演示院并采集基线（$BaselineSeconds 秒）"
$token = Login $CellAUrl $Username $Password
if (-not $token) { Fail "主院登录失败：$Username（确认 8080 网关与 8081 auth 在线）" }
Write-Ok "主院令牌已获取"

$baseline = Sample $BaselineSeconds $token
$baseStats = Stats $baseline
Write-Ok ("基线 P50={0}ms P95={1}ms P99={2}ms 错误率={3}% n={4}" -f `
        $baseStats.p50, $baseStats.p95, $baseStats.p99, $baseStats.errorRate, $baseStats.count)
if ($baseStats.errorRate -gt 0) {
    Fail "基线本身有错误（$($baseStats.errorRate)%），先修好主院再做故障演练"
}

$phases = New-Object System.Collections.ArrayList
[void]$phases.Add([ordered]@{
    name = "baseline"; label = "基线（无故障）"; injection = "none"; stats = $baseStats
})

$verdict = [ordered]@{
    processIsolation = "SKIPPED"
    dbIsolation = "SKIPPED"
    cpuIsolation = "SKIPPED"
    notes = New-Object System.Collections.ArrayList
}

# ---------------------------------------------------------------- 1) CPU 打满
if (-not $SkipCpu) {
    Write-Step "故障 1/3：目标 Cell 容器内 CPU 打满（$FaultSeconds 秒）"
    docker exec -d $TargetContainer sh -c "dd if=/dev/zero of=/dev/null bs=4M 2>/dev/null & dd if=/dev/zero of=/dev/null bs=4M 2>/dev/null & wait" | Out-Null
    if ($LASTEXITCODE -ne 0) {
        Write-Warn "CPU 施压命令未成功注入（跳过该阶段）"
        [void]$verdict.notes.Add("CPU 阶段跳过：docker exec 注入失败")
    } else {
        $cpuSamples = Sample $FaultSeconds $token
        docker exec $TargetContainer sh -c "pkill -9 dd" 2>$null | Out-Null
        $cpuStats = Stats $cpuSamples
        $delta = DeltaP99 $baseStats.p99 $cpuStats.p99
        $pass = ($cpuStats.errorRate -eq 0) -and ($delta -le 5)
        if ($EnforceCpu) {
            $verdict.cpuIsolation = if ($pass) { "PASS" } else { "FAIL" }
        } else {
            $verdict.cpuIsolation = "LOCAL_REFERENCE"
        }
        [void]$verdict.notes.Add(
            ("CPU 阶段 P99 波动 {0}%（本地单机共享内核，仅作参考；生产由 per-Cell ResourceQuota 保证）" -f $delta))
        [void]$phases.Add([ordered]@{
            name = "cpu"; label = "CPU 打满（目标 Cell）"; injection = "docker-exec dd x2";
            stats = $cpuStats; p99DeltaPct = $delta; pass = $pass
        })
        Write-Ok ("CPU 阶段 P99={0}ms（基线 {1}ms，波动 {2}%）" -f $cpuStats.p99, $baseStats.p99, $delta)
    }
}

# ---------------------------------------------------------------- 2) kill auth
Write-Step "故障 2/3：kill -9 目标 Cell 的 auth 进程（端口 $TargetAuthPort）"
$conn = Get-NetTCPConnection -LocalPort $TargetAuthPort -State Listen -ErrorAction SilentlyContinue |
    Select-Object -First 1
if (-not $conn) {
    Write-Warn "端口 $TargetAuthPort 没有监听进程（二院 auth 未启动），跳过进程隔离阶段"
    [void]$verdict.notes.Add("进程隔离：二院 auth 未运行，未注入（可用多院入口模式启动后重跑）")
} else {
    $authPid = $conn.OwningProcess
    $proc = Get-CimInstance Win32_Process -Filter "ProcessId=$authPid" -ErrorAction SilentlyContinue
    if (-not $proc -or $proc.CommandLine -notmatch 'platform-auth' -or $proc.CommandLine -notmatch "$TargetAuthPort") {
        Write-Warn "PID $authPid 不是预期的二院 auth（命令行校验不通过），拒绝 kill"
        [void]$verdict.notes.Add("进程隔离：目标进程校验失败，未注入（保护主演示栈）")
    } else {
        Stop-Process -Id $authPid -Force
        Write-Ok "已 kill PID $authPid（$TargetContainer 的 auth）"
        $killSamples = Sample $FaultSeconds $token
        $killStats = Stats $killSamples
        $delta = DeltaP99 $baseStats.p99 $killStats.p99
        $pass = ($killStats.errorRate -eq 0) -and ($delta -le 5)
        $verdict.processIsolation = if ($pass) { "PASS" } else { "FAIL" }
        [void]$phases.Add([ordered]@{
            name = "kill-auth"; label = "目标 Cell auth 被杀"; injection = "Stop-Process -Force";
            stats = $killStats; p99DeltaPct = $delta; pass = $pass
        })
        Write-Ok ("进程故障期 P99={0}ms（基线 {1}ms，波动 {2}%）" -f $killStats.p99, $baseStats.p99, $delta)
    }
}

# ---------------------------------------------------------------- 3) 停库
Write-Step "故障 3/3：停止目标 Cell 的 PostgreSQL（$TargetContainer）"
docker stop $TargetContainer | Out-Null
if ($LASTEXITCODE -ne 0) { Fail "docker stop $TargetContainer 失败" }
$dbSamples = Sample $FaultSeconds $token
$dbStats = Stats $dbSamples
$delta = DeltaP99 $baseStats.p99 $dbStats.p99
$pass = ($dbStats.errorRate -eq 0) -and ($delta -le 5)
$verdict.dbIsolation = if ($pass) { "PASS" } else { "FAIL" }
[void]$phases.Add([ordered]@{
    name = "stop-db"; label = "目标 Cell 数据库停机"; injection = "docker stop";
    stats = $dbStats; p99DeltaPct = $delta; pass = $pass
})
Write-Ok ("数据库故障期 P99={0}ms（基线 {1}ms，波动 {2}%）" -f $dbStats.p99, $baseStats.p99, $delta)

Write-Step "恢复目标 Cell 数据库"
docker start $TargetContainer | Out-Null
$ready = $false
for ($i = 0; $i -lt 30; $i++) {
    docker exec $TargetContainer pg_isready -U medagent -d medagent *> $null
    if ($LASTEXITCODE -eq 0) { $ready = $true; break }
    Start-Sleep -Milliseconds 1000
}
if ($ready) { Write-Ok "数据库已恢复" } else { Write-Warn "数据库 30 秒内未就绪：docker logs $TargetContainer" }

# ---------------------------------------------------------------- 证据
Write-Step "生成证据"
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$evidence = [ordered]@{
    generatedAt  = (Get-Date).ToString("o")
    adr          = "ADR-001 §3 计算/数据隔离契约"
    cellA        = $CellAUrl
    cellB        = $CellBUrl
    target       = [ordered]@{ container = $TargetContainer; authPort = $TargetAuthPort }
    samplePath   = "GET /api/v2/auth/me（主院，经 8080 网关）"
    baseline     = $baseStats
    phases       = $phases
    verdict      = [ordered]@{
        processIsolation = $verdict.processIsolation
        dbIsolation      = $verdict.dbIsolation
        cpuIsolation     = $verdict.cpuIsolation
        notes            = @($verdict.notes)
    }
}
if (-not (Test-Path $OutDir)) { New-Item -ItemType Directory -Path $OutDir -Force | Out-Null }
$jsonPath = Join-Path $OutDir ("cell-fault-" + $stamp + ".json")
($evidence | ConvertTo-Json -Depth 8) | Out-File -FilePath $jsonPath -Encoding utf8

Write-Host ""
Write-Host "阶段                 P50      P95      P99      最大     错误率   P99 波动" -ForegroundColor White
foreach ($p in $phases) {
    $s = $p.stats
    Write-Host ("{0,-18} {1,7}  {2,7}  {3,7}  {4,7}  {5,6}%  {6,7}%" -f `
            $p.label, $s.p50, $s.p95, $s.p99, $s.max, $s.errorRate, $(if ($p.Contains("p99DeltaPct")) { $p.p99DeltaPct } else { 0 }))
}
Write-Host ""
Write-Host ("结论：进程隔离={0} · 数据隔离={1} · CPU 隔离={2}" -f `
        $verdict.processIsolation, $verdict.dbIsolation, $verdict.cpuIsolation) -ForegroundColor White
Write-Host "证据：$jsonPath"
Write-Host ""
Write-Host "二院 auth 已被停止（一次性 Cell）。需要恢复时：" -ForegroundColor Yellow
Write-Host "  `$env:PG_PORT='5434'; `$env:TENANT_DEFAULT_CODE='tenant-002'; `$env:TENANT_INSTANCE_CODE='MEDASSIST-HOSP-B-0001'"
Write-Host "  java -jar platform\auth\target\platform-auth-0.1.0-SNAPSHOT.jar --server.port=$TargetAuthPort --spring.profiles.active=dev"

if ($verdict.processIsolation -eq "FAIL" -or $verdict.dbIsolation -eq "FAIL") { exit 1 }
if ($EnforceCpu -and $verdict.cpuIsolation -eq "FAIL") { exit 1 }
exit 0
