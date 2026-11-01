# ============================================================================
# MedAssist knowledge-base retrieval evaluation (recall@3 / MRR / P95 latency).
#
# Builds a throw-away space, ingests the case corpus from fixtures, runs a fixed
# query set and reports:
#   * RECALL_AT_3 : share of queries whose expected document is in the top 3
#   * MRR         : mean reciprocal rank of the expected document
#   * P95_MS      : 95th percentile of server reported retrieval latency
#   * grounded answer on the first query (extractive, with citations)
#
# Gate: recall >= MinRecall and p95 <= MaxP95Ms, otherwise exit 1.
# ASCII only. Chinese corpus lives in scripts/e2e/fixtures/kb_eval_cases.json.
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\e2e\kb_eval.ps1
# ============================================================================
param(
    [string]$BaseUrl = "http://127.0.0.1:8080",
    [string]$Username = "admin01",
    [string]$Password = "Med@2026",
    [double]$MinRecall = 0.85,
    [int]$MaxP95Ms = 1500
)

$ErrorActionPreference = "Stop"
$script:fail = 0

function Invoke-Api {
    param([string]$Method, [string]$Path, $Body = $null, [string]$Token = $null)
    $out = [IO.Path]::GetTempFileName()
    $args = @('--noproxy', '*', '-s', '-o', $out, '-w', '%{http_code}', '-X', $Method, ($BaseUrl + $Path))
    if ($Token) { $args += @('-H', "Authorization: Bearer $Token") }
    if ($null -ne $Body) {
        $json = $Body | ConvertTo-Json -Depth 8 -Compress
        $bf = [IO.Path]::GetTempFileName()
        [IO.File]::WriteAllText($bf, $json, (New-Object System.Text.UTF8Encoding($false)))
        $args += @('-H', 'Content-Type: application/json; charset=utf-8', '--data-binary', "@$bf")
    }
    $code = & curl.exe @args
    $text = Get-Content -Raw -Path $out -Encoding UTF8
    Remove-Item $out -Force
    return [pscustomobject]@{ Status = [int]$code; Body = $text }
}

function Res($r) { return (($r.Body | ConvertFrom-Json).data) }

function Check($name, $actual, $expected) {
    if ("$actual" -eq "$expected") { Write-Output "PASS $name = $actual" }
    else { Write-Output "FAIL $name expected=$expected actual=$actual"; $script:fail++ }
}

function CheckTrue($name, $actual) {
    if ("$actual" -eq 'True') { Write-Output "PASS $name = True" }
    else { Write-Output "FAIL $name expected=True actual=$actual"; $script:fail++ }
}

function Wait-Ready {
    param([string]$DocumentId, [string]$Token)
    for ($i = 0; $i -lt 40; $i++) {
        $r = Invoke-Api 'GET' "/api/v2/kb/documents/$DocumentId" $null $Token
        if ($r.Status -eq 200) {
            $doc = Res $r
            if ("$($doc.status)" -eq 'READY') { return $doc }
            if ("$($doc.status)" -eq 'FAILED') { return $doc }
        }
        Start-Sleep -Milliseconds 500
    }
    return $null
}

$suffix = (Get-Date).ToString('HHmmssff')
$fixtureDir = Join-Path $PSScriptRoot 'fixtures'
$eval = Get-Content -Raw -Path (Join-Path $fixtureDir 'kb_eval_cases.json') -Encoding UTF8 | ConvertFrom-Json

# ---------------------------------------------------------------- 1. login + space
$r = Invoke-Api 'POST' '/api/v2/auth/login' @{ username = $Username; password = $Password }
Check 'login' $r.Status 200
$token = (Res $r).accessToken
if (-not $token) { Write-Output "FATAL no token: $($r.Body)"; exit 1 }

$r = Invoke-Api 'POST' '/api/v2/kb/spaces' @{
    code = "KB-EVAL-$suffix"; name = "Retrieval Eval $suffix"; visibility = 'PRIVATE'
} $token
Check 'space.create' $r.Status 200
$spaceId = (Res $r).id

