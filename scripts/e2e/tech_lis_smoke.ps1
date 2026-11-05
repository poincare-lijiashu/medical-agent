# ============================================================================
# MedAssist LIS inbound (HL7 v2 ORU) smoke test, through the gateway.
#
# Covers: lab application -> specimen collect/receive -> ORU result write-back
#         -> report draft -> critical value -> duplicate message idempotency
#         -> unknown application (FAILED, still 200) -> non-ORU rejected (422)
#
# ASCII only (Windows PowerShell 5.1 reads BOM-less UTF-8 as ANSI).
# Prerequisites: auth :8081, empi :8082, tech :8085, gateway :8080
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\e2e\tech_lis_smoke.ps1
# ============================================================================
param(
    [string]$BaseUrl = "http://127.0.0.1:8080",
    [string]$Username = "admin01",
    [string]$Password = "Med@2026"
)

$ErrorActionPreference = "Stop"
$script:fail = 0

function Invoke-Api {
    param([string]$Method, [string]$Path, $Body = $null, [string]$Token = $null,
          [string]$ContentType = 'application/json; charset=utf-8', [string]$RawBody = $null)
    $out = [IO.Path]::GetTempFileName()
    $args = @('--noproxy', '*', '-s', '-o', $out, '-w', '%{http_code}', '-X', $Method, ($BaseUrl + $Path))
    if ($Token) { $args += @('-H', "Authorization: Bearer $Token") }
    if ($null -ne $Body) {
        $json = $Body | ConvertTo-Json -Depth 8 -Compress
        $bf = [IO.Path]::GetTempFileName()
        [IO.File]::WriteAllText($bf, $json, (New-Object System.Text.UTF8Encoding($false)))
        $args += @('-H', "Content-Type: $ContentType", '--data-binary', "@$bf")
    } elseif ($null -ne $RawBody) {
        $bf = [IO.Path]::GetTempFileName()
        [IO.File]::WriteAllText($bf, $RawBody, (New-Object System.Text.UTF8Encoding($false)))
        $args += @('-H', "Content-Type: $ContentType", '--data-binary', "@$bf")
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

# ---------------------------------------------------------------- 1. login
$r = Invoke-Api 'POST' '/api/v2/auth/login' @{ username = $Username; password = $Password }
Check 'login' $r.Status 200
$token = (Res $r).accessToken
if (-not $token) { Write-Output "FATAL no token: $($r.Body)"; exit 1 }

# ---------------------------------------------------------------- 2. patient
$r = Invoke-Api 'POST' '/api/v2/empi/patients' @{
    name = "Lis Patient $suffix"; gender = 'MALE'; birthDate = '1972-04-04'
    idType = 'ID_CARD'; idNo = "11010119720404$suffix"
} $token
Check 'patient.create' $r.Status 200
$patientId = (Res $r).id

# ---------------------------------------------------------------- 3. lab application + specimen
$r = Invoke-Api 'POST' '/api/v2/tech/applications' @{
    patientId = $patientId; itemCode = 'LAB-K'; priority = 'ROUTINE'; clinicalDiag = 'follow up'
} $token
Check 'app.create' $r.Status 200
$appId = (Res $r).application.id
$appNo = (Res $r).application.applicationNo
Check 'app.labCategory' (Res $r).application.category 'LAB'
Check 'app.hasSpecimenType' ((Res $r).application.specimenType.Length -gt 0) 'True'

$r = Invoke-Api 'POST' "/api/v2/tech/applications/$appId/specimen" @{ specimenType = 'SERUM' } $token
Check 'specimen.collect' $r.Status 200

$r = Invoke-Api 'POST' "/api/v2/tech/applications/$appId/specimen/receive" @{ accept = $true } $token
Check 'specimen.receive' $r.Status 200
Check 'app.received' (Res $r).application.status 'RECEIVED'

# ---------------------------------------------------------------- 4. ORU write-back (HH = critical)
$controlId = "MSG-$suffix"
$msg = "MSH|^~\&|LIS|HOSP|EMR|HOSP|20261101120000||ORU^R01|$controlId|P|2.5`n"
$msg += "PID|1||P$suffix`n"
$msg += "OBR|1|$appNo|LAB-K^Serum Potassium|`n"
$msg += "OBX|1|NM|LAB-K^Serum Potassium||6.8|mmol/L|3.5-5.3|HH|||F`n"
$r = Invoke-Api 'POST' '/api/v2/tech/lis/oru' $null $token 'text/plain; charset=utf-8' $msg
Check 'oru.accept' $r.Status 200
Check 'oru.status' (Res $r).status 'PROCESSED'
Check 'oru.duplicate' (Res $r).duplicate 'False'
Check 'oru.application' (Res $r).applicationNo $appNo
$reportId = (Res $r).reportId
Check 'oru.reportId' ($null -ne $reportId) 'True'

$r = Invoke-Api 'GET' "/api/v2/tech/reports/$reportId" $null $token
Check 'report.detail' $r.Status 200
Check 'report.status' (Res $r).report.status 'DRAFT'
Check 'report.findings' ((Res $r).report.findings -match '6.8') 'True'
Check 'report.criticals' (@((Res $r).criticals).Count) 1
Check 'report.criticalPending' (Res $r).criticals[0].status 'PENDING'
Check 'report.criticalSeverity' (Res $r).criticals[0].severity 'CRITICAL'

$r = Invoke-Api 'GET' "/api/v2/tech/applications/$appId" $null $token
Check 'app.inProgress' (Res $r).application.status 'IN_PROGRESS'

# ---------------------------------------------------------------- 5. idempotency
$r = Invoke-Api 'POST' '/api/v2/tech/lis/oru' $null $token 'text/plain; charset=utf-8' $msg
Check 'oru.duplicate.status' $r.Status 200
Check 'oru.duplicate.flag' (Res $r).duplicate 'True'
Check 'oru.duplicate.sameReport' (Res $r).reportId $reportId

# ---------------------------------------------------------------- 6. failure semantics
$unknownMsg = "MSH|^~\&|LIS|HOSP|EMR|HOSP|20261101120000||ORU^R01|UNK-$suffix|P|2.5`n"
$unknownMsg += "OBR|1|AP-NOT-EXIST|LAB-K^Serum Potassium|`n"
$unknownMsg += "OBX|1|NM|LAB-K^Serum Potassium||4.0|mmol/L||N`n"
$r = Invoke-Api 'POST' '/api/v2/tech/lis/oru' $null $token 'text/plain; charset=utf-8' $unknownMsg
Check 'oru.unknownApp.200' $r.Status 200
Check 'oru.unknownApp.failed' (Res $r).status 'FAILED'
Check 'oru.unknownApp.reason' ((Res $r).errorMessage.Length -gt 0) 'True'

$badMsg = "MSH|^~\&|LIS|HOSP|EMR|HOSP|20261101120000||ORM^O01|BAD-$suffix|P|2.5`n"
$badMsg += "OBR|1|$appNo|LAB-K^Serum Potassium|`n"
$badMsg += "OBX|1|NM|LAB-K^Serum Potassium||4.0|mmol/L||N`n"
$r = Invoke-Api 'POST' '/api/v2/tech/lis/oru' $null $token 'text/plain; charset=utf-8' $badMsg
Check 'oru.notOru.422' $r.Status 422

# ---------------------------------------------------------------- 7. replay (G67)
# A lab ORU that arrives before the specimen is received must be recorded as
# FAILED; after collect/receive the same message can be replayed to PROCESSED.
$r = Invoke-Api 'POST' '/api/v2/tech/applications' @{
    patientId = $patientId; itemCode = 'LAB-K'; priority = 'ROUTINE'; clinicalDiag = 'replay case'
} $token
Check 'replay.app.create' $r.Status 200
$replayAppId = (Res $r).application.id
$replayAppNo = (Res $r).application.applicationNo

$replayControlId = "RPL-$suffix"
$replayMsg = "MSH|^~\&|LIS|HOSP|EMR|HOSP|20261101120000||ORU^R01|$replayControlId|P|2.5`n"
$replayMsg += "OBR|1|$replayAppNo|LAB-K^Serum Potassium|`n"
$replayMsg += "OBX|1|NM|LAB-K^Serum Potassium||4.1|mmol/L|3.5-5.3|N|||F`n"
$r = Invoke-Api 'POST' '/api/v2/tech/lis/oru' $null $token 'text/plain; charset=utf-8' $replayMsg
Check 'replay.beforeReceive.status' $r.Status 200
Check 'replay.beforeReceive.failed' (Res $r).status 'FAILED'
$replayMsgId = (Res $r).messageId
Check 'replay.messageId' ($null -ne $replayMsgId) 'True'

$r = Invoke-Api 'POST' "/api/v2/tech/applications/$replayAppId/specimen" @{ specimenType = 'SERUM' } $token
Check 'replay.specimen.collect' $r.Status 200
$r = Invoke-Api 'POST' "/api/v2/tech/applications/$replayAppId/specimen/receive" @{ accept = $true } $token
Check 'replay.specimen.receive' $r.Status 200

$r = Invoke-Api 'POST' "/api/v2/tech/lis/messages/$replayMsgId/replay" $null $token
Check 'replay.status' $r.Status 200
Check 'replay.processed' (Res $r).status 'PROCESSED'
Check 'replay.reportId' ($null -ne (Res $r).reportId) 'True'

$r = Invoke-Api 'POST' "/api/v2/tech/lis/messages/$replayMsgId/replay" $null $token
Check 'replay.again.status' $r.Status 200
Check 'replay.again.duplicate' (Res $r).duplicate 'True'

Write-Output "-----"
Write-Output "APPLICATION_ID=$appId"
Write-Output "APPLICATION_NO=$appNo"
Write-Output "REPORT_ID=$reportId"
Write-Output "REPLAY_MESSAGE_ID=$replayMsgId"
if ($script:fail -gt 0) { Write-Output "RESULT FAIL ($script:fail)"; exit 1 }
Write-Output 'RESULT PASS'
exit 0
