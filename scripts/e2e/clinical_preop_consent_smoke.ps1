# ============================================================================
# MedAssist preop smoke test (discussion / informed consent / opening gate).
#
# Covers: summary starts with 2 missing consents -> discussion draft
#         -> sign without participants/plan 422 -> fill + sign -> frozen
#         -> opening blocked without consents 422 -> add SURGERY consent
#         -> duplicate type 409 -> summary still missing ANESTHESIA
#         -> add ANESTHESIA -> readyForSurgery true -> surgery starts
#         -> revoke requires reason / revoke + re-sign -> complete surgery
#         -> consent on finished surgery 422 -> permission (QC read-only).
#
# ASCII only (Windows PowerShell 5.1 reads BOM-less UTF-8 as ANSI).
# Prerequisites: auth :8081, empi :8082, clinical :8083, gateway :8080
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\e2e\clinical_preop_consent_smoke.ps1
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

# ---------------------------------------------------------------- 1. login
$r = Invoke-Api 'POST' '/api/v2/auth/login' @{ username = $Username; password = $Password }
Check 'login' $r.Status 200
$token = (Res $r).accessToken
if (-not $token) { Write-Output "FATAL no token: $($r.Body)"; exit 1 }

$r = Invoke-Api 'POST' '/api/v2/auth/login' @{ username = 'qc01'; password = $Password }
Check 'login.qc' $r.Status 200
$qcToken = (Res $r).accessToken

# ---------------------------------------------------------------- 2. patient + admission + surgery
$r = Invoke-Api 'POST' '/api/v2/empi/patients' @{
    name = "Preop Patient $suffix"; gender = 'FEMALE'; birthDate = '1972-09-09'
    idType = 'ID_CARD'; idNo = "11010119720909$suffix"
} $token
Check 'patient.create' $r.Status 200
$patientId = (Res $r).id

$r = Invoke-Api 'POST' '/api/v2/clinical/wards' @{
    code = "WARD-PO$suffix"; name = "Preop Ward $suffix"; deptId = $deptId
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

$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/surgery-cases" @{
    operationCode = '47.0901'; surgeon = 'doctor01'; anesthesiaType = 'GENERAL'; incisionLevel = 'II'
} $token
Check 'surgery.plan' $r.Status 200
$caseId = (Res $r).id

# level 1 => exempt from approval, but the record itself is mandatory before opening
$r = Invoke-Api 'PUT' "/api/v2/clinical/surgery-cases/$caseId/level" @{ level = 1 } $token
Check 'surgery.level' $r.Status 200

# ---------------------------------------------------------------- 3. preop summary baseline
$r = Invoke-Api 'GET' "/api/v2/clinical/surgery-cases/$caseId/preop-summary" $null $token
Check 'summary.baseline' $r.Status 200
$summary = Res $r
Check 'summary.baseline.missing' ($summary.missingConsents -join ',') 'SURGERY,ANESTHESIA'
Check 'summary.baseline.ready' $summary.readyForSurgery 'False'
Check 'summary.baseline.discussion' ("$($summary.discussion)") ''

# ---------------------------------------------------------------- 4. discussion
$r = Invoke-Api 'PUT' "/api/v2/clinical/surgery-cases/$caseId/preop-discussion" @{
    moderator = 'doctor01'
} $token
Check 'discussion.create' $r.Status 200
Check 'discussion.status' (Res $r).status 'DRAFT'

$r = Invoke-Api 'POST' "/api/v2/clinical/surgery-cases/$caseId/preop-discussion/sign" @{ version = 0 } $token
Check 'discussion.sign.incomplete.422' $r.Status 422

$r = Invoke-Api 'PUT' "/api/v2/clinical/surgery-cases/$caseId/preop-discussion" @{
    moderator = 'doctor01'; participants = 'doctor02,doctor03'
    diagnosisSummary = 'acute appendicitis'; procedurePlan = 'laparoscopic appendectomy'
    risks = 'bleeding, infection'; anesthesiaPlan = 'general anesthesia'
    bloodPreparation = 'type and screen'; version = 0
} $token
Check 'discussion.update' $r.Status 200
Check 'discussion.update.version' (Res $r).version 1

$r = Invoke-Api 'POST' "/api/v2/clinical/surgery-cases/$caseId/preop-discussion/sign" @{ version = 1 } $token
Check 'discussion.sign' $r.Status 200
Check 'discussion.sign.status' (Res $r).status 'SIGNED'
Check 'discussion.sign.by' (Res $r).signedBy 'admin01'

$r = Invoke-Api 'PUT' "/api/v2/clinical/surgery-cases/$caseId/preop-discussion" @{
    moderator = 'doctor01'; participants = 'doctor02'; procedurePlan = 'x'; version = 2
} $token
Check 'discussion.afterSign.update.409' $r.Status 409

