# =============================================================================
# SLO / capacity baseline runner (section 268). ASCII only.
#
# Runs staged, weighted read-only scenarios through the gateway, then writes a
# Markdown report + JSON machine-readable result. It does NOT write clinical
# data: the default and mixed scenarios are GET-only.
#
# Usage:
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\perf\slo_capacity.ps1
#   ... -Scenario mixed -Stages 2,4,8 -StageSeconds 10
#   ... -Strict -PeakRps 150 -RequiredHeadroom 2
#
# Exit: 0 = report written; 1 = -Strict and no stage meets the targets.
# =============================================================================
param(
    [string]$BaseUrl = "http://127.0.0.1:8080",
    [string]$Username = "admin01",
    [string]$Password = "Med@2026",
    [ValidateSet("core", "mixed")]
    [string]$Scenario = "core",
    [string]$Stages = "2,4,8",
    [int]$StageSeconds = 10,
    [int]$PaceMs = 20,
    [double]$TargetP95Ms = 800,
    [double]$TargetErrorRate = 0.01,
    [double]$TargetThrottleRate = 0.01,
    [double]$PeakRps = 150,
    [double]$RequiredHeadroom = 2.0,
    [string]$OutputDir = "reports/perf",
    [switch]$Strict
)

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Net.Http
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
Set-Location $root
if (-not (Test-Path $OutputDir)) { New-Item -ItemType Directory -Path $OutputDir -Force | Out-Null }

function Invoke-Login {
    param([string]$Base)
    $json = @{ username = $Username; password = $Password } | ConvertTo-Json -Compress
    $f = [IO.Path]::GetTempFileName()
    [IO.File]::WriteAllText($f, $json, (New-Object System.Text.UTF8Encoding($false)))
    $out = [IO.Path]::GetTempFileName()
    $code = & curl.exe --noproxy '*' -s -o $out -w '%{http_code}' -X POST `
        -H 'Content-Type: application/json; charset=utf-8' --data-binary "@$f" `
        "$Base/api/v2/auth/login"
    $body = Get-Content -Raw $out -Encoding UTF8 | ConvertFrom-Json
    Remove-Item $f, $out -Force
    if ([int]$code -ne 200) { throw "login failed: HTTP $code" }
    return $body.data.accessToken
}

function Percentile {
    param([double[]]$Sorted, [double]$P)
    if ($Sorted.Count -eq 0) { return 0 }
    $idx = [int][Math]::Floor(($Sorted.Count - 1) * $P)
    return $Sorted[$idx]
}

function New-EndpointProfile {
    $core = @(
        @{ path = '/api/v2/clinical/departments'; weight = 25; p95 = 800 },
        @{ path = '/api/v2/empi/patients?patientNo=P2026&page=1&size=5'; weight = 30; p95 = 800 },
        @{ path = '/api/v2/clinical/transfusion/board'; weight = 15; p95 = 1200 },
        @{ path = '/api/v2/tech/applications/summary'; weight = 15; p95 = 1500 },
        @{ path = '/api/v2/emr/ca/board'; weight = 15; p95 = 1200 }
    )
    if ($Scenario -eq "mixed") {
        $core += @(
            @{ path = '/api/v2/clinical/reports/ops-insights'; weight = 2; p95 = 3000 },
            @{ path = '/api/v2/audit/events?page=1&size=20'; weight = 2; p95 = 3000 },
            @{ path = '/api/v2/clinical/nursing/indicator-board?deptId=33333333-3333-3333-3333-333333333333'; weight = 2; p95 = 2000 }
        )
    }
    $expanded = @()
    foreach ($e in $core) { for ($i = 0; $i -lt $e.weight; $i++) { $expanded += $e } }
    return @{ Endpoints = $core; Expanded = $expanded }
}

