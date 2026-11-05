# ============================================================================
# MedAssist EMR record-homepage smoke test, through the gateway.
#
# Covers: discharged inpatient -> homepage generation (diagnosis/fee snapshot)
#         -> duplicate 409 -> non-inpatient 409 -> draft补录 -> incomplete submit 409
#         -> submit -> stale version 409 -> four-eyes archive 409 -> archive
#         -> print watermark -> permission (doctor read-only).
#
# ASCII only (Windows PowerShell 5.1 reads BOM-less UTF-8 as ANSI).
# Prerequisites: auth :8081, empi :8082, clinical :8083, emr :8086, gateway :8080
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\e2e\emr_homepage_smoke.ps1
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

# JSON numbers come back without trailing zeros (38.00 -> 38).
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

# timestamp-based suffix: random 4-digit values collide on idNo/ward unique indexes within a day
$suffix = (Get-Date).ToString('HHmmssff')

# ---------------------------------------------------------------- 1. login
$r = Invoke-Api 'POST' '/api/v2/auth/login' @{ username = $Username; password = $Password }
Check 'login' $r.Status 200
$token = (Res $r).accessToken
if (-not $token) { Write-Output "FATAL no token: $($r.Body)"; exit 1 }

# medical record QC: archives the homepage (four-eyes principle)
$r = Invoke-Api 'POST' '/api/v2/auth/login' @{ username = 'qc01'; password = $Password }
Check 'login.qc' $r.Status 200
$qcToken = (Res $r).accessToken
if (-not $qcToken) { Write-Output "FATAL no qc token: $($r.Body)"; exit 1 }

# doctor: homepage is read-only (病案室 maintains it)
$r = Invoke-Api 'POST' '/api/v2/auth/login' @{ username = 'doctor01'; password = $Password }
Check 'login.doctor' $r.Status 200
$doctorToken = (Res $r).accessToken

# ---------------------------------------------------------------- 2. patient + admission
$r = Invoke-Api 'POST' '/api/v2/empi/patients' @{
    name = "Homepage Patient $suffix"; gender = 'MALE'; birthDate = '1958-04-05'
    idType = 'ID_CARD'; idNo = "11010119580405$suffix"
} $token
Check 'patient.create' $r.Status 200
$patientId = (Res $r).id

# ward dept must be one doctor01 is bound to, otherwise the dept+campus guard
# (correctly) hides the homepage from the read-only doctor check at the end.
$deptId = '33333333-3333-3333-3333-333333333333'
$r = Invoke-Api 'POST' '/api/v2/clinical/wards' @{
    code = "WARD-HP$suffix"; name = "Homepage Ward $suffix"; deptId = $deptId
} $token
Check 'ward.create' $r.Status 200
$wardId = (Res $r).id

$r = Invoke-Api 'POST' "/api/v2/clinical/wards/$wardId/beds" @{ bedNos = @('H1') } $token
Check 'bed.create' $r.Status 200
$bedId = (Res $r)[0].id

$r = Invoke-Api 'POST' '/api/v2/clinical/inpatient/admissions' @{
    patientId = $patientId; wardId = $wardId; bedId = $bedId; attendingDoctor = 'doctor01'
} $token
Check 'admission.create' $r.Status 200
$admissionId = (Res $r).id
$visitId = (Res $r).visitId
Check 'admission.visitIdPresent' ("$visitId".Length -gt 0) 'True'

# ---------------------------------------------------------------- 3. diagnoses + charges
$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/diagnoses" @{
    code = 'J18.900'; primary = $true
} $token
Check 'diagnosis.primary' $r.Status 200

$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/diagnoses" @{
    code = 'I10.x00'; primary = $false
} $token
Check 'diagnosis.secondary' $r.Status 200

# treatment + exam items: two different fee categories (exam transfer to tech may be
# marked FAILED when tech is offline, but the order stays valid and is still charged)
$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/orders" @{
    items = @(
        @{ itemCode = 'TRT-IV'; quantity = 1; orderMode = 'TEMP' },
        @{ itemCode = 'EXM-CBC'; quantity = 1; orderMode = 'TEMP' }
    )
} $token
Check 'orders.place' $r.Status 200

$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/settle" @{ paymentMethod = 'CASH' } $token
Check 'settle.ok' $r.Status 200
CheckNum 'settle.total' (Res $r).totalAmount 33.00

