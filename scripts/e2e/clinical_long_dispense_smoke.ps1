# ============================================================================
# MedAssist long-term drug daily dispensing smoke test (through gateway).
#
# Covers (B2 follow-up):
#   * LONG DRUG order is NOT auto-transferred at placement (one transfer can
#     only supply one day) -> transferStatus NONE
#   * ward daily request creates ONE prescription per (order, supply date)
#   * repeating the same-day request is idempotent (duplicate=true, same id)
#   * prescription keeps billingMode=ORDER (pharmacy never bills again) and
#     carries supplyDate + daily quantity
#   * future supply date is rejected (422)
#   * stopping the order cancels not-yet-dispensed daily prescriptions, while
#     a dispensed one is left to the return flow
#   * ward stock (V26): reception adds IN txn, unexecuted (REFUSED) return adds
#     RETURN txn, over-return is rejected (422), repeat return is idempotent,
#     executed medication can never be returned (422)
#
# ASCII only on purpose: Windows PowerShell 5.1 reads BOM-less UTF-8 as ANSI.
#
# Prerequisites: auth :8081, empi :8082, clinical :8083, pharmacy :8084, gateway
# Usage (repo root):
#   powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\e2e\clinical_long_dispense_smoke.ps1
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
$today = (Get-Date).ToString('yyyy-MM-dd')
$tomorrow = (Get-Date).AddDays(1).ToString('yyyy-MM-dd')

# ---------------------------------------------------------------- 1. login
$r = Invoke-Api 'POST' '/api/v2/auth/login' @{ username = $Username; password = $Password }
Check 'login' $r.Status 200
$token = (Res $r).accessToken
if (-not $token) { Write-Output "FATAL no token: $($r.Body)"; exit 1 }

# ---------------------------------------------------------------- 2. patient + admission
$r = Invoke-Api 'POST' '/api/v2/empi/patients' @{
    name = "Dispense Patient $suffix"; gender = 'MALE'; birthDate = '1970-02-11'
    idType = 'ID_CARD'; idNo = "11010119700211$suffix"
} $token
Check 'patient.create' $r.Status 200
$patientId = (Res $r).id

$r = Invoke-Api 'POST' '/api/v2/clinical/wards' @{
    code = "W-DS-$suffix"; name = "Dispense Ward $suffix"; deptId = $DeptId
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
$admissionId = (Res $r).id
$visitId = (Res $r).visitId

# ---------------------------------------------------------------- 3. stock
$r = Invoke-Api 'GET' '/api/v2/pharmacy/drugs?keyword=DRUG-AMOX' $null $token
Check 'drug.list' $r.Status 200
$amoxId = @(Res $r)[0].drug.id
$far = (Get-Date).AddDays(300).ToString('yyyy-MM-dd')
$r = Invoke-Api 'POST' "/api/v2/pharmacy/drugs/$amoxId/batches" @{
    batchNo = "B-DS-$suffix"; expireDate = $far; quantity = 30; supplier = 'GuoYao'
} $token
Check 'stock.receive' $r.Status 200

# ---------------------------------------------------------------- 4. LONG DRUG: no auto transfer
$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/orders" @{
    items = @(@{ itemCode = 'DRUG-AMOX-025'; quantity = 2; dose = '0.5g'; frequency = 'bid'; orderMode = 'LONG' })
} $token
Check 'longDrug.place' $r.Status 200
$orderA = @(Res $r)[0]
Check 'longDrug.transferStatus' $orderA.transferStatus 'NONE'
CheckAmt 'longDrug.dailyAmount' $orderA.amount 25.00
Check 'longDrug.frequencyTimes' $orderA.frequencyTimes 2

# ---------------------------------------------------------------- 5. daily request (today)
$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/daily-dispense-requests" @{} $token
Check 'dispenseRequest.ok' $r.Status 200
$reqA = Res $r
Check 'dispenseRequest.requested' $reqA.requested 1
Check 'dispenseRequest.created' $reqA.created 1
Check 'dispenseRequest.duplicate' $reqA.duplicate 0
Check 'dispenseRequest.failed' $reqA.failed 0
$rxAId = @($reqA.items)[0].prescriptionId
Check 'dispenseRequest.date' $reqA.date $today

