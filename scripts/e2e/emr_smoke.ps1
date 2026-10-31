# ============================================================================
# MedAssist EMR smoke test, through the gateway.
#
# Covers: template -> draft -> QC (fatal blocks submit) -> submit -> sign
#         -> hash verify -> archive (3-level policy) -> amendment
#         -> CDR search / print -> archived change request (4-eyes)
#         -> timeliness QC with real admission (needs clinical).
#
# ASCII only (Windows PowerShell 5.1 reads BOM-less UTF-8 as ANSI).
# Prerequisites: auth :8081, empi :8082, clinical :8083, emr :8086, gateway :8080
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\e2e\emr_smoke.ps1
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

# timestamp-based suffix: random 4-digit values collide on idNo/ward unique indexes within a day
$suffix = (Get-Date).ToString('HHmmssff')

# ---------------------------------------------------------------- 1. login
$r = Invoke-Api 'POST' '/api/v2/auth/login' @{ username = $Username; password = $Password }
Check 'login' $r.Status 200
$token = (Res $r).accessToken
if (-not $token) { Write-Output "FATAL no token: $($r.Body)"; exit 1 }

# QC user: approves archived change requests (four-eyes principle)
$r = Invoke-Api 'POST' '/api/v2/auth/login' @{ username = 'qc01'; password = $Password }
Check 'login.qc' $r.Status 200
$qcToken = (Res $r).accessToken
if (-not $qcToken) { Write-Output "FATAL no qc token: $($r.Body)"; exit 1 }

# Attending doctor: MUST be a different person from the author (MF-02 signer identity)
$r = Invoke-Api 'POST' '/api/v2/auth/login' @{ username = 'doctor01'; password = $Password }
Check 'login.doctor' $r.Status 200
$doctorToken = (Res $r).accessToken
if (-not $doctorToken) { Write-Output "FATAL no doctor token: $($r.Body)"; exit 1 }

# ---------------------------------------------------------------- 2. patient
$r = Invoke-Api 'POST' '/api/v2/empi/patients' @{
    name = "Emr Patient $suffix"; gender = 'MALE'; birthDate = '1955-02-03'
    idType = 'ID_CARD'; idNo = "11010119550203$suffix"
} $token
Check 'patient.create' $r.Status 200
$patientId = (Res $r).id

# ---------------------------------------------------------------- 3. template
$r = Invoke-Api 'GET' '/api/v2/emr/templates?docType=ADMISSION_NOTE' $null $token
Check 'template.list' $r.Status 200
$templates = @(Res $r)
Check 'template.seeded' $templates.Count 1
Check 'template.code' $templates[0].templateCode 'TPL-ADMISSION'

# ---------------------------------------------------------------- 4. draft
$r = Invoke-Api 'POST' '/api/v2/emr/records' @{
    patientId = $patientId
    templateCode = 'TPL-ADMISSION'
    title = 'Admission Note'
    content = @{ physicalExam = 'pharynx red' }
} $token
Check 'record.create' $r.Status 200
$rec = Res $r
$recordId = $rec.record.id
$recordNo = $rec.record.recordNo
Check 'record.draft' $rec.record.status 'DRAFT'
Check 'record.revision' $rec.record.revision 1

# unarchived print must carry the watermark (S-07)
$r = Invoke-Api 'GET' "/api/v2/emr/records/$recordId/print" $null $token
Check 'print.draft.ok' $r.Status 200
Check 'print.draft.watermark' ($r.Body -match 'NOT ARCHIVED') 'True'

# S-04: a visit that belongs to another patient must be rejected
$r = Invoke-Api 'POST' '/api/v2/empi/patients' @{
    name = "Other Patient $suffix"; gender = 'FEMALE'; birthDate = '1990-01-01'
    idType = 'ID_CARD'; idNo = "11010119900101$suffix"
} $token
$otherPatient = (Res $r).id
$r = Invoke-Api 'POST' '/api/v2/clinical/registrations' @{
    patientId = $otherPatient; deptId = '11111111-1111-1111-1111-111111111111'
    doctorId = 'admin01'; visitType = 'OUTPATIENT'
} $token
Check 'record.visitOwnership.reg' $r.Status 200
$otherVisit = (Res $r).id

