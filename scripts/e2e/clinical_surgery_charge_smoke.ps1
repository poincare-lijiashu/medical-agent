# ============================================================================
# MedAssist surgery charge package smoke test (batch 5).
#
# Covers: complete surgery -> charge preview (operating room fee by category,
#         anesthesia fee by method, material kit) -> idempotent charge
#         -> charge sheet totals -> second surgery (OBGYN/REGIONAL) priced
#         differently -> charge blocked before completion -> third surgery
#         with anesthesia NONE (no anesthesia fee) -> repeat charge adds 0
#         -> permission (QC cannot read or settle charges).
#
# Expected amounts come from clinical V19 seeds:
#   SURG-GENERAL 800 / SURG-OBGYN 1500 / SURG-NEPH 700
#   ANES-GENERAL 600 / ANES-REGIONAL 400 / MAT-OPKIT 150
#
# ASCII only (Windows PowerShell 5.1 reads BOM-less UTF-8 as ANSI).
# Prerequisites: auth :8081, empi :8082, clinical :8083, gateway :8080
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\e2e\clinical_surgery_charge_smoke.ps1
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

function Money($v) { return [math]::Round([double]$v, 2) }

function OpenSurgery {
    param([string]$Code, [string]$Anesthesia, [string]$Incision)
    $r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/surgery-cases" @{
        operationCode = $Code; surgeon = 'doctor01'; anesthesiaType = $Anesthesia
        incisionLevel = $Incision
    } $token
    $script:planStatus = $r.Status
    $script:planId = (Res $r).id
}

function PrepareCase {
    param([string]$CaseId, [string]$Label)
    $r = Invoke-Api 'PUT' "/api/v2/clinical/surgery-cases/$CaseId/level" @{ level = 1 } $token
    Check "$Label.level" $r.Status 200
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
    name = "Charge Patient $suffix"; gender = 'MALE'; birthDate = '1970-05-05'
    idType = 'ID_CARD'; idNo = "11010119700505$suffix"
} $token
Check 'patient.create' $r.Status 200
$patientId = (Res $r).id

$r = Invoke-Api 'POST' '/api/v2/clinical/wards' @{
    code = "WARD-CH$suffix"; name = "Charge Ward $suffix"; deptId = $deptId
} $token
Check 'ward.create' $r.Status 200
$wardId = (Res $r).id

$r = Invoke-Api 'POST' "/api/v2/clinical/wards/$wardId/beds" @{ bedNos = @('C1') } $token
Check 'bed.create' $r.Status 200
$bedId = (Res $r)[0].id

$r = Invoke-Api 'POST' '/api/v2/clinical/inpatient/admissions' @{
    patientId = $patientId; wardId = $wardId; bedId = $bedId; attendingDoctor = 'doctor01'
} $token
Check 'admission.create' $r.Status 200
$visitId = (Res $r).visitId

# ---------------------------------------------------------------- 3. case A: general surgery
OpenSurgery '47.0901' 'GENERAL' 'II'
Check 'caseA.plan' $script:planStatus 200
$caseA = $script:planId
PrepareCase $caseA 'caseA'

$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/surgery-cases/$caseA/start" @{ version = 0 } $token
Check 'caseA.start' $r.Status 200
$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/surgery-cases/$caseA/complete" @{ version = 1 } $token
Check 'caseA.complete' $r.Status 200

$r = Invoke-Api 'GET' "/api/v2/clinical/surgery-cases/$caseA/charge-preview" $null $token
Check 'caseA.preview' $r.Status 200
$previewA = Res $r
Check 'caseA.preview.codes' (@($previewA.lines).itemCode -join ',') 'SURG-GENERAL,ANES-GENERAL,MAT-OPKIT'
Check 'caseA.preview.total' (Money $previewA.totalAmount) 1550
Check 'caseA.preview.pending' (Money $previewA.pendingAmount) 1550
Check 'caseA.preview.fullyCharged' $previewA.fullyCharged 'False'

$r = Invoke-Api 'POST' "/api/v2/clinical/surgery-cases/$caseA/charges" $null $token
Check 'caseA.charge' $r.Status 200
Check 'caseA.charge.added' (Money (Res $r).addedAmount) 1550
Check 'caseA.charge.lines' (@((Res $r).lines).Count) 3
Check 'caseA.charge.total' (Money (Res $r).chargeTotal) 1550
$chargeNoA = (Res $r).chargeNo

