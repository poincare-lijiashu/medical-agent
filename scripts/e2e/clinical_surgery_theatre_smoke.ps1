# ============================================================================
# MedAssist operating theatre smoke test (rooms / scheduling / anesthesia / nursing).
#
# Covers: room create + duplicate 409 -> schedule first case (seq=1)
#         -> overlapping slot 409 -> adjacent slot allowed (seq=2)
#         -> duplicate schedule for same case 409 -> room board
#         -> start surgery moves schedule to IN_PROGRESS
#         -> anesthesia draft -> sign without vitals 422 -> add vital
#         -> duplicate vital timestamp 409 -> update draft (CAS) -> sign
#         -> post-sign update/vital 409 -> nursing draft/sign/frozen
#         -> complete surgery moves schedule to COMPLETED
#         -> cancel surgery cascades schedule CANCELLED
#         -> maintenance room rejects scheduling 422 -> permission (QC read-only).
#
# ASCII only (Windows PowerShell 5.1 reads BOM-less UTF-8 as ANSI).
# Prerequisites: auth :8081, empi :8082, clinical :8083, gateway :8080
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\e2e\clinical_surgery_theatre_smoke.ps1
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
$base = (Get-Date).ToUniversalTime().Date.AddDays(1).AddHours(1)
$iso = { param($t) $t.ToString('yyyy-MM-ddTHH:mm:ssZ') }
$t0 = $base
$t1 = $base.AddHours(1)
$t2 = $base.AddHours(2)
$t3 = $base.AddHours(3)

# ---------------------------------------------------------------- 1. login
$r = Invoke-Api 'POST' '/api/v2/auth/login' @{ username = $Username; password = $Password }
Check 'login' $r.Status 200
$token = (Res $r).accessToken
if (-not $token) { Write-Output "FATAL no token: $($r.Body)"; exit 1 }

$r = Invoke-Api 'POST' '/api/v2/auth/login' @{ username = 'qc01'; password = $Password }
Check 'login.qc' $r.Status 200
$qcToken = (Res $r).accessToken

# ---------------------------------------------------------------- 2. patient + admission
$r = Invoke-Api 'POST' '/api/v2/empi/patients' @{
    name = "Theatre Patient $suffix"; gender = 'MALE'; birthDate = '1968-06-06'
    idType = 'ID_CARD'; idNo = "11010119680606$suffix"
} $token
Check 'patient.create' $r.Status 200
$patientId = (Res $r).id

$r = Invoke-Api 'POST' '/api/v2/clinical/wards' @{
    code = "WARD-OR$suffix"; name = "Theatre Ward $suffix"; deptId = $deptId
} $token
Check 'ward.create' $r.Status 200
$wardId = (Res $r).id

$r = Invoke-Api 'POST' "/api/v2/clinical/wards/$wardId/beds" @{ bedNos = @('O1') } $token
Check 'bed.create' $r.Status 200
$bedId = (Res $r)[0].id

$r = Invoke-Api 'POST' '/api/v2/clinical/inpatient/admissions' @{
    patientId = $patientId; wardId = $wardId; bedId = $bedId; attendingDoctor = 'doctor01'
} $token
Check 'admission.create' $r.Status 200
$visitId = (Res $r).visitId

# ---------------------------------------------------------------- 3. two planned surgeries
$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/surgery-cases" @{
    operationCode = '47.0901'; surgeon = 'doctor01'; anesthesiaType = 'GENERAL'; incisionLevel = 'II'
} $token
Check 'surgery.a.plan' $r.Status 200
$caseA = (Res $r).id

$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/surgery-cases" @{
    operationCode = '88.0100'; surgeon = 'doctor01'; incisionLevel = 'II'
} $token
Check 'surgery.b.plan' $r.Status 200
$caseB = (Res $r).id

