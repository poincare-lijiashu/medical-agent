# Platform load-test baseline (G43). ASCII only.
#
# Runs fixed-concurrency read probes through the gateway and gates on p95 + error rate.
# It deliberately avoids login during the measured window (auth is rate-limited) and
# avoids write endpoints (no test data mutation).
#
# Usage:
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\perf\platform_loadtest.ps1
#   ... -Concurrency 8 -DurationSeconds 20 -P95LimitMs 800 -ErrorRateLimit 0.01
param(
    [string]$BaseUrl = "http://127.0.0.1:8080",
    [string]$Username = "admin01",
    [string]$Password = "Med@2026",
    [int]$Concurrency = 8,
    [int]$DurationSeconds = 20,
    [double]$P95LimitMs = 800,
    [double]$ErrorRateLimit = 0.01,
    [int]$PaceMs = 50
)

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Net.Http

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

$token = Invoke-Login $BaseUrl
Write-Output "loadtest base=$BaseUrl concurrency=$Concurrency duration=${DurationSeconds}s user=$Username"

$endpoints = @(
    '/api/v2/clinical/departments',
    '/api/v2/empi/patients?patientNo=P2026&page=1&size=5',
    '/api/v2/emr/ca/board',
    '/api/v2/clinical/transfusion/board',
    '/api/v2/tech/applications/summary'
)

# warm-up: first query per endpoint pays index/cache cost, do not count it
$warm = [System.Net.Http.HttpClient]::new()
$warm.DefaultRequestHeaders.Authorization =
    [System.Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer', $token)
foreach ($e in $endpoints) { $null = $warm.GetAsync($BaseUrl + $e).GetAwaiter().GetResult() }
$warm.Dispose()

$jobs = @()
$wall = [Diagnostics.Stopwatch]::StartNew()
for ($i = 0; $i -lt $Concurrency; $i++) {
    $jobs += Start-Job -ScriptBlock {
        param($Base, $Token, $Endpoints, $Duration, $Worker, $PaceMs)
        Add-Type -AssemblyName System.Net.Http
        $client = [System.Net.Http.HttpClient]::new()
        $client.DefaultRequestHeaders.Authorization =
            [System.Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer', $Token)
        $lat = New-Object 'System.Collections.Generic.List[double]'
        $errors = 0
        $throttled = 0
        $statuses = @{}
        $total = 0
        $deadline = [DateTime]::UtcNow.AddSeconds($Duration)
        $idx = $Worker
        while ([DateTime]::UtcNow -lt $deadline) {
            $url = $Base + $Endpoints[$idx % $Endpoints.Count]
            $sw = [Diagnostics.Stopwatch]::StartNew()
            try {
                $resp = $client.GetAsync($url).GetAwaiter().GetResult()
                $sw.Stop()
                if ([int]$resp.StatusCode -eq 200) { $lat.Add($sw.Elapsed.TotalMilliseconds) }
                elseif ([int]$resp.StatusCode -eq 429) { $throttled++ }
                else {
                    $errors++
                    $code = [string][int]$resp.StatusCode
                    if ($statuses.ContainsKey($code)) { $statuses[$code]++ } else { $statuses[$code] = 1 }
                }
                $resp.Dispose()
            } catch {
                $sw.Stop()
                $errors++
                if ($statuses.ContainsKey('EXC')) { $statuses['EXC']++ } else { $statuses['EXC'] = 1 }
            }
            $total++
            $idx++
            if ($PaceMs -gt 0) { Start-Sleep -Milliseconds $PaceMs }
        }
        $client.Dispose()
        return [pscustomobject]@{ total = $total; errors = $errors; throttled = $throttled;
            lat = $lat.ToArray(); statuses = $statuses }
    } -ArgumentList $BaseUrl, $token, $endpoints, $DurationSeconds, $i, $PaceMs
}

$null = Wait-Job -Job $jobs -Timeout ($DurationSeconds + 120)
$wall.Stop()

$all = @()
$errors = 0
$throttled = 0
$total = 0
$statusMap = @{}
foreach ($job in $jobs) {
    $rows = @(Receive-Job -Job $job)
    foreach ($row in $rows) {
        if ($null -eq $row) { continue }
        $total += [int]$row.total
        $errors += [int]$row.errors
        $throttled += [int]$row.throttled
        if ($row.lat) { $all += $row.lat }
        if ($row.statuses) {
            $st = @{}
            if ($row.statuses -is [System.Collections.IDictionary]) {
                foreach ($key in $row.statuses.Keys) { $st[[string]$key] = [int]$row.statuses[$key] }
            } else {
                foreach ($p in $row.statuses.PSObject.Properties) { $st[[string]$p.Name] = [int]$p.Value }
            }
            foreach ($k in $st.Keys) {
                if ($statusMap.ContainsKey($k)) { $statusMap[$k] += $st[$k] }
                else { $statusMap[$k] = $st[$k] }
            }
        }
    }
    Remove-Job -Job $job -Force -ErrorAction SilentlyContinue
}

$sorted = [double[]]($all | Sort-Object)
$p50 = Percentile $sorted 0.50
$p95 = Percentile $sorted 0.95
$p99 = Percentile $sorted 0.99
$ok = $sorted.Count
$errorRate = if ($total -gt 0) { $errors / $total } else { 1 }
$throttleRate = if ($total -gt 0) { $throttled / $total } else { 1 }
$rps = if ($DurationSeconds -gt 0) { $total / $DurationSeconds } else { 0 }

Write-Output ("samples={0} total={1} errors={2} errorRate={3:P2} throttled={4} rps={5:N1}" -f $ok, $total, $errors, $errorRate, $throttled, $rps)
if ($statusMap.Count -gt 0) {
    $parts = @()
    foreach ($k in ($statusMap.Keys | Sort-Object)) { $parts += "$k=$($statusMap[$k])" }
    Write-Output ("non200 " + ($parts -join ' '))
}
Write-Output ("latency_ms p50={0:N1} p95={1:N1} p99={2:N1} max={3:N1}" -f $p50, $p95, $p99, (Percentile $sorted 1.0))
Write-Output ("thresholds p95<={0}ms errorRate<={1:P2}" -f $P95LimitMs, $ErrorRateLimit)

$fail = 0
if ($ok -eq 0) { Write-Output 'FAIL no successful sample'; $fail++ }
if ($p95 -gt $P95LimitMs) { Write-Output "FAIL p95 $([Math]::Round($p95,1))ms > $P95LimitMs ms"; $fail++ }
if ($errorRate -gt $ErrorRateLimit) { Write-Output "FAIL errorRate $([Math]::Round($errorRate * 100,2))% > $($ErrorRateLimit * 100)%"; $fail++ }
if ($throttleRate -gt 0.01) {
    Write-Output "FAIL throttled $([Math]::Round($throttleRate * 100,2))% - lower the pace (increase PaceMs) before recording a baseline"
    $fail++
}

Write-Output "-----"
if ($fail -gt 0) { Write-Output "RESULT FAIL ($fail)"; exit 1 }
Write-Output 'RESULT PASS'
exit 0