# ---------------------------------------------------------------- 6. idempotent same day
$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/daily-dispense-requests" @{} $token
Check 'dispenseRequest.repeat.ok' $r.Status 200
$reqA2 = Res $r
Check 'dispenseRequest.repeat.duplicate' $reqA2.duplicate 1
Check 'dispenseRequest.repeat.created' $reqA2.created 0
Check 'dispenseRequest.repeat.sameRx' @($reqA2.items)[0].prescriptionId $rxAId

# ---------------------------------------------------------------- 7. prescription mirrors daily supply
$r = Invoke-Api 'GET' "/api/v2/pharmacy/prescriptions/$rxAId" $null $token
Check 'rx.get' $r.Status 200
$rxA = Res $r
Check 'rx.billingMode' $rxA.prescription.billingMode 'ORDER'
Check 'rx.supplyDate' $rxA.prescription.supplyDate $today
Check 'rx.item.quantity' @($rxA.items)[0].quantity 2

# ---------------------------------------------------------------- 8. dispense does not re-bill
$r = Invoke-Api 'GET' "/api/v2/clinical/visits/$visitId/charge" $null $token
$chargeBefore = [decimal](Res $r).totalAmount
$r = Invoke-Api 'POST' "/api/v2/pharmacy/prescriptions/$rxAId/review" @{ approve = $true } $token
Check 'rx.approve' $r.Status 200
$r = Invoke-Api 'POST' "/api/v2/pharmacy/prescriptions/$rxAId/dispense" @{} $token
Check 'rx.dispense' $r.Status 200
$dispAId = (Res $r).dispense.id
Check 'rx.dispense.status' (Res $r).dispense.status 'DISPENSED'
Start-Sleep -Milliseconds 500
$r = Invoke-Api 'GET' "/api/v2/clinical/visits/$visitId/charge" $null $token
CheckAmt 'charge.noSecondBilling' (Res $r).totalAmount $chargeBefore

# ---------------------------------------------------------------- 8b. ward board before receipt
$r = Invoke-Api 'GET' "/api/v2/clinical/visits/$visitId/dispense-board" $null $token
Check 'board.ok' $r.Status 200
$board = Res $r
Check 'board.ordered' $board.ordered 1
Check 'board.requested' $board.requested 1
Check 'board.received' $board.received 0
$rowA = @($board.items) | Where-Object { $_.orderId -eq $orderA.id }
Check 'board.requestStatus' $rowA.requestStatus 'REQUESTED'
Check 'board.prescriptionNo' ($rowA.prescriptionNo -ne $null -and $rowA.prescriptionNo -ne '') True
Check 'board.receivedQty' $rowA.receivedQuantity 0
Check 'board.nursingStatus' $rowA.nursingStatus 'NOT_EXECUTED'

# ---------------------------------------------------------------- 8c. nursing execution flows into board
$r = Invoke-Api 'POST' "/api/v2/clinical/inpatient/admissions/$admissionId/nursing-executions" @{
    orderId = $orderA.id; status = 'EXECUTED'
} $token
Check 'nursing.execute' $r.Status 200
$r = Invoke-Api 'GET' "/api/v2/clinical/visits/$visitId/dispense-board" $null $token
$rowA = @((Res $r).items) | Where-Object { $_.orderId -eq $orderA.id }
Check 'board.nursingStatus.afterExec' $rowA.nursingStatus 'EXECUTED'

# ---------------------------------------------------------------- 8d. ward receipt (idempotent)
$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/orders/$($orderA.id)/ward-receipts" @{
    dispenseId = $dispAId; supplyDate = $today; quantity = 2
} $token
Check 'receipt.ok' $r.Status 200
$receiptId = (Res $r).id
Check 'receipt.quantity' (Res $r).quantity 2
Check 'receipt.status' (Res $r).status 'RECEIVED'

$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/orders/$($orderA.id)/ward-receipts" @{
    dispenseId = $dispAId; supplyDate = $today; quantity = 2
} $token
Check 'receipt.repeat.ok' $r.Status 200
Check 'receipt.repeat.sameId' (Res $r).id $receiptId

$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/orders/$($orderA.id)/ward-receipts" @{
    dispenseId = $dispAId; supplyDate = $today; quantity = 3
} $token
Check 'receipt.overQuantity.422' $r.Status 422

