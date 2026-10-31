# ============================================================================
# MedAssist tech (radiology/lab) smoke test, through the gateway.
#
# Covers: application -> specimen -> receive -> start -> report -> review
#         -> critical value -> acknowledge -> close -> amend.
#
# ASCII only (Windows PowerShell 5.1 reads BOM-less UTF-8 as ANSI).
# Prerequisites: auth :8081, empi :8082, tech :8085, gateway :18080
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\e2e\tech_smoke.ps1
# ============================================================================
param(
    [string]$BaseUrl = "http://127.0.0.1:18080",
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

$suffix = Get-Random -Minimum 1000 -Maximum 9999

# ---------------------------------------------------------------- 1. login
$r = Invoke-Api 'POST' '/api/v2/auth/login' @{ username = $Username; password = $Password }
Check 'login' $r.Status 200
$token = (Res $r).accessToken
if (-not $token) { Write-Output "FATAL no token: $($r.Body)"; exit 1 }

# ---------------------------------------------------------------- 2. patient
$r = Invoke-Api 'POST' '/api/v2/empi/patients' @{
    name = "Tech Patient $suffix"; gender = 'FEMALE'; birthDate = '1968-07-09'
    idType = 'ID_CARD'; idNo = "11010119680709$suffix"
} $token
Check 'patient.create' $r.Status 200
$patientId = (Res $r).id

# ---------------------------------------------------------------- 3. exam item catalog
$r = Invoke-Api 'GET' '/api/v2/tech/items?category=LAB' $null $token
Check 'items.list' $r.Status 200
$items = @(Res $r)
Check 'items.labCount' $items.Count 2

# ---------------------------------------------------------------- 4. LAB application
$r = Invoke-Api 'POST' '/api/v2/tech/applications' @{
    patientId = $patientId; itemCode = 'LAB-K'; priority = 'STAT'; clinicalDiag = 'hypokalemia workup'
} $token
Check 'app.create' $r.Status 200
$app = Res $r
$appId = $app.application.id
Check 'app.status' $app.application.status 'APPLIED'
Check 'app.specimenTypeSet' ($app.application.specimenType.Length -gt 0) 'True'
Check 'app.priority' $app.application.priority 'STAT'

# ---------------------------------------------------------------- 5. specimen
$r = Invoke-Api 'POST' "/api/v2/tech/applications/$appId/specimen" @{} $token
Check 'specimen.collect' $r.Status 200
$collected = Res $r
Check 'specimen.status' $collected.application.status 'SPECIMEN_COLLECTED'
Check 'specimen.barcode' ($collected.specimen.barcode.Length -gt 10) 'True'

$r = Invoke-Api 'POST' "/api/v2/tech/applications/$appId/specimen" @{} $token
Check 'specimen.duplicate.409' $r.Status 409

$r = Invoke-Api 'POST' "/api/v2/tech/applications/$appId/specimen/receive" @{ accept = $true } $token
Check 'specimen.receive' $r.Status 200
Check 'specimen.received.status' (Res $r).application.status 'RECEIVED'

$r = Invoke-Api 'POST' "/api/v2/tech/applications/$appId/start" $null $token
Check 'app.start' $r.Status 200
Check 'app.inProgress' (Res $r).application.status 'IN_PROGRESS'

# ---------------------------------------------------------------- 6. report with critical value
$r = Invoke-Api 'POST' "/api/v2/tech/applications/$appId/reports" @{
    findings = 'serum K 7.2 mmol/L, markedly elevated'
    conclusion = 'hyperkalemia'
    criticalLines = @(@{ itemName = 'serum potassium'; resultValue = '7.2 mmol/L'; referenceRange = '3.5-5.3'; severity = 'CRITICAL'; notifiedTo = 'ICU' })
} $token
Check 'report.write' $r.Status 200
$report = Res $r
$reportId = $report.report.id
Check 'report.draft' $report.report.status 'DRAFT'
Check 'report.revision' $report.report.revision 1
Check 'report.criticalCount' $report.criticals.Count 1
Check 'critical.pending' $report.criticals[0].status 'PENDING'
$criticalId = $report.criticals[0].id

$r = Invoke-Api 'POST' "/api/v2/tech/applications/$appId/reports" @{
    findings = 'dup'; conclusion = 'dup'
} $token
Check 'report.duplicate.409' $r.Status 409

$r = Invoke-Api 'POST' "/api/v2/tech/reports/$reportId/rewrite" @{
    findings = 'serum K 7.2 mmol/L (rechecked)'; conclusion = 'hyperkalemia, recheck advised'
} $token
Check 'report.rewrite' $r.Status 200
Check 'report.rewrite.status' (Res $r).report.status 'DRAFT'

# ---------------------------------------------------------------- 7. review
$r = Invoke-Api 'POST' "/api/v2/tech/reports/$reportId/review" @{ approve = $false; reason = 'please add reference range' } $token
Check 'report.reject' $r.Status 200
Check 'report.reject.keepsDraft' (Res $r).report.status 'DRAFT'

$r = Invoke-Api 'POST' "/api/v2/tech/reports/$reportId/review" @{ approve = $true } $token
Check 'report.final' $r.Status 200
Check 'report.final.status' (Res $r).report.status 'FINAL'

$r = Invoke-Api 'GET' "/api/v2/tech/applications/$appId" $null $token
Check 'app.reported' (Res $r).application.status 'REPORTED'

# ---------------------------------------------------------------- 8. critical closed loop
$r = Invoke-Api 'POST' "/api/v2/tech/applications/$appId/close" $null $token
Check 'app.close.blockedByCritical.409' $r.Status 409

$r = Invoke-Api 'POST' "/api/v2/tech/criticals/$criticalId/close" @{ note = 'try close first' } $token
Check 'critical.closeBeforeAck.409' $r.Status 409

$r = Invoke-Api 'GET' "/api/v2/tech/criticals?applicationId=$appId" $null $token
Check 'critical.list' $r.Status 200
Check 'critical.list.count' @(Res $r).Count 1

$r = Invoke-Api 'POST' "/api/v2/tech/criticals/$criticalId/acknowledge" @{ note = 'ICU physician notified by phone' } $token
Check 'critical.ack' $r.Status 200
Check 'critical.ack.status' (Res $r).critical.status 'ACKNOWLEDGED'

$r = Invoke-Api 'POST' "/api/v2/tech/criticals/$criticalId/close" @{ note = 'handled' } $token
Check 'critical.close' $r.Status 200
Check 'critical.close.status' (Res $r).critical.status 'CLOSED'

$r = Invoke-Api 'POST' "/api/v2/tech/applications/$appId/close" $null $token
Check 'app.close' $r.Status 200
Check 'app.closed' (Res $r).application.status 'CLOSED'

# ---------------------------------------------------------------- 9. amend report
$r = Invoke-Api 'POST' "/api/v2/tech/reports/$reportId/amend" @{
    findings = 'serum K 7.2 mmol/L (recheck 6.8)'; conclusion = 'hyperkalemia, still high on recheck'; reason = 'add recheck result'
} $token
Check 'report.amend' $r.Status 200
Check 'report.amend.revision' (Res $r).report.revision 2
Check 'report.amend.status' (Res $r).report.status 'AMENDED'
Check 'report.history' (Res $r).history.Count 2

# ---------------------------------------------------------------- 10. EXAM flow (no specimen)
$r = Invoke-Api 'POST' '/api/v2/tech/applications' @{
    patientId = $patientId; itemCode = 'EXM-CT-CHEST'; bodyPart = 'chest'; priority = 'ROUTINE'
} $token
Check 'exam.create' $r.Status 200
$examId = (Res $r).application.id

$r = Invoke-Api 'POST' "/api/v2/tech/applications/$examId/specimen" @{} $token
Check 'exam.noSpecimen.422' $r.Status 422

$r = Invoke-Api 'POST' "/api/v2/tech/applications/$examId/start" $null $token
Check 'exam.start' $r.Status 200

$r = Invoke-Api 'POST' "/api/v2/tech/applications/$examId/reports" @{
    findings = 'lungs clear, no consolidation'; conclusion = 'no obvious abnormality on chest CT'
} $token
Check 'exam.report' $r.Status 200
$examReportId = (Res $r).report.id

$r = Invoke-Api 'POST' "/api/v2/tech/reports/$examReportId/review" @{ approve = $true } $token
Check 'exam.review' $r.Status 200

$r = Invoke-Api 'POST' "/api/v2/tech/applications/$examId/close" $null $token
Check 'exam.close' $r.Status 200
Check 'exam.closed' (Res $r).application.status 'CLOSED'

# ---------------------------------------------------------------- 11. specimen rejected cancels application
$r = Invoke-Api 'POST' '/api/v2/tech/applications' @{
    patientId = $patientId; itemCode = 'EXM-CBC'; priority = 'ROUTINE'
} $token
$rejId = (Res $r).application.id
Check 'rej.create' $r.Status 200

$r = Invoke-Api 'POST' "/api/v2/tech/applications/$rejId/specimen" @{} $token
Check 'rej.collect' $r.Status 200

$r = Invoke-Api 'POST' "/api/v2/tech/applications/$rejId/specimen/receive" @{ accept = $false; reason = 'hemolyzed specimen' } $token
Check 'rej.reject' $r.Status 200
Check 'rej.cancelled' (Res $r).application.status 'CANCELLED'
Check 'rej.specimenStatus' (Res $r).specimen.status 'REJECTED'

Write-Output "-----"
Write-Output "APPLICATION_ID=$appId"
Write-Output "REPORT_ID=$reportId"
if ($script:fail -gt 0) { Write-Output "RESULT FAIL ($script:fail)"; exit 1 }
Write-Output 'RESULT PASS'
exit 0