# ---------------------------------------------------------------- 5. opening gate
$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/surgery-cases/$caseId/start" @{ version = 0 } $token
Check 'start.noConsent.422' $r.Status 422

$r = Invoke-Api 'POST' "/api/v2/clinical/surgery-cases/$caseId/consents" @{
    consentType = 'SURGERY'; signerName = 'Patient A'; signerRelation = 'SELF'; witness = 'nurse01'
} $token
Check 'consent.surgery' $r.Status 200
$surgeryConsentId = (Res $r).id
Check 'consent.surgery.type' (Res $r).consentType 'SURGERY'

$r = Invoke-Api 'POST' "/api/v2/clinical/surgery-cases/$caseId/consents" @{
    consentType = 'SURGERY'; signerName = 'Patient A'
} $token
Check 'consent.duplicate.409' $r.Status 409

$r = Invoke-Api 'GET' "/api/v2/clinical/surgery-cases/$caseId/preop-summary" $null $token
Check 'summary.afterSurgeryConsent.missing' ((Res $r).missingConsents -join ',') 'ANESTHESIA'
Check 'summary.afterSurgeryConsent.ready' (Res $r).readyForSurgery 'False'

$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/surgery-cases/$caseId/start" @{ version = 0 } $token
Check 'start.missingAnesthesia.422' $r.Status 422

$r = Invoke-Api 'POST' "/api/v2/clinical/surgery-cases/$caseId/consents" @{
    consentType = 'ANESTHESIA'; signerName = 'Patient A'; signerRelation = 'SELF'
} $token
Check 'consent.anesthesia' $r.Status 200

$r = Invoke-Api 'GET' "/api/v2/clinical/surgery-cases/$caseId/preop-summary" $null $token
Check 'summary.ready.missing' ((Res $r).missingConsents -join ',') ''
Check 'summary.ready' (Res $r).readyForSurgery 'True'
Check 'summary.discussionSigned' (Res $r).discussion.status 'SIGNED'
Check 'summary.consentCount' (@((Res $r).consents).Count) 2

$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/surgery-cases/$caseId/start" @{ version = 0 } $token
Check 'start.ok' $r.Status 200
Check 'start.status' (Res $r).status 'IN_PROGRESS'

# ---------------------------------------------------------------- 6. revoke + re-sign
$r = Invoke-Api 'POST' "/api/v2/clinical/surgery-cases/$caseId/consents" @{
    consentType = 'SPECIAL'; signerName = 'Patient A'; notes = 'special procedure'
} $token
Check 'consent.special' $r.Status 200

$r = Invoke-Api 'POST' "/api/v2/clinical/surgery-cases/$caseId/consents/$surgeryConsentId/revoke" @{
    version = 0; reason = '  '
} $token
Check 'consent.revoke.blankReason.422' $r.Status 422

$r = Invoke-Api 'POST' "/api/v2/clinical/surgery-cases/$caseId/consents/$surgeryConsentId/revoke" @{
    version = 0; reason = 'wrong signer name'
} $token
Check 'consent.revoke' $r.Status 200
Check 'consent.revoke.reason' (Res $r).revokeReason 'wrong signer name'
Check 'consent.revoke.revokedAt' ("$((Res $r).revokedAt)".Length -gt 0) 'True'

$r = Invoke-Api 'POST' "/api/v2/clinical/surgery-cases/$caseId/consents" @{
    consentType = 'SURGERY'; signerName = 'Patient B'; signerRelation = 'SELF'
} $token
Check 'consent.resign' $r.Status 200

# ---------------------------------------------------------------- 7. finish + post-op guard
$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/surgery-cases/$caseId/complete" @{ version = 1 } $token
Check 'surgery.complete' $r.Status 200
Check 'surgery.completed' (Res $r).status 'COMPLETED'

$r = Invoke-Api 'POST' "/api/v2/clinical/surgery-cases/$caseId/consents" @{
    consentType = 'BLOOD_TRANSFUSION'; signerName = 'Patient B'
} $token
Check 'consent.afterComplete.422' $r.Status 422

# ---------------------------------------------------------------- 8. permission
$r = Invoke-Api 'GET' "/api/v2/clinical/surgery-cases/$caseId/preop-summary" $null $qcToken
Check 'perm.qc.read' $r.Status 200

$r = Invoke-Api 'POST' "/api/v2/clinical/surgery-cases/$caseId/consents" @{
    consentType = 'SPECIAL'; signerName = 'qc'
} $qcToken
Check 'perm.qc.write.403' $r.Status 403

Write-Output "-----"
Write-Output "VISIT_ID=$visitId"
Write-Output "CASE_ID=$caseId"
if ($script:fail -gt 0) { Write-Output "RESULT FAIL ($script:fail)"; exit 1 }
Write-Output 'RESULT PASS'
exit 0
