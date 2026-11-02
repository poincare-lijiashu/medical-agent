# ============================================================================
# MedAssist pharmacy <-> clinical billing smoke test (through the gateway).
#
# Covers (N2 closing batch):
#   * prescription billingMode=DISPENSE requires a visit
#   * dispense posts charges into the clinical charge sheet (idempotent
#     source_key DISPENSE:{dispenseItemId})
#   * ORDER mode prescriptions never create a second billing chain
#   * settled bill + drug return => pharmacy marks REFUND_PENDING (no fake refund)
#   * internal endpoint without token => 403
#
# ASCII only on purpose: Windows PowerShell 5.1 reads BOM-less UTF-8 as ANSI.
#
# Prerequisites: auth :8081, empi :8082, clinical :8083, pharmacy :8084, gateway
# Usage (repo root):
#   powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\e2e\pharmacy_billing_smoke.ps1
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

$suffix = Get-Random -Minimum 1000 -Maximum 9999

# ---------------------------------------------------------------- 1. login
$r = Invoke-Api 'POST' '/api/v2/auth/login' @{ username = $Username; password = $Password }
Check 'login' $r.Status 200
$token = (Res $r).accessToken
if (-not $token) { Write-Output "FATAL no token: $($r.Body)"; exit 1 }

# ---------------------------------------------------------------- 2. patient + visit
$r = Invoke-Api 'POST' '/api/v2/empi/patients' @{
    name = "Bill Patient $suffix"; gender = 'MALE'; birthDate = '1980-05-06'
    idType = 'ID_CARD'; idNo = "11010119800506$suffix"
} $token
Check 'patient.create' $r.Status 200
$patientId = (Res $r).id

$r = Invoke-Api 'POST' '/api/v2/clinical/registrations' @{
    patientId = $patientId; deptId = $DeptId; doctorId = 'doctor01'
} $token
Check 'visit.register' $r.Status 200
$visit = Res $r
$visitId = $visit.id
$visitNo = $visit.visitNo

# ---------------------------------------------------------------- 3. stock
$r = Invoke-Api 'GET' '/api/v2/pharmacy/drugs?keyword=DRUG-AMOX' $null $token
Check 'drug.list' $r.Status 200
$amoxId = @(Res $r)[0].drug.id

$far = (Get-Date).AddDays(300).ToString('yyyy-MM-dd')
$r = Invoke-Api 'POST' "/api/v2/pharmacy/drugs/$amoxId/batches" @{
    batchNo = "B-BILL-$suffix"; expireDate = $far; quantity = 10; supplier = 'GuoYao'
} $token
Check 'stock.receive' $r.Status 200

# ---------------------------------------------------------------- 4. DISPENSE mode requires visit
$r = Invoke-Api 'POST' '/api/v2/pharmacy/prescriptions' @{
    patientId = $patientId; billingMode = 'DISPENSE'
    items = @(@{ drugCode = 'DRUG-AMOX-025'; quantity = 1 })
} $token
Check 'rx.dispenseMode.noVisit.422' $r.Status 422

# ---------------------------------------------------------------- 5. charge before dispense
$r = Invoke-Api 'GET' "/api/v2/clinical/visits/$visitId/charge" $null $token
Check 'charge.before' $r.Status 200
$chargeBefore = Res $r
$totalBefore = [decimal]$chargeBefore.totalAmount

# ---------------------------------------------------------------- 6. DISPENSE mode prescription
$r = Invoke-Api 'POST' '/api/v2/pharmacy/prescriptions' @{
    patientId = $patientId; visitId = $visitId; visitNo = $visitNo; billingMode = 'DISPENSE'
    items = @(@{ drugCode = 'DRUG-AMOX-025'; quantity = 2; dosage = '0.5g'; frequency = 'bid'; days = 5 })
} $token
Check 'rx.create.dispenseMode' $r.Status 200
$rx = Res $r
$rxId = $rx.prescription.id
Check 'rx.billingMode' $rx.prescription.billingMode 'DISPENSE'
CheckAmt 'rx.total' $rx.prescription.totalAmount 25.00

$r = Invoke-Api 'POST' "/api/v2/pharmacy/prescriptions/$rxId/review" @{ approve = $true } $token
Check 'rx.approve' $r.Status 200

$r = Invoke-Api 'POST' "/api/v2/pharmacy/prescriptions/$rxId/dispense" @{} $token
Check 'dispense.ok' $r.Status 200
$disp = Res $r
$dispId = $disp.dispense.id
# Charge is dispatched after the local transaction commits: the response is PENDING,
# then converges to CHARGED (or CHARGE_FAILED when clinical is down).
Check 'dispense.billingStatus.initial' $disp.dispense.billingStatus 'PENDING'
$billingStatus = $disp.dispense.billingStatus
for ($i = 0; $i -lt 20; $i++) {
    $r = Invoke-Api 'GET' "/api/v2/pharmacy/dispenses/$dispId" $null $token
    $billingStatus = (Res $r).dispense.billingStatus
    if ("$billingStatus" -ne 'PENDING') { break }
    Start-Sleep -Milliseconds 200
}
Check 'dispense.billingStatus.final' $billingStatus 'CHARGED'

