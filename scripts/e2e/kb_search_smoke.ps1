# ============================================================================
# MedAssist knowledge-base smoke test (spaces / documents / retrieval).
#
# Covers: space creation (creator becomes OWNER) -> list -> document ingest
#         -> async indexing reaches READY -> keyword retrieval with citation
#         -> topK clamp -> blank query 422 -> duplicate content 409
#         -> cross-space isolation 403 -> VIEWER cannot write
#         -> PUBLIC space readable by non-member -> stats -> archived space 409.
#
# ASCII only (Windows PowerShell 5.1 reads BOM-less UTF-8 as ANSI).
# Chinese payloads live in scripts/e2e/fixtures/*.txt and are read as UTF-8.
# Prerequisites: auth :8081, kb :8088, gateway :8080
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\e2e\kb_search_smoke.ps1
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

# wait until the async indexer marks the document READY (dev worker polls every 1s)
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
$docBody = [IO.File]::ReadAllText((Join-Path $fixtureDir 'kb_document.txt'), [Text.Encoding]::UTF8)
$queryText = ([IO.File]::ReadAllText((Join-Path $fixtureDir 'kb_query.txt'), [Text.Encoding]::UTF8)).Trim()

# ---------------------------------------------------------------- 1. login
$r = Invoke-Api 'POST' '/api/v2/auth/login' @{ username = $Username; password = $Password }
Check 'login' $r.Status 200
$login = Res $r
$token = $login.accessToken
$adminUserId = $login.user.id
if (-not $token) { Write-Output "FATAL no token: $($r.Body)"; exit 1 }

$r = Invoke-Api 'POST' '/api/v2/auth/login' @{ username = 'doctor01'; password = $Password }
Check 'login.doctor' $r.Status 200
$doctorLogin = Res $r
$doctorToken = $doctorLogin.accessToken
$doctorUserId = $doctorLogin.user.id

# ---------------------------------------------------------------- 2. space
$spaceCode = "KB-ADM-$suffix"
$r = Invoke-Api 'POST' '/api/v2/kb/spaces' @{
    code = $spaceCode; name = "Admin Knowledge $suffix"; visibility = 'PRIVATE'
} $token
Check 'space.create' $r.Status 200
$spaceId = (Res $r).id
Check 'space.create.status' (Res $r).status 'ACTIVE'
Check 'space.create.version' (Res $r).version 0

$r = Invoke-Api 'POST' '/api/v2/kb/spaces' @{ code = $spaceCode; name = 'dup' } $token
Check 'space.create.duplicate.409' $r.Status 409

$r = Invoke-Api 'GET' '/api/v2/kb/spaces' $null $token
Check 'space.list' $r.Status 200
$spaceIds = @((Res $r) | ForEach-Object { "$($_.id)" })
CheckTrue 'space.list.contains' ($spaceIds -contains "$spaceId")

# ---------------------------------------------------------------- 3. document + async index
$content = $docBody + "`nrun=" + $suffix
$r = Invoke-Api 'POST' "/api/v2/kb/spaces/$spaceId/documents" @{
    title = "Appendicitis guideline $suffix"; content = $content; sourceType = 'MANUAL'
} $token
Check 'document.create' $r.Status 200
$documentId = (Res $r).id
Check 'document.create.status' (Res $r).status 'PENDING'

$doc = Wait-Ready $documentId $token
CheckTrue 'document.indexed' ($null -ne $doc)
Check 'document.status' $doc.status 'READY'
CheckTrue 'document.chunkCount' ([int]$doc.chunkCount -ge 1)
CheckTrue 'document.keywordOnlyWarning' ("$($doc.errorMessage)".Length -gt 0)

# ---------------------------------------------------------------- 4. retrieval
$r = Invoke-Api 'POST' "/api/v2/kb/spaces/$spaceId/search" @{
    query = $queryText; topK = 5
} $token
Check 'search.ok' $r.Status 200
$search = Res $r
Check 'search.mode' $search.mode 'KEYWORD'
Check 'search.vectorUsed' $search.vectorUsed 'False'
Check 'search.degraded' $search.degraded 'True'
Check 'search.degradedReason' $search.degradedReason 'EMBEDDING_UNAVAILABLE'
CheckTrue 'search.hitCount' ([int]$search.hitCount -ge 1)
CheckTrue 'search.snippet' ("$($search.hits[0].snippet)".Length -gt 0)
CheckTrue 'search.citation' ("$($search.hits[0].citation)".Length -gt 0)
Check 'search.matchedBy' $search.hits[0].matchedBy 'KEYWORD'
CheckTrue 'search.charRange' ([int]$search.hits[0].charEnd -gt 0)