function Invoke-Stage {
    param([int]$Concurrency, [int]$Duration, [string[]]$Paths, [string]$Token)
    $jobs = @()
    for ($i = 0; $i -lt $Concurrency; $i++) {
        $jobs += Start-Job -ScriptBlock {
            param($Base, $Token, $Paths, $Duration, $Worker, $PaceMs)
            Add-Type -AssemblyName System.Net.Http
            $client = [System.Net.Http.HttpClient]::new()
            $client.Timeout = [TimeSpan]::FromSeconds(20)
            $client.DefaultRequestHeaders.Authorization =
                [System.Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer', $Token)
            $stats = @{}
            $deadline = [DateTime]::UtcNow.AddSeconds($Duration)
            $idx = $Worker
            while ([DateTime]::UtcNow -lt $deadline) {
                $path = $Paths[$idx % $Paths.Count]
                if (-not $stats.ContainsKey($path)) {
                    $stats[$path] = @{ total = 0; errors = 0; throttled = 0; lat = (New-Object 'System.Collections.Generic.List[double]') }
                }
                $s = $stats[$path]
                $sw = [Diagnostics.Stopwatch]::StartNew()
                try {
                    $resp = $client.GetAsync($Base + $path).GetAwaiter().GetResult()
                    $sw.Stop()
                    $code = [int]$resp.StatusCode
                    if ($code -eq 200) { $s.lat.Add($sw.Elapsed.TotalMilliseconds) }
                    elseif ($code -eq 429) { $s.throttled++ }
                    else { $s.errors++ }
                    $resp.Dispose()
                } catch {
                    $sw.Stop()
                    $s.errors++
                }
                $s.total++
                $idx++
                if ($PaceMs -gt 0) { Start-Sleep -Milliseconds $PaceMs }
            }
            $client.Dispose()
            $rows = @()
            foreach ($key in $stats.Keys) {
                $rows += [pscustomobject]@{
                    path = $key; total = $stats[$key].total; errors = $stats[$key].errors
                    throttled = $stats[$key].throttled; lat = $stats[$key].lat.ToArray()
                }
            }
            return $rows
        } -ArgumentList $BaseUrl, $Token, $Paths, $Duration, $i, $PaceMs
    }
    $null = Wait-Job -Job $jobs -Timeout ($Duration + 120)
    $rows = @()
    foreach ($job in $jobs) {
        $rows += @(Receive-Job -Job $job)
        Remove-Job -Job $job -Force -ErrorAction SilentlyContinue
    }
    return $rows
}

$profile = New-EndpointProfile
$stageList = @($Stages.Split(",") | ForEach-Object { [int]$_.Trim() } | Where-Object { $_ -gt 0 })
if ($stageList.Count -eq 0) { throw "Stages must contain at least one positive integer, e.g. 2,4,8" }
$paths = @($profile.Expanded | ForEach-Object { $_.path })
$token = Invoke-Login $BaseUrl

# Cold-start probe: first request per endpoint includes JIT / connection / index / cache
# warm-up. It must not be mixed into the steady-state percentile, but it is exactly what
# the first user after a deployment sees, so record it separately.
$warm = [System.Net.Http.HttpClient]::new()
$warm.Timeout = [TimeSpan]::FromSeconds(30)
$warm.DefaultRequestHeaders.Authorization =
    [System.Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer', $token)
$coldRows = @()
$coldDetail = @()
foreach ($e in $profile.Endpoints) {
    $sw = [Diagnostics.Stopwatch]::StartNew()
    $code = 0
    try {
        $resp = $warm.GetAsync($BaseUrl + $e.path).GetAwaiter().GetResult()
        $sw.Stop()
        $code = [int]$resp.StatusCode
        $resp.Dispose()
    } catch {
        $sw.Stop()
        $code = -1
    }
    $coldRows += "| $($e.path) | $($e.weight) | $code | $([math]::Round($sw.Elapsed.TotalMilliseconds,1)) | $($e.p95) |"
    $coldDetail += [pscustomobject]@{ path = $e.path; code = $code; coldMs = $sw.Elapsed.TotalMilliseconds }
}
$warm.Dispose()

$stageResults = @()
foreach ($concurrency in $stageList) {
    Write-Output ("stage concurrency={0} duration={1}s scenario={2}" -f $concurrency, $StageSeconds, $Scenario)
    $rows = Invoke-Stage -Concurrency $concurrency -Duration $StageSeconds -Paths $paths -Token $token
    $allLat = @()
    $total = 0; $errors = 0; $throttled = 0
    $byPath = @{}
    foreach ($row in $rows) {
        if ($null -eq $row) { continue }
        $total += [int]$row.total
        $errors += [int]$row.errors
        $throttled += [int]$row.throttled
        if ($row.lat) { $allLat += $row.lat }
        if (-not $byPath.ContainsKey($row.path)) {
            $byPath[$row.path] = @{ total = 0; errors = 0; throttled = 0; lat = @() }
        }
        $byPath[$row.path].total += [int]$row.total
        $byPath[$row.path].errors += [int]$row.errors
        $byPath[$row.path].throttled += [int]$row.throttled
        $byPath[$row.path].lat += @($row.lat)
    }
    $sorted = [double[]]($allLat | Sort-Object)
    $p50 = Percentile $sorted 0.50
    $p95 = Percentile $sorted 0.95
    $p99 = Percentile $sorted 0.99
    $rps = if ($StageSeconds -gt 0) { $total / $StageSeconds } else { 0 }
    $errorRate = if ($total -gt 0) { $errors / $total } else { 1 }
    $throttleRate = if ($total -gt 0) { $throttled / $total } else { 1 }
    $pass = ($p95 -le $TargetP95Ms) -and ($errorRate -le $TargetErrorRate) -and ($throttleRate -le $TargetThrottleRate) -and ($total -gt 0)
    $pathSummary = @()
    foreach ($key in $byPath.Keys) {
        $v = $byPath[$key]
        $pathLat = [double[]]($v.lat | Sort-Object)
        $pathSummary += [pscustomobject]@{
            path = $key; total = $v.total; errors = $v.errors; throttled = $v.throttled
            p95 = (Percentile $pathLat 0.95)
        }
    }
    $stage = [pscustomobject]@{
        concurrency = $concurrency; total = $total; rps = $rps; p50 = $p50; p95 = $p95; p99 = $p99
        errors = $errors; errorRate = $errorRate; throttled = $throttled; throttleRate = $throttleRate
        pass = $pass; pathSummary = $pathSummary
    }
    $stageResults += $stage
    Write-Output ("  total={0} rps={1:N1} p50={2:N1} p95={3:N1} p99={4:N1} errors={5} throttled={6} pass={7}" -f `
        $total, $rps, $p50, $p95, $p99, $errors, $throttled, $pass)
}

$passing = @($stageResults | Where-Object { $_.pass })
$capacity = if ($passing.Count -gt 0) { $passing | Sort-Object rps -Descending | Select-Object -First 1 } else { $null }
$safeRps = if ($capacity) { [double]$capacity.rps } else { 0 }
$requiredRps = $PeakRps * $RequiredHeadroom
$requiredReplicas = if ($safeRps -gt 0) { [int][Math]::Ceiling($requiredRps / $safeRps) } else { 0 }
$profileName = if ($Scenario -eq "core") { "core read-only" } else { "mixed read-only (heavy views included)" }
$commit = (& git rev-parse HEAD 2>$null)
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"

$stageRows = $stageResults | ForEach-Object {
    "| $($_.concurrency) | $([math]::Round($_.rps,1)) | $([math]::Round($_.p50,1)) | $([math]::Round($_.p95,1)) | $([math]::Round($_.p99,1)) | $($_.errors) | $([math]::Round($_.errorRate * 100,2))% | $($_.throttled) | $($_.pass) |"
}
$pathRows = @()
foreach ($stage in $stageResults) {
    foreach ($v in $stage.pathSummary) {
        $pathRows += "| $($stage.concurrency) | $($v.path) | $($v.total) | $([math]::Round($v.p95,1)) | $($v.errors) | $($v.throttled) |"
    }
}

$report = @"
# SLO / Capacity Baseline (section 268)

> Generated by ``scripts/perf/slo_capacity.ps1``. Read-only probes through the gateway.
> This is a **capacity baseline**, not an SLO attainment report: it does not include
> write paths, TLS termination cost, database failover, or production network RTT.

## Environment

- Generated at: $((Get-Date).ToString("yyyy-MM-dd HH:mm:ss zzz"))
- Base URL: $BaseUrl
- Scenario: $profileName
- Stages (concurrency): $($stageList -join ", "), stage seconds: $StageSeconds, pace: ${PaceMs}ms
- Targets: p95 <= ${TargetP95Ms}ms, error rate <= $($TargetErrorRate * 100)%, throttle rate <= $($TargetThrottleRate * 100)%
- Git commit: $commit
- Host: $env:COMPUTERNAME

## Stage results

| Concurrency | RPS | p50(ms) | p95(ms) | p99(ms) | Errors | Error% | 429 | Pass |
|---|---|---|---|---|---|---|---|---|
$($stageRows -join "`n")

## Cold-start latency (first request after deployment)

| Endpoint | Weight | HTTP | Cold(ms) | p95 target(ms) |
|---|---|---|---|---|
$($coldRows -join "`n")

> Cold-start includes JIT, first DB plan, index/cache warm-up and is deliberately kept
> out of the steady-state percentile. A cold value far above the target means the first
> user after a deployment waits; pre-warm the heavy views or add a startup warm-up job.

## Per-endpoint detail

| Concurrency | Endpoint | Requests | p95(ms) | Errors | 429 |
|---|---|---|---|---|---|
$($pathRows -join "`n")

## Capacity model

- Highest passing stage: $(if ($capacity) { "$($capacity.concurrency) concurrency / $([math]::Round($capacity.rps,1)) RPS" } else { "none" })
- Safe per-platform throughput (this environment): $([math]::Round($safeRps,1)) RPS
- Planning peak: $PeakRps RPS, required headroom: ${RequiredHeadroom}x => required capacity $([math]::Round($requiredRps,1)) RPS
- Minimum replicas at this per-replica throughput: $requiredReplicas

> The replica count is a lower bound for **this** environment. Production capacity must
> be re-measured with production CPU/memory limits, TLS ingress, real data volume and
> cross-node network RTT. See ``docs/CAPACITY-MODEL-20261111.md`` for the full formula.

## Caveats

- Client and server run on the same machine; latency excludes WAN/ingress RTT.
- ``mixed`` includes heavy report/audit views; use it to find slow endpoints, not to
  claim they meet the core p95 target.
- No write paths are executed. Order creation / charging / signing capacity must be
  measured against a disposable test patient in a staging environment.
- A passing stage only proves the measured window; run a 30-minute soak before go-live.
"@

$mdPath = Join-Path $OutputDir ("slo-capacity-{0}.md" -f $stamp)
$jsonPath = Join-Path $OutputDir ("slo-capacity-{0}.json" -f $stamp)
$report | Out-File -FilePath $mdPath -Encoding utf8
[pscustomobject]@{
    generatedAt = (Get-Date).ToString("o"); commit = $commit; scenario = $Scenario
    baseUrl = $BaseUrl; stages = $stageResults; safeRps = $safeRps
    coldStart = $coldDetail
    peakRps = $PeakRps; requiredHeadroom = $RequiredHeadroom; requiredReplicas = $requiredReplicas
} | ConvertTo-Json -Depth 8 | Out-File -FilePath $jsonPath -Encoding utf8

Write-Output "report=$mdPath"
Write-Output "json=$jsonPath"
Write-Output ("capacity safeRps={0:N1} requiredReplicas={1}" -f $safeRps, $requiredReplicas)

if ($Strict -and -not $capacity) {
    Write-Output "RESULT FAIL: no stage met the targets"
    exit 1
}
Write-Output "RESULT PASS"
exit 0
