# ============================================================================
# MedAssist HQMS homepage export smoke test, through the gateway.
#
# Covers: archived homepage -> export batch (payload + hash) -> only ARCHIVED
#         homes are included -> duplicate export 409 -> payload download (PHI)
#         -> reject without reason 422 -> reject releases homes -> re-export
#         -> cancel -> re-export -> accept -> receipt twice 409 -> cancel after
#         receipt 409 -> permission (doctor 403).
#
# ASCII only (Windows PowerShell 5.1 reads BOM-less UTF-8 as ANSI).
# Prerequisites: auth :8081, empi :8082, clinical :8083, emr :8086, gateway :8080
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\e2e\emr_hqms_export_smoke.ps1
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

# Build a discharged inpatient and return its visit id / admission id.
function New-ArchivedHomepage($token, $qcToken, $suffix, $wardId, $bedId, $deptId, $archive) {
    $r = Invoke-Api 'POST' '/api/v2/empi/patients' @{
        name = "Hqms Patient $suffix"; gender = 'MALE'; birthDate = '1960-05-06'
        idType = 'ID_CARD'; idNo = "11010219600506$suffix"
    } $token
    $patientId = (Res $r).id

    $r = Invoke-Api 'POST' '/api/v2/clinical/inpatient/admissions' @{
        patientId = $patientId; wardId = $wardId; bedId = $bedId; attendingDoctor = 'doctor01'
    } $token
    $admissionId = (Res $r).id
    $visitId = (Res $r).visitId

    $r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/diagnoses" @{
        code = 'J18.900'; primary = $true
    } $token

    $r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/orders" @{
        items = @(@{ itemCode = 'TRT-IV'; quantity = 1; orderMode = 'TEMP' })
    } $token

    $r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/settle" @{ paymentMethod = 'CASH' } $token
    $r = Invoke-Api 'POST' "/api/v2/clinical/inpatient/admissions/$admissionId/discharge" $null $token

    $r = Invoke-Api 'POST' '/api/v2/emr/homepages' @{ visitId = $visitId } $token
    $homepageId = (Res $r).homepage.id
    $homepageNo = (Res $r).homepage.homepageNo
    $version = (Res $r).homepage.version

    $r = Invoke-Api 'PUT' "/api/v2/emr/homepages/$homepageId" @{
        version = $version
        dischargeMode = 'BY_DOCTOR'
        chiefPhysician = 'chief01'
        residentPhysician = 'resident01'
        coder = 'coder01'
    } $token

    if ($archive) {
        $r = Invoke-Api 'POST' "/api/v2/emr/homepages/$homepageId/submit" $null $token
        if ($r.Status -ne 200) { Write-Output "FATAL submit: $($r.Body)"; exit 1 }
        $r = Invoke-Api 'POST' "/api/v2/emr/homepages/$homepageId/archive" $null $qcToken
        if ($r.Status -ne 200) { Write-Output "FATAL archive: $($r.Body)"; exit 1 }
    }
    return [pscustomobject]@{ VisitId = $visitId; HomepageId = $homepageId; HomepageNo = $homepageNo }
}

$suffix = (Get-Date).ToString('HHmmssff')
$today = (Get-Date).ToString('yyyy-MM-dd')
$deptId = '11111111-1111-1111-1111-111111111111'

# ---------------------------------------------------------------- 1. login
$r = Invoke-Api 'POST' '/api/v2/auth/login' @{ username = $Username; password = $Password }
Check 'login' $r.Status 200
$token = (Res $r).accessToken
if (-not $token) { Write-Output "FATAL no token: $($r.Body)"; exit 1 }

$r = Invoke-Api 'POST' '/api/v2/auth/login' @{ username = 'qc01'; password = $Password }
Check 'login.qc' $r.Status 200
$qcToken = (Res $r).accessToken

$r = Invoke-Api 'POST' '/api/v2/auth/login' @{ username = 'doctor01'; password = $Password }
Check 'login.doctor' $r.Status 200
$doctorToken = (Res $r).accessToken

# ---------------------------------------------------------------- 2. ward + two homepages
$r = Invoke-Api 'POST' '/api/v2/clinical/wards' @{
    code = "WARD-HQ$suffix"; name = "Hqms Ward $suffix"; deptId = $deptId
} $token
Check 'ward.create' $r.Status 200
$wardId = (Res $r).id

$r = Invoke-Api 'POST' "/api/v2/clinical/wards/$wardId/beds" @{ bedNos = @('Q1') } $token
Check 'bed.create' $r.Status 200
$bedId = (Res $r)[0].id

# archived homepage (included in export)
$archived = New-ArchivedHomepage $token $qcToken "$suffix" $wardId $bedId $deptId $true
Check 'archived.homepageNo' ($archived.HomepageNo.StartsWith('HP')) 'True'

# draft homepage (must NOT be exported)
$draft = New-ArchivedHomepage $token $qcToken "${suffix}9" $wardId $bedId $deptId $false
Check 'draft.homepageNo' ($draft.HomepageNo.StartsWith('HP')) 'True'

# ---------------------------------------------------------------- 3. export batch
$r = Invoke-Api 'POST' "/api/v2/emr/hqms/export-batches?from=$today&to=$today" $null $qcToken
Check 'export.create' $r.Status 200
$batch = Res $r
$batchId = $batch.id
$batchNo = $batch.batchNo
Check 'export.status' $batch.status 'GENERATED'
Check 'export.noPrefix' ($batchNo.StartsWith('HQ')) 'True'
Check 'export.hash' $batch.payloadHash.Length 64
Check 'export.hasHomepages' ([int]$batch.homepageCount -ge 1) 'True'
Check 'export.periodFrom' $batch.periodFrom $today
Check 'export.periodTo' $batch.periodTo $today