# level 1 => exempt from approval, but the record itself is mandatory before opening
$r = Invoke-Api 'PUT' "/api/v2/clinical/surgery-cases/$caseA/level" @{ level = 1 } $token
Check 'surgery.a.level' $r.Status 200
Check 'surgery.a.level.status' (Res $r).approvalStatus 'NOT_REQUIRED'

# ---------------------------------------------------------------- 4. rooms
$roomCode = "OR-$suffix"
$r = Invoke-Api 'POST' '/api/v2/clinical/operating-rooms' @{
    code = $roomCode; name = "Room $suffix"; deptId = $deptId; location = '3F'
} $token
Check 'room.create' $r.Status 200
$roomId = (Res $r).id
Check 'room.status' (Res $r).status 'ACTIVE'

$r = Invoke-Api 'POST' '/api/v2/clinical/operating-rooms' @{ code = $roomCode; name = 'dup' } $token
Check 'room.duplicate.409' $r.Status 409

# ---------------------------------------------------------------- 5. scheduling
$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/surgery-cases/$caseA/schedule" @{
    roomId = $roomId; plannedStart = (& $iso $t0); plannedEnd = (& $iso $t1)
} $token
Check 'schedule.a' $r.Status 200
Check 'schedule.a.status' (Res $r).status 'SCHEDULED'
Check 'schedule.a.sequence' (Res $r).sequence 1

$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/surgery-cases/$caseB/schedule" @{
    roomId = $roomId; plannedStart = (& $iso $t0.AddMinutes(30)); plannedEnd = (& $iso $t1.AddMinutes(30))
} $token
Check 'schedule.overlap.409' $r.Status 409

$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/surgery-cases/$caseB/schedule" @{
    roomId = $roomId; plannedStart = (& $iso $t1); plannedEnd = (& $iso $t2)
} $token
Check 'schedule.adjacent.ok' $r.Status 200
Check 'schedule.b.sequence' (Res $r).sequence 2

$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/surgery-cases/$caseA/schedule" @{
    roomId = $roomId; plannedStart = (& $iso $t2); plannedEnd = (& $iso $t3)
} $token
Check 'schedule.duplicateCase.409' $r.Status 409

$r = Invoke-Api 'GET' "/api/v2/clinical/operating-rooms/$roomId/schedules?date=$($base.ToString('yyyy-MM-dd'))" $null $token
Check 'board.ok' $r.Status 200
Check 'board.count' (@(Res $r).Count) 2

# ---------------------------------------------------------------- 6. start surgery -> schedule IN_PROGRESS
# opening a case requires signed surgical + anesthesia consents (hard gate)
$r = Invoke-Api 'POST' "/api/v2/clinical/surgery-cases/$caseA/consents" @{
    consentType = 'SURGERY'; signerName = 'Patient'; signerRelation = 'SELF'
} $token
Check 'consent.surgery' $r.Status 200

$r = Invoke-Api 'POST' "/api/v2/clinical/surgery-cases/$caseA/consents" @{
    consentType = 'ANESTHESIA'; signerName = 'Patient'; signerRelation = 'SELF'
} $token
Check 'consent.anesthesia' $r.Status 200

# 安全核查（§265）：麻醉前 + 手术开始前必须完成才能开台
$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/surgery-cases/$caseA/safety-checks" @{
    phase = 'PRE_ANESTHESIA'
    checkedItems = @('PATIENT_ID', 'SITE_MARK', 'PROCEDURE_CONFIRM', 'ANESTHESIA_CONSENT',
                     'ALLERGY', 'AIRWAY', 'BLOOD_LOSS_RISK', 'IMAGING', 'EQUIPMENT')
} $token
Check 'safety.preAnesthesia' $r.Status 200

$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/surgery-cases/$caseA/safety-checks" @{
    phase = 'TIME_OUT'
    checkedItems = @('TEAM_INTRO', 'PATIENT_SITE_PROCEDURE', 'ANTIBIOTIC_PROPHYLAXIS',
                     'IMAGING_DISPLAYED', 'STERILITY')
} $token
Check 'safety.timeOut' $r.Status 200
Check 'safety.readyToStart' (Res $r).readyToStart $true