# ---------------------------------------------------------------- 7. charges landed in clinical
# The billing callback runs after the pharmacy transaction commits: poll until the
# amount converges. A fixed sleep would mark "not finished yet" as "not charged"
# on slow machines (P2-15).
$chargeAfter = $null
$totalAfter = $totalBefore
for ($i = 0; $i -lt 25; $i++) {
    $r = Invoke-Api 'GET' "/api/v2/clinical/visits/$visitId/charge" $null $token
    $chargeAfter = Res $r
    $totalAfter = [decimal]$chargeAfter.totalAmount
    if (($totalAfter - $totalBefore) -eq 25.00) { break }
    Start-Sleep -Milliseconds 200
}
Check 'charge.after' $r.Status 200
CheckAmt 'charge.delta' ($totalAfter - $totalBefore) 25.00
$drugItems = @($chargeAfter.items | Where-Object { $_.itemCode -eq 'DRUG-AMOX-025' })
Check 'charge.drugItemCount' $drugItems.Count 1
CheckAmt 'charge.drugItemAmount' $drugItems[0].amount 25.00

# duplicate dispense rejected (and must NOT double charge)
$r = Invoke-Api 'POST' "/api/v2/pharmacy/prescriptions/$rxId/dispense" @{} $token
Check 'dispense.repeat.409' $r.Status 409
$r = Invoke-Api 'GET' "/api/v2/clinical/visits/$visitId/charge" $null $token
CheckAmt 'charge.noDoubleCharge' (Res $r).totalAmount $totalAfter

# ---------------------------------------------------------------- 8. ORDER mode never bills again
$r = Invoke-Api 'POST' '/api/v2/pharmacy/prescriptions' @{
    patientId = $patientId; visitId = $visitId
    items = @(@{ drugCode = 'DRUG-AMOX-025'; quantity = 1 })
} $token
Check 'rx.create.orderMode' $r.Status 200
$orderRxId = (Res $r).prescription.id
Check 'rx.orderMode.default' (Res $r).prescription.billingMode 'ORDER'
$r = Invoke-Api 'POST' "/api/v2/pharmacy/prescriptions/$orderRxId/review" @{ approve = $true } $token
Check 'rx.orderMode.approve' $r.Status 200
$r = Invoke-Api 'POST' "/api/v2/pharmacy/prescriptions/$orderRxId/dispense" @{} $token
Check 'rx.orderMode.dispense' $r.Status 200
Check 'rx.orderMode.billingStatus' (Res $r).dispense.billingStatus 'NONE'
# Negative assertion: an ORDER-mode prescription must NOT touch the charge.
# Polling cannot prove absence, so keep a bounded observation window (P2-15 note).
Start-Sleep -Milliseconds 500
$r = Invoke-Api 'GET' "/api/v2/clinical/visits/$visitId/charge" $null $token
CheckAmt 'charge.orderMode.unchanged' (Res $r).totalAmount $totalAfter

# ---------------------------------------------------------------- 9. settle then return => refund pending
$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/settle" @{ paymentMethod = 'CASH' } $token
Check 'settle.ok' $r.Status 200
Check 'settle.status' (Res $r).status 'PAID'

$firstItem = @($disp.items)[0]
$r = Invoke-Api 'POST' "/api/v2/pharmacy/dispenses/$dispId/returns" @{
    dispenseItemId = $firstItem.id; quantity = 2; reason = 'patient stopped'
} $token
Check 'return.ok' $r.Status 200
Check 'return.dispenseStatus' (Res $r).dispense.status 'RETURNED'

# Poll the refund status instead of a fixed sleep (P2-15): the refund callback runs
# after the return transaction commits and may take a few hundred ms.
$refundStatus = $null
for ($i = 0; $i -lt 25; $i++) {
    $r = Invoke-Api 'GET' "/api/v2/pharmacy/dispenses/$dispId" $null $token
    $refundStatus = (Res $r).dispense.billingStatus
    if ("$refundStatus" -eq 'REFUND_PENDING' -or "$refundStatus" -eq 'REFUND_FAILED') { break }
    Start-Sleep -Milliseconds 200
}
Check 'return.billingStatus' $refundStatus 'REFUND_PENDING'

# settled bill is not silently rewritten
$r = Invoke-Api 'GET' "/api/v2/clinical/visits/$visitId/charge" $null $token
Check 'charge.afterReturn.status' (Res $r).status 'PAID'
CheckAmt 'charge.afterReturn.total' (Res $r).totalAmount $totalAfter

# ---------------------------------------------------------------- 10. internal endpoint must reject unauthenticated calls
$r = Invoke-Api 'POST' '/internal/pharmacy-charges' @{
    visitId = $visitId; dispenseId = $dispId; actor = 'attacker'; items = @()
} $null $ClinicalUrl
Check 'internal.noToken.403' $r.Status 403
Check 'internal.noToken.code' (Body $r).code 40300

# ---------------------------------------------------------------- 11. billing retry is idempotent for charged dispense
$r = Invoke-Api 'POST' "/api/v2/pharmacy/dispenses/$dispId/billing-retry" @{} $token
Check 'billingRetry.refundPending.ok' $r.Status 200

Write-Output "-----"
Write-Output "VISIT_ID=$visitId"
Write-Output "PRESCRIPTION_ID=$rxId"
Write-Output "DISPENSE_ID=$dispId"
if ($script:fail -gt 0) { Write-Output "RESULT FAIL ($script:fail)"; exit 1 }
Write-Output 'RESULT PASS'
exit 0