$r = Invoke-Api 'POST' "/api/v2/kb/spaces/$spaceId/search" @{
    query = $queryText; topK = 1
} $token
Check 'search.topK1' $r.Status 200
CheckTrue 'search.topK1.count' (@((Res $r).hits).Count -le 1)

$r = Invoke-Api 'POST' "/api/v2/kb/spaces/$spaceId/search" @{ query = ',.;!' } $token
Check 'search.blank.422' $r.Status 422

# ---------------------------------------------------------------- 5. duplicate + isolation
$r = Invoke-Api 'POST' "/api/v2/kb/spaces/$spaceId/documents" @{
    title = 'duplicate'; content = $content
} $token
Check 'document.duplicate.409' $r.Status 409

$r = Invoke-Api 'GET' "/api/v2/kb/spaces/$spaceId/documents" $null $doctorToken
Check 'isolation.notMember.403' $r.Status 403

$r = Invoke-Api 'POST' "/api/v2/kb/spaces/$spaceId/members" @{
    userId = $doctorUserId; role = 'VIEWER'
} $token
Check 'member.add.viewer' $r.Status 200

$r = Invoke-Api 'GET' "/api/v2/kb/spaces/$spaceId/documents" $null $doctorToken
Check 'member.viewer.read' $r.Status 200

$r = Invoke-Api 'POST' "/api/v2/kb/spaces/$spaceId/documents" @{
    title = 'viewer write'; content = $content + ' viewer'
} $doctorToken
Check 'member.viewer.write.403' $r.Status 403

$r = Invoke-Api 'POST' "/api/v2/kb/spaces/$spaceId/search" @{ query = $queryText } $doctorToken
Check 'member.viewer.search' $r.Status 200

# ---------------------------------------------------------------- 6. public space
$publicCode = "KB-PUB-$suffix"
$r = Invoke-Api 'POST' '/api/v2/kb/spaces' @{
    code = $publicCode; name = "Public Knowledge $suffix"; visibility = 'PUBLIC'
} $doctorToken
Check 'public.create' $r.Status 200
$publicSpaceId = (Res $r).id

$r = Invoke-Api 'POST' "/api/v2/kb/spaces/$publicSpaceId/documents" @{
    title = "Public guideline $suffix"; content = $docBody + "`npublic=" + $suffix
} $doctorToken
Check 'public.document.create' $r.Status 200
$publicDoc = Wait-Ready ((Res $r).id) $doctorToken
Check 'public.document.status' $publicDoc.status 'READY'

$r = Invoke-Api 'POST' "/api/v2/kb/spaces/$publicSpaceId/search" @{ query = $queryText } $token
Check 'public.search.byNonMember' $r.Status 200
CheckTrue 'public.search.hits' (((Res $r).hitCount) -ge 1)

# ---------------------------------------------------------------- 7. stats + archive
$r = Invoke-Api 'GET' "/api/v2/kb/spaces/$spaceId/stats" $null $token
Check 'stats.ok' $r.Status 200
$stats = Res $r
CheckTrue 'stats.documents' ([int]$stats.documents -ge 1)
CheckTrue 'stats.chunks' ([int]$stats.chunks -ge 1)
Check 'stats.indexedChunks' $stats.indexedChunks 0

$r = Invoke-Api 'POST' "/api/v2/kb/spaces/$spaceId/archive" @{ version = 0 } $token
Check 'space.archive' $r.Status 200
Check 'space.archive.status' (Res $r).status 'ARCHIVED'

$r = Invoke-Api 'POST' "/api/v2/kb/spaces/$spaceId/search" @{ query = $queryText } $token
Check 'space.archived.search.409' $r.Status 409

Write-Output "-----"
Write-Output "SPACE_ID=$spaceId"
Write-Output "PUBLIC_SPACE_ID=$publicSpaceId"
Write-Output "DOCUMENT_ID=$documentId"
if ($script:fail -gt 0) { Write-Output "RESULT FAIL ($script:fail)"; exit 1 }
Write-Output 'RESULT PASS'
exit 0
