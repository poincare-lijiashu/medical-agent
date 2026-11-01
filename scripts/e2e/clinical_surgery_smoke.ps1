# ============================================================================
# MedAssist surgical domain smoke test (ICD-9-CM-3 catalog + surgery case).
#
# Covers: code catalog search -> plan (inpatient only) -> duplicate 409
#         -> unknown code 404 -> outpatient 422 -> CAS start/complete
#         -> cancel requires reason -> completed cannot cancel
#         -> cancelled record frees the unique slot -> case list
#         -> completed surgery auto-feeds the record homepage procedures
#         -> permission (QC read-only).
#
# ASCII only (Windows PowerShell 5.1 reads BOM-less UTF-8 as ANSI).
# Prerequisites: auth :8081, empi :8082, clinical :8083, emr :8086, gateway :8080
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\e2e\clinical_surgery_smoke.ps1
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
$deptId = '11111111-1111-1111-1111-111111111111'
$plannedAt = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ')

# ---------------------------------------------------------------- 1. login
$r = Invoke-Api 'POST' '/api/v2/auth/login' @{ username = $Username; password = $Password }
Check 'login' $r.Status 200
$token = (Res $r).accessToken
if (-not $token) { Write-Output "FATAL no token: $($r.Body)"; exit 1 }

# medical record QC: read-only for the surgical domain
$r = Invoke-Api 'POST' '/api/v2/auth/login' @{ username = 'qc01'; password = $Password }
Check 'login.qc' $r.Status 200
$qcToken = (Res $r).accessToken

# ---------------------------------------------------------------- 2. catalog
$r = Invoke-Api 'GET' '/api/v2/clinical/operation-catalog?keyword=47&limit=5' $null $token
Check 'catalog.search' $r.Status 200
$hits = @(Res $r)
Check 'catalog.search.nonEmpty' ($hits.Count -gt 0) 'True'
$codes = @($hits | ForEach-Object { $_.code })
Check 'catalog.search.code' ($codes -contains '47.0901') 'True'
Check 'catalog.search.name' (@($hits | Where-Object { $_.code -eq '47.0901' })[0].name.Length -gt 0) 'True'

# ---------------------------------------------------------------- 3. patient + admission
$r = Invoke-Api 'POST' '/api/v2/empi/patients' @{
    name = "Surgery Patient $suffix"; gender = 'MALE'; birthDate = '1970-03-12'
    idType = 'ID_CARD'; idNo = "11010119700312$suffix"
} $token
Check 'patient.create' $r.Status 200
$patientId = (Res $r).id

$r = Invoke-Api 'POST' '/api/v2/clinical/wards' @{
    code = "WARD-SU$suffix"; name = "Surgery Ward $suffix"; deptId = $deptId
} $token
Check 'ward.create' $r.Status 200
$wardId = (Res $r).id

$r = Invoke-Api 'POST' "/api/v2/clinical/wards/$wardId/beds" @{ bedNos = @('S1') } $token
Check 'bed.create' $r.Status 200
$bedId = (Res $r)[0].id

$r = Invoke-Api 'POST' '/api/v2/clinical/inpatient/admissions' @{
    patientId = $patientId; wardId = $wardId; bedId = $bedId; attendingDoctor = 'doctor01'
} $token
Check 'admission.create' $r.Status 200
$admissionId = (Res $r).id
$visitId = (Res $r).visitId

# ---------------------------------------------------------------- 4. plan
$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/surgery-cases" @{
    operationCode = '47.0901'; plannedAt = $plannedAt; surgeon = 'doctor01'
    anesthesiaType = 'GENERAL'; asaGrade = 'II'; incisionLevel = 'II'
} $token
Check 'surgery.plan' $r.Status 200
$caseId = (Res $r).id
$caseNo = (Res $r).caseNo
Check 'surgery.plan.status' (Res $r).status 'PLANNED'
Check 'surgery.plan.noPrefix' ($caseNo.StartsWith('OP')) 'True'
Check 'surgery.plan.nameSnapshot' ((Res $r).operationName.Length -gt 0) 'True'
Check 'surgery.plan.admissionId' (Res $r).admissionId $admissionId

# same visit + same operation -> 409 (unique index excludes CANCELLED only)
$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/surgery-cases" @{
    operationCode = '47.0901'; surgeon = 'doctor01'
} $token
Check 'surgery.plan.duplicate.409' $r.Status 409

$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/surgery-cases" @{
    operationCode = '99.9999'
} $token
Check 'surgery.plan.unknownCode.404' $r.Status 404

