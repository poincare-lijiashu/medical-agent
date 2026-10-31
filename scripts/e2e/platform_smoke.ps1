# ============================================================================
# MedAssist platform smoke test (gateway path, /api/v2).
#
# WHY THIS FILE EXISTS: the 2026-10-31 clinical route bug (StripPrefix=3 instead
# of 2) made every clinical endpoint return 404 through the gateway. Direct calls
# to 8083 passed, so unit tests never caught it. This script always goes through
# the gateway, which is the path real users take.
#
# ASCII only on purpose: Windows PowerShell 5.1 reads BOM-less UTF-8 as ANSI and
# non-ASCII characters break parsing.
#
# Prerequisites (all on the same host):
#   auth       :8081   empi :8082   clinical :8083   gateway :18080
#   PostgreSQL :5433 (container medagent_pg)
#   The gateway JAR must contain the current routing config (StripPrefix=2).
#
# Usage (from repo root):
#   powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\e2e\platform_smoke.ps1
#   powershell ... -File .\scripts\e2e\platform_smoke.ps1 -BaseUrl http://127.0.0.1:18080
#
# Exit code 0 = all assertions passed, 1 = at least one failed.
# ============================================================================
param(
    [string]$BaseUrl = "http://127.0.0.1:18080",
    [string]$Username = "admin01",
    [string]$Password = "Med@2026",
    # dept seeded by clinical V1 migration
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

# ---------------------------------------------------------------- 1. auth
$r = Invoke-Api 'POST' '/api/v2/auth/login' @{ username = $Username; password = $Password }
Check 'login' $r.Status 200
$token = (Res $r).accessToken
if (-not $token) { Write-Output "FATAL no token: $($r.Body)"; exit 1 }

# ---------------------------------------------------------------- 2. patient (EMPI)
$r = Invoke-Api 'POST' '/api/v2/empi/patients' @{
    name = "Smoke Patient $suffix"; gender = 'MALE'; birthDate = '1980-05-06'
    idType = 'ID_CARD'; idNo = "11010119800506$suffix"; phone = '13900001111'
} $token
Check 'patient.create' $r.Status 200
$patientId = (Res $r).id

# ---------------------------------------------------------------- 3. ward + beds
$r = Invoke-Api 'POST' '/api/v2/clinical/wards' @{
    code = "W-SMOKE-$suffix"; name = "Smoke Ward $suffix"; deptId = $DeptId
} $token
Check 'ward.create' $r.Status 200
$wardId = (Res $r).id

$r = Invoke-Api 'POST' "/api/v2/clinical/wards/$wardId/beds" @{ bedNos = @('01', '02') } $token
Check 'bed.create' $r.Status 200
$beds = Res $r
$bedId = ($beds | Where-Object { $_.bedNo -eq '01' }).id

# ---------------------------------------------------------------- 4. admit
$r = Invoke-Api 'POST' '/api/v2/clinical/inpatient/admissions' @{
    patientId = $patientId; wardId = $wardId; bedId = $bedId; attendingDoctor = 'doctor01'
} $token
Check 'admit' $r.Status 200
$admission = Res $r
$admissionId = $admission.id
$visitId = $admission.visitId
Check 'admit.status' $admission.status 'IN_HOSPITAL'
Check 'admit.bedNo' $admission.bedNo '01'

# ---------------------------------------------------------------- 5. orders
$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/orders" @{
    items = @(
        @{ itemCode = 'TRT-IV';  quantity = 1; dose = '100ml'; frequency = 'qd'; orderMode = 'LONG' },
        @{ itemCode = 'EXM-CBC'; quantity = 1; frequency = 'qd'; orderMode = 'LONG' },
        @{ itemCode = 'EXM-CBC'; quantity = 1; orderMode = 'TEMP' }
    )
} $token
Check 'order.place' $r.Status 200
$orders = Res $r
$longIv  = $orders | Where-Object { $_.orderMode -eq 'LONG' -and $_.itemCode -eq 'TRT-IV' }
$longCbc = $orders | Where-Object { $_.orderMode -eq 'LONG' -and $_.itemCode -eq 'EXM-CBC' }
$tempCbc = $orders | Where-Object { $_.orderMode -eq 'TEMP' }
CheckAmt 'order.longIv.amount'  $longIv.amount  8
CheckAmt 'order.longCbc.amount' $longCbc.amount 25
CheckAmt 'order.tempCbc.amount' $tempCbc.amount 25

