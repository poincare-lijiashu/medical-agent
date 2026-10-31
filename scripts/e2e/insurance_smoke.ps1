# ============================================================================
# MedAssist insurance smoke test, through the gateway.
#
# Covers: catalog item -> charge-item mapping -> member enrollment
#         -> settlement bill (server-side calculation) -> confirm -> recompute 409
#         -> catalog batch import -> permission check (doctor is read-only).
#
# ASCII only (Windows PowerShell 5.1 reads BOM-less UTF-8 as ANSI).
# Prerequisites: auth :8081, empi :8082, clinical :8083, insurance :8087, gateway :8080
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\e2e\insurance_smoke.ps1
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

# Amounts come back as JSON numbers (10.00 -> 10 in PowerShell), compare numerically.
function CheckNum($name, $actual, $expected) {
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

$r = Invoke-Api 'POST' '/api/v2/auth/login' @{ username = 'doctor01'; password = $Password }
Check 'login.doctor' $r.Status 200
$doctorToken = (Res $r).accessToken

# ---------------------------------------------------------------- 2. patient + visit with charge
$r = Invoke-Api 'POST' '/api/v2/empi/patients' @{
    name = "Insurance Patient $suffix"; gender = 'FEMALE'; birthDate = '1968-06-01'
    idType = 'ID_CARD'; idNo = "11010119680601$suffix"
} $token
Check 'patient.create' $r.Status 200
$patientId = (Res $r).id

$deptId = '11111111-1111-1111-1111-111111111111'
$r = Invoke-Api 'POST' '/api/v2/clinical/registrations' @{
    patientId = $patientId; deptId = $deptId; doctorId = 'admin01'; visitType = 'OUTPATIENT'
} $token
Check 'registration.create' $r.Status 200
$visitId = (Res $r).id
Check 'registration.visitIdPresent' ("$visitId".Length -gt 0) 'True'

# ---------------------------------------------------------------- 3. catalog + mapping
$r = Invoke-Api 'POST' '/api/v2/insurance/items' @{
    insuranceCode = 'I-REG-A'; name = '普通门诊诊查费（甲类）'; category = 'SERVICE'
    payCategory = 'A'; selfPayRatio = 0; priceLimit = 50
} $token
Check 'item.create' $r.Status 200
Check 'item.payCategory' (Res $r).payCategory 'A'
$itemVersion = (Res $r).version

# optimistic lock: matching version updates, stale version 409
$r = Invoke-Api 'POST' '/api/v2/insurance/items' @{
    insuranceCode = 'I-REG-A'; name = '普通门诊诊查费（甲类）'; category = 'SERVICE'
    payCategory = 'A'; selfPayRatio = 0; priceLimit = 50; version = $itemVersion
} $token
Check 'item.update.ok' $r.Status 200
Check 'item.update.version' (Res $r).version ($itemVersion + 1)

$r = Invoke-Api 'POST' '/api/v2/insurance/items' @{
    insuranceCode = 'I-REG-A'; name = '普通门诊诊查费（甲类）'; category = 'SERVICE'
    payCategory = 'A'; selfPayRatio = 0; priceLimit = 50; version = $itemVersion
} $token
Check 'item.update.stale.409' $r.Status 409

$r = Invoke-Api 'PUT' '/api/v2/insurance/mappings/REG-GENERAL' @{
    insuranceCode = 'I-REG-A'; enabled = $true
} $token
Check 'mapping.upsert' $r.Status 200
Check 'mapping.code' (Res $r).chargeItemCode 'REG-GENERAL'

$r = Invoke-Api 'GET' '/api/v2/insurance/mappings?q=REG-GENERAL' $null $token
Check 'mapping.list' (@(Res $r).Count -ge 1) 'True'

# ---------------------------------------------------------------- 4. member enrollment
$r = Invoke-Api 'POST' '/api/v2/insurance/members' @{
    patientId = $patientId; insuranceType = 'EMPLOYEE'; region = 'LOCAL'
    personCategory = 'ACTIVE_WORKER'; deductible = 0; reimbursementRatio = 1.0; cap = 300000
} $token
Check 'member.register' $r.Status 200
Check 'member.status' (Res $r).status 'ACTIVE'

$r = Invoke-Api 'GET' "/api/v2/insurance/members/$patientId" $null $token
Check 'member.get' $r.Status 200

# ---------------------------------------------------------------- 5. settlement bill
$r = Invoke-Api 'POST' '/api/v2/insurance/settlements' @{ visitId = $visitId } $token
Check 'bill.generate' $r.Status 200
$billView = Res $r
$billId = $billView.bill.id
Check 'bill.status' $billView.bill.status 'DRAFT'
CheckNum 'bill.total' $billView.bill.totalAmount 10.00
CheckNum 'bill.eligible' $billView.bill.eligibleAmount 10.00
CheckNum 'bill.insurance' $billView.bill.insuranceAmount 10.00
CheckNum 'bill.selfPay' $billView.bill.selfPayAmount 0.00
Check 'bill.lineCount' (@($billView.lines).Count) 1
Check 'bill.lineMapped' $billView.lines[0].unmapped 'False'
Check 'bill.lineChargeStatus' $billView.lines[0].chargeStatus 'UNPAID'

$r = Invoke-Api 'GET' "/api/v2/insurance/settlements/by-visit/$visitId" $null $token
Check 'bill.byVisit' $r.Status 200
Check 'bill.byVisit.id' (Res $r).bill.id $billId

$r = Invoke-Api 'POST' "/api/v2/insurance/settlements/$billId/confirm" $null $token
Check 'bill.confirm' $r.Status 200
Check 'bill.confirm.status' (Res $r).bill.status 'CONFIRMED'
Check 'bill.confirm.by' (Res $r).bill.confirmedBy 'admin01'

$r = Invoke-Api 'POST' '/api/v2/insurance/settlements' @{ visitId = $visitId } $token
Check 'bill.recompute.confirmed.409' $r.Status 409

# ---------------------------------------------------------------- 5.1 reconcile + void + regenerate
$r = Invoke-Api 'GET' "/api/v2/insurance/settlements/reconcile/$visitId" $null $token
Check 'reconcile.ok' $r.Status 200
Check 'reconcile.matched' (Res $r).matched 'True'
CheckNum 'reconcile.billTotal' (Res $r).billTotal 10.00
CheckNum 'reconcile.chargeTotal' (Res $r).chargeTotal 10.00

$r = Invoke-Api 'POST' "/api/v2/insurance/settlements/$billId/void" @{ reason = 'wrong item price' } $token
Check 'bill.void' $r.Status 200
Check 'bill.void.status' (Res $r).bill.status 'VOIDED'
Check 'bill.void.reason' (Res $r).bill.voidReason 'wrong item price'
Check 'bill.void.by' (Res $r).bill.voidedBy 'admin01'

$r = Invoke-Api 'POST' "/api/v2/insurance/settlements/$billId/void" @{ reason = 'again' } $token
Check 'bill.void.twice.409' $r.Status 409

$r = Invoke-Api 'POST' '/api/v2/insurance/settlements' @{ visitId = $visitId } $token
Check 'bill.regenerate.afterVoid' $r.Status 200
Check 'bill.regenerate.status' (Res $r).bill.status 'DRAFT'
$newBillId = (Res $r).bill.id
Check 'bill.regenerate.newId' ($newBillId -ne $billId) 'True'

# ---------------------------------------------------------------- 5.2 annual cap accumulation (S-10)
$r = Invoke-Api 'POST' '/api/v2/empi/patients' @{
    name = "Cap Patient $suffix"; gender = 'MALE'; birthDate = '1965-03-03'
    idType = 'ID_CARD'; idNo = "11010119650303$suffix"
} $token
Check 'cap.patient.create' $r.Status 200
$capPatient = (Res $r).id

$r = Invoke-Api 'POST' '/api/v2/insurance/members' @{
    patientId = $capPatient; insuranceType = 'EMPLOYEE'; region = 'LOCAL'
    deductible = 0; reimbursementRatio = 1.0; cap = 15
} $token
Check 'cap.member.register' $r.Status 200

$r = Invoke-Api 'POST' '/api/v2/clinical/registrations' @{
    patientId = $capPatient; deptId = $deptId; doctorId = 'admin01'; visitType = 'OUTPATIENT'
} $token
$capVisit1 = (Res $r).id

$r = Invoke-Api 'POST' '/api/v2/insurance/settlements' @{ visitId = $capVisit1 } $token
Check 'cap.bill1.generate' $r.Status 200
CheckNum 'cap.bill1.insurance' (Res $r).bill.insuranceAmount 10.00
CheckNum 'cap.bill1.capUsed' (Res $r).capUsed 0.00
CheckNum 'cap.bill1.capRemaining' (Res $r).capRemaining 15.00
$capBill1 = (Res $r).bill.id

$r = Invoke-Api 'POST' "/api/v2/insurance/settlements/$capBill1/confirm" $null $token
Check 'cap.bill1.confirm' $r.Status 200

# 同一患者同科室同一天只允许一个在诊挂号，第二次换科室
$r = Invoke-Api 'POST' '/api/v2/clinical/registrations' @{
    patientId = $capPatient; deptId = '22222222-2222-2222-2222-222222222222'
    doctorId = 'admin01'; visitType = 'OUTPATIENT'
} $token
Check 'cap.visit2.registration' $r.Status 200
$capVisit2 = (Res $r).id

$r = Invoke-Api 'POST' '/api/v2/insurance/settlements' @{ visitId = $capVisit2 } $token
Check 'cap.bill2.generate' $r.Status 200
CheckNum 'cap.bill2.capUsed' (Res $r).capUsed 10.00
CheckNum 'cap.bill2.capRemaining' (Res $r).capRemaining 5.00
CheckNum 'cap.bill2.insurance' (Res $r).bill.insuranceAmount 5.00
CheckNum 'cap.bill2.selfPay' (Res $r).bill.selfPayAmount 5.00

# ---------------------------------------------------------------- 6. batch import + not-enrolled
$r = Invoke-Api 'POST' '/api/v2/insurance/items/import' @{
    items = @(
        @{ insuranceCode = 'I-DRUG-B'; name = '示例乙类药品'; category = 'DRUG'; payCategory = 'B'; selfPayRatio = 0.1 },
        @{ insuranceCode = 'I-MAT-C'; name = '示例丙类耗材'; category = 'MATERIAL'; payCategory = 'C'; selfPayRatio = 0 }
    )
} $token
Check 'item.import' $r.Status 200
Check 'item.import.count' (Res $r).imported 2

$r = Invoke-Api 'POST' '/api/v2/empi/patients' @{
    name = "NoIns Patient $suffix"; gender = 'MALE'; birthDate = '1970-01-01'
    idType = 'ID_CARD'; idNo = "11010119700101$suffix"
} $token
$patient2 = (Res $r).id
$r = Invoke-Api 'POST' '/api/v2/clinical/registrations' @{
    patientId = $patient2; deptId = $deptId; doctorId = 'admin01'; visitType = 'OUTPATIENT'
} $token
$visit2 = (Res $r).id
$r = Invoke-Api 'POST' '/api/v2/insurance/settlements' @{ visitId = $visit2 } $token
Check 'bill.notEnrolled.409' $r.Status 409

# ---------------------------------------------------------------- 6.5 refunded charges are excluded (MF-01)
$r = Invoke-Api 'POST' '/api/v2/empi/patients' @{
    name = "Refund Patient $suffix"; gender = 'MALE'; birthDate = '1975-05-05'
    idType = 'ID_CARD'; idNo = "11010119750505$suffix"
} $token
Check 'refund.patient.create' $r.Status 200
$refundPatient = (Res $r).id

$r = Invoke-Api 'POST' '/api/v2/insurance/members' @{
    patientId = $refundPatient; insuranceType = 'EMPLOYEE'; region = 'LOCAL'
    deductible = 0; reimbursementRatio = 1.0; cap = 300000
} $token
Check 'refund.member.register' $r.Status 200

$r = Invoke-Api 'POST' '/api/v2/clinical/registrations' @{
    patientId = $refundPatient; deptId = $deptId; doctorId = 'admin01'; visitType = 'OUTPATIENT'
} $token
Check 'refund.registration' $r.Status 200
$refundVisit = (Res $r).id

$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$refundVisit/cancel" $null $token
Check 'refund.cancelVisit' $r.Status 200

$r = Invoke-Api 'POST' '/api/v2/insurance/settlements' @{ visitId = $refundVisit } $token
Check 'bill.allRefunded.409' $r.Status 409

# ---------------------------------------------------------------- 7. permission: doctor read-only
$r = Invoke-Api 'POST' "/api/v2/insurance/settlements/$billId/confirm" $null $doctorToken
Check 'perm.doctor.confirm.403' $r.Status 403

$r = Invoke-Api 'GET' "/api/v2/insurance/settlements/$billId" $null $doctorToken
Check 'perm.doctor.read' $r.Status 200

Write-Output "-----"
Write-Output "BILL_ID=$billId"
Write-Output "VISIT_ID=$visitId"
if ($script:fail -gt 0) { Write-Output "RESULT FAIL ($script:fail)"; exit 1 }
Write-Output 'RESULT PASS'
exit 0
