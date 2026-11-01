# ============================================================================
# MedAssist clinical order -> tech application smoke test (through gateway).
#
# Covers (HIS main path, second step):
#   * an EXAM order placed in clinical is transferred to tech automatically
#     (order.transferStatus: PENDING -> TRANSFERRED, externalType=EXAM_APPLICATION)
#   * the application category comes from the tech dictionary (EXAM vs LAB)
#   * appliedBy carries the ordering doctor; amounts stay on the clinical order
#   * cancelling the order cancels an APPLIED application
#   * once the specimen is collected, cancelling the order is rejected (409)
#   * transfer-retry is rejected for a cancelled order (409)
#   * the tech internal endpoint rejects calls without a token (403)
#
# ASCII only on purpose: Windows PowerShell 5.1 reads BOM-less UTF-8 as ANSI.
#
# Prerequisites: auth :8081, empi :8082, clinical :8083, tech :8085, gateway
# Usage (repo root):
#   powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\e2e\clinical_tech_order_smoke.ps1
# ============================================================================
param(
    [string]$BaseUrl = "http://127.0.0.1:8080",
    [string]$TechUrl = "http://127.0.0.1:8085",
    [string]$Username = "admin01",
    [string]$Password = "Med@2026",
    [string]$DeptId = "11111111-1111-1111-1111-111111111111"
)

$ErrorActionPreference = "Stop"
$script:fail = 0

function Invoke-Api {
    param([string]$Method, [string]$Path, $Body = $null, [string]$Token = $null, [string]$Base = $null)
    if (-not $Base) { $Base = $BaseUrl }
    $out = [IO.Path]::GetTempFileName()
    $args = @('--noproxy', '*', '-s', '-o', $out, '-w', '%{http_code}', '-X', $Method, ($Base + $Path))
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

function CheckAmt($name, $actual, $expected) {
    if ([decimal]$actual -eq [decimal]$expected) { Write-Output "PASS $name = $actual" }
    else { Write-Output "FAIL $name expected=$expected actual=$actual"; $script:fail++ }
}

function Res($r) { return (($r.Body | ConvertFrom-Json).data) }
function Body($r) { return ($r.Body | ConvertFrom-Json) }

$suffix = Get-Random -Minimum 1000 -Maximum 9999

# ---------------------------------------------------------------- 1. login
$r = Invoke-Api 'POST' '/api/v2/auth/login' @{ username = $Username; password = $Password }
Check 'login' $r.Status 200
$token = (Res $r).accessToken
if (-not $token) { Write-Output "FATAL no token: $($r.Body)"; exit 1 }

# ---------------------------------------------------------------- 2. patient + visit
$r = Invoke-Api 'POST' '/api/v2/empi/patients' @{
    name = "Tech Patient $suffix"; gender = 'FEMALE'; birthDate = '1979-07-21'
    idType = 'ID_CARD'; idNo = "11010119790721$suffix"
} $token
Check 'patient.create' $r.Status 200
$patientId = (Res $r).id

$r = Invoke-Api 'POST' '/api/v2/clinical/registrations' @{
    patientId = $patientId; deptId = $DeptId; doctorId = 'doctor01'
} $token
Check 'visit.register' $r.Status 200
$visitId = (Res $r).id

$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/start" @{} $token
Check 'visit.start' $r.Status 200
Check 'visit.status' (Res $r).status 'IN_TREATMENT'

# ---------------------------------------------------------------- 3. place EXAM + LAB orders
$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/orders" @{
    items = @(
        @{ itemCode = 'EXM-CT-CHEST'; quantity = 1; frequency = 'once' },
        @{ itemCode = 'LAB-K'; quantity = 1; frequency = 'once' }
    )
} $token
Check 'order.place' $r.Status 200
$orders = Res $r
$examOrder = $orders | Where-Object { $_.itemCode -eq 'EXM-CT-CHEST' }
$labOrder = $orders | Where-Object { $_.itemCode -eq 'LAB-K' }
CheckAmt 'order.exam.amount' $examOrder.amount 260.00
CheckAmt 'order.lab.amount' $labOrder.amount 8.00
Check 'order.exam.transfer.initial' $examOrder.transferStatus 'PENDING'

