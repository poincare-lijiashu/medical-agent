# ============================================================================
# MedAssist PACU (post-anesthesia care unit) smoke test.
#
# Covers: only completed surgery can enter PACU -> duplicate admission 409
#         -> Aldrete observation total -> discharge blocked below 9
#         -> observation before admission 422 -> duplicate timestamp 409
#         -> stale discharge version 409 -> discharge out -> append after out 409
#         -> self-signature 409 -> second-person sign -> signed record frozen
#         -> read view -> PACU board (scope pushed to SQL) -> permission 403.
#
# ASCII only (Windows PowerShell 5.1 reads BOM-less UTF-8 as ANSI).
# Prerequisites: auth :8081, empi :8082, clinical :8083, gateway :8080
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\e2e\clinical_pacu_smoke.ps1
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
$base = (Get-Date).ToUniversalTime().AddMinutes(1)
$iso = { param($minutes) $base.AddMinutes($minutes).ToString('yyyy-MM-ddTHH:mm:ssZ') }

# ---------------------------------------------------------------- 1. login
$r = Invoke-Api 'POST' '/api/v2/auth/login' @{ username = $Username; password = $Password }
Check 'login.admin' $r.Status 200
$token = (Res $r).accessToken
if (-not $token) { Write-Output "FATAL no admin token: $($r.Body)"; exit 1 }

# doctor01 is bound to dept 3333..., used as the second signer (four eyes)
$r = Invoke-Api 'POST' '/api/v2/auth/login' @{ username = 'doctor01'; password = $Password }
Check 'login.doctor' $r.Status 200
$doctorToken = (Res $r).accessToken

$r = Invoke-Api 'POST' '/api/v2/auth/login' @{ username = 'qc01'; password = $Password }
Check 'login.qc' $r.Status 200
$qcToken = (Res $r).accessToken

# ---------------------------------------------------------------- 2. patient + admission
$r = Invoke-Api 'POST' '/api/v2/empi/patients' @{
    name = "Pacu Patient $suffix"; gender = 'FEMALE'; birthDate = '1975-06-18'
    idType = 'ID_CARD'; idNo = "11010119750618$suffix"
} $token
Check 'patient.create' $r.Status 200
$patientId = (Res $r).id

$r = Invoke-Api 'POST' '/api/v2/clinical/wards' @{
    code = "WARD-PA$suffix"; name = "PACU Ward $suffix"; deptId = $deptId
} $token
Check 'ward.create' $r.Status 200
$wardId = (Res $r).id

$r = Invoke-Api 'POST' "/api/v2/clinical/wards/$wardId/beds" @{ bedNos = @('P1') } $token
Check 'bed.create' $r.Status 200
$bedId = (Res $r)[0].id

$r = Invoke-Api 'POST' '/api/v2/clinical/inpatient/admissions' @{
    patientId = $patientId; wardId = $wardId; bedId = $bedId; attendingDoctor = 'doctor01'
} $token
Check 'admission.create' $r.Status 200
$visitId = (Res $r).visitId

# ---------------------------------------------------------------- 3. completed surgery
$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/surgery-cases" @{
    operationCode = '47.0901'; plannedAt = (& $iso 0); surgeon = 'doctor01'
    anesthesiaType = 'GENERAL'; asaGrade = 'II'; incisionLevel = 'II'
} $token
Check 'surgery.plan' $r.Status 200
$caseId = (Res $r).id

$r = Invoke-Api 'PUT' "/api/v2/clinical/surgery-cases/$caseId/level" @{ level = 1 } $token
Check 'surgery.level' $r.Status 200

$r = Invoke-Api 'POST' "/api/v2/clinical/surgery-cases/$caseId/consents" @{
    consentType = 'SURGERY'; signerName = 'Patient'; signerRelation = 'SELF'
} $token
Check 'consent.surgery' $r.Status 200

