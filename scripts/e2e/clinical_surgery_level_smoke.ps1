# ============================================================================
# MedAssist surgery level + theatre utilization smoke test (batch 4).
#
# Covers: level query before applying (empty data) -> out-of-range 422
#         -> level 1 auto exempt -> start blocked without consents -> start ok
#         -> level change after start 422 -> level 3 pending blocks start
#         -> duplicate pending submit 409 -> reject requires reason 422
#         -> four-eyes: applicant cannot reject/approve -> QC rejects 422 start
#         -> re-apply -> QC approves -> start ok -> complete
#         -> room utilization: scheduled/completed/cancelled/occupied/rate
#         -> hospital-wide utilization -> invalid open window 422
#         -> permission: QC cannot set level (403) but can read reports.
#
# ASCII only (Windows PowerShell 5.1 reads BOM-less UTF-8 as ANSI).
# Business zone assumption: Asia/Shanghai (dev default), so local "today"
#   is used for both scheduling instants and the utilization window.
# Prerequisites: auth :8081, empi :8082, clinical :8083, gateway :8080
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\e2e\clinical_surgery_level_smoke.ps1
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
$day = (Get-Date).ToString('yyyy-MM-dd')
$today = (Get-Date).Date
$iso = { param($t) ([DateTimeOffset]::new($t)).ToString('yyyy-MM-ddTHH:mm:sszzz') }
$bStart = (& $iso ($today.AddHours(10)))
$bEnd = (& $iso ($today.AddHours(12)))
$cStart = (& $iso ($today.AddHours(14)))
$cEnd = (& $iso ($today.AddHours(15)))

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
    name = "Level Patient $suffix"; gender = 'MALE'; birthDate = '1965-03-03'
    idType = 'ID_CARD'; idNo = "11010119650303$suffix"
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

# ---------------------------------------------------------------- 3. case A: level 1 (no approval)
$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/surgery-cases" @{
    operationCode = '47.0901'; surgeon = 'doctor01'; anesthesiaType = 'GENERAL'; incisionLevel = 'II'
} $token
Check 'caseA.plan' $r.Status 200
$caseA = (Res $r).id

$r = Invoke-Api 'GET' "/api/v2/clinical/surgery-cases/$caseA/level" $null $token
Check 'caseA.level.unset' $r.Status 200
Check 'caseA.level.unset.data' "$(Res $r)" ''

$r = Invoke-Api 'PUT' "/api/v2/clinical/surgery-cases/$caseA/level" @{ level = 5 } $token
Check 'caseA.level.outOfRange.422' $r.Status 422

$r = Invoke-Api 'PUT' "/api/v2/clinical/surgery-cases/$caseA/level" @{ level = 1 } $token
Check 'caseA.level.set' $r.Status 200
Check 'caseA.level.set.level' (Res $r).level 1
Check 'caseA.level.set.status' (Res $r).approvalStatus 'NOT_REQUIRED'
Check 'caseA.level.set.appliedBy' (Res $r).appliedBy $Username

$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/surgery-cases/$caseA/start" @{ version = 0 } $token
Check 'caseA.start.noConsent.422' $r.Status 422

$r = Invoke-Api 'POST' "/api/v2/clinical/surgery-cases/$caseA/consents" @{
    consentType = 'SURGERY'; signerName = 'Patient A'; signerRelation = 'SELF'
} $token
Check 'caseA.consent.surgery' $r.Status 200

$r = Invoke-Api 'POST' "/api/v2/clinical/surgery-cases/$caseA/consents" @{
    consentType = 'ANESTHESIA'; signerName = 'Patient A'; signerRelation = 'SELF'
} $token
Check 'caseA.consent.anesthesia' $r.Status 200

$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/surgery-cases/$caseA/start" @{ version = 0 } $token
Check 'caseA.start' $r.Status 200
Check 'caseA.start.status' (Res $r).status 'IN_PROGRESS'

$r = Invoke-Api 'PUT' "/api/v2/clinical/surgery-cases/$caseA/level" @{ level = 2; version = 0 } $token
Check 'caseA.level.afterStart.422' $r.Status 422

$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/surgery-cases/$caseA/complete" @{ version = 1 } $token
Check 'caseA.complete' $r.Status 200
Check 'caseA.completed' (Res $r).status 'COMPLETED'

# ---------------------------------------------------------------- 4. case B: level 3/4 needs approval
$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/surgery-cases" @{
    operationCode = '81.5101'; surgeon = 'doctor01'; anesthesiaType = 'GENERAL'; incisionLevel = 'III'
} $token
Check 'caseB.plan' $r.Status 200
$caseB = (Res $r).id

$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/surgery-cases/$caseB/start" @{ version = 0 } $token
Check 'caseB.start.noLevel.422' $r.Status 422