$r = Invoke-Api 'POST' '/api/v2/emr/records' @{
    patientId = $patientId; visitId = $otherVisit; templateCode = 'TPL-ADMISSION'
    title = 'Cross Patient Note'; content = @{ physicalExam = 'x' }
} $token
Check 'record.visitOwnership.422' $r.Status 422

# QC preview: chief complaint missing => fatal
$r = Invoke-Api 'GET' "/api/v2/emr/records/$recordId/qc" $null $token
Check 'qc.preview' $r.Status 200
Check 'qc.preview.blocked' (Res $r).passed 'False'
Check 'qc.preview.fatal' (Res $r).fatalCount 1

$r = Invoke-Api 'POST' "/api/v2/emr/records/$recordId/submit" $null $token
Check 'submit.blockedByQc.409' $r.Status 409

# ---------------------------------------------------------------- 5. save complete content
$full = @{
    chiefComplaint = 'fever for 3 days'
    presentIllness = 'fever up to 39C with sore throat'
    physicalExam = 'pharynx red, no rash'
    initialDiagnosis = 'acute upper respiratory infection'
}
$r = Invoke-Api 'PUT' "/api/v2/emr/records/$recordId" @{ content = $full; version = $rec.record.version } $token
Check 'record.save' $r.Status 200
Check 'record.save.status' (Res $r).record.status 'DRAFT'
$recVersion = (Res $r).record.version

$r = Invoke-Api 'GET' "/api/v2/emr/records/$recordId/qc" $null $token
Check 'qc.preview.passed' (Res $r).passed 'True'

# ---------------------------------------------------------------- 6. submit + sign
$r = Invoke-Api 'POST' "/api/v2/emr/records/$recordId/submit" $null $token
Check 'submit.ok' $r.Status 200
Check 'submit.status' (Res $r).record.record.status 'SUBMITTED'
Check 'submit.qc.passed' (Res $r).qc.passed 'True'

$r = Invoke-Api 'POST' "/api/v2/emr/records/$recordId/sign" $null $token
Check 'sign.ok' $r.Status 200
$signed = Res $r
Check 'sign.status' $signed.record.status 'SIGNED'
Check 'sign.hashPresent' ($signed.record.contentHash.Length -eq 64) 'True'
Check 'sign.signatureCount' $signed.signatures.Count 1
Check 'sign.signatureType' $signed.signatures[0].signType 'AUTHOR'

$r = Invoke-Api 'GET' "/api/v2/emr/records/$recordId/verify" $null $token
Check 'verify.ok' $r.Status 200
Check 'verify.valid' (Res $r).valid 'True'
Check 'verify.signaturesValid' (Res $r).signaturesValid 'True'

# ---------------------------------------------------------------- 7. archive needs attending signature
$r = Invoke-Api 'POST' "/api/v2/emr/records/$recordId/archive" $null $token
Check 'archive.blockedBeforeAttending.409' $r.Status 409

# self countersign must be rejected: author (admin01) cannot sign ATTENDING
$r = Invoke-Api 'POST' "/api/v2/emr/records/$recordId/sign" @{ level = 'ATTENDING' } $token
Check 'sign.attending.self.409' $r.Status 409

$r = Invoke-Api 'POST' "/api/v2/emr/records/$recordId/sign" @{ level = 'ATTENDING' } $doctorToken
Check 'sign.attending.ok' $r.Status 200
Check 'sign.attending.count' ((Res $r).signatures | Where-Object { $_.revision -eq 1 }).Count 2
Check 'sign.attending.type' ((Res $r).signatures | Where-Object { $_.signType -eq 'ATTENDING' }).signer 'doctor01'

$r = Invoke-Api 'POST' "/api/v2/emr/records/$recordId/archive" $null $token
Check 'archive.ok' $r.Status 200
Check 'archive.status' (Res $r).record.status 'ARCHIVED'