# ---------------------------------------------------------------- 2. corpus
$documentIds = @{}
foreach ($doc in $eval.documents) {
    $r = Invoke-Api 'POST' "/api/v2/kb/spaces/$spaceId/documents" @{
        title = $doc.title; content = $doc.content; sourceType = 'TEXT'
    } $token
    if ($r.Status -ne 200) { Write-Output ("FATAL ingest failed key=" + $doc.key + " status=" + $r.Status); exit 1 }
    $documentIds[$doc.key] = ((Res $r).id)
}
foreach ($doc in $eval.documents) {
    $ready = Wait-Ready $documentIds[$doc.key] $token
    Check ("corpus.ready." + $doc.key) $ready.status 'READY'
}

# ---------------------------------------------------------------- 3. evaluation
$latencies = @()
$clientLatencies = @()
$hitCount = 0
$reciprocal = 0.0
$caseNo = 0
foreach ($case in $eval.cases) {
    $caseNo++
    $sw = [Diagnostics.Stopwatch]::StartNew()
    $r = Invoke-Api 'POST' "/api/v2/kb/spaces/$spaceId/search" @{ query = $case.query; topK = 3 } $token
    $sw.Stop()
    if ($r.Status -ne 200) { Write-Output ("FAIL case " + $caseNo + " status=" + $r.Status); $script:fail++; continue }
    $search = Res $r
    $latencies += [int]$search.latencyMs
    $clientLatencies += [int]$sw.ElapsedMilliseconds
    $rank = 0
    $hits = @($search.hits)
    for ($i = 0; $i -lt $hits.Count; $i++) {
        if ("$($hits[$i].documentId)" -eq "$($documentIds[$case.expected])") { $rank = $i + 1; break }
    }
    if ($rank -gt 0) {
        $hitCount++
        $reciprocal += (1.0 / $rank)
    }
    Write-Output ("CASE " + $caseNo + " expected=" + $case.expected + " rank=" + $rank + " hits=" + $hits.Count + " server=" + $search.latencyMs + "ms client=" + $sw.ElapsedMilliseconds + "ms")
}

$total = @($eval.cases).Count
$recall = 0.0
$mrr = 0.0
if ($total -gt 0) {
    $recall = [double]$hitCount / $total
    $mrr = $reciprocal / $total
}
$sorted = @($latencies | Sort-Object)
$clientSorted = @($clientLatencies | Sort-Object)
$p95 = 0
$clientP95 = 0
if ($sorted.Count -gt 0) {
    $idx = [int][Math]::Ceiling(0.95 * $sorted.Count) - 1
    if ($idx -lt 0) { $idx = 0 }
    $p95 = [int]$sorted[$idx]
}
if ($clientSorted.Count -gt 0) {
    $cidx = [int][Math]::Ceiling(0.95 * $clientSorted.Count) - 1
    if ($cidx -lt 0) { $cidx = 0 }
    $clientP95 = [int]$clientSorted[$cidx]
}

Write-Output "-----"
Write-Output ("CORPUS_DOCS=" + @($eval.documents).Count + " CASES=" + $total)
Write-Output ("RECALL_AT_3=" + [Math]::Round($recall, 3) + " (" + $hitCount + "/" + $total + ")")
Write-Output ("MRR=" + [Math]::Round($mrr, 3))
Write-Output ("P95_SERVER_MS=" + $p95)
Write-Output ("P95_CLIENT_MS=" + $clientP95)

if ($recall -lt $MinRecall) { Write-Output ("FAIL recall below gate " + $MinRecall); $script:fail++ }
if ($clientP95 -gt $MaxP95Ms) { Write-Output ("FAIL client p95 above gate " + $MaxP95Ms); $script:fail++ }

# ---------------------------------------------------------------- 4. grounded answer on the first query
$first = @($eval.cases)[0]
$r = Invoke-Api 'POST' "/api/v2/kb/spaces/$spaceId/answer" @{ question = $first.query; topK = 3 } $token
Check 'answer.ok' $r.Status 200
$answer = Res $r
CheckTrue 'answer.grounded' $answer.grounded
Check 'answer.extractive' $answer.extractive 'True'
Check 'answer.llmUsed' $answer.llmUsed 'False'
CheckTrue 'answer.citations' (@($answer.citations).Count -ge 1)

Write-Output "-----"
Write-Output "SPACE_ID=$spaceId"
if ($script:fail -gt 0) { Write-Output "RESULT FAIL ($script:fail)"; exit 1 }
Write-Output 'RESULT PASS'
exit 0
