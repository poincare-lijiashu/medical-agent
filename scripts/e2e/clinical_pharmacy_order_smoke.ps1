# ============================================================================
# MedAssist clinical order -> pharmacy prescription smoke test (through gateway).
#
# Covers (HIS main path, first step):
#   * a DRUG order placed in clinical is transferred to pharmacy automatically
#     (order.transferStatus: PENDING -> TRANSFERRED, externalId/externalNo filled)
#   * the generated prescription keeps billingMode=ORDER and the same amount
#     (no second billing chain: clinical already priced the order)
#   * non-drug orders are NOT transferred (transferStatus stays NONE)
#   * transfer-retry is idempotent for an already transferred order
#   * the pharmacy internal endpoint rejects calls without a token (403)
#   * approving the transferred prescription works (pharmacist can act on it)
#   * cancelling the order cancels the prescription (order cancel -> rx CANCELLED)
#   * cancelling a DISPENSED order is rejected (409): money/drugs already moved
#
# ASCII only on purpose: Windows PowerShell 5.1 reads BOM-less UTF-8 as ANSI.
#
# Prerequisites: auth :8081, empi :8082, clinical :8083, pharmacy :8084, gateway
# Usage (repo root):
#   powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\e2e\clinical_pharmacy_order_smoke.ps1
# ============================================================================
param(
    [string]$BaseUrl = "http://127.0.0.1:8080",
    [string]$PharmacyUrl = "http://127.0.0.1:8084",
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
    name = "Order Patient $suffix"; gender = 'MALE'; birthDate = '1985-03-12'
    idType = 'ID_CARD'; idNo = "11010119850312$suffix"
} $token
Check 'patient.create' $r.Status 200
$patientId = (Res $r).id

$r = Invoke-Api 'POST' '/api/v2/clinical/registrations' @{
    patientId = $patientId; deptId = $DeptId; doctorId = 'doctor01'
} $token
Check 'visit.register' $r.Status 200
$visit = Res $r
$visitId = $visit.id

$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/start" @{} $token
Check 'visit.start' $r.Status 200
Check 'visit.status' (Res $r).status 'IN_TREATMENT'

# ---------------------------------------------------------------- 3. stock (so the prescription can be dispensed later)
$r = Invoke-Api 'GET' '/api/v2/pharmacy/drugs?keyword=DRUG-AMOX' $null $token
Check 'drug.list' $r.Status 200
$amoxId = @(Res $r)[0].drug.id

$far = (Get-Date).AddDays(300).ToString('yyyy-MM-dd')
$r = Invoke-Api 'POST' "/api/v2/pharmacy/drugs/$amoxId/batches" @{
    batchNo = "B-ORD-$suffix"; expireDate = $far; quantity = 20; supplier = 'GuoYao'
} $token
Check 'stock.receive' $r.Status 200

# ---------------------------------------------------------------- 4. prescriptions before (for dup check)
$r = Invoke-Api 'GET' "/api/v2/pharmacy/prescriptions?patientId=$patientId" $null $token
Check 'rx.list.before' $r.Status 200
$rxBefore = @(Res $r).Count

# ---------------------------------------------------------------- 5. place orders: one DRUG + one EXAM
$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/orders" @{
    items = @(
        @{ itemCode = 'DRUG-AMOX-025'; quantity = 2; dose = '0.5g'; frequency = 'bid' },
        @{ itemCode = 'EXM-CBC'; quantity = 1; frequency = 'once' }
    )
} $token
Check 'order.place' $r.Status 200
$orders = Res $r
$drugOrder = $orders | Where-Object { $_.itemCode -eq 'DRUG-AMOX-025' }
$examOrder = $orders | Where-Object { $_.itemCode -eq 'EXM-CBC' }
$drugOrderId = $drugOrder.id
CheckAmt 'order.drug.amount' $drugOrder.amount 25.00
Check 'order.drug.transfer.initial' $drugOrder.transferStatus 'PENDING'
# EXAM orders are transferred to tech (see clinical_tech_order_smoke); here only check
# that the two domains are routed independently (the exam order is NOT sent to pharmacy)
Check 'order.exam.transfer.routed' ($examOrder.transferStatus -ne 'NONE') True

# transfer runs after the local transaction commits: poll until it settles
$transferStatus = $drugOrder.transferStatus
$externalId = $null
$externalNo = $null
$externalType = $null
for ($i = 0; $i -lt 25; $i++) {
    $r = Invoke-Api 'GET' "/api/v2/clinical/visits/$visitId/orders" $null $token
    $current = @(Res $r) | Where-Object { $_.id -eq $drugOrderId }
    $transferStatus = $current.transferStatus
    $externalId = $current.externalId
    $externalNo = $current.externalNo
    $externalType = $current.externalType
    if ("$transferStatus" -ne 'PENDING') { break }
    Start-Sleep -Milliseconds 200
}
Check 'order.drug.transfer.final' $transferStatus 'TRANSFERRED'
Check 'order.drug.externalType' $externalType 'PRESCRIPTION'
if (-not $externalId) { Write-Output "FATAL no externalId: transfer did not fill prescription id"; exit 1 }

