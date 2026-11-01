# ============================================================================
# MedAssist knowledge-base second batch smoke test (parsing + extractive answer).
#
# Covers: HTML/Markdown ingestion with parse report -> async indexing READY
#         -> extractive answer with citations (grounded / extractive / llmUsed)
#         -> refusal when no evidence (grounded=false, refusedReason)
#         -> PDF upload rejected 422 -> HTML that cleans to nothing 422
#         -> non-member answer 403 -> VIEWER can answer -> search regression
#         -> archived space 409.
#
# ASCII only (Windows PowerShell 5.1 reads BOM-less UTF-8 as ANSI).
# Chinese payloads live in scripts/e2e/fixtures/* and are read as UTF-8.
# Prerequisites: auth :8081, kb :8088, gateway :8080
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\e2e\kb_answer_smoke.ps1
# ============================================================================
param(
    [string]$BaseUrl = "http://127.0.0.1:8080",
    [string]$Username = "admin01",
    [string]$Password = "Med@2026"
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

function Check($name, $actual, $expected) {
    if ("$actual" -eq "$expected") { Write-Output "PASS $name = $actual" }
    else { Write-Output "FAIL $name expected=$expected actual=$actual"; $script:fail++ }
}

function CheckTrue($name, $actual) {
    if ("$actual" -eq 'True') { Write-Output "PASS $name = True" }
    else { Write-Output "FAIL $name expected=True actual=$actual"; $script:fail++ }
}

function Res($r) { return (($r.Body | ConvertFrom-Json).data) }

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
$htmlBody = [IO.File]::ReadAllText((Join-Path $fixtureDir 'kb_document.html'), [Text.Encoding]::UTF8)
$mdBody = [IO.File]::ReadAllText((Join-Path $fixtureDir 'kb_document.md'), [Text.Encoding]::UTF8)
$unrelated = ([IO.File]::ReadAllText((Join-Path $fixtureDir 'kb_query_unrelated.txt'), [Text.Encoding]::UTF8)).Trim()
$question = ([IO.File]::ReadAllText((Join-Path $fixtureDir 'kb_query_answer.txt'), [Text.Encoding]::UTF8)).Trim()
$queryText = ([IO.File]::ReadAllText((Join-Path $fixtureDir 'kb_query.txt'), [Text.Encoding]::UTF8)).Trim()

# ---------------------------------------------------------------- 1. login
$r = Invoke-Api 'POST' '/api/v2/auth/login' @{ username = $Username; password = $Password }
Check 'login' $r.Status 200
$login = Res $r
$token = $login.accessToken
if (-not $token) { Write-Output "FATAL no token: $($r.Body)"; exit 1 }

$r = Invoke-Api 'POST' '/api/v2/auth/login' @{ username = 'doctor01'; password = $Password }
Check 'login.doctor' $r.Status 200
$doctorLogin = Res $r
$doctorToken = $doctorLogin.accessToken
$doctorUserId = $doctorLogin.user.id

# ---------------------------------------------------------------- 2. space
$spaceCode = "KB-ANS-$suffix"
$r = Invoke-Api 'POST' '/api/v2/kb/spaces' @{
    code = $spaceCode; name = "Answer Knowledge $suffix"; visibility = 'PRIVATE'
} $token
Check 'space.create' $r.Status 200
$spaceId = (Res $r).id

# ---------------------------------------------------------------- 3. HTML ingestion
$r = Invoke-Api 'POST' "/api/v2/kb/spaces/$spaceId/documents" @{
    title = "Appendicitis HTML $suffix"; content = $htmlBody; sourceType = 'HTML'
} $token
Check 'html.create' $r.Status 200
$htmlDocId = (Res $r).id
$htmlDoc = Wait-Ready $htmlDocId $token
Check 'html.status' $htmlDoc.status 'READY'
CheckTrue 'html.parseNotes.kind' ("$($htmlDoc.parseNotes)" -like '*HTML*')
CheckTrue 'html.rawGtParsed' ([int]$htmlDoc.rawCharCount -gt [int]$htmlDoc.charCount)
CheckTrue 'html.chunkCount' ([int]$htmlDoc.chunkCount -ge 1)

# ---------------------------------------------------------------- 4. Markdown ingestion
$r = Invoke-Api 'POST' "/api/v2/kb/spaces/$spaceId/documents" @{
    title = "Perioperative MD $suffix"; content = $mdBody; sourceType = 'MARKDOWN'
} $token
Check 'md.create' $r.Status 200
$mdDoc = Wait-Ready ((Res $r).id) $token
Check 'md.status' $mdDoc.status 'READY'
CheckTrue 'md.parseNotes.kind' ("$($mdDoc.parseNotes)" -like '*MARKDOWN*')
CheckTrue 'md.chunkCount' ([int]$mdDoc.chunkCount -ge 1)

# ---------------------------------------------------------------- 5. extractive answer
$r = Invoke-Api 'POST' "/api/v2/kb/spaces/$spaceId/answer" @{
    question = $question; topK = 3
} $token
Check 'answer.ok' $r.Status 200
$answer = Res $r
Check 'answer.grounded' $answer.grounded 'True'
Check 'answer.extractive' $answer.extractive 'True'
Check 'answer.llmUsed' $answer.llmUsed 'False'
Check 'answer.refusedReason' $answer.refusedReason ''
Check 'answer.mode' $answer.mode 'KEYWORD'
Check 'answer.degraded' $answer.degraded 'True'
Check 'answer.degradedReason' $answer.degradedReason 'EMBEDDING_UNAVAILABLE'
CheckTrue 'answer.citations' (@($answer.citations).Count -ge 1)
CheckTrue 'answer.text' ("$($answer.answer)".Length -gt 0)
# answer must be built from the real quote (no generated text)
CheckTrue 'answer.isExtractiveText' ("$($answer.answer)" -like "*$($answer.citations[0].quote)*")
CheckTrue 'answer.citation.range' ([int]$answer.citations[0].charEnd -gt [int]$answer.citations[0].charStart)
CheckTrue 'answer.citation.docId' ("$($answer.citations[0].documentId)".Length -gt 0)
CheckTrue 'answer.latency' ([int]$answer.latencyMs -ge 0)

# ---------------------------------------------------------------- 6. refusal without evidence
$r = Invoke-Api 'POST' "/api/v2/kb/spaces/$spaceId/answer" @{ question = $unrelated } $token
Check 'refusal.ok' $r.Status 200
$refusal = Res $r
Check 'refusal.grounded' $refusal.grounded 'False'
Check 'refusal.reason' $refusal.refusedReason 'NO_EVIDENCE'
Check 'refusal.noCitations' (@($refusal.citations).Count) 0
CheckTrue 'refusal.text' ("$($refusal.answer)".Length -gt 0)

# ---------------------------------------------------------------- 7. unsupported / empty documents
$r = Invoke-Api 'POST' "/api/v2/kb/spaces/$spaceId/documents" @{
    title = 'pdf upload'; content = '%PDF-1.7 binary'; sourceType = 'UPLOAD'; sourceUri = 'guide.pdf'
} $token
Check 'document.pdf.422' $r.Status 422

$r = Invoke-Api 'POST' "/api/v2/kb/spaces/$spaceId/documents" @{
    title = 'blank html'; content = '<html><script>var a = 1</script><style>.x{}</style></html>'; sourceType = 'HTML'
} $token
Check 'document.empty.422' $r.Status 422

# ---------------------------------------------------------------- 8. permissions
$r = Invoke-Api 'POST' "/api/v2/kb/spaces/$spaceId/answer" @{ question = $question } $doctorToken
Check 'answer.nonMember.403' $r.Status 403

$r = Invoke-Api 'POST' "/api/v2/kb/spaces/$spaceId/members" @{
    userId = $doctorUserId; role = 'VIEWER'
} $token
Check 'member.add.viewer' $r.Status 200

$r = Invoke-Api 'POST' "/api/v2/kb/spaces/$spaceId/answer" @{ question = $question } $doctorToken
Check 'answer.viewer.ok' $r.Status 200
Check 'answer.viewer.grounded' (Res $r).grounded 'True'

# ---------------------------------------------------------------- 9. search regression
$r = Invoke-Api 'POST' "/api/v2/kb/spaces/$spaceId/search" @{ query = $queryText; topK = 5 } $token
Check 'search.ok' $r.Status 200
CheckTrue 'search.hits' ([int](Res $r).hitCount -ge 1)

# ---------------------------------------------------------------- 10. archive
$r = Invoke-Api 'POST' "/api/v2/kb/spaces/$spaceId/archive" @{ version = 0 } $token
Check 'space.archive' $r.Status 200
$r = Invoke-Api 'POST' "/api/v2/kb/spaces/$spaceId/answer" @{ question = $question } $token
Check 'space.archived.answer.409' $r.Status 409

Write-Output "-----"
Write-Output "SPACE_ID=$spaceId"
Write-Output "HTML_DOCUMENT_ID=$htmlDocId"
if ($script:fail -gt 0) { Write-Output "RESULT FAIL ($script:fail)"; exit 1 }
Write-Output 'RESULT PASS'
exit 0
