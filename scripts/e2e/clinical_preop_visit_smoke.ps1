# ============================================================================
# MedAssist preop visit / anesthesia assessment smoke test (batch 6).
#
# Covers: level-1 surgery starts without a visit -> level-4 surgery cannot start
#         without a signed visit -> incomplete sign 422 -> full draft -> sign
#         -> frozen 409 -> start ok -> UNFIT conclusion blocks any level
#         -> permission (QC read-only).
#
# ASCII only (Windows PowerShell 5.1 reads BOM-less UTF-8 as ANSI).
# Prerequisites: auth :8081, empi :8082, clinical :8083, gateway :8080
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\e2e\clinical_preop_visit_smoke.ps1
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

function OpenSurgery {
    param([string]$Code, [string]$Anesthesia, [string]$Incision)
    $r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/surgery-cases" @{
        operationCode = $Code; surgeon = 'doctor01'; anesthesiaType = $Anesthesia
        incisionLevel = $Incision
    } $token
    $script:planStatus = $r.Status
    $script:planId = (Res $r).id
}

function AddConsents {
    param([string]$CaseId, [string]$Label)
    $r = Invoke-Api 'POST' "/api/v2/clinical/surgery-cases/$CaseId/consents" @{
        consentType = 'SURGERY'; signerName = 'Patient'; signerRelation = 'SELF'
    } $token
    Check "$Label.consent.surgery" $r.Status 200
    $r = Invoke-Api 'POST' "/api/v2/clinical/surgery-cases/$CaseId/consents" @{
        consentType = 'ANESTHESIA'; signerName = 'Patient'; signerRelation = 'SELF'
    } $token
    Check "$Label.consent.anesthesia" $r.Status 200
}

$suffix = (Get-Date).ToString('HHmmssff')
$deptId = '11111111-1111-1111-1111-111111111111'

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
    name = "Visit Patient $suffix"; gender = 'FEMALE'; birthDate = '1971-02-02'
    idType = 'ID_CARD'; idNo = "11010119710202$suffix"
} $token
Check 'patient.create' $r.Status 200
$patientId = (Res $r).id

$r = Invoke-Api 'POST' '/api/v2/clinical/wards' @{
    code = "WARD-PV$suffix"; name = "Visit Ward $suffix"; deptId = $deptId
} $token
Check 'ward.create' $r.Status 200
$wardId = (Res $r).id

$r = Invoke-Api 'POST' "/api/v2/clinical/wards/$wardId/beds" @{ bedNos = @('V1') } $token
Check 'bed.create' $r.Status 200
$bedId = (Res $r)[0].id

$r = Invoke-Api 'POST' '/api/v2/clinical/inpatient/admissions' @{
    patientId = $patientId; wardId = $wardId; bedId = $bedId; attendingDoctor = 'doctor01'
} $token
Check 'admission.create' $r.Status 200
$visitId = (Res $r).visitId

# ---------------------------------------------------------------- 3. case A: level 1 needs no visit
OpenSurgery '47.0901' 'GENERAL' 'II'
Check 'caseA.plan' $script:planStatus 200
$caseA = $script:planId

$r = Invoke-Api 'PUT' "/api/v2/clinical/surgery-cases/$caseA/level" @{ level = 1 } $token
Check 'caseA.level' $r.Status 200

$r = Invoke-Api 'GET' "/api/v2/clinical/surgery-cases/$caseA/preop-visit" $null $token
Check 'caseA.visit.unset' $r.Status 200
Check 'caseA.visit.unset.data' "$(Res $r)" ''

AddConsents $caseA 'caseA'
$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/surgery-cases/$caseA/start" @{ version = 0 } $token
Check 'caseA.start.withoutVisit' $r.Status 200
$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/surgery-cases/$caseA/complete" @{ version = 1 } $token
Check 'caseA.complete' $r.Status 200

# ---------------------------------------------------------------- 4. case B: level 4 requires a signed visit
OpenSurgery '81.5101' 'GENERAL' 'III'
Check 'caseB.plan' $script:planStatus 200
$caseB = $script:planId

$r = Invoke-Api 'PUT' "/api/v2/clinical/surgery-cases/$caseB/level" @{ level = 4 } $token
Check 'caseB.level.pending' $r.Status 200
$r = Invoke-Api 'POST' "/api/v2/clinical/surgery-cases/$caseB/level/approve" @{ version = 0 } $qcToken
Check 'caseB.level.approve' $r.Status 200

AddConsents $caseB 'caseB'

