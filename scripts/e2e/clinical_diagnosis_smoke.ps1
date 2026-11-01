# ============================================================================
# MedAssist structured diagnosis (ICD-10) smoke test (through gateway).
#
# Covers:
#   * diagnosis catalog search (GET /clinical/diagnoses?keyword=)
#   * add primary + secondary diagnoses to a visit
#   * only ONE primary diagnosis per visit (second primary -> 409)
#   * switch primary diagnosis (old primary demoted, new one promoted)
#   * duplicate code in the same visit -> 409; unknown code -> 404
#   * remove a diagnosis; finished visit rejects further changes (409)
#
# ASCII only on purpose: Windows PowerShell 5.1 reads BOM-less UTF-8 as ANSI.
#
# Prerequisites: auth :8081, empi :8082, clinical :8083, gateway :8080
# Usage (repo root):
#   powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\e2e\clinical_diagnosis_smoke.ps1
# ============================================================================
param(
    [string]$BaseUrl = "http://127.0.0.1:8080",
    [string]$Username = "admin01",
    [string]$Password = "Med@2026",
    [string]$DeptId = "11111111-1111-1111-1111-111111111111"
)

$ErrorActionPreference = "Stop"
$script:fail = 0

function Invoke-Api {
    param([string]$Method, [string]$Path, $Body = $null, [string]$Token = $null, [string]$Base = $null)
    if (-not $Base) { $Base = $BaseUrl }
    $out = [IO.Path]::GetTempFileName()
    $args = @('--noproxy', '*', '-s', '-o', $out, '-w', '%{http_code}', '-X', $Method, ($Base + $Path))
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
function Body($r) { return ($r.Body | ConvertFrom-Json) }

$suffix = Get-Random -Minimum 1000 -Maximum 9999

# ---------------------------------------------------------------- 1. login
$r = Invoke-Api 'POST' '/api/v2/auth/login' @{ username = $Username; password = $Password }
Check 'login' $r.Status 200
$token = (Res $r).accessToken
if (-not $token) { Write-Output "FATAL no token: $($r.Body)"; exit 1 }

# ---------------------------------------------------------------- 2. patient + visit
$r = Invoke-Api 'POST' '/api/v2/empi/patients' @{
    name = "Diag Patient $suffix"; gender = 'MALE'; birthDate = '1972-11-03'
    idType = 'ID_CARD'; idNo = "11010119721103$suffix"
} $token
Check 'patient.create' $r.Status 200
$patientId = (Res $r).id

$r = Invoke-Api 'POST' '/api/v2/clinical/registrations' @{
    patientId = $patientId; deptId = $DeptId; doctorId = 'doctor01'
} $token
Check 'visit.register' $r.Status 200
$visitId = (Res $r).id

$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/start" @{} $token
Check 'visit.start' $r.Status 200

# ---------------------------------------------------------------- 3. catalog search
$r = Invoke-Api 'GET' '/api/v2/clinical/diagnoses?keyword=J06' $null $token
Check 'diag.catalog' $r.Status 200
$catalog = @(Res $r)
Check 'diag.catalog.count' $catalog.Count 1
Check 'diag.catalog.code' $catalog[0].code 'J06.900'

# ---------------------------------------------------------------- 4. add primary + secondary
$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/diagnoses" @{
    code = 'J06.900'; primary = $true
} $token
Check 'diag.add.primary' $r.Status 200
$list = @(Res $r)
Check 'diag.add.primary.count' $list.Count 1
Check 'diag.add.primary.flag' $list[0].primaryFlag True
$primaryId = $list[0].id

$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/diagnoses" @{
    code = 'I10.x00'; primary = $false
} $token
Check 'diag.add.secondary' $r.Status 200
$list = @(Res $r)
Check 'diag.add.secondary.count' $list.Count 2
$secondaryId = ($list | Where-Object { $_.code -eq 'I10.x00' }).id

# ---------------------------------------------------------------- 5. only one primary
$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/diagnoses" @{
    code = 'E11.900'; primary = $true
} $token
Check 'diag.secondPrimary.409' $r.Status 409

# too many diagnoses? no - now switch the primary
$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/diagnoses/$secondaryId/primary" @{} $token
Check 'diag.setPrimary' $r.Status 200
$list = @(Res $r)
Check 'diag.setPrimary.newPrimary' ($list | Where-Object { $_.code -eq 'I10.x00' }).primaryFlag True
Check 'diag.setPrimary.oldPrimary' ($list | Where-Object { $_.code -eq 'J06.900' }).primaryFlag False
Check 'diag.setPrimary.primaryCount' @($list | Where-Object { $_.primaryFlag }).Count 1

# ---------------------------------------------------------------- 6. duplicate / unknown code
$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/diagnoses" @{
    code = 'J06.900'; primary = $false
} $token
Check 'diag.duplicate.409' $r.Status 409

$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/diagnoses" @{
    code = 'X99.999'; primary = $false
} $token
Check 'diag.unknown.404' $r.Status 404

# ---------------------------------------------------------------- 7. remove a diagnosis
$r = Invoke-Api 'DELETE' "/api/v2/clinical/visits/$visitId/diagnoses/$primaryId" $null $token
Check 'diag.remove' $r.Status 200
Check 'diag.remove.count' @(Res $r).Count 1
Check 'diag.remove.remaining' (Res $r)[0].code 'I10.x00'

# ---------------------------------------------------------------- 8. finished visit is read-only
$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/finish" @{} $token
Check 'visit.finish' $r.Status 200
Check 'visit.finished.status' (Res $r).status 'FINISHED'

$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/diagnoses" @{
    code = 'E11.900'; primary = $false
} $token
Check 'diag.finished.409' $r.Status 409

$r = Invoke-Api 'GET' "/api/v2/clinical/visits/$visitId/diagnoses" $null $token
Check 'diag.list.afterFinish' $r.Status 200
Check 'diag.list.afterFinish.count' @(Res $r).Count 1

Write-Output "-----"
Write-Output "VISIT_ID=$visitId"
Write-Output "PRIMARY_DIAGNOSIS=$secondaryId"
if ($script:fail -gt 0) { Write-Output "RESULT FAIL ($script:fail)"; exit 1 }
Write-Output 'RESULT PASS'
exit 0