$r = Invoke-Api 'POST' "/api/v2/emr/records/$recordId/amend" @{
    content = $full; reason = 'try to amend archived record'
} $token
Check 'amend.archived.409' $r.Status 409

# ---------------------------------------------------------------- 7.1 CDR search + print view
$r = Invoke-Api 'GET' "/api/v2/emr/records/search?q=pharyngitis&docType=ADMISSION_NOTE" $null $token
Check 'search.ok' $r.Status 200
Check 'search.hits' (@(Res $r).Count -ge 1) 'True'

# record_no / patient_no use prefix matching (index-friendly)
$prefix = $recordNo.Substring(0, 10)
$r = Invoke-Api 'GET' "/api/v2/emr/records/search?q=$prefix" $null $token
Check 'search.byRecordNoPrefix.ok' $r.Status 200
$prefixHits = @(Res $r)
$matched = @($prefixHits | Where-Object { $_.recordNo -eq $recordNo })
Check 'search.byRecordNoPrefix.hit' $matched.Count 1

# "%" must be escaped, never a match-all
$r = Invoke-Api 'GET' '/api/v2/emr/records/search?q=%25' $null $token
Check 'search.wildcardEscaped' (@(Res $r).Count) 0

# N-08: keyword longer than 200 chars must be rejected (no silent truncation)
$longQ = 'x' * 201
$r = Invoke-Api 'GET' "/api/v2/emr/records/search?q=$longQ" $null $token
Check 'search.tooLongKeyword.422' $r.Status 422

$r = Invoke-Api 'GET' "/api/v2/emr/records/$recordId/print" $null $token
Check 'print.ok' $r.Status 200
Check 'print.hasHash' ($r.Body -match 'SHA-256') 'True'
Check 'print.archived.noWatermark' ($r.Body -match 'NOT ARCHIVED') 'False'

# ---------------------------------------------------------------- 7.2 archived change request
$revised = $full.Clone()
$revised['initialDiagnosis'] = 'acute pharyngitis, bacterial superinfection'
$r = Invoke-Api 'POST' "/api/v2/emr/records/$recordId/change-requests" @{
    content = $revised; reason = 'add culture result to diagnosis'
} $token
Check 'change.request.ok' $r.Status 200
$reqId = (Res $r).id

$r = Invoke-Api 'GET' "/api/v2/emr/records/$recordId/change-requests" $null $token
Check 'change.list.count' (@(Res $r).Count) 1
Check 'change.list.status' (@(Res $r))[0].status 'PENDING'

$r = Invoke-Api 'POST' "/api/v2/emr/records/$recordId/change-requests/$reqId/approve" @{
    comment = 'self approve'
} $token
Check 'change.selfApprove.409' $r.Status 409

$r = Invoke-Api 'POST' "/api/v2/emr/records/$recordId/change-requests/$reqId/approve" @{
    comment = 'approved by QC'
} $qcToken
Check 'change.approve.ok' $r.Status 200
Check 'change.approve.status' (Res $r).record.status 'SUBMITTED'
Check 'change.approve.revision' (Res $r).record.revision 2
Check 'change.approve.signatureCleared' ("$((Res $r).record.contentHash)" -eq '') 'True'
Check 'change.approve.historyCount' (@((Res $r).amendments).Count) 1

$r = Invoke-Api 'GET' "/api/v2/emr/records/$recordId/verify" $null $token
Check 'change.verify.invalidUntilResign' (Res $r).valid 'False'

# ---------------------------------------------------------------- 8. second record: amend keeps history
$r = Invoke-Api 'POST' '/api/v2/emr/records' @{
    patientId = $patientId; templateCode = 'TPL-ADMISSION'; title = 'Admission Note 2'; content = $full
} $token
Check 'record2.create' $r.Status 200
$record2Id = (Res $r).record.id

$r = Invoke-Api 'POST' "/api/v2/emr/records/$record2Id/submit" $null $token
Check 'record2.submit' $r.Status 200

