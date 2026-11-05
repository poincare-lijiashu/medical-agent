# ============================================================================
# MedAssist device campus isolation smoke test (G83).
#
# Covers: device created by a MAIN admin carries MAIN; device created by an
#         EAST-only admin carries EAST -> EAST admin's device list and summary
#         only cover EAST -> EAST admin cannot open the MAIN device (404)
#         -> EAST admin cannot report a work order on the MAIN device (404)
#         -> work order list is campus-scoped -> all-campus admin sees both.
#
# ASCII only (Windows PowerShell 5.1 reads BOM-less UTF-8 as ANSI).
# Prerequisites: auth :8081, tech :8085, gateway :8080
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\e2e\tech_device_campus_smoke.ps1
# ============================================================================
param(
    [string]$BaseUrl = "http://127.0.0.1:8080",
    [string]$Username = "admin01",
    [string]$Password = "Med@2026"
)

$ErrorActionPreference = "Stop"
$script:fail = 0

$MAIN = "a0000000-0000-0000-0000-000000000001"
$EAST = "a0000000-0000-0000-0000-000000000002"
$DEPT = "11111111-1111-1111-1111-111111111111"

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
$adminToken = (Res $r).accessToken
if (-not $adminToken) { Write-Output "FATAL no admin token: $($r.Body)"; exit 1 }

# EAST-only admin (same pattern as campus_isolation_smoke)
$eastUser = "easttech$suffix"
$eastPwd = 'East@2026x'
$r = Invoke-Api 'POST' '/api/v2/auth/users' @{
    username = $eastUser; password = $eastPwd; displayName = "East Tech Admin"
    deptIds = @($DEPT); roles = @('ADMIN')
} $adminToken
Check 'user.create.east' $r.Status 200
$eastUserId = (Res $r).id

$r = Invoke-Api 'PUT' "/api/v2/auth/users/$eastUserId/campuses" @{ campusIds = @($EAST) } $adminToken
Check 'user.campus.east' $r.Status 200

$r = Invoke-Api 'POST' '/api/v2/auth/login' @{ username = $eastUser; password = $eastPwd }
Check 'login.east' $r.Status 200
$eastToken = (Res $r).accessToken

# ---------------------------------------------------------------- 2. devices per campus
$r = Invoke-Api 'POST' '/api/v2/tech/devices' @{
    name = "CT-MAIN-$suffix"; modality = 'CT'; location = 'Main 3F'
} $adminToken
Check 'device.main.create' $r.Status 200
$mainDeviceId = (Res $r).device.id
Check 'device.main.campus' (Res $r).device.campusId $MAIN
Check 'device.main.source' (Res $r).device.campusSource 'RUNTIME'

$r = Invoke-Api 'POST' '/api/v2/tech/devices' @{
    name = "CT-EAST-$suffix"; modality = 'CT'; location = 'East 1F'
} $eastToken
Check 'device.east.create' $r.Status 200
$eastDeviceId = (Res $r).device.id
Check 'device.east.campus' (Res $r).device.campusId $EAST

$r = Invoke-Api 'GET' "/api/v2/tech/devices?keyword=CT-MAIN-$suffix" $null $adminToken
Check 'device.list.main.byAdmin' (@(Res $r)).Count 1

$r = Invoke-Api 'GET' "/api/v2/tech/devices?keyword=CT-MAIN-$suffix" $null $eastToken
Check 'device.list.main.hiddenFromEast' (@(Res $r)).Count 0

$r = Invoke-Api 'GET' "/api/v2/tech/devices?keyword=CT-EAST-$suffix" $null $eastToken
Check 'device.list.east.byEast' (@(Res $r)).Count 1

$r = Invoke-Api 'GET' "/api/v2/tech/devices/$mainDeviceId" $null $eastToken
Check 'device.detail.main.east.404' $r.Status 404
Check 'device.detail.main.east.code' (($r.Body | ConvertFrom-Json).code) 46430

$r = Invoke-Api 'GET' "/api/v2/tech/devices/$mainDeviceId" $null $adminToken
Check 'device.detail.main.admin' $r.Status 200

# ---------------------------------------------------------------- 3. work orders per campus
$r = Invoke-Api 'POST' "/api/v2/tech/devices/$mainDeviceId/work-orders" @{
    orderType = 'CORRECTIVE'; title = 'Main tube noise'
} $eastToken
Check 'order.create.main.east.404' $r.Status 404

$r = Invoke-Api 'POST' "/api/v2/tech/devices/$eastDeviceId/work-orders" @{
    orderType = 'PREVENTIVE'; title = 'East quarterly service'
} $eastToken
Check 'order.create.east' $r.Status 200
$eastOrderId = (Res $r).id

$r = Invoke-Api 'POST' "/api/v2/tech/devices/$mainDeviceId/work-orders" @{
    orderType = 'PREVENTIVE'; title = 'Main quarterly service'
} $adminToken
Check 'order.create.main' $r.Status 200

$r = Invoke-Api 'GET' '/api/v2/tech/device-work-orders?limit=200' $null $eastToken
Check 'order.list.east' $r.Status 200
$eastOrders = @(Res $r)
Check 'order.list.east.contains' (@($eastOrders | Where-Object { $_.id -eq $eastOrderId }).Count) 1
Check 'order.list.east.noMain' (@($eastOrders | Where-Object { $_.deviceId -eq $mainDeviceId }).Count) 0

$r = Invoke-Api 'GET' '/api/v2/tech/device-work-orders?limit=200' $null $adminToken
Check 'order.list.admin' $r.Status 200
$mainOrders = @(Res $r) | Where-Object { $_.deviceId -eq $mainDeviceId }
Check 'order.list.admin.seesMain' (@($mainOrders).Count -ge 1) 'True'

$r = Invoke-Api 'GET' "/api/v2/tech/device-work-orders/$eastOrderId" $null $adminToken
Check 'order.detail.crossCampusAdmin' $r.Status 200

# ---------------------------------------------------------------- 4. summary scoped to campus
$r = Invoke-Api 'GET' '/api/v2/tech/devices?limit=200' $null $eastToken
$eastDeviceCount = @(Res $r).Count
$r = Invoke-Api 'GET' '/api/v2/tech/devices/summary' $null $eastToken
Check 'summary.east.countMatchesList' (Res $r).total $eastDeviceCount

Write-Output "-----"
Write-Output "MAIN_DEVICE=$mainDeviceId"
Write-Output "EAST_DEVICE=$eastDeviceId"
Write-Output "EAST_USER=$eastUser"
if ($script:fail -gt 0) { Write-Output "RESULT FAIL ($script:fail)"; exit 1 }
Write-Output 'RESULT PASS'
exit 0
