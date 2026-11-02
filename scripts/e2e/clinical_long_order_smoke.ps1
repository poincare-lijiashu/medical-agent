# ============================================================================
# MedAssist long-term order quantity contract smoke test (through gateway).
#
# Covers (multi-agent review B2):
#   * frequency vocabulary endpoint is the single source of truth
#   * LONG order: quantity = DAILY total, amount = daily amount, frequency is
#     required and must be a daily frequency (prn / qod are rejected)
#   * frequencyTimes is snapshotted by the server (BID -> 2), not sent by client
#   * daily-bill charges a LONG order once per day (idempotent on re-run)
#   * LONG DRUG orders are NOT auto-transferred to pharmacy (one transfer can
#     only supply one day; ward-based daily dispensing is a later batch), while
#     TEMP DRUG orders still transfer normally
#
# ASCII only on purpose: Windows PowerShell 5.1 reads BOM-less UTF-8 as ANSI.
#
# Prerequisites: auth :8081, empi :8082, clinical :8083, pharmacy :8084, gateway
# Usage (repo root):
#   powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\e2e\clinical_long_order_smoke.ps1
# ============================================================================
param(
    [string]$BaseUrl = "http://127.0.0.1:8080",
    [string]$Username = "admin01",
    [string]$Password = "Med@2026",
    [string]$DeptId = "11111111-1111-1111-1111-111111111111"
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

function CheckAmt($name, $actual, $expected) {
    if ([decimal]$actual -eq [decimal]$expected) { Write-Output "PASS $name = $actual" }
    else { Write-Output "FAIL $name expected=$expected actual=$actual"; $script:fail++ }
}

function Res($r) { return (($r.Body | ConvertFrom-Json).data) }

$suffix = Get-Random -Minimum 1000 -Maximum 9999

# ---------------------------------------------------------------- 1. login
$r = Invoke-Api 'POST' '/api/v2/auth/login' @{ username = $Username; password = $Password }
Check 'login' $r.Status 200
$token = (Res $r).accessToken
if (-not $token) { Write-Output "FATAL no token: $($r.Body)"; exit 1 }

# ---------------------------------------------------------------- 2. patient + admission
$r = Invoke-Api 'POST' '/api/v2/empi/patients' @{
    name = "Long Order Patient $suffix"; gender = 'MALE'; birthDate = '1972-04-09'
    idType = 'ID_CARD'; idNo = "11010119720409$suffix"
} $token
Check 'patient.create' $r.Status 200
$patientId = (Res $r).id

$r = Invoke-Api 'POST' '/api/v2/clinical/wards' @{
    code = "W-LONG-$suffix"; name = "Long Ward $suffix"; deptId = $DeptId
} $token
Check 'ward.create' $r.Status 200
$wardId = (Res $r).id
$r = Invoke-Api 'POST' "/api/v2/clinical/wards/$wardId/beds" @{ bedNos = @('01') } $token
Check 'bed.create' $r.Status 200
$bedId = @(Res $r)[0].id

$r = Invoke-Api 'POST' '/api/v2/clinical/inpatient/admissions' @{
    patientId = $patientId; wardId = $wardId; bedId = $bedId; attendingDoctor = 'doctor01'
} $token
Check 'admit' $r.Status 200
$visitId = (Res $r).visitId

# ---------------------------------------------------------------- 3. frequency vocabulary
$r = Invoke-Api 'GET' '/api/v2/clinical/frequencies' $null $token
Check 'frequencies.ok' $r.Status 200
$freqs = @(Res $r)
$bid = $freqs | Where-Object { $_.code -eq 'bid' }
$qod = $freqs | Where-Object { $_.code -eq 'qod' }
Check 'frequencies.bid.times' $bid.timesPerDay 2
Check 'frequencies.bid.daily' $bid.daily True
Check 'frequencies.qod.daily' $qod.daily False

# ---------------------------------------------------------------- 4. stock + prescription baseline
$r = Invoke-Api 'GET' '/api/v2/pharmacy/drugs?keyword=DRUG-AMOX' $null $token
Check 'drug.list' $r.Status 200
$amoxId = @(Res $r)[0].drug.id
$far = (Get-Date).AddDays(300).ToString('yyyy-MM-dd')
$r = Invoke-Api 'POST' "/api/v2/pharmacy/drugs/$amoxId/batches" @{
    batchNo = "B-LONG-$suffix"; expireDate = $far; quantity = 10; supplier = 'GuoYao'
} $token
Check 'stock.receive' $r.Status 200
$r = Invoke-Api 'GET' "/api/v2/pharmacy/prescriptions?patientId=$patientId" $null $token
$rxBefore = @(Res $r).Count

# ---------------------------------------------------------------- 5. LONG rules
$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/orders" @{
    items = @(@{ itemCode = 'TRT-IV'; quantity = 3; dose = '0.9%NS 250ml'; frequency = 'BID'; orderMode = 'LONG' })
} $token
Check 'long.place' $r.Status 200
$longIv = @(Res $r)[0]
CheckAmt 'long.amount.dailyTotal' $longIv.amount 24.00
Check 'long.frequency.normalized' $longIv.frequency 'bid'
Check 'long.frequencyTimes' $longIv.frequencyTimes 2
Check 'long.orderMode' $longIv.orderMode 'LONG'