$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/surgery-cases/$caseA/start" @{ version = 0 } $token
Check 'surgery.a.start' $r.Status 200
Check 'surgery.a.status' (Res $r).status 'IN_PROGRESS'

$r = Invoke-Api 'GET' "/api/v2/clinical/visits/$visitId/surgery-cases/$caseA/schedule" $null $token
Check 'schedule.a.inProgress' (Res $r).status 'IN_PROGRESS'

# ---------------------------------------------------------------- 7. anesthesia
$r = Invoke-Api 'PUT' "/api/v2/clinical/surgery-cases/$caseA/anesthesia" @{
    method = 'GENERAL'; asaGrade = 'II'; anesthesiologist = 'doctor01'; airway = 'ETT'
    startedAt = (& $iso $t0); endedAt = (& $iso $t1); notes = 'induction smooth'
} $token
Check 'anesthesia.create' $r.Status 200
Check 'anesthesia.status' (Res $r).record.status 'DRAFT'

$r = Invoke-Api 'POST' "/api/v2/clinical/surgery-cases/$caseA/anesthesia/sign" @{ version = 0 } $token
Check 'anesthesia.sign.noVitals.422' $r.Status 422

$r = Invoke-Api 'POST' "/api/v2/clinical/surgery-cases/$caseA/anesthesia/vitals" @{
    recordedAt = (& $iso $t0.AddMinutes(10)); heartRate = 78; sbp = 120; dbp = 70; spo2 = 99
    etco2 = 35.5; temperature = 36.6
} $token
Check 'anesthesia.vital.add' $r.Status 200

$r = Invoke-Api 'POST' "/api/v2/clinical/surgery-cases/$caseA/anesthesia/vitals" @{
    recordedAt = (& $iso $t0.AddMinutes(10)); heartRate = 80
} $token
Check 'anesthesia.vital.duplicateTime.409' $r.Status 409

$r = Invoke-Api 'PUT' "/api/v2/clinical/surgery-cases/$caseA/anesthesia" @{
    method = 'GENERAL'; asaGrade = 'II'; anesthesiologist = 'doctor01'; airway = 'ETT'
    startedAt = (& $iso $t0); endedAt = (& $iso $t1); notes = 'maintenance stable'; version = 0
} $token
Check 'anesthesia.update' $r.Status 200
Check 'anesthesia.update.version' (Res $r).record.version 1

$r = Invoke-Api 'POST' "/api/v2/clinical/surgery-cases/$caseA/anesthesia/sign" @{ version = 1 } $token
Check 'anesthesia.sign' $r.Status 200
Check 'anesthesia.sign.status' (Res $r).record.status 'SIGNED'
Check 'anesthesia.sign.vitals' (@((Res $r).vitals).Count) 1

$r = Invoke-Api 'PUT' "/api/v2/clinical/surgery-cases/$caseA/anesthesia" @{
    method = 'GENERAL'; startedAt = (& $iso $t0); endedAt = (& $iso $t1); version = 2
} $token
Check 'anesthesia.afterSign.update.409' $r.Status 409

$r = Invoke-Api 'POST' "/api/v2/clinical/surgery-cases/$caseA/anesthesia/vitals" @{
    recordedAt = (& $iso $t0.AddMinutes(20)); heartRate = 82
} $token
Check 'anesthesia.afterSign.vital.409' $r.Status 409

# ---------------------------------------------------------------- 8. nursing
$r = Invoke-Api 'PUT' "/api/v2/clinical/surgery-cases/$caseA/nursing" @{
    scrubNurse = 'nurse01'; circulatingNurse = 'nurse02'; patientPosition = 'SUPINE'
    skinPrep = 'chlorhexidine'; specimenCount = 1; notes = 'count correct'
} $token
Check 'nursing.create' $r.Status 200
Check 'nursing.status' (Res $r).status 'DRAFT'

