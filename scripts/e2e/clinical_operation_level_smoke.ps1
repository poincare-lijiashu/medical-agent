# ============================================================================
# MedAssist surgery level catalog smoke test (G81).
#
# Covers: catalog list -> invalid level 422 -> unknown code 422
#         -> create default -> update without version 422 -> stale version 409
#         -> update -> doctor (surgery:write only) 403 on catalog write
#         -> plan surgery auto-applies default (3 -> PENDING)
#         -> self approval 422 (four eyes) -> QC approval 200
#         -> delete stale 409 -> delete 200 -> code no longer in catalog
#         -> a code without default still plans unleveled.
#
# ASCII only (Windows PowerShell 5.1 reads BOM-less UTF-8 as ANSI).
# Prerequisites: auth :8081, empi :8082, clinical :8083, gateway :8080
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\e2e\clinical_operation_level_smoke.ps1
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
$deptId = '33333333-3333-3333-3333-333333333333'
# seeded ICD-9-CM-3 code not used by any other smoke, so the default cannot leak
$catalogCode = '81.5401'
$plainCode = '86.2201'

# ---------------------------------------------------------------- 1. login
$r = Invoke-Api 'POST' '/api/v2/auth/login' @{ username = $Username; password = $Password }
Check 'login.admin' $r.Status 200
$token = (Res $r).accessToken
if (-not $token) { Write-Output "FATAL no admin token: $($r.Body)"; exit 1 }

$r = Invoke-Api 'POST' '/api/v2/auth/login' @{ username = 'doctor01'; password = $Password }
Check 'login.doctor' $r.Status 200
$doctorToken = (Res $r).accessToken

$r = Invoke-Api 'POST' '/api/v2/auth/login' @{ username = 'qc01'; password = $Password }
Check 'login.qc' $r.Status 200
$qcToken = (Res $r).accessToken

# ---------------------------------------------------------------- 2. catalog maintenance
$r = Invoke-Api 'GET' '/api/v2/clinical/operation-levels?limit=100' $null $token
Check 'catalog.list' $r.Status 200

$r = Invoke-Api 'PUT' "/api/v2/clinical/operation-levels/$catalogCode" @{ level = 9 } $token
Check 'catalog.invalidLevel.422' $r.Status 422

$r = Invoke-Api 'PUT' '/api/v2/clinical/operation-levels/99.9999' @{ level = 2 } $token
Check 'catalog.unknownCode.422' $r.Status 422

$r = Invoke-Api 'PUT' "/api/v2/clinical/operation-levels/$catalogCode" @{
    level = 3; note = 'default three'
} $token
Check 'catalog.create' $r.Status 200
Check 'catalog.create.level' (Res $r).level 3
Check 'catalog.create.version' (Res $r).version 0
Check 'catalog.create.by' (Res $r).updatedBy 'admin01'

$r = Invoke-Api 'PUT' "/api/v2/clinical/operation-levels/$catalogCode" @{ level = 3 } $token
Check 'catalog.update.noVersion.422' $r.Status 422

$r = Invoke-Api 'PUT' "/api/v2/clinical/operation-levels/$catalogCode" @{
    level = 3; version = 9
} $token
Check 'catalog.update.stale.409' $r.Status 409

$r = Invoke-Api 'PUT' "/api/v2/clinical/operation-levels/$catalogCode" @{
    level = 3; note = 'hospital policy'; version = 0
} $token
Check 'catalog.update' $r.Status 200
Check 'catalog.update.version' (Res $r).version 1

$r = Invoke-Api 'PUT' "/api/v2/clinical/operation-levels/$catalogCode" @{
    level = 2; version = 1
} $doctorToken
Check 'catalog.write.doctor.403' $r.Status 403

$r = Invoke-Api 'GET' "/api/v2/clinical/operation-levels?keyword=$catalogCode" $null $token
Check 'catalog.list.afterCreate' $r.Status 200
$row = @(Res $r) | Where-Object { $_.operationCode -eq $catalogCode }
Check 'catalog.list.contains' (@($row).Count) 1
Check 'catalog.list.name' (@($row)[0].operationName.Length -gt 0) 'True'

