# ============================================================================
# MedAssist PACS integration (Worklist / Study status) smoke test, via gateway.
#
# Covers: exam application -> worklist -> study register -> idempotent re-register
#         -> conflicting UID (409) -> invalid UID (422) -> ARRIVED -> illegal jump (409)
#         -> IN_PROGRESS with counts -> COMPLETED -> worklist drops finished study
#         -> query by UID / by application -> unknown UID (404) -> pharmacist (403)
#
# ASCII only (Windows PowerShell 5.1 reads BOM-less UTF-8 as ANSI).
# Prerequisites: auth :8081, empi :8082, tech :8085, gateway :8080
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\e2e\tech_pacs_smoke.ps1
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

function Res($r) { return (($r.Body | ConvertFrom-Json).data) }

$suffix = (Get-Date).ToString('HHmmssff')
$today = (Get-Date).ToString('yyyy-MM-dd')

# ---------------------------------------------------------------- 1. login
$r = Invoke-Api 'POST' '/api/v2/auth/login' @{ username = $Username; password = $Password }
Check 'login' $r.Status 200
$token = (Res $r).accessToken
if (-not $token) { Write-Output "FATAL no token: $($r.Body)"; exit 1 }

# ---------------------------------------------------------------- 2. patient + exam application
$r = Invoke-Api 'POST' '/api/v2/empi/patients' @{
    name = "Pacs Patient $suffix"; gender = 'MALE'; birthDate = '1968-06-06'
    idType = 'ID_CARD'; idNo = "11010119680606$suffix"
} $token
Check 'patient.create' $r.Status 200
$patientId = (Res $r).id

$r = Invoke-Api 'POST' '/api/v2/tech/applications' @{
    patientId = $patientId; itemCode = 'EXM-CT-CHEST'; priority = 'URGENT'
    bodyPart = 'CHEST'; clinicalDiag = 'cough'
} $token
Check 'app.create' $r.Status 200
$appId = (Res $r).application.id
$appNo = (Res $r).application.applicationNo
Check 'app.category' (Res $r).application.category 'EXAM'

# ---------------------------------------------------------------- 3. worklist before registration
$r = Invoke-Api 'GET' "/api/v2/tech/pacs/worklist?modality=CT&date=$today&limit=200" $null $token
Check 'worklist.status' $r.Status 200
$items = @(Res $r)
$mine = @($items | Where-Object { $_.applicationNo -eq $appNo })
Check 'worklist.containsApp' $mine.Count 1
Check 'worklist.modality' $mine[0].modality 'CT'
Check 'worklist.noStudyYet' ($null -eq $mine[0].studyInstanceUid) 'True'

# ---------------------------------------------------------------- 4. register study
$uid = "1.2.826.0.1.3680043.10.999.$suffix"
$r = Invoke-Api 'POST' '/api/v2/tech/pacs/studies' @{
    applicationId = $appId; studyInstanceUid = $uid; bodyPart = 'CHEST'
} $token
Check 'study.register' $r.Status 200
Check 'study.accession' (Res $r).accessionNumber $appNo
Check 'study.modality' (Res $r).modality 'CT'
Check 'study.status' (Res $r).status 'SCHEDULED'

$r = Invoke-Api 'POST' '/api/v2/tech/pacs/studies' @{
    applicationId = $appId; studyInstanceUid = $uid
} $token
Check 'study.register.idempotent' $r.Status 200
Check 'study.register.sameUid' (Res $r).studyInstanceUid $uid

# conflicting UID for the same application
$r = Invoke-Api 'POST' '/api/v2/tech/pacs/studies' @{
    applicationId = $appId; studyInstanceUid = "1.2.826.0.1.3680043.10.999.2.$suffix"
} $token
Check 'study.register.conflict' $r.Status 409

# invalid UID (letters) -> bean validation 422
$r = Invoke-Api 'POST' '/api/v2/tech/pacs/studies' @{
    applicationId = $appId; studyInstanceUid = 'not-a-uid'
} $token
Check 'study.register.invalidUid' $r.Status 422

# ---------------------------------------------------------------- 5. status machine
$r = Invoke-Api 'POST' "/api/v2/tech/pacs/studies/$uid/status" @{ status = 'ARRIVED' } $token
Check 'study.arrived' $r.Status 200
Check 'study.arrivedStatus' (Res $r).status 'ARRIVED'

$r = Invoke-Api 'POST' "/api/v2/tech/pacs/studies/$uid/status" @{ status = 'COMPLETED' } $token
Check 'study.illegalJump' $r.Status 409
Check 'study.illegalJump.code' ((($r.Body | ConvertFrom-Json).code)) 46413

$r = Invoke-Api 'POST' "/api/v2/tech/pacs/studies/$uid/status" @{
    status = 'IN_PROGRESS'; seriesCount = 2; imageCount = 120
} $token
Check 'study.inProgress' $r.Status 200
Check 'study.series' (Res $r).seriesCount 2
Check 'study.images' (Res $r).imageCount 120

$r = Invoke-Api 'POST' "/api/v2/tech/pacs/studies/$uid/status" @{
    status = 'COMPLETED'; seriesCount = 2; imageCount = 128
    viewerUrl = "http://pacs.local/viewer?study=$uid"
} $token
Check 'study.completed' $r.Status 200
Check 'study.completedStatus' (Res $r).status 'COMPLETED'
Check 'study.completedAt' ($null -ne (Res $r).completedAt) 'True'
Check 'study.viewer' ((Res $r).viewerUrl -match 'viewer') 'True'

# ---------------------------------------------------------------- 6. worklist drops finished study
$r = Invoke-Api 'GET' "/api/v2/tech/pacs/worklist?modality=CT&date=$today&limit=200" $null $token
Check 'worklist.afterComplete.status' $r.Status 200
$items = @(Res $r)
$still = @($items | Where-Object { $_.applicationNo -eq $appNo })
Check 'worklist.afterComplete.excluded' $still.Count 0

# ---------------------------------------------------------------- 7. queries
$r = Invoke-Api 'GET' "/api/v2/tech/pacs/studies/$uid" $null $token
Check 'study.byUid' $r.Status 200
Check 'study.byUid.accession' (Res $r).accessionNumber $appNo

$r = Invoke-Api 'GET' "/api/v2/tech/pacs/applications/$appId" $null $token
Check 'study.byApplication' $r.Status 200
Check 'study.byApplication.uid' (Res $r).studyInstanceUid $uid

$r = Invoke-Api 'GET' '/api/v2/tech/pacs/studies/1.2.3.4.5.6' $null $token
Check 'study.unknown' $r.Status 404
Check 'study.unknown.code' ((($r.Body | ConvertFrom-Json).code)) 46406

# ---------------------------------------------------------------- 8. permission
$r = Invoke-Api 'POST' '/api/v2/auth/login' @{ username = 'pharm01'; password = 'Med@2026' }
if ($r.Status -eq 200) {
    $pharmToken = (Res $r).accessToken
    $r = Invoke-Api 'GET' '/api/v2/tech/pacs/worklist' $null $pharmToken
    Check 'worklist.pharmacist.forbidden' $r.Status 403
} else {
    Write-Output "SKIP pharmacist login (status=$($r.Status))"
}

if ($script:fail -gt 0) { Write-Output "FAILED: $script:fail assertion(s)"; exit 1 }
Write-Output "ALL PASSED"