$r = Invoke-Api 'POST' "/api/v2/emr/records/$record2Id/sign" $null $token
Check 'record2.sign' $r.Status 200

$amended = $full.Clone()
$amended['initialDiagnosis'] = 'acute pharyngitis, viral'
$r = Invoke-Api 'POST' "/api/v2/emr/records/$record2Id/amend" @{
    content = $amended; reason = 'diagnosis refined after recheck'
} $token
Check 'amend.ok' $r.Status 200
$amendedView = Res $r
Check 'amend.revision' $amendedView.record.revision 2
Check 'amend.backToSubmitted' $amendedView.record.status 'SUBMITTED'
Check 'amend.signatureCleared' ("$($amendedView.record.contentHash)" -eq '') 'True'
Check 'amend.historyCount' $amendedView.amendments.Count 1
Check 'amend.historyRevision' $amendedView.amendments[0].revision 1

$r = Invoke-Api 'GET' "/api/v2/emr/records/$record2Id/verify" $null $token
Check 'amend.verify.invalidUntilResign' (Res $r).valid 'False'

# ---------------------------------------------------------------- 9. re-sign + archive + rejected change request
$r = Invoke-Api 'POST' "/api/v2/emr/records/$record2Id/sign" $null $token
Check 'record2.resign.author' $r.Status 200
Check 'record2.resign.authorCount' (Res $r).signatures.Count 2

$r = Invoke-Api 'POST' "/api/v2/emr/records/$record2Id/sign" @{ level = 'ATTENDING' } $doctorToken
Check 'record2.resign.attending' $r.Status 200

$r = Invoke-Api 'POST' "/api/v2/emr/records/$record2Id/archive" $null $token
Check 'record2.archive' $r.Status 200

$r = Invoke-Api 'POST' "/api/v2/emr/records/$record2Id/change-requests" @{
    content = $full; reason = 'revert diagnosis wording'
} $token
Check 'record2.change.request' $r.Status 200
$req2Id = (Res $r).id

$r = Invoke-Api 'POST' "/api/v2/emr/records/$record2Id/change-requests/$req2Id/reject" @{
    comment = 'insufficient evidence'
} $qcToken
Check 'record2.change.reject.ok' $r.Status 200
Check 'record2.change.reject.status' (Res $r).status 'REJECTED'

$r = Invoke-Api 'GET' "/api/v2/emr/records/$record2Id" $null $token
Check 'record2.change.reject.recordStillArchived' (Res $r).record.status 'ARCHIVED'

# ---------------------------------------------------------------- 9.1 withdraw + resubmit (S-06)
$r = Invoke-Api 'POST' "/api/v2/emr/records/$record2Id/change-requests" @{
    content = $full; reason = 'second attempt'; resubmitOf = $req2Id
} $token
Check 'change.resubmit.ok' $r.Status 200
Check 'change.resubmit.link' (Res $r).resubmitOf $req2Id
$req3Id = (Res $r).id

$r = Invoke-Api 'POST' "/api/v2/emr/records/$record2Id/change-requests/$req3Id/withdraw" $null $doctorToken
Check 'change.withdraw.other.409' $r.Status 409

$r = Invoke-Api 'POST' "/api/v2/emr/records/$record2Id/change-requests/$req3Id/withdraw" $null $token
Check 'change.withdraw.ok' $r.Status 200
Check 'change.withdraw.status' (Res $r).status 'WITHDRAWN'

# withdraw releases the single-PENDING slot, so a new request can be filed
$r = Invoke-Api 'POST' "/api/v2/emr/records/$record2Id/change-requests" @{
    content = $full; reason = 'third attempt'; resubmitOf = $req3Id
} $token
Check 'change.afterWithdraw.ok' $r.Status 200
Check 'change.afterWithdraw.status' (Res $r).status 'PENDING'