# ---------------------------------------------------------------- 3. patient + surgery
$r = Invoke-Api 'POST' '/api/v2/empi/patients' @{
    name = "Level Patient $suffix"; gender = 'MALE'; birthDate = '1968-09-02'
    idType = 'ID_CARD'; idNo = "11010119680902$suffix"
} $token
Check 'patient.create' $r.Status 200
$patientId = (Res $r).id

$r = Invoke-Api 'POST' '/api/v2/clinical/wards' @{
    code = "WARD-LV$suffix"; name = "Level Ward $suffix"; deptId = $deptId
} $token
Check 'ward.create' $r.Status 200
$wardId = (Res $r).id

$r = Invoke-Api 'POST' "/api/v2/clinical/wards/$wardId/beds" @{ bedNos = @('L1') } $token
Check 'bed.create' $r.Status 200
$bedId = (Res $r)[0].id

$r = Invoke-Api 'POST' '/api/v2/clinical/inpatient/admissions' @{
    patientId = $patientId; wardId = $wardId; bedId = $bedId; attendingDoctor = 'doctor01'
} $token
Check 'admission.create' $r.Status 200
$visitId = (Res $r).visitId

$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/surgery-cases" @{
    operationCode = $catalogCode; surgeon = 'doctor01'; anesthesiaType = 'GENERAL'
    asaGrade = 'II'; incisionLevel = 'II'
} $token
Check 'surgery.plan' $r.Status 200
$caseId = (Res $r).id

$r = Invoke-Api 'GET' "/api/v2/clinical/surgery-cases/$caseId/level" $null $doctorToken
Check 'level.autoApplied' $r.Status 200
Check 'level.autoApplied.value' (Res $r).level 3
Check 'level.autoApplied.status' (Res $r).approvalStatus 'PENDING'
Check 'level.autoApplied.by' (Res $r).appliedBy 'admin01'

# auto-applied default is not an approval: the applicant cannot approve it
$r = Invoke-Api 'POST' "/api/v2/clinical/surgery-cases/$caseId/level/approve" @{
    version = 0
} $token
Check 'level.selfApprove.422' $r.Status 422

$r = Invoke-Api 'POST' "/api/v2/clinical/surgery-cases/$caseId/level/approve" @{
    version = 0
} $qcToken
Check 'level.approve' $r.Status 200
Check 'level.approve.status' (Res $r).approvalStatus 'APPROVED'

# ---------------------------------------------------------------- 4. remove default
$r = Invoke-Api 'DELETE' "/api/v2/clinical/operation-levels/${catalogCode}?version=9" $null $token
Check 'catalog.delete.stale.409' $r.Status 409

$r = Invoke-Api 'DELETE' "/api/v2/clinical/operation-levels/${catalogCode}?version=1" $null $token
Check 'catalog.delete' $r.Status 200

$r = Invoke-Api 'GET' "/api/v2/clinical/operation-levels?keyword=$catalogCode" $null $token
Check 'catalog.list.afterDelete' (@(Res $r)).Count 0

# a surgery code without a catalog default keeps the old behaviour (no auto level)
$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/surgery-cases" @{
    operationCode = $plainCode; surgeon = 'doctor01'
} $token
Check 'surgery.plan.noDefault' $r.Status 200
$plainCaseId = (Res $r).id

$r = Invoke-Api 'GET' "/api/v2/clinical/surgery-cases/$plainCaseId/level" $null $doctorToken
Check 'level.none' $r.Status 200
Check 'level.none.data' ($null -eq (Res $r)) 'True'

Write-Output "-----"
Write-Output "VISIT_ID=$visitId"
Write-Output "SURGERY_CASE_ID=$caseId"
if ($script:fail -gt 0) { Write-Output "RESULT FAIL ($script:fail)"; exit 1 }
Write-Output 'RESULT PASS'
exit 0