# ---------------------------------------------------------------- 6. nursing
$r = Invoke-Api 'POST' "/api/v2/clinical/inpatient/admissions/$admissionId/nursing-executions" @{
    orderId = $longCbc.id; status = 'REFUSED'; note = 'patient refused blood draw'
} $token
Check 'nursing.refused' $r.Status 200

$r = Invoke-Api 'POST' "/api/v2/clinical/inpatient/admissions/$admissionId/nursing-executions" @{
    orderId = $longCbc.id; status = 'EXECUTED'
} $token
Check 'nursing.duplicate.409' $r.Status 409

$future = (Get-Date).AddDays(1).ToString('yyyy-MM-dd')
$r = Invoke-Api 'POST' "/api/v2/clinical/inpatient/admissions/$admissionId/nursing-executions" @{
    orderId = $longIv.id; execDate = $future; status = 'EXECUTED'
} $token
Check 'nursing.future.422' $r.Status 422

# ---------------------------------------------------------------- 7. daily bill
$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/daily-bill" $null $token
Check 'dailyBill.first' $r.Status 200
$bill = Res $r
Check   'dailyBill.orderCount'   $bill.orderCount 1
CheckAmt 'dailyBill.billedAmount' $bill.billedAmount 8
Check   'dailyBill.skipped'      $bill.skippedOrderNos.Count 1

$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/daily-bill" $null $token
Check   'dailyBill.idempotent.count'  (Res $r).orderCount 0
CheckAmt 'dailyBill.idempotent.amount' (Res $r).billedAmount 0

$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/daily-bill?date=2099-01-01" $null $token
Check 'dailyBill.future.422' $r.Status 422

# refused order can no longer be flipped after the day is billed (no write-off path yet)
$r = Invoke-Api 'POST' "/api/v2/clinical/inpatient/admissions/$admissionId/nursing-executions" @{
    orderId = $longIv.id; status = 'REFUSED'
} $token
Check 'nursing.refusedAfterBilled.409' $r.Status 409

# ---------------------------------------------------------------- 8. finance board + alerts
$r = Invoke-Api 'GET' "/api/v2/clinical/inpatient/admissions/$admissionId/finance" $null $token
Check 'finance.read' $r.Status 200
$fin = Res $r
CheckAmt 'finance.billedTotal'    $fin.billedTotal 8
CheckAmt 'finance.unpaidTotal'    $fin.unpaidTotal 8
CheckAmt 'finance.pendingAmount'  $fin.pendingAmount 25
CheckAmt 'finance.depositBalance' $fin.depositBalance 0
CheckAmt 'finance.shortfall'      $fin.shortfall 33
Check   'finance.warning'         $fin.warning 'True'

$r = Invoke-Api 'GET' "/api/v2/clinical/inpatient/alerts?wardId=$wardId" $null $token
Check 'alerts.read' $r.Status 200
$hit = @((Res $r) | Where-Object { $_.admissionId -eq $admissionId })
Check 'alerts.contains' $hit.Count 1

# ---------------------------------------------------------------- 9. deposit + settle
$r = Invoke-Api 'POST' "/api/v2/clinical/inpatient/admissions/$admissionId/deposits" @{
    amount = 50; method = 'CASH'; remark = 'smoke top-up'
} $token
Check 'deposit.create' $r.Status 200
CheckAmt 'deposit.balance' (Res $r).balance 50

$r = Invoke-Api 'POST' "/api/v2/clinical/inpatient/admissions/$admissionId/refunds" @{
    amount = 60; method = 'CASH'; remark = 'over-refund must fail'
} $token
Check 'refund.overBalance.409' $r.Status 409

$r = Invoke-Api 'GET' "/api/v2/clinical/inpatient/admissions/$admissionId/finance" $null $token
CheckAmt 'finance.shortfall.afterDeposit' (Res $r).shortfall 0
Check   'finance.warning.afterDeposit'    (Res $r).warning 'False'