$r = Invoke-Api 'GET' "/api/v2/clinical/visits/$visitId/dispense-board" $null $token
$board2 = Res $r
Check 'board.received.afterReceipt' $board2.received 1
$rowA2 = @($board2.items) | Where-Object { $_.orderId -eq $orderA.id }
Check 'board.receivedQty.afterReceipt' $rowA2.receivedQuantity 2

# ---------------------------------------------------------------- 8e. board rejects future date
$r = Invoke-Api 'GET' "/api/v2/clinical/visits/$visitId/dispense-board?date=$tomorrow" $null $token
Check 'board.future.422' $r.Status 422

# ---------------------------------------------------------------- 8f. ward secondary stock + unexecuted return
$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/orders" @{
    items = @(@{ itemCode = 'DRUG-AMOX-025'; quantity = 2; dose = '0.5g'; frequency = 'qd'; orderMode = 'LONG' })
} $token
Check 'longDrugC.place' $r.Status 200
$orderC = @(Res $r)[0]

$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/daily-dispense-requests" @{} $token
Check 'dispenseRequestC.ok' $r.Status 200
$rxCId = @((Res $r).items) | Where-Object { $_.orderId -eq $orderC.id } | Select-Object -ExpandProperty prescriptionId
Check 'dispenseRequestC.created' ($rxCId -ne $null) True

$r = Invoke-Api 'POST' "/api/v2/pharmacy/prescriptions/$rxCId/review" @{ approve = $true } $token
Check 'rxC.approve' $r.Status 200
$r = Invoke-Api 'POST' "/api/v2/pharmacy/prescriptions/$rxCId/dispense" @{} $token
Check 'rxC.dispense' $r.Status 200
$dispCId = (Res $r).dispense.id

# patient refuses the drug -> medication is still in the ward, so it can be returned
$r = Invoke-Api 'POST' "/api/v2/clinical/inpatient/admissions/$admissionId/nursing-executions" @{
    orderId = $orderC.id; status = 'REFUSED'; note = 'patient refused'
} $token
Check 'nursingC.refused' $r.Status 200

$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/orders/$($orderC.id)/ward-receipts" @{
    dispenseId = $dispCId; supplyDate = $today; quantity = 2
} $token
Check 'receiptC.ok' $r.Status 200
$receiptCId = (Res $r).id
Check 'receiptC.status' (Res $r).status 'RECEIVED'

# board hints that the unexecuted medication can be returned
$r = Invoke-Api 'GET' "/api/v2/clinical/visits/$visitId/dispense-board" $null $token
$rowC = @((Res $r).items) | Where-Object { $_.orderId -eq $orderC.id }
Check 'board.C.returnable' $rowC.returnable True
Check 'board.C.returnHint' ($rowC.returnHint -ne $null -and $rowC.returnHint -ne '') True
Check 'board.C.stockBalance' $rowC.stockBalance 2

# ward stock now holds orderA (executed, 2) + orderC (refused, 2) = 4
$r = Invoke-Api 'GET' "/api/v2/clinical/visits/$visitId/ward-stock" $null $token
Check 'stock.ok' $r.Status 200
Check 'stock.totalBeforeReturn' (Res $r).totalBalance 4

$returnId = [guid]::NewGuid().ToString()
$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/ward-receipts/$receiptCId/returns" @{
    returnId = $returnId; quantity = 3; reason = 'patient refused'
} $token
Check 'return.overQuantity.422' $r.Status 422

$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/ward-receipts/$receiptCId/returns" @{
    returnId = $returnId; quantity = 2; reason = 'patient refused'
} $token
Check 'return.ok' $r.Status 200
Check 'return.quantity' (Res $r).quantity 2
Check 'return.receiptStatus' (Res $r).receiptStatus 'RETURNED'
Check 'return.stockBalance' (Res $r).stockBalance 0

# same returnId replays the same record (no second stock movement)
$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/ward-receipts/$receiptCId/returns" @{
    returnId = $returnId; quantity = 2; reason = 'patient refused'
} $token
Check 'return.repeat.ok' $r.Status 200
Check 'return.repeat.sameId' (Res $r).id $returnId
Check 'return.repeat.stockBalance' (Res $r).stockBalance 0

