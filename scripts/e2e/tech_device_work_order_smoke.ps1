# ============================================================================
# MedAssist device work order smoke test (G82).
#
# Covers: create device -> create corrective order -> list/detail
#         -> start (stale 409, device becomes MAINTENANCE)
#         -> finish requires resolution, then DONE with cost
#         -> self acceptance 409 (four eyes) -> wrong permission 403
#         -> QC acceptance 200 (device back to ACTIVE)
#         -> accepted order is frozen -> cancel requires reason
#         -> retired device rejects new orders -> unknown status 422
#         -> doctor without tech:device:read 403.
#
# ASCII only (Windows PowerShell 5.1 reads BOM-less UTF-8 as ANSI).
# Prerequisites: auth :8081, tech :8085, gateway :8080
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\e2e\tech_device_work_order_smoke.ps1
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

# ---------------------------------------------------------------- 1. login
$r = Invoke-Api 'POST' '/api/v2/auth/login' @{ username = $Username; password = $Password }
Check 'login.admin' $r.Status 200
$token = (Res $r).accessToken
if (-not $token) { Write-Output "FATAL no admin token: $($r.Body)"; exit 1 }

$r = Invoke-Api 'POST' '/api/v2/auth/login' @{ username = 'qc01'; password = $Password }
Check 'login.qc' $r.Status 200
$qcToken = (Res $r).accessToken

$r = Invoke-Api 'POST' '/api/v2/auth/login' @{ username = 'doctor01'; password = $Password }
Check 'login.doctor' $r.Status 200
$doctorToken = (Res $r).accessToken

# ---------------------------------------------------------------- 2. device
$r = Invoke-Api 'POST' '/api/v2/tech/devices' @{
    name = "CT-WO-$suffix"; modality = 'CT'; location = '3F'
    manufacturer = 'GE'; model = 'Revolution'; serialNo = "SN$suffix"
} $token
Check 'device.create' $r.Status 200
$deviceId = (Res $r).device.deviceNo
$deviceUuid = (Res $r).device.id
Check 'device.noPrefix' ($deviceId.StartsWith('DEV')) 'True'
Check 'device.status' (Res $r).device.status 'ACTIVE'

# ---------------------------------------------------------------- 3. create order
$r = Invoke-Api 'POST' "/api/v2/tech/devices/$deviceUuid/work-orders" @{
    orderType = 'corrective'; priority = 'high'; title = 'Tube noise'
    description = 'Abnormal noise when rotating'; vendor = 'GE Service'
} $token
Check 'order.create' $r.Status 200
$orderId = (Res $r).id
$orderNo = (Res $r).orderNo
Check 'order.noPrefix' ($orderNo.StartsWith('WO')) 'True'
Check 'order.type' (Res $r).orderType 'CORRECTIVE'
Check 'order.priority' (Res $r).priority 'HIGH'
Check 'order.status' (Res $r).status 'OPEN'

$r = Invoke-Api 'GET' "/api/v2/tech/device-work-orders?deviceId=$deviceUuid&limit=50" $null $token
Check 'order.list' $r.Status 200
$listed = @(Res $r) | Where-Object { $_.id -eq $orderId }
Check 'order.list.contains' (@($listed).Count) 1

$r = Invoke-Api 'GET' '/api/v2/tech/device-work-orders/00000000-0000-0000-0000-000000000000' $null $token
Check 'order.detail.unknown.404' $r.Status 404

# ---------------------------------------------------------------- 4. start
$r = Invoke-Api 'POST' "/api/v2/tech/device-work-orders/$orderId/start" @{ version = 9 } $token
Check 'order.start.stale.409' $r.Status 409

$r = Invoke-Api 'POST' "/api/v2/tech/device-work-orders/$orderId/start" @{ version = 0 } $token
Check 'order.start' $r.Status 200
Check 'order.start.status' (Res $r).status 'IN_PROGRESS'

$r = Invoke-Api 'GET' "/api/v2/tech/devices/$deviceUuid" $null $token
Check 'device.maintenance' (Res $r).device.status 'MAINTENANCE'

# ---------------------------------------------------------------- 5. finish
$r = Invoke-Api 'POST' "/api/v2/tech/device-work-orders/$orderId/finish" @{
    version = 1; resolution = '   '; costAmount = 1200.00
} $token
Check 'order.finish.blankResolution.422' $r.Status 422