$examAppId = $null
$labAppId = $null
for ($i = 0; $i -lt 25; $i++) {
    $r = Invoke-Api 'GET' "/api/v2/clinical/visits/$visitId/orders" $null $token
    $current = @(Res $r)
    $curExam = $current | Where-Object { $_.id -eq $examOrder.id }
    $curLab = $current | Where-Object { $_.id -eq $labOrder.id }
    $examAppId = $curExam.externalId
    $labAppId = $curLab.externalId
    if ("$($curExam.transferStatus)" -ne 'PENDING' -and "$($curLab.transferStatus)" -ne 'PENDING') { break }
    Start-Sleep -Milliseconds 200
}
Check 'order.exam.transfer' $curExam.transferStatus 'TRANSFERRED'
Check 'order.lab.transfer' $curLab.transferStatus 'TRANSFERRED'
Check 'order.exam.externalType' $curExam.externalType 'EXAM_APPLICATION'
Check 'order.lab.externalType' $curLab.externalType 'EXAM_APPLICATION'
Check 'order.exam.externalNo.prefix' $curExam.externalNo.Substring(0, 2) 'AP'
if (-not $examAppId -or -not $labAppId) { Write-Output 'FATAL missing application id'; exit 1 }

# ---------------------------------------------------------------- 4. tech side mirrors the orders
$r = Invoke-Api 'GET' "/api/v2/tech/applications/$examAppId" $null $token
Check 'tech.exam.get' $r.Status 200
$examApp = (Res $r).application
Check 'tech.exam.category' $examApp.category 'EXAM'
Check 'tech.exam.itemCode' $examApp.itemCode 'EXM-CT-CHEST'
Check 'tech.exam.appliedBy' $examApp.appliedBy $examOrder.orderedBy
Check 'tech.exam.status' $examApp.status 'APPLIED'
Check 'tech.exam.visitId' $examApp.visitId $visitId

$r = Invoke-Api 'GET' "/api/v2/tech/applications/$labAppId" $null $token
Check 'tech.lab.get' $r.Status 200
$labApp = (Res $r).application
Check 'tech.lab.category' $labApp.category 'LAB'
# specimenType comes from the tech dictionary (non-ASCII in DB, so assert presence + length)
Check 'tech.lab.specimenType.present' ([bool]$labApp.specimenType) True
Check 'tech.lab.specimenType.length' $labApp.specimenType.Length 2

# ---------------------------------------------------------------- 5. cancel EXAM order -> application cancelled
$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/orders/$($examOrder.id)/cancel" @{} $token
Check 'order.exam.cancel' $r.Status 200
Check 'order.exam.cancel.status' (Res $r).status 'CANCELLED'
$r = Invoke-Api 'GET' "/api/v2/tech/applications/$examAppId" $null $token
Check 'tech.exam.cancelled' (Res $r).application.status 'CANCELLED'

# retry is refused for a cancelled order
$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/orders/$($examOrder.id)/transfer-retry" @{} $token
Check 'transferRetry.cancelled.409' $r.Status 409

# ---------------------------------------------------------------- 6. collected specimen blocks cancellation
$r = Invoke-Api 'POST' "/api/v2/tech/applications/$labAppId/specimen" @{} $token
Check 'specimen.collect' $r.Status 200
Check 'specimen.collect.status' (Res $r).application.status 'SPECIMEN_COLLECTED'

# R6-MF-03: cancel succeeds (medical decision); the void of a specimen-collected
# application is deferred to a retry task, so the application keeps its status for now.
$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/orders/$($labOrder.id)/cancel" @{} $token
Check 'order.lab.cancel.afterCollect.ok' $r.Status 200
Check 'order.lab.cancel.afterCollect.status' (Res $r).status 'CANCELLED'
$r = Invoke-Api 'GET' "/api/v2/tech/applications/$labAppId" $null $token
Check 'tech.lab.still.collected' (Res $r).application.status 'SPECIMEN_COLLECTED'

# ---------------------------------------------------------------- 7. internal endpoint rejects unauthenticated calls
$r = Invoke-Api 'POST' '/internal/applications' @{
    orderId = $examOrder.id; patientId = $patientId; itemCode = 'EXM-CT-CHEST'
} $null $TechUrl
Check 'internal.noToken.403' $r.Status 403
Check 'internal.noToken.code' (Body $r).code 40300

Write-Output "-----"
Write-Output "VISIT_ID=$visitId"
Write-Output "EXAM_ORDER=$($examOrder.id)"
Write-Output "EXAM_APPLICATION=$examAppId"
Write-Output "LAB_APPLICATION=$labAppId"
if ($script:fail -gt 0) { Write-Output "RESULT FAIL ($script:fail)"; exit 1 }
Write-Output 'RESULT PASS'
exit 0
