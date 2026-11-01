# ============================================================================
# MedAssist pharmacy smoke test (through the gateway, /api/v2/pharmacy/**).
#
# Covers: drug catalog -> batch receiving -> prescription -> review -> FEFO
# dispensing -> partial return -> narcotic double-check -> stock guardrails.
#
# ASCII only on purpose: Windows PowerShell 5.1 reads BOM-less UTF-8 as ANSI.
#
# Prerequisites: auth :8081, empi :8082, pharmacy :8084, gateway :18080
# Usage (repo root):
#   powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\e2e\pharmacy_smoke.ps1
# ============================================================================
param(
    [string]$BaseUrl = "http://127.0.0.1:18080",
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

# ---------------------------------------------------------------- 2. patient
$r = Invoke-Api 'POST' '/api/v2/empi/patients' @{
    name = "Rx Patient $suffix"; gender = 'MALE'; birthDate = '1975-03-04'
    idType = 'ID_CARD'; idNo = "11010119750304$suffix"
} $token
Check 'patient.create' $r.Status 200
$patientId = (Res $r).id

# ---------------------------------------------------------------- 3. drug catalog
$r = Invoke-Api 'GET' '/api/v2/pharmacy/drugs?keyword=DRUG-AMOX' $null $token
Check 'drug.list' $r.Status 200
$drugs = @(Res $r)
Check 'drug.seeded' $drugs.Count 1
$amox = $drugs[0].drug
$amoxId = $amox.id
Check 'drug.insuranceCategory' $amox.insuranceCategory 'A'
CheckAmt 'drug.price' $amox.price 12.50
# Stock accumulates across runs: use a baseline + delta so the script is repeatable.
$stockBase = [decimal]$drugs[0].totalStock
Check 'drug.stock.baseline.readable' ($stockBase -ge 0) $true

# ---------------------------------------------------------------- 4. receive batches
$near = (Get-Date).AddDays(40).ToString('yyyy-MM-dd')
$far = (Get-Date).AddDays(400).ToString('yyyy-MM-dd')

$r = Invoke-Api 'POST' "/api/v2/pharmacy/drugs/$amoxId/batches" @{
    batchNo = "B-NEAR-$suffix"; expireDate = $near; quantity = 3; supplier = 'GuoYao'
} $token
Check 'stock.receive.near' $r.Status 200
Check 'stock.receive.near.qty' (Res $r).quantity 3

$r = Invoke-Api 'POST' "/api/v2/pharmacy/drugs/$amoxId/batches" @{
    batchNo = "B-FAR-$suffix"; expireDate = $far; quantity = 10; supplier = 'GuoYao'
} $token
Check 'stock.receive.far' $r.Status 200

$past = (Get-Date).AddDays(-1).ToString('yyyy-MM-dd')
$r = Invoke-Api 'POST' "/api/v2/pharmacy/drugs/$amoxId/batches" @{
    batchNo = "B-EXPIRED-$suffix"; expireDate = $past; quantity = 5
} $token
Check 'stock.receive.expired.409' $r.Status 409

$r = Invoke-Api 'POST' "/api/v2/pharmacy/drugs/$amoxId/batches" @{
    batchNo = "B-FAR-$suffix"; expireDate = $far; quantity = 1
} $token
Check 'stock.receive.duplicateBatch.409' $r.Status 409

$r = Invoke-Api 'GET' "/api/v2/pharmacy/drugs/$amoxId" $null $token
CheckAmt 'stock.total' (Res $r).totalStock ($stockBase + 13)

# ---------------------------------------------------------------- 5. prescription
$r = Invoke-Api 'POST' '/api/v2/pharmacy/prescriptions' @{
    patientId = $patientId
    items = @(@{ drugCode = 'DRUG-AMOX-025'; quantity = 5; dosage = '0.5g'; frequency = 'tid'; days = 5 })
} $token
Check 'rx.create' $r.Status 200
$rx = Res $r
$rxId = $rx.prescription.id
Check 'rx.status' $rx.prescription.status 'PENDING_REVIEW'
CheckAmt 'rx.total' $rx.prescription.totalAmount 62.50
Check 'rx.itemCount' $rx.items.Count 1

$r = Invoke-Api 'POST' '/api/v2/pharmacy/prescriptions' @{
    patientId = $patientId
    items = @(
        @{ drugCode = 'DRUG-AMOX-025'; quantity = 1 },
        @{ drugCode = 'DRUG-AMOX-025'; quantity = 2 }
    )
} $token
Check 'rx.duplicateDrug.422' $r.Status 422

$r = Invoke-Api 'POST' "/api/v2/pharmacy/prescriptions/$rxId/dispense" @{} $token
Check 'dispense.beforeReview.409' $r.Status 409

# ---------------------------------------------------------------- 6. review
$r = Invoke-Api 'POST' "/api/v2/pharmacy/prescriptions/$rxId/review" @{ approve = $true } $token
Check 'rx.approve' $r.Status 200
Check 'rx.approved.status' (Res $r).prescription.status 'APPROVED'

# ---------------------------------------------------------------- 7. dispense (FEFO: 3 near + 2 far)
$r = Invoke-Api 'POST' "/api/v2/pharmacy/prescriptions/$rxId/dispense" @{} $token
Check 'dispense.ok' $r.Status 200
$disp = Res $r
$dispId = $disp.dispense.id
Check 'dispense.status' $disp.dispense.status 'DISPENSED'
CheckAmt 'dispense.total' $disp.dispense.totalAmount 62.50
Check 'dispense.batchSplits' $disp.items.Count 2
Check 'dispense.fefo.firstBatch' $disp.items[0].batchNo "B-NEAR-$suffix"
Check 'dispense.fefo.firstQty' $disp.items[0].quantity 3
Check 'dispense.fefo.secondQty' $disp.items[1].quantity 2

$r = Invoke-Api 'POST' "/api/v2/pharmacy/prescriptions/$rxId/dispense" @{} $token
Check 'dispense.repeat.409' $r.Status 409

$r = Invoke-Api 'GET' "/api/v2/pharmacy/drugs/$amoxId" $null $token
CheckAmt 'stock.afterDispense' (Res $r).totalStock ($stockBase + 8)

# ---------------------------------------------------------------- 8. return
$farItem = $disp.items[1].id
$r = Invoke-Api 'POST' "/api/v2/pharmacy/dispenses/$dispId/returns" @{
    dispenseItemId = $farItem; quantity = 2; reason = 'patient stopped'
} $token
Check 'return.ok' $r.Status 200
Check 'return.status' (Res $r).dispense.status 'PARTIAL_RETURNED'
Check 'return.recordCount' (Res $r).returns.Count 1

$r = Invoke-Api 'POST' "/api/v2/pharmacy/dispenses/$dispId/returns" @{
    dispenseItemId = $farItem; quantity = 1; reason = 'over return'
} $token
Check 'return.exceed.409' $r.Status 409

$r = Invoke-Api 'GET' "/api/v2/pharmacy/drugs/$amoxId" $null $token
CheckAmt 'stock.afterReturn' (Res $r).totalStock ($stockBase + 10)

# ---------------------------------------------------------------- 9. narcotic double check
$r = Invoke-Api 'POST' '/api/v2/pharmacy/drugs' @{
    drugCode = "DRUG-MOR-$suffix"; name = 'Morphine Injection'; spec = '10mg'
    dosageForm = 'INJECTION'; unit = 'amp'; price = 5.60
    insuranceCategory = 'B'; narcotic = $true
} $token
Check 'drug.create.narcotic' $r.Status 200
$morId = (Res $r).drug.id

$r = Invoke-Api 'POST' "/api/v2/pharmacy/drugs/$morId/batches" @{
    batchNo = "B-MOR-$suffix"; expireDate = $far; quantity = 5
} $token
Check 'stock.receive.narcotic' $r.Status 200

# narcotic drugs require a licensed prescriber (pharmacy V6)
$r = Invoke-Api 'POST' '/api/v2/pharmacy/narcotic/prescribers' @{
    prescriber = 'admin01'; licenseNo = 'MZ-E2E'
} $token
Check 'narcotic.prescriber.grant' $r.Status 200

$r = Invoke-Api 'POST' '/api/v2/pharmacy/prescriptions' @{
    patientId = $patientId
    items = @(@{ drugCode = "DRUG-MOR-$suffix"; quantity = 1 })
} $token
$morRxId = (Res $r).prescription.id
Check 'rx.create.narcotic' $r.Status 200

$r = Invoke-Api 'POST' "/api/v2/pharmacy/prescriptions/$morRxId/review" @{ approve = $true } $token
Check 'rx.approve.narcotic' $r.Status 200

$r = Invoke-Api 'POST' "/api/v2/pharmacy/prescriptions/$morRxId/dispense" @{} $token
Check 'dispense.narcotic.noChecker.422' $r.Status 422

$r = Invoke-Api 'POST' "/api/v2/pharmacy/prescriptions/$morRxId/dispense" @{ checker = 'admin01' } $token
Check 'dispense.narcotic.sameChecker.422' $r.Status 422

$r = Invoke-Api 'POST' "/api/v2/pharmacy/prescriptions/$morRxId/dispense" @{ checker = 'pharmacist02' } $token
Check 'dispense.narcotic.ok' $r.Status 200
Check 'dispense.narcotic.checker' (Res $r).dispense.checker 'pharmacist02'

# ---------------------------------------------------------------- 10. stock warnings
$r = Invoke-Api 'GET' '/api/v2/pharmacy/stock-warnings' $null $token
Check 'warnings.read' $r.Status 200

Write-Output "-----"
Write-Output "PRESCRIPTION_ID=$rxId"
Write-Output "DISPENSE_ID=$dispId"
if ($script:fail -gt 0) { Write-Output "RESULT FAIL ($script:fail)"; exit 1 }
Write-Output 'RESULT PASS'
exit 0