$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/orders" @{
    items = @(@{ itemCode = 'TRT-IV'; quantity = 1; frequency = $null; orderMode = 'LONG' })
} $token
Check 'long.noFrequency.422' $r.Status 422

$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/orders" @{
    items = @(@{ itemCode = 'TRT-IV'; quantity = 1; frequency = 'prn'; orderMode = 'LONG' })
} $token
Check 'long.prn.422' $r.Status 422

$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/orders" @{
    items = @(@{ itemCode = 'TRT-IV'; quantity = 1; frequency = 'qod'; orderMode = 'LONG' })
} $token
Check 'long.qod.422' $r.Status 422

# ---------------------------------------------------------------- 6. LONG DRUG: no auto transfer, still billed daily
$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/orders" @{
    items = @(@{ itemCode = 'DRUG-AMOX-025'; quantity = 2; dose = '0.5g'; frequency = 'bid'; orderMode = 'LONG' })
} $token
Check 'longDrug.place' $r.Status 200
$longDrug = @(Res $r)[0]
Check 'longDrug.transferStatus' $longDrug.transferStatus 'NONE'
CheckAmt 'longDrug.amount.dailyTotal' $longDrug.amount 25.00
Check 'longDrug.frequencyTimes' $longDrug.frequencyTimes 2

Start-Sleep -Milliseconds 800
$r = Invoke-Api 'GET' "/api/v2/pharmacy/prescriptions?patientId=$patientId" $null $token
Check 'longDrug.noPrescription' @(Res $r).Count $rxBefore

$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/orders/$($longDrug.id)/transfer-retry" @{} $token
Check 'longDrug.retry.422' $r.Status 422

# ---------------------------------------------------------------- 7. TEMP drug still transfers
$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/orders" @{
    items = @(@{ itemCode = 'DRUG-AMOX-025'; quantity = 2; dose = '0.5g'; frequency = 'bid' })
} $token
Check 'tempDrug.place' $r.Status 200
$tempDrugId = @(Res $r)[0].id
$tempStatus = $null
for ($i = 0; $i -lt 30; $i++) {
    $r = Invoke-Api 'GET' "/api/v2/clinical/visits/$visitId/orders" $null $token
    $cur = @(Res $r) | Where-Object { $_.id -eq $tempDrugId }
    $tempStatus = $cur.transferStatus
    if ("$tempStatus" -ne 'PENDING') { break }
    Start-Sleep -Milliseconds 200
}
Check 'tempDrug.transferStatus' $tempStatus 'TRANSFERRED'
Check 'tempDrug.frequencyTimes' $cur.frequencyTimes 2

# ---------------------------------------------------------------- 8. daily bill: once per day
$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/daily-bill" $null $token
Check 'dailyBill.ok' $r.Status 200
$bill = Res $r
Check   'dailyBill.orderCount'  $bill.orderCount 2
CheckAmt 'dailyBill.billedAmount' $bill.billedAmount 49.00

$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/daily-bill" $null $token
Check 'dailyBill.repeat.ok' $r.Status 200
Check   'dailyBill.repeat.orderCount' (Res $r).orderCount 0
CheckAmt 'dailyBill.repeat.billedAmount' (Res $r).billedAmount 0

# ---------------------------------------------------------------- 8b. long orders cannot be cancelled (P1-7)
# Daily billing does not flip order status to CHARGED, so a LONG order that already
# billed several days would still look "PLACED". Cancelling it would silently drop
# those billed days - it must be rejected with 409 and point to /stop instead.
$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/orders/$($longIv.id)/cancel" $null $token
Check 'long.cancel.rejected' $r.Status 409

# ---------------------------------------------------------------- 9. stop is effective for the same day's billing
$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/orders/$($longIv.id)/stop" @{
    reason = 'switch to oral'
} $token
Check 'long.stop.ok' $r.Status 200
Check 'long.stop.status' (Res $r).status 'PLACED'

Write-Output "-----"
Write-Output "VISIT_ID=$visitId"
Write-Output "LONG_TRT_ORDER_ID=$($longIv.id)"
Write-Output "LONG_DRUG_ORDER_ID=$($longDrug.id)"
if ($script:fail -gt 0) { Write-Output "RESULT FAIL ($script:fail)"; exit 1 }
Write-Output 'RESULT PASS'
exit 0