# anything beyond the received quantity is rejected
$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/ward-receipts/$receiptCId/returns" @{
    returnId = [guid]::NewGuid().ToString(); quantity = 1
} $token
Check 'return.afterFull.422' $r.Status 422

# executed medication can never be returned (orderA was EXECUTED before receipt)
$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/ward-receipts/$receiptId/returns" @{
    returnId = [guid]::NewGuid().ToString(); quantity = 1; reason = 'wrong medicine'
} $token
Check 'return.executed.422' $r.Status 422

$r = Invoke-Api 'GET' "/api/v2/clinical/visits/$visitId/ward-stock" $null $token
Check 'stock.totalAfterReturn' (Res $r).totalBalance 2
$txns = @((Res $r).recentTxns)
Check 'stock.hasReturnTxn' (@($txns | Where-Object { $_.txnType -eq 'RETURN' }).Count) 1

$r = Invoke-Api 'GET' "/api/v2/clinical/visits/$visitId/dispense-board" $null $token
$rowC2 = @((Res $r).items) | Where-Object { $_.orderId -eq $orderC.id }
Check 'board.C.returnedQty' $rowC2.returnedQuantity 2
Check 'board.C.stockAfterReturn' $rowC2.stockBalance 0
Check 'board.C.returnableAfterReturn' $rowC2.returnable False

# ---------------------------------------------------------------- 9. future date rejected
$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/daily-dispense-requests" @{ date = $tomorrow } $token
Check 'dispenseRequest.future.422' $r.Status 422

# ---------------------------------------------------------------- 10. stop cancels undispensed daily prescriptions
$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/orders" @{
    items = @(@{ itemCode = 'DRUG-AMOX-025'; quantity = 2; dose = '0.5g'; frequency = 'qd'; orderMode = 'LONG' })
} $token
Check 'longDrug2.place' $r.Status 200
$orderB = @(Res $r)[0]
$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/daily-dispense-requests" @{} $token
Check 'dispenseRequest2.ok' $r.Status 200
$rxBId = @((Res $r).items) | Where-Object { $_.orderId -eq $orderB.id } | Select-Object -ExpandProperty prescriptionId
Check 'dispenseRequest2.created' ($rxBId -ne $null) True

$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/orders/$($orderB.id)/stop" @{ reason = 'switch drug' } $token
Check 'longDrug2.stop.ok' $r.Status 200
Start-Sleep -Milliseconds 500
$r = Invoke-Api 'GET' "/api/v2/pharmacy/prescriptions/$rxBId" $null $token
Check 'longDrug2.rxB.cancelled' (Res $r).prescription.status 'CANCELLED'

# dispensed prescription of order A stays DISPENSED (return flow owns it)
$r = Invoke-Api 'GET' "/api/v2/pharmacy/prescriptions/$rxAId" $null $token
Check 'longDrugA.rxA.stillDispensed' (Res $r).prescription.status 'DISPENSED'

# ---------------------------------------------------------------- 11. outpatient cannot request daily supply
$r = Invoke-Api 'POST' '/api/v2/empi/patients' @{
    name = "Opd Patient $suffix"; gender = 'FEMALE'; birthDate = '1991-09-09'
    idType = 'ID_CARD'; idNo = "11010119910909$suffix"
} $token
Check 'opd.patient.create' $r.Status 200
$opdPatientId = (Res $r).id
$r = Invoke-Api 'POST' '/api/v2/clinical/registrations' @{
    patientId = $opdPatientId; deptId = $DeptId; doctorId = 'doctor01'
} $token
Check 'opd.register' $r.Status 200
$opdVisitId = (Res $r).id
$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$opdVisitId/start" @{} $token
Check 'opd.start' $r.Status 200
$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$opdVisitId/daily-dispense-requests" @{} $token
Check 'dispenseRequest.opd.422' $r.Status 422

Write-Output "-----"
Write-Output "VISIT_ID=$visitId"
Write-Output "ORDER_A_ID=$($orderA.id)"
Write-Output "RX_A_ID=$rxAId"
Write-Output "ORDER_B_ID=$($orderB.id)"
Write-Output "RX_B_ID=$rxBId"
if ($script:fail -gt 0) { Write-Output "RESULT FAIL ($script:fail)"; exit 1 }
Write-Output 'RESULT PASS'
exit 0