$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/settle" @{ paymentMethod = 'CASH' } $token
Check 'settle.cash' $r.Status 200
Check 'settle.cash.status' (Res $r).status 'PAID'
CheckAmt 'settle.cash.total' (Res $r).totalAmount 33

# new charge after settlement -> auto-opens a new charge document
$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/orders" @{
    items = @(@{ itemCode = 'TRT-IV'; quantity = 2; orderMode = 'TEMP' })
} $token
Check 'order.place.second' $r.Status 200

$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/settle" @{ paymentMethod = 'DEPOSIT' } $token
Check 'settle.deposit.newCharge' $r.Status 200
$c2 = Res $r
Check 'settle.deposit.status' $c2.status 'PAID'
CheckAmt 'settle.deposit.total' $c2.totalAmount 16
CheckAmt 'deposit.balance.afterSettle' (Res (Invoke-Api 'GET' "/api/v2/clinical/inpatient/admissions/$admissionId/deposits" $null $token)).balance 34

$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/settle" @{ paymentMethod = 'DEPOSIT' } $token
Check 'settle.idempotent' $r.Status 200
Check 'settle.idempotent.chargeNo' (Res $r).chargeNo $c2.chargeNo

$r = Invoke-Api 'GET' "/api/v2/clinical/inpatient/admissions/$admissionId/finance" $null $token
$fin2 = Res $r
CheckAmt 'finance.billedTotal.final'  $fin2.billedTotal 49
CheckAmt 'finance.settledTotal.final' $fin2.settledTotal 49
CheckAmt 'finance.unpaidTotal.final'  $fin2.unpaidTotal 0
CheckAmt 'finance.pending.final'      $fin2.pendingAmount 0
Check   'finance.warning.final'       $fin2.warning 'False'

# ---------------------------------------------------------------- 10. discharge
$r = Invoke-Api 'POST' "/api/v2/clinical/inpatient/admissions/$admissionId/discharge" $null $token
Check 'discharge' $r.Status 200
Check 'discharge.status' (Res $r).status 'DISCHARGED'

$r = Invoke-Api 'GET' "/api/v2/clinical/wards/$wardId/beds" $null $token
$bed = (Res $r) | Where-Object { $_.bedNo -eq '01' }
Check 'bed.released' $bed.status 'FREE'

$r = Invoke-Api 'GET' "/api/v2/clinical/visits/$visitId" $null $token
Check 'visit.finished' (Res $r).status 'FINISHED'

$r = Invoke-Api 'POST' "/api/v2/clinical/inpatient/admissions/$admissionId/nursing-executions" @{
    orderId = $longIv.id; status = 'EXECUTED'
} $token
Check 'nursing.afterDischarge.409' $r.Status 409

# ---------------------------------------------------------------- 11. outpatient cancel voids the open registration charge
$r = Invoke-Api 'POST' '/api/v2/empi/patients' @{
    name = "Smoke Opd $suffix"; gender = 'FEMALE'; birthDate = '1990-01-02'
    idType = 'ID_CARD'; idNo = "11010119900102$suffix"
} $token
$opdPatientId = (Res $r).id

$r = Invoke-Api 'POST' '/api/v2/clinical/registrations' @{
    patientId = $opdPatientId; deptId = $DeptId; doctorId = 'doctor01'
} $token
Check 'opd.register' $r.Status 200
$opdVisitId = (Res $r).id

$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$opdVisitId/cancel" $null $token
Check 'opd.cancel' $r.Status 200

$r = Invoke-Api 'GET' "/api/v2/clinical/visits/$opdVisitId/charge" $null $token
Check 'opd.cancel.voidsCharge' (Res $r).status 'REFUNDED'

Write-Output "-----"
Write-Output "ADMISSION_ID=$admissionId"
Write-Output "VISIT_ID=$visitId"
Write-Output "WARD_ID=$wardId"
if ($script:fail -gt 0) { Write-Output "RESULT FAIL ($script:fail)"; exit 1 }
Write-Output 'RESULT PASS'
exit 0