$r = Invoke-Api 'PUT' "/api/v2/clinical/surgery-cases/$caseB/level" @{ level = 4 } $token
Check 'caseB.level.pending' $r.Status 200
Check 'caseB.level.pending.status' (Res $r).approvalStatus 'PENDING'

$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/surgery-cases/$caseB/start" @{ version = 0 } $token
Check 'caseB.start.pending.422' $r.Status 422

$r = Invoke-Api 'PUT' "/api/v2/clinical/surgery-cases/$caseB/level" @{ level = 4; version = 0 } $token
Check 'caseB.level.duplicatePending.409' $r.Status 409

$r = Invoke-Api 'POST' "/api/v2/clinical/surgery-cases/$caseB/consents" @{
    consentType = 'SURGERY'; signerName = 'Patient B'; signerRelation = 'SELF'
} $token
Check 'caseB.consent.surgery' $r.Status 200

$r = Invoke-Api 'POST' "/api/v2/clinical/surgery-cases/$caseB/consents" @{
    consentType = 'ANESTHESIA'; signerName = 'Patient B'; signerRelation = 'SELF'
} $token
Check 'caseB.consent.anesthesia' $r.Status 200

$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/surgery-cases/$caseB/start" @{ version = 0 } $token
Check 'caseB.start.pendingWithConsent.422' $r.Status 422

$r = Invoke-Api 'POST' "/api/v2/clinical/surgery-cases/$caseB/level/reject" @{
    version = 0; reason = '  '
} $qcToken
Check 'caseB.reject.blankReason.422' $r.Status 422

$r = Invoke-Api 'POST' "/api/v2/clinical/surgery-cases/$caseB/level/reject" @{
    version = 0; reason = 'imaging not ready'
} $token
Check 'caseB.reject.selfApply.422' $r.Status 422

$r = Invoke-Api 'POST' "/api/v2/clinical/surgery-cases/$caseB/level/reject" @{
    version = 0; reason = 'imaging not ready'
} $qcToken
Check 'caseB.reject' $r.Status 200
Check 'caseB.reject.status' (Res $r).approvalStatus 'REJECTED'
Check 'caseB.reject.reason' (Res $r).rejectReason 'imaging not ready'
Check 'caseB.reject.reviewedBy' (Res $r).reviewedBy 'qc01'

$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/surgery-cases/$caseB/start" @{ version = 0 } $token
Check 'caseB.start.rejected.422' $r.Status 422

$r = Invoke-Api 'PUT' "/api/v2/clinical/surgery-cases/$caseB/level" @{ level = 4; version = 1 } $token
Check 'caseB.level.reapply' $r.Status 200
Check 'caseB.level.reapply.status' (Res $r).approvalStatus 'PENDING'
Check 'caseB.level.reapply.version' (Res $r).version 2

$r = Invoke-Api 'POST' "/api/v2/clinical/surgery-cases/$caseB/level/approve" @{ version = 2 } $token
Check 'caseB.approve.selfApply.422' $r.Status 422

$r = Invoke-Api 'POST' "/api/v2/clinical/surgery-cases/$caseB/level/approve" @{ version = 2 } $qcToken
Check 'caseB.approve' $r.Status 200
Check 'caseB.approve.status' (Res $r).approvalStatus 'APPROVED'
Check 'caseB.approve.reviewedBy' (Res $r).reviewedBy 'qc01'

# ---------------------------------------------------------------- 5. room + schedules
$roomCode = "OR-LV$suffix"
$r = Invoke-Api 'POST' '/api/v2/clinical/operating-rooms' @{
    code = $roomCode; name = "Level Room $suffix"; deptId = $deptId; location = '4F'
} $token
Check 'room.create' $r.Status 200
$roomId = (Res $r).id

$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/surgery-cases/$caseB/schedule" @{
    roomId = $roomId; plannedStart = $bStart; plannedEnd = $bEnd
} $token
Check 'caseB.schedule' $r.Status 200
Check 'caseB.schedule.status' (Res $r).status 'SCHEDULED'

$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/surgery-cases/$caseB/start" @{ version = 0 } $token
Check 'caseB.start.approved' $r.Status 200
Check 'caseB.start.status' (Res $r).status 'IN_PROGRESS'

$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/surgery-cases/$caseB/complete" @{ version = 1 } $token
Check 'caseB.complete' $r.Status 200

$r = Invoke-Api 'GET' "/api/v2/clinical/visits/$visitId/surgery-cases/$caseB/schedule" $null $token
Check 'caseB.schedule.completed' (Res $r).status 'COMPLETED'

# ---------------------------------------------------------------- 6. case C: scheduled then cancelled
$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/surgery-cases" @{
    operationCode = '51.2301'; surgeon = 'doctor01'; incisionLevel = 'II'
} $token
Check 'caseC.plan' $r.Status 200
$caseC = (Res $r).id

$r = Invoke-Api 'PUT' "/api/v2/clinical/surgery-cases/$caseC/level" @{ level = 1 } $token
Check 'caseC.level' $r.Status 200