$r = Invoke-Api 'POST' "/api/v2/tech/device-work-orders/$orderId/finish" @{
    version = 9; resolution = 'Replaced tube'; costAmount = 1200.00
} $token
Check 'order.finish.stale.409' $r.Status 409

$r = Invoke-Api 'POST' "/api/v2/tech/device-work-orders/$orderId/finish" @{
    version = 1; resolution = 'Replaced tube'; costAmount = 1200.00
} $token
Check 'order.finish' $r.Status 200
Check 'order.finish.status' (Res $r).status 'DONE'
Check 'order.finish.cost' ([double](Res $r).costAmount) 1200

# ---------------------------------------------------------------- 6. accept (four eyes)
$r = Invoke-Api 'POST' "/api/v2/tech/device-work-orders/$orderId/accept" @{
    version = 2; note = 'checked'
} $token
Check 'order.accept.self.409' $r.Status 409

$r = Invoke-Api 'POST' "/api/v2/tech/device-work-orders/$orderId/accept" @{
    version = 2; note = 'checked'
} $doctorToken
Check 'order.accept.doctor.403' $r.Status 403

$r = Invoke-Api 'POST' "/api/v2/tech/device-work-orders/$orderId/accept" @{
    version = 2; note = 'image quality verified'
} $qcToken
Check 'order.accept' $r.Status 200
Check 'order.accept.status' (Res $r).status 'ACCEPTED'
Check 'order.accept.by' (Res $r).acceptedBy 'qc01'

$r = Invoke-Api 'GET' "/api/v2/tech/devices/$deviceUuid" $null $token
Check 'device.activeAgain' (Res $r).device.status 'ACTIVE'

$r = Invoke-Api 'POST' "/api/v2/tech/device-work-orders/$orderId/cancel" @{
    version = 3; reason = 'duplicate'
} $token
Check 'order.cancel.accepted.409' $r.Status 409

# ---------------------------------------------------------------- 7. cancel + retired device
$r = Invoke-Api 'POST' "/api/v2/tech/devices/$deviceUuid/work-orders" @{
    orderType = 'PREVENTIVE'; title = 'Quarterly service'
} $token
Check 'order.create.preventive' $r.Status 200
$preventiveId = (Res $r).id
Check 'order.create.preventive.priority' (Res $r).priority 'NORMAL'

$r = Invoke-Api 'POST' "/api/v2/tech/device-work-orders/$preventiveId/cancel" @{
    version = 0; reason = '   '
} $token
Check 'order.cancel.blank.422' $r.Status 422

$r = Invoke-Api 'POST' "/api/v2/tech/device-work-orders/$preventiveId/cancel" @{
    version = 0; reason = 'rescheduled'
} $token
Check 'order.cancel' $r.Status 200
Check 'order.cancel.status' (Res $r).status 'CANCELLED'
Check 'order.cancel.reason' (Res $r).cancelReason 'rescheduled'

$r = Invoke-Api 'POST' '/api/v2/tech/devices' @{
    name = "DR-RETIRED-$suffix"; modality = 'DR'
} $token
Check 'device2.create' $r.Status 200
$retiredUuid = (Res $r).device.id

$r = Invoke-Api 'POST' "/api/v2/tech/devices/$retiredUuid/status" @{
    status = 'RETIRED'; version = 0
} $token
Check 'device2.retire' $r.Status 200

$r = Invoke-Api 'POST' "/api/v2/tech/devices/$retiredUuid/work-orders" @{
    orderType = 'CORRECTIVE'; title = 'Should fail'
} $token
Check 'order.create.retired.409' $r.Status 409

# ---------------------------------------------------------------- 8. permission + validation
$r = Invoke-Api 'GET' '/api/v2/tech/device-work-orders?status=UNKNOWN' $null $token
Check 'order.list.badStatus.422' $r.Status 422

$r = Invoke-Api 'GET' "/api/v2/tech/device-work-orders?deviceId=$deviceUuid" $null $doctorToken
Check 'order.list.doctor.403' $r.Status 403

$r = Invoke-Api 'GET' "/api/v2/tech/device-work-orders?deviceId=$deviceUuid" $null $qcToken
Check 'order.list.qc.read' $r.Status 200

Write-Output "-----"
Write-Output "DEVICE_ID=$deviceUuid"
Write-Output "WORK_ORDER_ID=$orderId"
Write-Output "WORK_ORDER_NO=$orderNo"
if ($script:fail -gt 0) { Write-Output "RESULT FAIL ($script:fail)"; exit 1 }
Write-Output 'RESULT PASS'
exit 0
