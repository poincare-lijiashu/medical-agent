# ============================================================================
# MedAssist DRG grouping smoke test (real diagnosis + homepage procedures).
#
# Covers: discharged inpatient -> clinical diagnosis + homepage procedure input
#         -> scheme/groups/rules -> simulate WITHOUT explicit codes (auto source)
#         -> source flags (CLINICAL / HOMEPAGE) -> manual override (MANUAL)
#         -> visit without diagnosis 422 -> permission (doctor read-only).
#
# ASCII only (Windows PowerShell 5.1 reads BOM-less UTF-8 as ANSI).
# Prerequisites: auth :8081, empi :8082, clinical :8083, emr :8086,
#                insurance :8087, gateway :8080
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\e2e\insurance_drg_smoke.ps1
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

function CheckNum($name, $actual, $expected) {
    if ($null -eq $actual -or "$actual".Trim() -eq '') {
        Write-Output "FAIL $name expected=$expected actual=<null>"
        $script:fail++
        return
    }
    if ([decimal]$actual -eq [decimal]$expected) { Write-Output "PASS $name = $actual" }
    else { Write-Output "FAIL $name expected=$expected actual=$actual"; $script:fail++ }
}

function Res($r) { return (($r.Body | ConvertFrom-Json).data) }

$suffix = (Get-Date).ToString('HHmmssff')
$today = (Get-Date).ToString('yyyy-MM-dd')
$deptId = '11111111-1111-1111-1111-111111111111'

# ---------------------------------------------------------------- 1. login
$r = Invoke-Api 'POST' '/api/v2/auth/login' @{ username = $Username; password = $Password }
Check 'login' $r.Status 200
$token = (Res $r).accessToken
if (-not $token) { Write-Output "FATAL no token: $($r.Body)"; exit 1 }

$r = Invoke-Api 'POST' '/api/v2/auth/login' @{ username = 'doctor01'; password = $Password }
Check 'login.doctor' $r.Status 200
$doctorToken = (Res $r).accessToken

# ---------------------------------------------------------------- 2. discharged inpatient with diagnosis
$r = Invoke-Api 'POST' '/api/v2/empi/patients' @{
    name = "Drg Patient $suffix"; gender = 'MALE'; birthDate = '1962-07-08'
    idType = 'ID_CARD'; idNo = "11010119620708$suffix"
} $token
Check 'patient.create' $r.Status 200
$patientId = (Res $r).id

$r = Invoke-Api 'POST' '/api/v2/clinical/wards' @{
    code = "WARD-DRG$suffix"; name = "Drg Ward $suffix"; deptId = $deptId
} $token
Check 'ward.create' $r.Status 200
$wardId = (Res $r).id

$r = Invoke-Api 'POST' "/api/v2/clinical/wards/$wardId/beds" @{ bedNos = @('D1') } $token
Check 'bed.create' $r.Status 200
$bedId = (Res $r)[0].id

$r = Invoke-Api 'POST' '/api/v2/clinical/inpatient/admissions' @{
    patientId = $patientId; wardId = $wardId; bedId = $bedId; attendingDoctor = 'doctor01'
} $token
Check 'admission.create' $r.Status 200
$admissionId = (Res $r).id
$visitId = (Res $r).visitId

$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/diagnoses" @{
    code = 'J18.900'; primary = $true
} $token
Check 'diagnosis.primary' $r.Status 200

$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/orders" @{
    items = @(@{ itemCode = 'TRT-IV'; quantity = 1; orderMode = 'TEMP' })
} $token
Check 'orders.place' $r.Status 200

$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/settle" @{ paymentMethod = 'CASH' } $token
Check 'settle.ok' $r.Status 200

$r = Invoke-Api 'POST' "/api/v2/clinical/inpatient/admissions/$admissionId/discharge" $null $token
Check 'discharge.ok' $r.Status 200