$r = Invoke-Api 'POST' "/api/v2/clinical/surgery-cases/$caseA/nursing/sign" @{ version = 0 } $token
Check 'nursing.sign' $r.Status 200
Check 'nursing.sign.status' (Res $r).status 'SIGNED'

$r = Invoke-Api 'PUT' "/api/v2/clinical/surgery-cases/$caseA/nursing" @{
    scrubNurse = 'nurse01'; circulatingNurse = 'nurse02'; version = 1
} $token
Check 'nursing.afterSign.update.409' $r.Status 409

# ---------------------------------------------------------------- 9. complete surgery -> schedule COMPLETED
# 离室前核查（§265）：清点/标本/复苏计划完成才允许结束手术
$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/surgery-cases/$caseA/safety-checks" @{
    phase = 'SIGN_OUT'
    checkedItems = @('PROCEDURE_RECORDED', 'SPECIMEN_LABELED', 'INSTRUMENT_COUNT',
                     'RECOVERY_PLAN', 'BLOOD_LOSS_RECORDED')
} $token
Check 'safety.signOut' $r.Status 200
Check 'safety.readyToComplete' (Res $r).readyToComplete $true

$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/surgery-cases/$caseA/complete" @{ version = 1 } $token
Check 'surgery.a.complete' $r.Status 200
Check 'surgery.a.completed' (Res $r).status 'COMPLETED'

$r = Invoke-Api 'GET' "/api/v2/clinical/visits/$visitId/surgery-cases/$caseA/schedule" $null $token
Check 'schedule.a.completed' (Res $r).status 'COMPLETED'

# ---------------------------------------------------------------- 10. cancel surgery -> schedule CANCELLED
$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/surgery-cases/$caseB/cancel" @{
    version = 0; reason = 'patient fever'
} $token
Check 'surgery.b.cancel' $r.Status 200
Check 'surgery.b.cancelled' (Res $r).status 'CANCELLED'

$r = Invoke-Api 'GET' "/api/v2/clinical/visits/$visitId/surgery-cases/$caseB/schedule" $null $token
Check 'schedule.b.cancelled' (Res $r).status 'CANCELLED'

# ---------------------------------------------------------------- 11. maintenance room rejects scheduling
$r = Invoke-Api 'POST' '/api/v2/clinical/operating-rooms' @{
    code = "OR-M$suffix"; name = "Maintenance Room $suffix"
} $token
Check 'room2.create' $r.Status 200
$room2 = (Res $r).id

$r = Invoke-Api 'POST' "/api/v2/clinical/operating-rooms/$room2/status" @{
    status = 'MAINTENANCE'; version = 0
} $token
Check 'room2.maintenance' $r.Status 200

$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/surgery-cases" @{
    operationCode = '53.0001'; surgeon = 'doctor01'
} $token
Check 'surgery.c.plan' $r.Status 200
$caseC = (Res $r).id

$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/surgery-cases/$caseC/schedule" @{
    roomId = $room2; plannedStart = (& $iso $t0); plannedEnd = (& $iso $t1)
} $token
Check 'schedule.maintenanceRoom.422' $r.Status 422

# ---------------------------------------------------------------- 12. permission
$r = Invoke-Api 'GET' '/api/v2/clinical/operating-rooms' $null $qcToken
Check 'perm.qc.read' $r.Status 200

$r = Invoke-Api 'POST' '/api/v2/clinical/operating-rooms' @{
    code = "OR-QC$suffix"; name = 'qc room'
} $qcToken
Check 'perm.qc.write.403' $r.Status 403

Write-Output "-----"
Write-Output "VISIT_ID=$visitId"
Write-Output "ROOM_ID=$roomId"
Write-Output "CASE_A=$caseA"
if ($script:fail -gt 0) { Write-Output "RESULT FAIL ($script:fail)"; exit 1 }
Write-Output 'RESULT PASS'
exit 0