# ---------------------------------------------------------------- 4. payload (PHI download)
$r = Invoke-Api 'GET' "/api/v2/emr/hqms/export-batches/$batchId/payload" $null $qcToken
Check 'payload.ok' $r.Status 200
Check 'payload.root' ($r.Body -match '<homepageBatch version="1"') 'True'
Check 'payload.containsArchived' ($r.Body -match $archived.HomepageNo) 'True'
Check 'payload.excludesDraft' ($r.Body -match $draft.HomepageNo) 'False'
Check 'payload.hasDiagnosis' ($r.Body -match '<diagnosis code="J18.900"') 'True'
Check 'payload.hasFee' ($r.Body -match '<fee category=') 'True'
Check 'payload.hasCoder' ($r.Body -match 'coder="coder01"') 'True'

$r = Invoke-Api 'GET' "/api/v2/emr/hqms/export-batches/$batchId/payload" $null $doctorToken
Check 'payload.doctor.403' $r.Status 403

# everything archived in the period is already in this batch
$r = Invoke-Api 'POST' "/api/v2/emr/hqms/export-batches?from=$today&to=$today" $null $qcToken
Check 'export.duplicate.409' $r.Status 409

# ---------------------------------------------------------------- 5. reject releases homepages
$r = Invoke-Api 'POST' "/api/v2/emr/hqms/export-batches/$batchId/receipt" @{
    status = 'REJECTED'; receiptNo = 'RJ-1'
} $qcToken
Check 'receipt.reject.noReason.422' $r.Status 422

$r = Invoke-Api 'POST' "/api/v2/emr/hqms/export-batches/$batchId/receipt" @{
    status = 'REJECTED'; receiptNo = "RJ-$suffix"; message = 'diagnosis code invalid'
} $qcToken
Check 'receipt.reject.ok' $r.Status 200
Check 'receipt.reject.status' (Res $r).status 'REJECTED'

$r = Invoke-Api 'POST' "/api/v2/emr/hqms/export-batches?from=$today&to=$today" $null $qcToken
Check 'export.afterReject.ok' $r.Status 200
$batch2Id = (Res $r).id

# ---------------------------------------------------------------- 6. cancel releases homepages
$r = Invoke-Api 'POST' "/api/v2/emr/hqms/export-batches/$batch2Id/cancel" @{ reason = ' ' } $qcToken
Check 'cancel.noReason.422' $r.Status 422

$r = Invoke-Api 'POST' "/api/v2/emr/hqms/export-batches/$batch2Id/cancel" @{
    reason = 'wrong period'
} $qcToken
Check 'cancel.ok' $r.Status 200
Check 'cancel.status' (Res $r).status 'CANCELLED'
Check 'cancel.reason' (Res $r).cancelledReason 'wrong period'

$r = Invoke-Api 'POST' "/api/v2/emr/hqms/export-batches/$batch2Id/cancel" @{ reason = 'again' } $qcToken
Check 'cancel.twice.409' $r.Status 409

$r = Invoke-Api 'POST' "/api/v2/emr/hqms/export-batches?from=$today&to=$today" $null $qcToken
Check 'export.afterCancel.ok' $r.Status 200
$batch3Id = (Res $r).id

# ---------------------------------------------------------------- 7. accept + terminal states
$r = Invoke-Api 'POST' "/api/v2/emr/hqms/export-batches/$batch3Id/receipt" @{
    status = 'ACCEPTED'; receiptNo = "RC-$suffix"
} $qcToken
Check 'receipt.accept.ok' $r.Status 200
Check 'receipt.accept.status' (Res $r).status 'ACCEPTED'
Check 'receipt.accept.no' (Res $r).receiptNo "RC-$suffix"

$r = Invoke-Api 'POST' "/api/v2/emr/hqms/export-batches/$batch3Id/receipt" @{
    status = 'ACCEPTED'; receiptNo = "RC2-$suffix"
} $qcToken
Check 'receipt.twice.409' $r.Status 409

$r = Invoke-Api 'POST' "/api/v2/emr/hqms/export-batches/$batch3Id/cancel" @{ reason = 'late' } $qcToken
Check 'cancel.afterReceipt.409' $r.Status 409

$r = Invoke-Api 'GET' "/api/v2/emr/hqms/export-batches?limit=20" $null $qcToken
Check 'list.ok' $r.Status 200
$hits = @(Res $r | Where-Object { $_.id -eq $batch3Id })
Check 'list.contains' $hits.Count 1
Check 'list.status' $hits[0].status 'ACCEPTED'

# ---------------------------------------------------------------- 8. permission
$r = Invoke-Api 'POST' "/api/v2/emr/hqms/export-batches?from=$today&to=$today" $null $doctorToken
Check 'perm.doctor.export.403' $r.Status 403

Write-Output "-----"
Write-Output "BATCH_NO=$batchNo"
Write-Output "HOMEPAGE_NO=$($archived.HomepageNo)"
if ($script:fail -gt 0) { Write-Output "RESULT FAIL ($script:fail)"; exit 1 }
Write-Output 'RESULT PASS'
exit 0