# ---------------------------------------------------------------- 10. timeliness QC (needs clinical :8083)
$deptId = '11111111-1111-1111-1111-111111111111'
$r = Invoke-Api 'POST' '/api/v2/clinical/wards' @{
    code = "WARD-T$suffix"; name = "Timeliness Ward $suffix"; deptId = $deptId
} $token
Check 'ward.create' $r.Status 200
$wardId = (Res $r).id

$r = Invoke-Api 'POST' "/api/v2/clinical/wards/$wardId/beds" @{ bedNos = @('A1') } $token
Check 'bed.create' $r.Status 200
$bedId = (Res $r)[0].id

$r = Invoke-Api 'POST' '/api/v2/clinical/inpatient/admissions' @{
    patientId = $patientId; wardId = $wardId; bedId = $bedId; attendingDoctor = 'admin01'
} $token
Check 'admission.create' $r.Status 200
$visitId = (Res $r).visitId
Check 'admission.visitIdPresent' ("$visitId".Length -gt 0) 'True'

$r = Invoke-Api 'POST' '/api/v2/emr/records' @{
    patientId = $patientId; visitId = $visitId; templateCode = 'TPL-ADMISSION'
    title = 'Admission Note Timeliness'; content = $full
} $token
Check 'timeliness.record.create' $r.Status 200
$timelinessRecordId = (Res $r).record.id

$r = Invoke-Api 'GET' "/api/v2/emr/records/$timelinessRecordId/qc" $null $token
Check 'timeliness.qc.ok' $r.Status 200
$timeRules = @((Res $r).results | Where-Object { $_.ruleCode -eq 'QC-ADM-TIME' })
Check 'timeliness.rule.present' $timeRules.Count 1
Check 'timeliness.rule.passed' $timeRules[0].passed 'True'

# ---------------------------------------------------------------- 11. first course 8h rule (S-28)
$r = Invoke-Api 'POST' '/api/v2/emr/templates' @{
    templateCode = "TPL-FC-$suffix"; name = 'First Course'
    docType = 'FIRST_COURSE'; scope = 'INPATIENT'
    schemaJson = '[{"key":"course","label":"Course","type":"TEXTAREA","required":true}]'
} $qcToken
Check 'tpl.first.manage' $r.Status 200

$r = Invoke-Api 'POST' '/api/v2/emr/records' @{
    patientId = $patientId; visitId = $visitId; templateCode = "TPL-FC-$suffix"
    title = 'First Course Note'; content = @{ course = 'admitted, plan ordered' }
} $token
Check 'first.record.create' $r.Status 200
$firstRecordId = (Res $r).record.id

$r = Invoke-Api 'GET' "/api/v2/emr/records/$firstRecordId/qc" $null $token
Check 'first.qc.ok' $r.Status 200
$fcRules = @((Res $r).results | Where-Object { $_.ruleCode -eq 'QC-FC-TIME' })
Check 'first.qc.rulePresent' $fcRules.Count 1
Check 'first.qc.rulePassed' $fcRules[0].passed 'True'

# ---------------------------------------------------------------- 12. three-level signing + archive (S-28)
$r = Invoke-Api 'POST' '/api/v2/auth/users' @{
    username = "chief$suffix"; password = 'Med@2026'; displayName = 'Chief Physician'
    roles = @('DOCTOR')
} $token
Check 'chief.user.create' $r.Status 200

$r = Invoke-Api 'POST' '/api/v2/auth/login' @{ username = "chief$suffix"; password = 'Med@2026' }
Check 'login.chief' $r.Status 200
$chiefToken = (Res $r).accessToken

$r = Invoke-Api 'POST' '/api/v2/emr/templates' @{
    templateCode = "TPL-DIS-$suffix"; name = 'Discharge Summary'
    docType = 'DISCHARGE_SUMMARY'; scope = 'INPATIENT'
    schemaJson = '[{"key":"course","label":"Course","type":"TEXTAREA","required":true}]'
} $qcToken
Check 'tpl.discharge.manage' $r.Status 200