$r = Invoke-Api 'POST' "/api/v2/clinical/surgery-cases/$caseA/charges" $null $token
Check 'caseA.charge.repeat' $r.Status 200
Check 'caseA.charge.repeat.added' (Money (Res $r).addedAmount) 0
Check 'caseA.charge.repeat.lines' (@((Res $r).lines).Count) 0
Check 'caseA.charge.repeat.chargeNo' (Res $r).chargeNo $chargeNoA

$r = Invoke-Api 'GET' "/api/v2/clinical/surgery-cases/$caseA/charge-preview" $null $token
Check 'caseA.preview.after.fullyCharged' (Res $r).fullyCharged 'True'
Check 'caseA.preview.after.pending' (Money (Res $r).pendingAmount) 0

$r = Invoke-Api 'GET' "/api/v2/clinical/visits/$visitId/charge" $null $token
Check 'charge.sheet' $r.Status 200
Check 'charge.sheet.items' (@((Res $r).items).Count) 3
Check 'charge.sheet.total' (Money (Res $r).totalAmount) 1550

# ---------------------------------------------------------------- 4. case B: OBGYN, blocked before completion
OpenSurgery '74.1' 'REGIONAL' 'II'
Check 'caseB.plan' $script:planStatus 200
$caseB = $script:planId
$r = Invoke-Api 'GET' "/api/v2/clinical/surgery-cases/$caseB/charge-preview" $null $token
Check 'caseB.preview.beforeComplete' $r.Status 200
Check 'caseB.preview.codes' (@((Res $r).lines).itemCode -join ',') 'SURG-OBGYN,ANES-REGIONAL,MAT-OPKIT'
Check 'caseB.preview.total' (Money (Res $r).totalAmount) 2050

$r = Invoke-Api 'POST' "/api/v2/clinical/surgery-cases/$caseB/charges" $null $token
Check 'caseB.charge.beforeComplete.422' $r.Status 422

PrepareCase $caseB 'caseB'
$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/surgery-cases/$caseB/start" @{ version = 0 } $token
Check 'caseB.start' $r.Status 200
$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/surgery-cases/$caseB/complete" @{ version = 1 } $token
Check 'caseB.complete' $r.Status 200

$r = Invoke-Api 'POST' "/api/v2/clinical/surgery-cases/$caseB/charges" $null $token
Check 'caseB.charge' $r.Status 200
Check 'caseB.charge.added' (Money (Res $r).addedAmount) 2050
Check 'caseB.charge.total' (Money (Res $r).chargeTotal) 3600

# ---------------------------------------------------------------- 5. case C: nephrology, no anesthesia fee
OpenSurgery '39.9501' 'NONE' 'I'
Check 'caseC.plan' $script:planStatus 200
$caseC = $script:planId
PrepareCase $caseC 'caseC'
$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/surgery-cases/$caseC/start" @{ version = 0 } $token
Check 'caseC.start' $r.Status 200
$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/surgery-cases/$caseC/complete" @{ version = 1 } $token
Check 'caseC.complete' $r.Status 200

$r = Invoke-Api 'GET' "/api/v2/clinical/surgery-cases/$caseC/charge-preview" $null $token
Check 'caseC.preview.codes' (@((Res $r).lines).itemCode -join ',') 'SURG-NEPH,MAT-OPKIT'
Check 'caseC.preview.total' (Money (Res $r).totalAmount) 850

$r = Invoke-Api 'POST' "/api/v2/clinical/surgery-cases/$caseC/charges" $null $token
Check 'caseC.charge' $r.Status 200
Check 'caseC.charge.added' (Money (Res $r).addedAmount) 850

$r = Invoke-Api 'GET' "/api/v2/clinical/visits/$visitId/charge" $null $token
Check 'charge.sheet.final.items' (@((Res $r).items).Count) 8
Check 'charge.sheet.final.total' (Money (Res $r).totalAmount) 4450

# ---------------------------------------------------------------- 6. permission (QC has no charge perms)
$r = Invoke-Api 'GET' "/api/v2/clinical/surgery-cases/$caseA/charge-preview" $null $qcToken
Check 'perm.qc.preview.403' $r.Status 403

$r = Invoke-Api 'POST' "/api/v2/clinical/surgery-cases/$caseA/charges" $null $qcToken
Check 'perm.qc.charge.403' $r.Status 403

Write-Output "-----"
Write-Output "VISIT_ID=$visitId"
Write-Output "CASE_A=$caseA"
Write-Output "CASE_B=$caseB"
Write-Output "CASE_C=$caseC"
Write-Output "CHARGE_NO=$chargeNoA"
if ($script:fail -gt 0) { Write-Output "RESULT FAIL ($script:fail)"; exit 1 }
Write-Output 'RESULT PASS'
exit 0