# ---------------------------------------------------------------- 4. homepage before discharge (409)
$r = Invoke-Api 'POST' '/api/v2/emr/homepages' @{ visitId = $visitId } $token
Check 'homepage.notDischarged.409' $r.Status 409

$r = Invoke-Api 'POST' "/api/v2/clinical/inpatient/admissions/$admissionId/discharge" $null $token
Check 'discharge.ok' $r.Status 200
Check 'discharge.status' (Res $r).status 'DISCHARGED'

# ---------------------------------------------------------------- 5. homepage generation
$r = Invoke-Api 'POST' '/api/v2/emr/homepages' @{ visitId = $visitId } $token
Check 'homepage.create' $r.Status 200
$hp = Res $r
$homepageId = $hp.homepage.id
$hpVersion = $hp.homepage.version
Check 'homepage.status' $hp.homepage.status 'DRAFT'
Check 'homepage.noPrefix' ($hp.homepage.homepageNo.StartsWith('HP')) 'True'
Check 'homepage.stayDays' $hp.homepage.stayDays 1
CheckNum 'homepage.total' $hp.homepage.totalAmount 33.00
CheckNum 'homepage.fee.exam' $hp.feeSummary.EXAM 25.00
CheckNum 'homepage.fee.treatment' $hp.feeSummary.TREATMENT 8.00
CheckNum 'homepage.fee.drugZero' $hp.feeSummary.DRUG 0.00
Check 'homepage.diagnosisCount' (@($hp.diagnoses).Count) 2
$primaryDx = @($hp.diagnoses | Where-Object { $_.primary -eq $true })
Check 'homepage.primaryDiagnosis' $primaryDx.Count 1
Check 'homepage.primaryDiagnosisCode' $primaryDx[0].code 'J18.900'
Check 'homepage.patientName' ($hp.homepage.patientName.Length -gt 0) 'True'
Check 'homepage.deptId' $hp.homepage.deptId $deptId
Check 'homepage.wardName' $hp.homepage.wardName "Homepage Ward $suffix"
Check 'homepage.bedNo' $hp.homepage.bedNo 'H1'
Check 'homepage.dischargeMode.empty' ("$($hp.homepage.dischargeMode)" -eq '') 'True'

# one homepage per admission
$r = Invoke-Api 'POST' '/api/v2/emr/homepages' @{ visitId = $visitId } $token
Check 'homepage.duplicate.409' $r.Status 409

# outpatient visit can never produce a homepage
$r = Invoke-Api 'POST' '/api/v2/clinical/registrations' @{
    patientId = $patientId; deptId = '22222222-2222-2222-2222-222222222222'
    doctorId = 'admin01'; visitType = 'OUTPATIENT'
} $token
Check 'outpatient.registration' $r.Status 200
$outpatientVisit = (Res $r).id
$r = Invoke-Api 'POST' '/api/v2/emr/homepages' @{ visitId = $outpatientVisit } $token
Check 'homepage.outpatient.409' $r.Status 409

# ---------------------------------------------------------------- 6. incomplete submit is blocked
$r = Invoke-Api 'POST' "/api/v2/emr/homepages/$homepageId/submit" $null $token
Check 'submit.incomplete.409' $r.Status 409

# ---------------------------------------------------------------- 7. fill in homepage-only fields
$r = Invoke-Api 'PUT' "/api/v2/emr/homepages/$homepageId" @{
    version = $hpVersion
    admissionWay = 'EMERGENCY'
    dischargeMode = 'BY_DOCTOR'
    readmitPlan = 'NONE'
    maritalStatus = 'MARRIED'
    occupation = 'Engineer'
    nation = 'Han'
    bloodType = 'A'
    rhFactor = 'POSITIVE'
    rescueCount = 0
    rescueSuccessCount = 0
    criticalCase = $false
    hospitalInfection = $false
    chiefPhysician = 'chief01'
    residentPhysician = 'resident01'
    coder = 'coder01'
    procedures = @(
        @{ code = '88.0100'; name = 'Lung biopsy'; date = $null
           surgeon = 'doctor01'; anesthesia = 'LOCAL'; incisionLevel = 'II' }
    )
} $token
Check 'homepage.update' $r.Status 200
$hp2 = Res $r
Check 'homepage.update.dischargeMode' $hp2.homepage.dischargeMode 'BY_DOCTOR'
Check 'homepage.update.versionBumped' $hp2.homepage.version ($hpVersion + 1)
Check 'homepage.update.procedureCount' (@($hp2.procedures).Count) 1