$r = Invoke-Api 'POST' '/api/v2/emr/records' @{
    patientId = $patientId; visitId = $visitId; templateCode = "TPL-DIS-$suffix"
    title = 'Discharge Summary'; content = @{ course = 'treated and improved' }
} $token
Check 'dis.record.create' $r.Status 200
$disRecordId = (Res $r).record.id

$r = Invoke-Api 'POST' "/api/v2/emr/records/$disRecordId/submit" $null $token
Check 'dis.submit' $r.Status 200

$r = Invoke-Api 'POST' "/api/v2/emr/records/$disRecordId/sign" @{ level = 'AUTHOR' } $token
Check 'dis.sign.author' $r.Status 200

$r = Invoke-Api 'POST' "/api/v2/emr/records/$disRecordId/sign" @{ level = 'ATTENDING' } $doctorToken
Check 'dis.sign.attending' $r.Status 200

$r = Invoke-Api 'POST' "/api/v2/emr/records/$disRecordId/sign" @{ level = 'CHIEF' } $token
Check 'dis.sign.chief.self.409' $r.Status 409

$r = Invoke-Api 'POST' "/api/v2/emr/records/$disRecordId/sign" @{ level = 'CHIEF' } $chiefToken
Check 'dis.sign.chief' $r.Status 200
Check 'dis.signatures.count' (@((Res $r).signatures).Count) 3
$chiefSigs = @((Res $r).signatures | Where-Object { $_.signType -eq 'CHIEF' })
Check 'dis.signatures.chiefPresent' $chiefSigs.Count 1

$r = Invoke-Api 'POST' "/api/v2/emr/records/$disRecordId/archive" $null $qcToken
Check 'dis.archive' $r.Status 200
Check 'dis.archive.status' (Res $r).record.status 'ARCHIVED'

# ---------------------------------------------------------------- 13. sign policy management (N-04)
$r = Invoke-Api 'GET' '/api/v2/emr/sign-policies' $null $token
Check 'policy.list' $r.Status 200
$policies = @(Res $r)
$disPolicy = @($policies | Where-Object { $_.docType -eq 'DISCHARGE_SUMMARY' })[0]
Check 'policy.discharge.level' $disPolicy.requiredLevel 3
$policyVersion = [int64]$disPolicy.version

# only QC/admin may change the archive signing policy
$r = Invoke-Api 'PUT' '/api/v2/emr/sign-policies/DISCHARGE_SUMMARY' @{
    requiredLevel = 3; version = $policyVersion } $doctorToken
Check 'policy.update.doctor.403' $r.Status 403

# an existing policy cannot be changed without the current version (no silent overwrite)
$r = Invoke-Api 'PUT' '/api/v2/emr/sign-policies/DISCHARGE_SUMMARY' @{ requiredLevel = 3 } $qcToken
Check 'policy.update.noVersion.422' $r.Status 422

$r = Invoke-Api 'PUT' '/api/v2/emr/sign-policies/DISCHARGE_SUMMARY' @{
    requiredLevel = 3; version = $policyVersion } $qcToken
Check 'policy.update' $r.Status 200
Check 'policy.update.level' (Res $r).requiredLevel 3
Check 'policy.update.versionBumped' (Res $r).version ($policyVersion + 1)

# stale version must be rejected (CAS)
$r = Invoke-Api 'PUT' '/api/v2/emr/sign-policies/DISCHARGE_SUMMARY' @{
    requiredLevel = 3; version = $policyVersion } $qcToken
Check 'policy.update.stale.409' $r.Status 409

$r = Invoke-Api 'PUT' '/api/v2/emr/sign-policies/NOT_A_DOC_TYPE' @{ requiredLevel = 1 } $qcToken
Check 'policy.update.badDocType.422' $r.Status 422

Write-Output "-----"
Write-Output "RECORD_ID=$recordId"
Write-Output "RECORD2_ID=$record2Id"
Write-Output "DISCHARGE_ID=$disRecordId"
if ($script:fail -gt 0) { Write-Output "RESULT FAIL ($script:fail)"; exit 1 }
Write-Output 'RESULT PASS'
exit 0