$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/surgery-cases/$caseB/start" @{ version = 0 } $token
Check 'caseB.start.noVisit.422' $r.Status 422

$r = Invoke-Api 'PUT' "/api/v2/clinical/surgery-cases/$caseB/preop-visit" @{
    visitedBy = 'doctor01'
} $token
Check 'caseB.visit.draft' $r.Status 200
Check 'caseB.visit.draft.status' (Res $r).status 'DRAFT'

$r = Invoke-Api 'POST' "/api/v2/clinical/surgery-cases/$caseB/preop-visit/sign" @{ version = 0 } $token
Check 'caseB.visit.sign.incomplete.422' $r.Status 422

$r = Invoke-Api 'PUT' "/api/v2/clinical/surgery-cases/$caseB/preop-visit" @{
    visitedBy = 'doctor01'; anesthesiaPlan = 'GENERAL'; asaGrade = 'III'; mallampati = 'II'
    airwayNote = 'short neck'; anesthesiaHistory = 'no prior anesthesia'
    allergyHistory = 'none'; fastingSolidHours = 8; fastingClearHours = 2
    conclusion = 'FIT_WITH_CAUTION'; riskNote = 'monitor airway'; version = 0
} $token
Check 'caseB.visit.update' $r.Status 200
Check 'caseB.visit.update.version' (Res $r).version 1

$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/surgery-cases/$caseB/start" @{ version = 0 } $token
Check 'caseB.start.unsignedVisit.422' $r.Status 422

$r = Invoke-Api 'POST' "/api/v2/clinical/surgery-cases/$caseB/preop-visit/sign" @{ version = 1 } $token
Check 'caseB.visit.sign' $r.Status 200
Check 'caseB.visit.sign.status' (Res $r).status 'SIGNED'
Check 'caseB.visit.sign.by' (Res $r).signedBy $Username

$r = Invoke-Api 'PUT' "/api/v2/clinical/surgery-cases/$caseB/preop-visit" @{
    visitedBy = 'doctor01'; version = 2
} $token
Check 'caseB.visit.afterSign.409' $r.Status 409

$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/surgery-cases/$caseB/start" @{ version = 0 } $token
Check 'caseB.start.ok' $r.Status 200
Check 'caseB.start.status' (Res $r).status 'IN_PROGRESS'
$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/surgery-cases/$caseB/complete" @{ version = 1 } $token
Check 'caseB.complete' $r.Status 200

# ---------------------------------------------------------------- 5. case C: UNFIT blocks any level
OpenSurgery '51.2301' 'LOCAL' 'II'
Check 'caseC.plan' $script:planStatus 200
$caseC = $script:planId

$r = Invoke-Api 'PUT' "/api/v2/clinical/surgery-cases/$caseC/level" @{ level = 1 } $token
Check 'caseC.level' $r.Status 200
AddConsents $caseC 'caseC'

$r = Invoke-Api 'PUT' "/api/v2/clinical/surgery-cases/$caseC/preop-visit" @{
    visitedBy = 'doctor01'; anesthesiaPlan = 'LOCAL'; asaGrade = 'IV'; mallampati = 'III'
    fastingSolidHours = 6; fastingClearHours = 2; conclusion = 'UNFIT'
    riskNote = 'unstable cardiac status'
} $token
Check 'caseC.visit.unfit.draft' $r.Status 200

$r = Invoke-Api 'POST' "/api/v2/clinical/surgery-cases/$caseC/preop-visit/sign" @{ version = 0 } $token
Check 'caseC.visit.unfit.sign' $r.Status 200

$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/surgery-cases/$caseC/start" @{ version = 0 } $token
Check 'caseC.start.unfit.422' $r.Status 422

# ---------------------------------------------------------------- 6. permission
$r = Invoke-Api 'GET' "/api/v2/clinical/surgery-cases/$caseB/preop-visit" $null $qcToken
Check 'perm.qc.read' $r.Status 200

$r = Invoke-Api 'PUT' "/api/v2/clinical/surgery-cases/$caseB/preop-visit" @{
    visitedBy = 'qc'
} $qcToken
Check 'perm.qc.write.403' $r.Status 403

Write-Output "-----"
Write-Output "VISIT_ID=$visitId"
Write-Output "CASE_A=$caseA"
Write-Output "CASE_B=$caseB"
Write-Output "CASE_C=$caseC"
if ($script:fail -gt 0) { Write-Output "RESULT FAIL ($script:fail)"; exit 1 }
Write-Output 'RESULT PASS'
exit 0