# ---------------------------------------------------------------- 3. homepage with a procedure
$r = Invoke-Api 'POST' '/api/v2/emr/homepages' @{ visitId = $visitId } $token
Check 'homepage.create' $r.Status 200
$homepageId = (Res $r).homepage.id
$hpVersion = (Res $r).homepage.version

$r = Invoke-Api 'PUT' "/api/v2/emr/homepages/$homepageId" @{
    version = $hpVersion
    procedures = @(
        @{ code = '88.0100'; name = 'Lung biopsy'; date = $null
           surgeon = 'doctor01'; anesthesia = 'LOCAL'; incisionLevel = 'II' }
    )
} $token
Check 'homepage.procedure' $r.Status 200
Check 'homepage.procedureCount' (@((Res $r).procedures).Count) 1

# R6-MF-14: grouping only reads ARCHIVED homepages, so complete + submit + archive it first.
$hpVersion = (Res $r).homepage.version
$r = Invoke-Api 'PUT' "/api/v2/emr/homepages/$homepageId" @{
    version = $hpVersion; dischargeMode = 'BY_DOCTOR'
    chiefPhysician = 'chief01'; residentPhysician = 'resident01'; coder = 'coder01'
    procedures = @(
        @{ code = '88.0100'; name = 'Lung biopsy'; date = $null
           surgeon = 'doctor01'; anesthesia = 'LOCAL'; incisionLevel = 'II' }
    )
} $token
Check 'homepage.complete' $r.Status 200

$r = Invoke-Api 'POST' "/api/v2/emr/homepages/$homepageId/submit" $null $token
Check 'homepage.submit' $r.Status 200

$r = Invoke-Api 'POST' '/api/v2/auth/login' @{ username = 'qc01'; password = $Password }
Check 'login.qc' $r.Status 200
$qcToken = (Res $r).accessToken

$r = Invoke-Api 'POST' "/api/v2/emr/homepages/$homepageId/archive" $null $qcToken
Check 'homepage.archive' $r.Status 200
Check 'homepage.archive.status' (Res $r).homepage.status 'ARCHIVED'

# ---------------------------------------------------------------- 4. scheme + groups + rules
$schemeCode = "DRG-$suffix"
$r = Invoke-Api 'POST' '/api/v2/insurance/grouping/schemes' @{
    code = $schemeCode; name = 'DRG auto-source'; type = 'DRG'
    rateAmount = 100.00; rateEffectiveFrom = $today; enabled = $true
} $token
Check 'scheme.create' $r.Status 200

$r = Invoke-Api 'POST' "/api/v2/insurance/grouping/schemes/$schemeCode/groups/import" @{
    groups = @(
        @{ groupCode = 'GB1'; groupName = 'Respiratory'; weight = 1.5 },
        @{ groupCode = 'GB2'; groupName = 'Respiratory with procedure'; weight = 2.0 }
    )
} $token
Check 'groups.import' $r.Status 200
Check 'groups.created' (Res $r).created 2

# priority 1 needs BOTH diagnosis and procedure -> proves the homepage procedure was used
$r = Invoke-Api 'POST' "/api/v2/insurance/grouping/schemes/$schemeCode/rules/import" @{
    rules = @(
        @{ priority = 1; matchType = 'DIAG_AND_PROC'; diagPrefix = 'J18'; procPrefix = '88.01'; groupCode = 'GB2' },
        @{ priority = 2; matchType = 'DIAGNOSIS'; diagPrefix = 'J18'; groupCode = 'GB1' }
    )
} $token
Check 'rules.import' $r.Status 200
Check 'rules.created' (Res $r).created 2