# rescue success count must not exceed rescue count
$r = Invoke-Api 'PUT' "/api/v2/emr/homepages/$homepageId" @{
    version = ($hpVersion + 1); dischargeMode = 'BY_DOCTOR'
    chiefPhysician = 'chief01'; residentPhysician = 'resident01'
    rescueCount = 1; rescueSuccessCount = 2
} $token
Check 'homepage.update.rescueMismatch.422' $r.Status 422

# stale version must be rejected (CAS)
$r = Invoke-Api 'PUT' "/api/v2/emr/homepages/$homepageId" @{
    version = $hpVersion; dischargeMode = 'BY_DOCTOR'
} $token
Check 'homepage.update.stale.409' $r.Status 409

# ---------------------------------------------------------------- 8. submit + archive
$r = Invoke-Api 'POST' "/api/v2/emr/homepages/$homepageId/submit" $null $token
Check 'submit.ok' $r.Status 200
Check 'submit.status' (Res $r).homepage.status 'SUBMITTED'
Check 'submit.by' (Res $r).homepage.submittedBy 'admin01'

$r = Invoke-Api 'PUT' "/api/v2/emr/homepages/$homepageId" @{
    version = (Res $r).homepage.version; dischargeMode = 'DEATH'
} $token
Check 'update.afterSubmit.409' $r.Status 409

# archive requires ICD coding (G4): save a single primary diagnosis and submit it
$r = Invoke-Api 'PUT' "/api/v2/emr/coding/$homepageId" @{
    diagnoses = @(@{ code = 'A09.900'; primary = $true })
    procedures = @()
    version = 0
} $qcToken
Check 'coding.save' $r.Status 200
$r = Invoke-Api 'POST' "/api/v2/emr/coding/$homepageId/submit" $null $qcToken
Check 'coding.submit' $r.Status 200

# the submitter cannot archive his own homepage (four-eyes)
$r = Invoke-Api 'POST' "/api/v2/emr/homepages/$homepageId/archive" $null $token
Check 'archive.submitter.409' $r.Status 409

$r = Invoke-Api 'POST' "/api/v2/emr/homepages/$homepageId/archive" $null $qcToken
Check 'archive.ok' $r.Status 200
Check 'archive.status' (Res $r).homepage.status 'ARCHIVED'
Check 'archive.by' (Res $r).homepage.archivedBy 'qc01'

$r = Invoke-Api 'POST' "/api/v2/emr/homepages/$homepageId/archive" $null $qcToken
Check 'archive.twice.409' $r.Status 409

# archived homepage keeps the snapshot readable
$r = Invoke-Api 'GET' "/api/v2/emr/homepages/$homepageId" $null $doctorToken
Check 'detail.doctor.read' $r.Status 200
CheckNum 'detail.total' (Res $r).homepage.totalAmount 33.00
Check 'detail.status' (Res $r).homepage.status 'ARCHIVED'

# ---------------------------------------------------------------- 9. print view + list
$r = Invoke-Api 'GET' "/api/v2/emr/homepages/$homepageId/print" $null $qcToken
Check 'print.ok' $r.Status 200
Check 'print.homepageNo' ($r.Body -match $hp.homepage.homepageNo) 'True'
Check 'print.archived.noWatermark' ($r.Body -match 'NOT ARCHIVED') 'False'

$r = Invoke-Api 'GET' "/api/v2/emr/homepages?patientId=$patientId&status=ARCHIVED" $null $qcToken
Check 'list.ok' $r.Status 200
$hits = @(Res $r | Where-Object { $_.id -eq $homepageId })
Check 'list.contains' $hits.Count 1

# ---------------------------------------------------------------- 10. permission: doctor read-only
$r = Invoke-Api 'POST' '/api/v2/emr/homepages' @{ visitId = $visitId } $doctorToken
Check 'perm.doctor.create.403' $r.Status 403

$r = Invoke-Api 'GET' "/api/v2/emr/homepages/$homepageId" $null $doctorToken
Check 'perm.doctor.read' $r.Status 200

Write-Output "-----"
Write-Output "VISIT_ID=$visitId"
Write-Output "HOMEPAGE_ID=$homepageId"
if ($script:fail -gt 0) { Write-Output "RESULT FAIL ($script:fail)"; exit 1 }
Write-Output 'RESULT PASS'
exit 0