$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/surgery-cases/$caseC/schedule" @{
    roomId = $roomId; plannedStart = $cStart; plannedEnd = $cEnd
} $token
Check 'caseC.schedule' $r.Status 200

$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/surgery-cases/$caseC/schedule/cancel" @{
    version = 0; reason = 'patient febrile'
} $token
Check 'caseC.schedule.cancel' $r.Status 200
Check 'caseC.schedule.cancelled' (Res $r).status 'CANCELLED'

# ---------------------------------------------------------------- 7. utilization
$r = Invoke-Api 'GET' "/api/v2/clinical/operating-rooms/$roomId/utilization?from=$day&to=$day&openFrom=08:00&openTo=18:00" $null $token
Check 'util.room' $r.Status 200
$util = Res $r
Check 'util.room.days' (@($util.days).Count) 1
Check 'util.room.day' (@($util.days)[0].date) $day
Check 'util.room.scheduled' $util.totalScheduled 1
Check 'util.room.completed' $util.totalCompleted 1
Check 'util.room.cancelled' $util.totalCancelled 1
Check 'util.room.occupied' $util.totalOccupiedMinutes 120
Check 'util.room.available' $util.totalAvailableMinutes 600
Check 'util.room.turnoverCount' $util.turnoverCount 0
Check 'util.room.rate' ([math]::Round([double]$util.utilizationRate, 4)) 0.2

$r = Invoke-Api 'GET' "/api/v2/clinical/surgery/utilization?from=$day&to=$day&openFrom=08:00&openTo=18:00" $null $token
Check 'util.hospital' $r.Status 200
$rooms = @(Res $r | Where-Object { $_.roomCode -eq $roomCode })
Check 'util.hospital.myRoom' (@($rooms).Count) 1
Check 'util.hospital.myRoom.scheduled' (@($rooms)[0].totalScheduled) 1

$r = Invoke-Api 'GET' "/api/v2/clinical/operating-rooms/$roomId/utilization?from=$day&to=$day&openFrom=18:00&openTo=08:00" $null $token
Check 'util.badWindow.422' $r.Status 422

$r = Invoke-Api 'GET' "/api/v2/clinical/operating-rooms/$roomId/utilization?from=$day&to=$day" $null $qcToken
Check 'util.qc.read' $r.Status 200

# ---------------------------------------------------------------- 7b. turnover (two cases, same room)
$day2 = $today.AddDays(1)
$day2Text = $day2.ToString('yyyy-MM-dd')
$dStart = (& $iso ($day2.AddHours(10)))
$dEnd = (& $iso ($day2.AddHours(11)))
$eStart = (& $iso ($day2.AddHours(11).AddMinutes(30)))
$eEnd = (& $iso ($day2.AddHours(12).AddMinutes(30)))

$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/surgery-cases" @{
    operationCode = '53.0001'; surgeon = 'doctor01'
} $token
Check 'caseD.plan' $r.Status 200
$caseD = (Res $r).id

$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/surgery-cases" @{
    operationCode = '45.1301'; surgeon = 'doctor01'
} $token
Check 'caseE.plan' $r.Status 200
$caseE = (Res $r).id

$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/surgery-cases/$caseD/schedule" @{
    roomId = $roomId; plannedStart = $dStart; plannedEnd = $dEnd
} $token
Check 'caseD.schedule' $r.Status 200

$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/surgery-cases/$caseE/schedule" @{
    roomId = $roomId; plannedStart = $eStart; plannedEnd = $eEnd
} $token
Check 'caseE.schedule' $r.Status 200

$r = Invoke-Api 'GET' "/api/v2/clinical/operating-rooms/$roomId/utilization?from=$day2Text&to=$day2Text&openFrom=08:00&openTo=18:00" $null $token
Check 'util.turnover' $r.Status 200
Check 'util.turnover.scheduled' (Res $r).totalScheduled 2
Check 'util.turnover.occupied' (Res $r).totalOccupiedMinutes 120
Check 'util.turnover.count' (Res $r).turnoverCount 1
Check 'util.turnover.minutes' (Res $r).totalTurnoverMinutes 30
Check 'util.turnover.rate' ([math]::Round([double](Res $r).utilizationRate, 4)) 0.2

# ---------------------------------------------------------------- 8. permission
$r = Invoke-Api 'PUT' "/api/v2/clinical/surgery-cases/$caseC/level" @{ level = 2; version = 0 } $qcToken
Check 'perm.qc.setLevel.403' $r.Status 403

Write-Output "-----"
Write-Output "VISIT_ID=$visitId"
Write-Output "CASE_A=$caseA"
Write-Output "CASE_B=$caseB"
Write-Output "CASE_C=$caseC"
Write-Output "ROOM_ID=$roomId"
if ($script:fail -gt 0) { Write-Output "RESULT FAIL ($script:fail)"; exit 1 }
Write-Output 'RESULT PASS'
exit 0