$r = Invoke-Api 'POST' "/api/v2/clinical/surgery-cases/$caseId/consents" @{
    consentType = 'ANESTHESIA'; signerName = 'Patient'; signerRelation = 'SELF'
} $token
Check 'consent.anesthesia' $r.Status 200

$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/surgery-cases/$caseId/start" @{ version = 0 } $token
Check 'surgery.start' $r.Status 200

$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/surgery-cases/$caseId/complete" @{ version = 1 } $token
Check 'surgery.complete' $r.Status 200
Check 'surgery.complete.status' (Res $r).status 'COMPLETED'

# a still-planned case must be rejected by PACU admission
$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/surgery-cases" @{
    operationCode = '88.0100'; surgeon = 'doctor01'
} $token
Check 'surgery.plan.planned' $r.Status 200
$plannedCaseId = (Res $r).id

$r = Invoke-Api 'GET' "/api/v2/clinical/surgery-cases/$caseId/pacu" $null $token
Check 'pacu.get.beforeAdmit.404' $r.Status 404

$r = Invoke-Api 'POST' "/api/v2/clinical/surgery-cases/$plannedCaseId/pacu" @{ bedNo = 'B-02' } $token
Check 'pacu.admit.unfinished.422' $r.Status 422

# ---------------------------------------------------------------- 4. admit
$r = Invoke-Api 'POST' "/api/v2/clinical/surgery-cases/$caseId/pacu" @{
    bedNo = 'B-01'; admittedAt = (& $iso 0)
} $token
Check 'pacu.admit' $r.Status 200
$stay = (Res $r).stay
Check 'pacu.admit.status' $stay.status 'IN_PACU'
Check 'pacu.admit.bed' $stay.bedNo 'B-01'
$stayId = $stay.id

$r = Invoke-Api 'POST' "/api/v2/clinical/surgery-cases/$caseId/pacu" @{ bedNo = 'B-03' } $token
Check 'pacu.admit.duplicate.409' $r.Status 409

# ---------------------------------------------------------------- 5. Aldrete observations
$r = Invoke-Api 'POST' "/api/v2/clinical/surgery-cases/$caseId/pacu/observations" @{
    recordedAt = (& $iso 1); activity = 2; respiration = 2; circulation = 1
    consciousness = 1; oxygenation = 1; heartRate = 92; sbp = 118; dbp = 72; spo2 = 97
    temperature = 36.4
} $token
Check 'pacu.observation.low' $r.Status 200
Check 'pacu.observation.low.total' (Res $r).total 7

$r = Invoke-Api 'POST' "/api/v2/clinical/surgery-cases/$caseId/pacu/discharge" @{
    version = 0; dischargedAt = (& $iso 2); destination = 'WARD'
} $token
Check 'pacu.discharge.lowScore.422' $r.Status 422

$r = Invoke-Api 'POST' "/api/v2/clinical/surgery-cases/$caseId/pacu/observations" @{
    recordedAt = (& $iso 3); activity = 2; respiration = 2; circulation = 2
    consciousness = 2; oxygenation = 2; heartRate = 84; sbp = 122; dbp = 76; spo2 = 99
} $token
Check 'pacu.observation.pass' $r.Status 200
Check 'pacu.observation.pass.total' (Res $r).total 10

$r = Invoke-Api 'POST' "/api/v2/clinical/surgery-cases/$caseId/pacu/observations" @{
    recordedAt = (& $iso (-1)); activity = 1; respiration = 1; circulation = 1
    consciousness = 1; oxygenation = 1
} $token
Check 'pacu.observation.beforeAdmit.422' $r.Status 422

$r = Invoke-Api 'POST' "/api/v2/clinical/surgery-cases/$caseId/pacu/observations" @{
    recordedAt = (& $iso 3); activity = 2; respiration = 2; circulation = 2
    consciousness = 2; oxygenation = 2
} $token
Check 'pacu.observation.duplicate.409' $r.Status 409