# ---------------------------------------------------------------- 5. auto-sourced simulation
$r = Invoke-Api 'POST' '/api/v2/insurance/grouping/simulate' @{
    visitId = $visitId; schemeCode = $schemeCode
} $token
Check 'simulate.auto' $r.Status 200
$sim = Res $r
Check 'simulate.auto.matched' $sim.matched 'True'
Check 'simulate.auto.group' $sim.groupCode 'GB2'
CheckNum 'simulate.auto.payment' $sim.paymentStandard 200.00
Check 'simulate.auto.diagSource' $sim.diagnosesSource 'CLINICAL'
Check 'simulate.auto.procSource' $sim.proceduresSource 'HOMEPAGE'
Check 'simulate.auto.diagCode' ($sim.diagnoses -match 'J18.900') 'True'
Check 'simulate.auto.procCode' ($sim.procedures -match '88.0100') 'True'
Check 'simulate.auto.hash' $sim.inputHash.Length 64

$r = Invoke-Api 'GET' "/api/v2/insurance/grouping/by-visit/$visitId" $null $token
Check 'simulate.latest' $r.Status 200
Check 'simulate.latest.group' (Res $r).groupCode 'GB2'
Check 'simulate.latest.procSource' (Res $r).proceduresSource 'HOMEPAGE'

# ---------------------------------------------------------------- 6. manual override
$r = Invoke-Api 'POST' '/api/v2/insurance/grouping/simulate' @{
    visitId = $visitId; schemeCode = $schemeCode
    diagnoses = @('J18.900'); procedures = @()
} $token
Check 'simulate.manual' $r.Status 200
$manual = Res $r
Check 'simulate.manual.group' $manual.groupCode 'GB1'
Check 'simulate.manual.diagSource' $manual.diagnosesSource 'MANUAL'
CheckNum 'simulate.manual.payment' $manual.paymentStandard 150.00

$r = Invoke-Api 'POST' '/api/v2/insurance/grouping/simulate' @{
    visitId = $visitId; schemeCode = $schemeCode
    diagnoses = @('K35.900'); procedures = @()
} $token
Check 'simulate.unmatched' $r.Status 200
Check 'simulate.unmatched.matched' (Res $r).matched 'False'
Check 'simulate.unmatched.reason' ((Res $r).reason.Length -gt 0) 'True'

# ---------------------------------------------------------------- 7. visit without diagnosis -> 422
$r = Invoke-Api 'POST' '/api/v2/empi/patients' @{
    name = "Drg NoDx $suffix"; gender = 'FEMALE'; birthDate = '1970-02-02'
    idType = 'ID_CARD'; idNo = "11010119700202$suffix"
} $token
Check 'nodx.patient.create' $r.Status 200
$nodxPatient = (Res $r).id

$r = Invoke-Api 'POST' '/api/v2/clinical/inpatient/admissions' @{
    patientId = $nodxPatient; wardId = $wardId; bedId = $bedId; attendingDoctor = 'doctor01'
} $token
Check 'nodx.admission.create' $r.Status 200
$nodxVisit = (Res $r).visitId

$r = Invoke-Api 'POST' '/api/v2/insurance/grouping/simulate' @{
    visitId = $nodxVisit; schemeCode = $schemeCode
} $token
Check 'simulate.noDiagnosis.422' $r.Status 422

# ---------------------------------------------------------------- 8. permission
$r = Invoke-Api 'POST' '/api/v2/insurance/grouping/simulate' @{
    visitId = $visitId; schemeCode = $schemeCode
} $doctorToken
Check 'perm.doctor.simulate' $r.Status 200

$r = Invoke-Api 'POST' '/api/v2/insurance/grouping/schemes' @{
    code = "DRG-DOC-$suffix"; name = 'doc'; type = 'DRG'
    rateAmount = 1; rateEffectiveFrom = $today
} $doctorToken
Check 'perm.doctor.scheme.403' $r.Status 403

Write-Output "-----"
Write-Output "VISIT_ID=$visitId"
Write-Output "HOMEPAGE_ID=$homepageId"
Write-Output "SCHEME=$schemeCode"
if ($script:fail -gt 0) { Write-Output "RESULT FAIL ($script:fail)"; exit 1 }
Write-Output 'RESULT PASS'
exit 0
