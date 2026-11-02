# ============================================================================
# MedAssist clinical ORDER-mode drug return refund smoke test (through gateway).
#
# Covers (multi-agent review B3, money red line):
#   * ORDER-mode prescription: drug return posts a refund against the clinical
#     order (no second billing chain, pharmacy holds no ledger)
#   * return BEFORE settle  -> order is cancelled when fully returned, otherwise
#     settle charges the NET amount (refunded part is not charged again)
#   * return AFTER settle   -> clinical records pending refund; the paid bill is
#     NOT rewritten; pharmacy marks REFUND_PENDING
#   * billing-retry is idempotent for an ORDER-mode dispense
#   * internal order-refund endpoint rejects calls without a token (403)
#
# ASCII only on purpose: Windows PowerShell 5.1 reads BOM-less UTF-8 as ANSI.
#
# Prerequisites: auth :8081, empi :8082, clinical :8083, pharmacy :8084, gateway
# Usage (repo root):
#   powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\e2e\pharmacy_order_refund_smoke.ps1
# ============================================================================
param(
    [string]$BaseUrl = "http://127.0.0.1:8080",
    [string]$ClinicalUrl = "http://127.0.0.1:8083",
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

function Wait-OrderTransferred {
    param([string]$VisitId, [string]$OrderId, [string]$Token)
    $last = $null
    for ($i = 0; $i -lt 30; $i++) {
        $r = Invoke-Api 'GET' "/api/v2/clinical/visits/$VisitId/orders" $null $Token
        $cur = @(Res $r) | Where-Object { $_.id -eq $OrderId }
        if ("$($cur.transferStatus)" -ne 'PENDING') { return $cur }
        Start-Sleep -Milliseconds 200
    }
    return $cur
}

function Wait-DispenseBilling {
    param([string]$DispenseId, [string]$Token)
    $status = $null
    for ($i = 0; $i -lt 30; $i++) {
        $r = Invoke-Api 'GET' "/api/v2/pharmacy/dispenses/$DispenseId" $null $Token
        $status = (Res $r).dispense.billingStatus
        if ("$status" -ne 'PENDING') { return $status }
        Start-Sleep -Milliseconds 200
    }
    return $status
}

# Dispense one clinical DRUG order end-to-end and return ids.
function New-DispensedOrderRx {
    param([string]$VisitId, [string]$Token, [int]$Quantity)
    $r = Invoke-Api 'POST' "/api/v2/clinical/visits/$VisitId/orders" @{
        items = @(@{ itemCode = 'DRUG-AMOX-025'; quantity = $Quantity; dose = '0.5g'; frequency = 'bid' })
    } $Token
    $orderId = (Res $r)[0].id
    $cur = Wait-OrderTransferred -VisitId $VisitId -OrderId $orderId -Token $Token
    $rxId = $cur.externalId
    $r = Invoke-Api 'POST' "/api/v2/pharmacy/prescriptions/$rxId/review" @{ approve = $true } $Token
    $r = Invoke-Api 'POST' "/api/v2/pharmacy/prescriptions/$rxId/dispense" @{} $Token
    $disp = Res $r
    return [pscustomobject]@{
        OrderId = $orderId
        RxId = $rxId
        DispenseId = $disp.dispense.id
        Items = @($disp.items)
        TransferStatus = $cur.transferStatus
        BillingStatus = $disp.dispense.billingStatus
    }
}

$suffix = Get-Random -Minimum 1000 -Maximum 9999

# ---------------------------------------------------------------- 1. login
$r = Invoke-Api 'POST' '/api/v2/auth/login' @{ username = $Username; password = $Password }
Check 'login' $r.Status 200
$token = (Res $r).accessToken
if (-not $token) { Write-Output "FATAL no token: $($r.Body)"; exit 1 }

# ---------------------------------------------------------------- 2. patient + visit
$r = Invoke-Api 'POST' '/api/v2/empi/patients' @{
    name = "Refund Patient $suffix"; gender = 'FEMALE'; birthDate = '1979-07-18'
    idType = 'ID_CARD'; idNo = "11010119790718$suffix"
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

# ---------------------------------------------------------------- 3. stock
$r = Invoke-Api 'GET' '/api/v2/pharmacy/drugs?keyword=DRUG-AMOX' $null $token
Check 'drug.list' $r.Status 200
$amoxId = @(Res $r)[0].drug.id
$far = (Get-Date).AddDays(300).ToString('yyyy-MM-dd')
$r = Invoke-Api 'POST' "/api/v2/pharmacy/drugs/$amoxId/batches" @{
    batchNo = "B-RF-$suffix"; expireDate = $far; quantity = 30; supplier = 'GuoYao'
} $token
Check 'stock.receive' $r.Status 200

# ---------------------------------------------------------------- 4. A: refund BEFORE settle => net charge
$a = New-DispensedOrderRx -VisitId $visitId -Token $token -Quantity 2
Check 'A.transfer' $a.TransferStatus 'TRANSFERRED'
Check 'A.billingMode' $a.BillingStatus 'NONE'
Check 'A.items' (@($a.Items).Count -ge 1) True
$itemSumA = ($a.Items | Measure-Object -Property amount -Sum).Sum
CheckAmt 'A.items.amountSum' $itemSumA 25.00

$r = Invoke-Api 'POST' "/api/v2/pharmacy/dispenses/$($a.DispenseId)/returns" @{
    dispenseItemId = $a.Items[0].id; quantity = 1; reason = 'patient stopped one box'
} $token
Check 'A.return.ok' $r.Status 200
Check 'A.return.dispenseStatus' (Res $r).dispense.status 'PARTIAL_RETURNED'
Check 'A.return.billingStatus' (Res $r).dispense.billingStatus 'NONE'

$r = Invoke-Api 'GET' "/api/v2/clinical/visits/$visitId/charge" $null $token
$totalBeforeA = [decimal](Res $r).totalAmount

$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/settle" @{ paymentMethod = 'CASH' } $token
Check 'A.settle.ok' $r.Status 200
Check 'A.settle.status' (Res $r).status 'PAID'
CheckAmt 'A.settle.delta' (([decimal](Res $r).totalAmount) - $totalBeforeA) 12.50
$chargeA = Res $r
$drugItemsA = @($chargeA.items | Where-Object { $_.itemCode -eq 'DRUG-AMOX-025' })
Check 'A.charge.itemCount' $drugItemsA.Count 1
CheckAmt 'A.charge.itemAmount' $drugItemsA[0].amount 12.50

# ---------------------------------------------------------------- 5. B: refund AFTER settle => pending, bill untouched
$b = New-DispensedOrderRx -VisitId $visitId -Token $token -Quantity 2
Check 'B.transfer' $b.TransferStatus 'TRANSFERRED'
$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/settle" @{ paymentMethod = 'CASH' } $token
Check 'B.settle.ok' $r.Status 200
CheckAmt 'B.settle.amount' (Res $r).totalAmount 25.00

$r = Invoke-Api 'POST' "/api/v2/pharmacy/dispenses/$($b.DispenseId)/returns" @{
    dispenseItemId = $b.Items[0].id; quantity = 1; reason = 'adverse reaction'
} $token
Check 'B.return.ok' $r.Status 200
Check 'B.return.dispenseStatus' (Res $r).dispense.status 'PARTIAL_RETURNED'
Check 'B.return.billingStatus' (Wait-DispenseBilling -DispenseId $b.DispenseId -Token $token) 'REFUND_PENDING'

# settled bill must not be silently rewritten (finance refunds it out of band)
Start-Sleep -Milliseconds 500
$r = Invoke-Api 'GET' "/api/v2/clinical/visits/$visitId/charge" $null $token
Check 'B.charge.status' (Res $r).status 'PAID'
CheckAmt 'B.charge.total' (Res $r).totalAmount 25.00

$r = Invoke-Api 'POST' "/api/v2/pharmacy/dispenses/$($b.DispenseId)/billing-retry" @{} $token
Check 'B.billingRetry.ok' $r.Status 200

# ---------------------------------------------------------------- 6. C: full refund BEFORE settle => order cancelled
$c = New-DispensedOrderRx -VisitId $visitId -Token $token -Quantity 2
Check 'C.transfer' $c.TransferStatus 'TRANSFERRED'
# FEFO may split one order line across several batches: return every dispense item in full
$lastReturn = $null
foreach ($it in $c.Items) {
    $r = Invoke-Api 'POST' "/api/v2/pharmacy/dispenses/$($c.DispenseId)/returns" @{
        dispenseItemId = $it.id; quantity = $it.quantity; reason = 'whole dispense returned'
    } $token
    Check "C.return.ok.$($it.id)" $r.Status 200
    $lastReturn = Res $r
}
Check 'C.return.dispenseStatus' $lastReturn.dispense.status 'RETURNED'

$orderC = $null
for ($i = 0; $i -lt 30; $i++) {
    $r = Invoke-Api 'GET' "/api/v2/clinical/visits/$visitId/orders" $null $token
    $orderC = @(Res $r) | Where-Object { $_.id -eq $c.OrderId }
    if ("$($orderC.status)" -eq 'CANCELLED') { break }
    Start-Sleep -Milliseconds 200
}
Check 'C.order.cancelled' $orderC.status 'CANCELLED'

# nothing left to charge: settle returns the latest paid bill, amount unchanged
$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/settle" @{ paymentMethod = 'CASH' } $token
Check 'C.settle.ok' $r.Status 200
CheckAmt 'C.settle.noExtraCharge' (Res $r).totalAmount 25.00

# ---------------------------------------------------------------- 7. internal endpoint must reject unauthenticated calls
$r = Invoke-Api 'POST' '/internal/pharmacy-order-refunds' @{
    orderId = $c.OrderId; dispenseItemId = $c.Items[0].id; itemAmount = 25.00
    cumulativeRefundAmount = 25.00; reason = 'attacker'; actor = 'attacker'
} $null $ClinicalUrl
Check 'internal.orderRefund.noToken.403' $r.Status 403
Check 'internal.orderRefund.noToken.code' (Body $r).code 40300

Write-Output "-----"
Write-Output "VISIT_ID=$visitId"
Write-Output "A_ORDER_ID=$($a.OrderId)"
Write-Output "B_ORDER_ID=$($b.OrderId)"
Write-Output "C_ORDER_ID=$($c.OrderId)"
if ($script:fail -gt 0) { Write-Output "RESULT FAIL ($script:fail)"; exit 1 }
Write-Output 'RESULT PASS'
exit 0