# ---------------------------------------------------------------- 6. discharge + sign
$r = Invoke-Api 'POST' "/api/v2/clinical/surgery-cases/$caseId/pacu/discharge" @{
    version = 99; dischargedAt = (& $iso 5); destination = 'WARD'
} $token
Check 'pacu.discharge.stale.409' $r.Status 409

$r = Invoke-Api 'POST' "/api/v2/clinical/surgery-cases/$caseId/pacu/discharge" @{
    version = 0; dischargedAt = (& $iso 5); destination = 'ward'
} $token
Check 'pacu.discharge' $r.Status 200
Check 'pacu.discharge.status' (Res $r).stay.status 'OUT'
Check 'pacu.discharge.destination' (Res $r).stay.destination 'WARD'

$r = Invoke-Api 'POST' "/api/v2/clinical/surgery-cases/$caseId/pacu/observations" @{
    recordedAt = (& $iso 6); activity = 2; respiration = 2; circulation = 2
    consciousness = 2; oxygenation = 2
} $token
Check 'pacu.observation.afterOut.409' $r.Status 409

$r = Invoke-Api 'POST' "/api/v2/clinical/surgery-cases/$caseId/pacu/sign" @{ version = 1 } $token
Check 'pacu.sign.self.409' $r.Status 409

$r = Invoke-Api 'POST' "/api/v2/clinical/surgery-cases/$caseId/pacu/sign" @{ version = 1 } $doctorToken
Check 'pacu.sign' $r.Status 200
Check 'pacu.sign.by' (Res $r).stay.signedBy 'doctor01'
Check 'pacu.sign.at' ((Res $r).stay.signedAt.Length -gt 0) 'True'

$r = Invoke-Api 'POST' "/api/v2/clinical/surgery-cases/$caseId/pacu/sign" @{ version = 2 } $doctorToken
Check 'pacu.sign.repeat.409' $r.Status 409

$r = Invoke-Api 'GET' "/api/v2/clinical/surgery-cases/$caseId/pacu" $null $doctorToken
Check 'pacu.get' $r.Status 200
Check 'pacu.get.observations' (@((Res $r).observations).Count) 2

# ---------------------------------------------------------------- 7. board + permission
$r = Invoke-Api 'GET' '/api/v2/clinical/pacu/stays?limit=50' $null $token
Check 'pacu.board' $r.Status 200
$mine = @(Res $r) | Where-Object { $_.stay.id -eq $stayId }
Check 'pacu.board.contains' (@($mine).Count) 1
Check 'pacu.board.lastTotal' (@($mine)[0].lastTotal) 10
Check 'pacu.board.observationCount' (@($mine)[0].observationCount) 2
Check 'pacu.board.caseNo' ((@($mine)[0].caseNo).Length -gt 0) 'True'

$r = Invoke-Api 'GET' '/api/v2/clinical/pacu/stays?status=OUT&limit=5' $null $doctorToken
Check 'pacu.board.doctor' $r.Status 200
$doctorMine = @(Res $r) | Where-Object { $_.stay.id -eq $stayId }
Check 'pacu.board.doctor.contains' (@($doctorMine).Count) 1

$r = Invoke-Api 'GET' '/api/v2/clinical/pacu/stays?status=UNKNOWN' $null $token
Check 'pacu.board.badStatus.422' $r.Status 422

$r = Invoke-Api 'GET' '/api/v2/clinical/pacu/stays' $null $qcToken
Check 'pacu.board.qc.read' $r.Status 200

$r = Invoke-Api 'POST' "/api/v2/clinical/surgery-cases/$plannedCaseId/pacu" @{ bedNo = 'B-09' } $qcToken
Check 'pacu.write.qc.403' $r.Status 403

Write-Output "-----"
Write-Output "VISIT_ID=$visitId"
Write-Output "SURGERY_CASE_ID=$caseId"
Write-Output "PACU_STAY_ID=$stayId"
if ($script:fail -gt 0) { Write-Output "RESULT FAIL ($script:fail)"; exit 1 }
Write-Output 'RESULT PASS'
exit 0