# outpatient visits can never own a surgery case
$r = Invoke-Api 'POST' '/api/v2/clinical/registrations' @{
    patientId = $patientId; deptId = '22222222-2222-2222-2222-222222222222'
    doctorId = 'admin01'; visitType = 'OUTPATIENT'
} $token
Check 'outpatient.registration' $r.Status 200
$outpatientVisit = (Res $r).id
$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$outpatientVisit/surgery-cases" @{
    operationCode = '47.0901'
} $token
Check 'surgery.plan.outpatient.422' $r.Status 422

# ---------------------------------------------------------------- 5. state machine
$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/surgery-cases/$caseId/start" @{
    version = 99
} $token
Check 'surgery.start.stale.409' $r.Status 409

$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/surgery-cases/$caseId/start" @{
    version = 0
} $token
Check 'surgery.start' $r.Status 200
Check 'surgery.start.status' (Res $r).status 'IN_PROGRESS'

$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/surgery-cases/$caseId/complete" @{
    version = 0
} $token
Check 'surgery.complete.stale.409' $r.Status 409

$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/surgery-cases/$caseId/complete" @{
    version = 1
} $token
Check 'surgery.complete' $r.Status 200
Check 'surgery.complete.status' (Res $r).status 'COMPLETED'
Check 'surgery.complete.endedAt' ((Res $r).endedAt.Length -gt 0) 'True'

$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/surgery-cases/$caseId/cancel" @{
    version = 2; reason = 'too late'
} $token
Check 'surgery.cancel.completed.409' $r.Status 409

# ---------------------------------------------------------------- 6. cancel + re-plan
$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/surgery-cases" @{
    operationCode = '88.0100'; surgeon = 'doctor01'; anesthesiaType = 'LOCAL'; incisionLevel = 'II'
} $token
Check 'surgery.plan.second' $r.Status 200
$secondId = (Res $r).id

$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/surgery-cases/$secondId/cancel" @{
    version = 0; reason = '   '
} $token
Check 'surgery.cancel.blankReason.422' $r.Status 422

$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/surgery-cases/$secondId/cancel" @{
    version = 0; reason = 'patient refused'
} $token
Check 'surgery.cancel' $r.Status 200
Check 'surgery.cancel.status' (Res $r).status 'CANCELLED'
Check 'surgery.cancel.reason' (Res $r).cancelReason 'patient refused'

# cancelled slot is free again: same operation can be planned a second time
$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/surgery-cases" @{
    operationCode = '88.0100'; surgeon = 'doctor01'
} $token
Check 'surgery.replanAfterCancel' $r.Status 200

$r = Invoke-Api 'GET' "/api/v2/clinical/visits/$visitId/surgery-cases" $null $token
Check 'surgery.list' $r.Status 200
Check 'surgery.list.count' (@(Res $r).Count) 3

# ---------------------------------------------------------------- 7. homepage linkage
$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/orders" @{
    items = @(@{ itemCode = 'TRT-IV'; quantity = 1; orderMode = 'TEMP' })
} $token
Check 'orders.place' $r.Status 200

$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/settle" @{ paymentMethod = 'CASH' } $token
Check 'settle.ok' $r.Status 200

$r = Invoke-Api 'POST' "/api/v2/clinical/inpatient/admissions/$admissionId/discharge" $null $token
Check 'discharge.ok' $r.Status 200

$r = Invoke-Api 'POST' '/api/v2/emr/homepages' @{ visitId = $visitId } $token
Check 'homepage.create' $r.Status 200
$hp = Res $r
Check 'homepage.procedures.onlyCompleted' (@($hp.procedures).Count) 1
Check 'homepage.procedures.code' (@($hp.procedures)[0].code) '47.0901'
Check 'homepage.procedures.surgeon' (@($hp.procedures)[0].surgeon) 'doctor01'
Check 'homepage.procedures.incision' (@($hp.procedures)[0].incisionLevel) 'II'
Check 'homepage.procedures.date' ((@($hp.procedures)[0].date).Length -ge 10) 'True'

# ---------------------------------------------------------------- 8. permission
$r = Invoke-Api 'GET' "/api/v2/clinical/visits/$visitId/surgery-cases" $null $qcToken
Check 'perm.qc.read' $r.Status 200

$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/surgery-cases" @{
    operationCode = '47.0901'
} $qcToken
Check 'perm.qc.write.403' $r.Status 403

Write-Output "-----"
Write-Output "VISIT_ID=$visitId"
Write-Output "SURGERY_CASE_ID=$caseId"
Write-Output "SURGERY_CASE_NO=$caseNo"
if ($script:fail -gt 0) { Write-Output "RESULT FAIL ($script:fail)"; exit 1 }
Write-Output 'RESULT PASS'
exit 0