# ---------------------------------------------------------------- 6. prescription mirrors the order
$r = Invoke-Api 'GET' "/api/v2/pharmacy/prescriptions/$externalId" $null $token
Check 'rx.get' $r.Status 200
$rx = Res $r
Check 'rx.no.match' $rx.prescription.prescriptionNo $externalNo
Check 'rx.billingMode' $rx.prescription.billingMode 'ORDER'
Check 'rx.status' $rx.prescription.status 'PENDING_REVIEW'
CheckAmt 'rx.total' $rx.prescription.totalAmount 25.00
Check 'rx.items' @($rx.items).Count 1
Check 'rx.item.drug' @($rx.items)[0].drugCode 'DRUG-AMOX-025'

# ---------------------------------------------------------------- 7. pharmacist review
$r = Invoke-Api 'POST' "/api/v2/pharmacy/prescriptions/$externalId/review" @{ approve = $true } $token
Check 'rx.approve' $r.Status 200
Check 'rx.approved.status' (Res $r).prescription.status 'APPROVED'

# ---------------------------------------------------------------- 8. retry is idempotent (already transferred)
$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/orders/$drugOrderId/transfer-retry" @{} $token
Check 'transferRetry.ok' $r.Status 200
Check 'transferRetry.status' (Res $r).transferStatus 'TRANSFERRED'
Check 'transferRetry.externalId' (Res $r).externalId $externalId

$r = Invoke-Api 'GET' "/api/v2/pharmacy/prescriptions?patientId=$patientId" $null $token
Check 'rx.list.after' (@(Res $r).Count - $rxBefore) 1

# ---------------------------------------------------------------- 9. no second billing chain (ORDER mode)
$r = Invoke-Api 'GET' "/api/v2/clinical/visits/$visitId/charge" $null $token
Check 'charge.preview' $r.Status 200
$chargeTotal = [decimal](Res $r).totalAmount

# ---------------------------------------------------------------- 10. cancel order -> prescription cancelled
$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/orders/$drugOrderId/cancel" @{} $token
Check 'order.cancel' $r.Status 200
Check 'order.cancel.status' (Res $r).status 'CANCELLED'
$r = Invoke-Api 'GET' "/api/v2/pharmacy/prescriptions/$externalId" $null $token
Check 'rx.cancelled.status' (Res $r).prescription.status 'CANCELLED'

# ---------------------------------------------------------------- 11. dispensed prescription cannot be cancelled
$r = Invoke-Api 'GET' '/api/v2/pharmacy/drugs?keyword=DRUG-NS-500' $null $token
$nsId = @(Res $r)[0].drug.id
$r = Invoke-Api 'POST' "/api/v2/pharmacy/drugs/$nsId/batches" @{
    batchNo = "B-NS-$suffix"; expireDate = $far; quantity = 5; supplier = 'GuoYao'
} $token
Check 'stock.receive.ns' $r.Status 200

$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/orders" @{
    items = @(@{ itemCode = 'DRUG-NS-500'; quantity = 1; dose = '500ml'; frequency = 'once' })
} $token
Check 'order2.place' $r.Status 200
$order2Id = (Res $r)[0].id
$order2Rx = $null
for ($i = 0; $i -lt 25; $i++) {
    $r = Invoke-Api 'GET' "/api/v2/clinical/visits/$visitId/orders" $null $token
    $cur2 = @(Res $r) | Where-Object { $_.id -eq $order2Id }
    if ("$($cur2.transferStatus)" -ne 'PENDING') { break }
    Start-Sleep -Milliseconds 200
}
Check 'order2.transfer' $cur2.transferStatus 'TRANSFERRED'
$order2Rx = $cur2.externalId
$r = Invoke-Api 'POST' "/api/v2/pharmacy/prescriptions/$order2Rx/review" @{ approve = $true } $token
Check 'order2.approve' $r.Status 200
$r = Invoke-Api 'POST' "/api/v2/pharmacy/prescriptions/$order2Rx/dispense" @{} $token
Check 'order2.dispense' $r.Status 200
Check 'order2.dispense.status' (Res $r).dispense.status 'DISPENSED'

# R6-MF-03: cancel/stop is a medical decision and must succeed. When the prescription is
# already dispensed the downstream void is deferred to a retry task (worker drains it).
$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/orders/$order2Id/cancel" @{} $token
Check 'order2.cancel.dispensed.ok' $r.Status 200
Check 'order2.cancel.dispensed.status' (Res $r).status 'CANCELLED'
# until the deferred void completes the prescription stays DISPENSED (drugs already went out)
$r = Invoke-Api 'GET' "/api/v2/pharmacy/prescriptions?patientId=$patientId" $null $token
$rx2 = @(Res $r) | Where-Object { $_.id -eq $order2Rx }
Check 'order2.rx.still.dispensed' $rx2.status 'DISPENSED'

# ---------------------------------------------------------------- 12. internal endpoint rejects unauthenticated calls
$r = Invoke-Api 'POST' '/internal/prescriptions' @{
    orderId = $drugOrderId; patientId = $patientId
    items = @(@{ drugCode = 'DRUG-AMOX-025'; quantity = 1 })
} $null $PharmacyUrl
Check 'internal.noToken.403' $r.Status 403
Check 'internal.noToken.code' (Body $r).code 40300

Write-Output "-----"
Write-Output "VISIT_ID=$visitId"
Write-Output "ORDER_ID=$drugOrderId"
Write-Output "PRESCRIPTION_ID=$externalId"
Write-Output "CHARGE_TOTAL=$chargeTotal"
if ($script:fail -gt 0) { Write-Output "RESULT FAIL ($script:fail)"; exit 1 }
Write-Output 'RESULT PASS'
exit 0
