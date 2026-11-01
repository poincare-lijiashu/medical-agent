# ============================================================================
# MedAssist insurance smoke test, through the gateway.
#
# Covers: catalog item -> charge-item mapping -> member enrollment
#         -> settlement bill (server-side calculation) -> confirm -> recompute 409
#         -> catalog batch import -> catalog file import (CSV preview/apply, idempotent)
#         -> permission check (doctor is read-only).
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
# A missing field must FAIL, not silently become 0 (PowerShell casts $null to 0).
function CheckNum($name, $actual, $expected) {
    if ($null -eq $actual -or "$actual".Trim() -eq '') {
        Write-Output "FAIL $name expected=$expected actual=<null>"
        $script:fail++
        return
    }
    if ([decimal]$actual -eq [decimal]$expected) { Write-Output "PASS $name = $actual" }
    else { Write-Output "FAIL $name expected=$expected actual=$actual"; $script:fail++ }
}

function Res($r) { return (($r.Body | ConvertFrom-Json).data) }

# multipart upload (catalog CSV/XML import)
function Invoke-Upload {
    param([string]$Path, [string]$FilePath, [string]$Token = $null)
    $out = [IO.Path]::GetTempFileName()
    $args = @('--noproxy', '*', '-s', '-o', $out, '-w', '%{http_code}', '-X', 'POST',
              '-F', "file=@$FilePath", ($BaseUrl + $Path))
    if ($Token) { $args += @('-H', "Authorization: Bearer $Token") }
    $code = & curl.exe @args
    $text = Get-Content -Raw -Path $out -Encoding UTF8
    Remove-Item $out -Force
    return [pscustomobject]@{ Status = [int]$code; Body = $text }
}

# timestamp-based suffix: random 4-digit values collide on idNo/ward unique indexes within a day
$suffix = (Get-Date).ToString('HHmmssff')

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

# structured diagnosis (ICD-10): the settlement bill snapshots the primary diagnosis
$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/diagnoses" @{
    code = 'J06.900'; primary = $true
} $token
Check 'diagnosis.primary' $r.Status 200

# ---------------------------------------------------------------- 3. catalog + mapping
$r = Invoke-Api 'POST' '/api/v2/insurance/items' @{
    insuranceCode = 'I-REG-A'; name = 'General Consultation Fee (Class A)'; category = 'SERVICE'
    payCategory = 'A'; selfPayRatio = 0; priceLimit = 50
} $token
Check 'item.create' $r.Status 200
Check 'item.payCategory' (Res $r).payCategory 'A'
$itemVersion = (Res $r).version

# optimistic lock: matching version updates, stale version 409
$r = Invoke-Api 'POST' '/api/v2/insurance/items' @{
    insuranceCode = 'I-REG-A'; name = 'General Consultation Fee (Class A)'; category = 'SERVICE'
    payCategory = 'A'; selfPayRatio = 0; priceLimit = 50; version = $itemVersion
} $token
Check 'item.update.ok' $r.Status 200
Check 'item.update.version' (Res $r).version ($itemVersion + 1)

$r = Invoke-Api 'POST' '/api/v2/insurance/items' @{
    insuranceCode = 'I-REG-A'; name = 'General Consultation Fee (Class A)'; category = 'SERVICE'
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

# ---------------------------------------------------------------- 4.1 member history + disable/revive (S-12)
$r = Invoke-Api 'POST' '/api/v2/insurance/members' @{
    patientId = $patientId; insuranceType = 'EMPLOYEE'; region = 'LOCAL'
    deductible = 0; reimbursementRatio = 1.0; cap = 300000; personCategory = 'ACTIVE_WORKER'
} $token
Check 'member.update.ok' $r.Status 200

$r = Invoke-Api 'POST' '/api/v2/insurance/members' @{
    patientId = $patientId; insuranceType = 'EMPLOYEE'; region = 'LOCAL'
    deductible = 0; reimbursementRatio = 1.0; cap = 300000; status = 'DISABLED'
} $token
Check 'member.disable.ok' $r.Status 200
Check 'member.disable.status' (Res $r).status 'DISABLED'

# a disabled membership must not come back by simply omitting status
$r = Invoke-Api 'POST' '/api/v2/insurance/members' @{
    patientId = $patientId; insuranceType = 'EMPLOYEE'; region = 'LOCAL'
    deductible = 0; reimbursementRatio = 1.0; cap = 300000
} $token
Check 'member.reviveImplicit.409' $r.Status 409

$r = Invoke-Api 'POST' '/api/v2/insurance/members' @{
    patientId = $patientId; insuranceType = 'EMPLOYEE'; region = 'LOCAL'
    deductible = 0; reimbursementRatio = 1.0; cap = 300000; status = 'ACTIVE'
} $token
Check 'member.reviveExplicit.ok' $r.Status 200
Check 'member.reviveExplicit.status' (Res $r).status 'ACTIVE'

$r = Invoke-Api 'GET' "/api/v2/insurance/members/$patientId/history" $null $token
Check 'member.history.ok' $r.Status 200
$hist = @(Res $r)
Check 'member.history.count' $hist.Count 4
$histTypes = ($hist | ForEach-Object { $_.changeType }) -join ','
Check 'member.history.types' $histTypes 'ENABLE,DISABLE,UPDATE,CREATE'

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
Check 'bill.primaryDiagnosis' $billView.bill.primaryDiagnosisCode 'J06.900'
Check 'bill.diagnosisCount' $billView.bill.diagnosisCount 1

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

# one active outpatient registration per patient+dept+day: use another dept for the 2nd bill
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
        @{ insuranceCode = 'I-DRUG-B'; name = 'Sample Class B Drug'; category = 'DRUG'; payCategory = 'B'; selfPayRatio = 0.1 },
        @{ insuranceCode = 'I-MAT-C'; name = 'Sample Class C Material'; category = 'MATERIAL'; payCategory = 'C'; selfPayRatio = 0 }
    )
} $token
Check 'item.import' $r.Status 200
Check 'item.import.count' (Res $r).imported 2

# ---------------------------------------------------------------- 6.1 catalog file import (CSV preview/apply)
$fileCode = "I-FILE-$suffix"
$csvPath = Join-Path $env:TEMP "catalog-$suffix.csv"
$csvBody = "code,name,category,payCategory,selfPayRatio,priceLimit,enabled`n"
$csvBody += "$fileCode,File Drug Alpha,DRUG,B,0.1,50.00,true`n"
[IO.File]::WriteAllText($csvPath, $csvBody, (New-Object System.Text.UTF8Encoding($false)))

$r = Invoke-Upload "/api/v2/insurance/items/import-file?mode=preview" $csvPath $token
Check 'file.preview' $r.Status 200
Check 'file.preview.mode' (Res $r).mode 'PREVIEW'
Check 'file.preview.created' (Res $r).created 1
Check 'file.preview.updated' (Res $r).updated 0
Check 'file.preview.sha256' (Res $r).sha256.Length 64

# Excel is rejected with a clear message (no POI dependency)
$xlsxPath = Join-Path $env:TEMP "catalog-$suffix.xlsx"
[IO.File]::WriteAllText($xlsxPath, $csvBody, (New-Object System.Text.UTF8Encoding($false)))
$r = Invoke-Upload "/api/v2/insurance/items/import-file?mode=preview" $xlsxPath $token
Check 'file.xlsx.422' $r.Status 422

$r = Invoke-Upload "/api/v2/insurance/items/import-file?mode=apply" $csvPath $token
Check 'file.apply' $r.Status 200
Check 'file.apply.created' (Res $r).created 1
Check 'file.apply.unchanged' (Res $r).unchanged 0

# same file twice: nothing is rewritten (idempotent, no version churn)
$r = Invoke-Upload "/api/v2/insurance/items/import-file?mode=preview" $csvPath $token
Check 'file.previewAgain.created' (Res $r).created 0
Check 'file.previewAgain.unchanged' (Res $r).unchanged 1

$r = Invoke-Upload "/api/v2/insurance/items/import-file?mode=apply" $csvPath $token
Check 'file.applyAgain.created' (Res $r).created 0
Check 'file.applyAgain.unchanged' (Res $r).unchanged 1

$r = Invoke-Api 'GET' "/api/v2/insurance/items?q=$fileCode" $null $token
Check 'file.item.list' $r.Status 200
$fileItems = @(Res $r)
Check 'file.item.count' $fileItems.Count 1
Check 'file.item.payCategory' $fileItems[0].payCategory 'B'
CheckNum 'file.item.priceLimit' $fileItems[0].priceLimit 50.00

# doctor is read-only: cannot upload catalog files
$r = Invoke-Upload "/api/v2/insurance/items/import-file?mode=preview" $csvPath $doctorToken
Check 'file.doctor.403' $r.Status 403

# XML catalog (attribute form) goes through the same preview path
$xmlCode = "I-XML-$suffix"
$xmlPath = Join-Path $env:TEMP "catalog-$suffix.xml"
$xmlBody = "<catalog><item code=`"$xmlCode`" name=`"Xml Item`" category=`"SERVICE`" payCategory=`"A`" priceLimit=`"20.00`"/></catalog>"
[IO.File]::WriteAllText($xmlPath, $xmlBody, (New-Object System.Text.UTF8Encoding($false)))
$r = Invoke-Upload "/api/v2/insurance/items/import-file?mode=preview" $xmlPath $token
Check 'file.xml.preview' $r.Status 200
Check 'file.xml.created' (Res $r).created 1

$r = Invoke-Upload "/api/v2/insurance/items/import-file?mode=apply" $xmlPath $token
Check 'file.xml.apply' $r.Status 200
Check 'file.xml.applied' (Res $r).created 1

$r = Invoke-Api 'GET' "/api/v2/insurance/items?q=$xmlCode" $null $token
Check 'file.xml.list' (@(Res $r).Count) 1

Remove-Item $csvPath, $xlsxPath, $xmlPath -Force

# ---------------------------------------------------------------- 6.2 settlement export batch + receipt
$today = (Get-Date).ToString('yyyy-MM-dd')
$r = Invoke-Api 'POST' "/api/v2/insurance/export-batches?from=$today&to=$today" $null $token
Check 'export.create' $r.Status 200
$exportId = (Res $r).id
$exportBatchNo = (Res $r).batchNo
$exportHash = (Res $r).payloadHash
Check 'export.batchNoPrefix' ($exportBatchNo.StartsWith('EB')) 'True'
Check 'export.status' (Res $r).status 'GENERATED'
Check 'export.hash' $exportHash.Length 64
$exportCount = [int]((Res $r).billCount)
Check 'export.hasBills' ($exportCount -ge 1) 'True'

$r = Invoke-Api 'GET' "/api/v2/insurance/export-batches/$exportId/payload" $null $token
Check 'export.payload' $r.Status 200
Check 'export.payload.xml' ($r.Body -match '<settlementBatch') 'True'
Check 'export.payload.bills' ($r.Body -match '<bill billNo=') 'True'
Check 'export.payload.summary' ($r.Body -match '<summary billCount=') 'True'

# batch payload carries batch PHI: needs insurance:export:download (MF-02)
$r = Invoke-Api 'GET' "/api/v2/insurance/export-batches/$exportId/payload" $null $doctorToken
Check 'export.payload.doctor.403' $r.Status 403

# the same confirmed bill must not be exported twice (MF-01)
$r = Invoke-Api 'POST' "/api/v2/insurance/export-batches?from=$today&to=$today" $null $token
Check 'export.duplicate.409' $r.Status 409

# a bill already in a GENERATED batch cannot be voided (SF-01)
$r = Invoke-Api 'POST' "/api/v2/insurance/settlements/$capBill1/void" @{
    reason = 'void while exported'
} $token
Check 'export.voidBlocked.409' $r.Status 409

# REJECTED must carry a reason (bean validation -> 422)
$r = Invoke-Api 'POST' "/api/v2/insurance/export-batches/$exportId/receipt" @{
    status = 'REJECTED'; receiptNo = 'RJ-1'
} $token
Check 'export.reject.noReason.422' $r.Status 422

# cancel an un-receipted batch, then the bills can be exported again (SF-01)
$r = Invoke-Api 'POST' "/api/v2/insurance/export-batches/$exportId/cancel" @{
    reason = 'wrong period'
} $token
Check 'export.cancel' $r.Status 200
Check 'export.cancel.status' (Res $r).status 'CANCELLED'

$r = Invoke-Api 'POST' "/api/v2/insurance/export-batches?from=$today&to=$today" $null $token
Check 'export.reCreateAfterCancel' $r.Status 200
$exportId = (Res $r).id
$exportBatchNo = (Res $r).batchNo

# REJECTED with a reason reaches the terminal state (and releases the bills for re-export)
$r = Invoke-Api 'POST' "/api/v2/insurance/export-batches/$exportId/receipt" @{
    status = 'REJECTED'; receiptNo = "RJ-$suffix"; message = 'amount mismatch'
} $token
Check 'export.reject.ok' $r.Status 200
Check 'export.reject.status' (Res $r).status 'REJECTED'

$r = Invoke-Api 'POST' "/api/v2/insurance/export-batches?from=$today&to=$today" $null $token
Check 'export.reCreateAfterReject' $r.Status 200
$exportId = (Res $r).id
$exportBatchNo = (Res $r).batchNo

$r = Invoke-Api 'POST' "/api/v2/insurance/export-batches/$exportId/receipt" @{
    status = 'ACCEPTED'; receiptNo = "RC-$suffix"
} $token
Check 'export.receipt' $r.Status 200
Check 'export.receipt.status' (Res $r).status 'ACCEPTED'
Check 'export.receipt.no' (Res $r).receiptNo "RC-$suffix"

$r = Invoke-Api 'POST' "/api/v2/insurance/export-batches/$exportId/receipt" @{
    status = 'ACCEPTED'; receiptNo = "RC2-$suffix"
} $token
Check 'export.receipt.twice.409' $r.Status 409

$r = Invoke-Api 'POST' "/api/v2/insurance/export-batches/$exportId/cancel" @{
    reason = 'late cancel'
} $token
Check 'export.cancel.receipted.409' $r.Status 409

$r = Invoke-Api 'GET' "/api/v2/insurance/export-batches?limit=5" $null $token
Check 'export.list' $r.Status 200
$exportList = @(Res $r)
$matchedExport = @($exportList | Where-Object { $_.batchNo -eq $exportBatchNo })
Check 'export.list.contains' $matchedExport.Count 1

$r = Invoke-Api 'POST' "/api/v2/insurance/export-batches?from=$today&to=$today" $null $doctorToken
Check 'export.doctor.403' $r.Status 403

# ---------------------------------------------------------------- 6.3 DRG/DIP grouping simulation
$schemeCode = "DRG-$suffix"
$r = Invoke-Api 'POST' '/api/v2/insurance/grouping/schemes' @{
    code = $schemeCode; name = 'DRG simulation 2026'; type = 'DRG'
    rateAmount = 100.00; rateEffectiveFrom = $today; enabled = $true
} $token
Check 'group.scheme.create' $r.Status 200
CheckNum 'group.scheme.rate' (Res $r).rateAmount 100.00

$r = Invoke-Api 'POST' '/api/v2/insurance/grouping/schemes' @{
    code = $schemeCode; name = 'dup'; type = 'DRG'; rateAmount = 1; rateEffectiveFrom = $today
} $token
Check 'group.scheme.duplicate.409' $r.Status 409

$r = Invoke-Api 'POST' "/api/v2/insurance/grouping/schemes/$schemeCode/groups/import" @{
    groups = @(
        @{ groupCode = 'GB1'; groupName = 'Respiratory'; weight = 1.5 },
        @{ groupCode = 'GB2'; groupName = 'Procedure'; weight = 2.0 },
        @{ groupCode = 'GB3'; groupName = 'Cardiac procedure'; weight = 3.0 }
    )
} $token
Check 'group.groups.import' $r.Status 200
Check 'group.groups.created' (Res $r).created 3

$r = Invoke-Api 'POST' "/api/v2/insurance/grouping/schemes/$schemeCode/groups/import" @{
    groups = @(@{ groupCode = 'GB1'; groupName = 'Respiratory'; weight = 1.5 })
} $token
Check 'group.groups.idempotent' (Res $r).unchanged 1

$r = Invoke-Api 'POST' "/api/v2/insurance/grouping/schemes/$schemeCode/rules/import" @{
    rules = @(
        @{ priority = 1; matchType = 'DIAGNOSIS'; diagPrefix = 'J18'; groupCode = 'GB1' },
        @{ priority = 2; matchType = 'PROCEDURE'; procPrefix = '88.01'; groupCode = 'GB2' },
        @{ priority = 3; matchType = 'DIAG_AND_PROC'; diagPrefix = 'I50'; procPrefix = '93.90'; groupCode = 'GB3' }
    )
} $token
Check 'group.rules.import' $r.Status 200
Check 'group.rules.created' (Res $r).created 3

$r = Invoke-Api 'POST' '/api/v2/insurance/grouping/simulate' @{
    visitId = $visitId; schemeCode = $schemeCode; diagnoses = @('J18.900'); procedures = @()
} $token
Check 'group.simulate.diag' $r.Status 200
Check 'group.simulate.matched' (Res $r).matched 'True'
Check 'group.simulate.group' (Res $r).groupCode 'GB1'
CheckNum 'group.simulate.payment' (Res $r).paymentStandard 150.00

$r = Invoke-Api 'GET' "/api/v2/insurance/grouping/by-visit/$visitId" $null $token
Check 'group.latest.matched' (Res $r).groupCode 'GB1'

$r = Invoke-Api 'POST' '/api/v2/insurance/grouping/simulate' @{
    visitId = $visitId; schemeCode = $schemeCode; diagnoses = @('K35.900'); procedures = @()
} $token
Check 'group.simulate.unmatched' (Res $r).matched 'False'
Check 'group.simulate.unmatched.reason' ((Res $r).reason.Length -gt 0) 'True'

$r = Invoke-Api 'POST' '/api/v2/insurance/grouping/simulate' @{
    visitId = $visitId; schemeCode = $schemeCode; diagnoses = @('I50.900'); procedures = @('93.90')
} $token
Check 'group.simulate.combined' (Res $r).groupCode 'GB3'
CheckNum 'group.simulate.combined.payment' (Res $r).paymentStandard 300.00

$r = Invoke-Api 'GET' "/api/v2/insurance/grouping/by-visit/$visitId/history?limit=10" $null $token
Check 'group.history.ok' $r.Status 200
Check 'group.history.count' (@(Res $r).Count -ge 3) 'True'

$r = Invoke-Api 'PUT' "/api/v2/insurance/grouping/schemes/$schemeCode" @{
    name = 'DRG simulation 2026'; rateAmount = 120.00; rateEffectiveFrom = $today
    enabled = $true; version = 0
} $token
Check 'group.scheme.update' $r.Status 200
CheckNum 'group.scheme.rate.updated' (Res $r).rateAmount 120.00

$r = Invoke-Api 'PUT' "/api/v2/insurance/grouping/schemes/$schemeCode" @{
    name = 'DRG simulation 2026'; rateAmount = 130.00; rateEffectiveFrom = $today
    enabled = $true; version = 0
} $token
Check 'group.scheme.update.stale.409' $r.Status 409

$r = Invoke-Api 'POST' '/api/v2/insurance/grouping/schemes' @{
    code = "DRG-DOC-$suffix"; name = 'doc'; type = 'DRG'; rateAmount = 1; rateEffectiveFrom = $today
} $doctorToken
Check 'group.scheme.doctor.403' $r.Status 403

$r = Invoke-Api 'POST' '/api/v2/insurance/grouping/simulate' @{
    visitId = $visitId; schemeCode = $schemeCode; diagnoses = @('J18.900'); procedures = @()
} $doctorToken
Check 'group.simulate.doctor.read' $r.Status 200

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

# ---------------------------------------------------------------- 8. duty separation (S-16)
$r = Invoke-Api 'POST' '/api/v2/auth/users' @{
    username = "cashier$suffix"; password = 'Med@2026'; displayName = 'Window Cashier'
    roles = @('REGISTRAR')
} $token
Check 'user.cashier.create' $r.Status 200

$r = Invoke-Api 'POST' '/api/v2/auth/users' @{
    username = "yb$suffix"; password = 'Med@2026'; displayName = 'Insurance Officer'
    roles = @('INSURANCE_OFFICER')
} $token
Check 'user.officer.create' $r.Status 200

$r = Invoke-Api 'POST' '/api/v2/auth/login' @{ username = "cashier$suffix"; password = 'Med@2026' }
Check 'login.cashier' $r.Status 200
$cashierToken = (Res $r).accessToken

$r = Invoke-Api 'POST' '/api/v2/auth/login' @{ username = "yb$suffix"; password = 'Med@2026' }
Check 'login.officer' $r.Status 200
$officerToken = (Res $r).accessToken

$r = Invoke-Api 'POST' '/api/v2/empi/patients' @{
    name = "Duty Patient $suffix"; gender = 'FEMALE'; birthDate = '1980-01-01'
    idType = 'ID_CARD'; idNo = "11010119800101$suffix"
} $token
$dutyPatient = (Res $r).id
$r = Invoke-Api 'POST' '/api/v2/insurance/members' @{
    patientId = $dutyPatient; insuranceType = 'EMPLOYEE'; region = 'LOCAL'
    deductible = 0; reimbursementRatio = 1.0; cap = 300000
} $token
Check 'duty.member.register' $r.Status 200
$r = Invoke-Api 'POST' '/api/v2/clinical/registrations' @{
    patientId = $dutyPatient; deptId = $deptId; doctorId = 'admin01'; visitType = 'OUTPATIENT'
} $token
$dutyVisit = (Res $r).id

# window: can generate, cannot confirm, cannot touch catalog
$r = Invoke-Api 'POST' '/api/v2/insurance/settlements' @{ visitId = $dutyVisit } $cashierToken
Check 'duty.cashier.generate' $r.Status 200
$dutyBill = (Res $r).bill.id

$r = Invoke-Api 'POST' "/api/v2/insurance/settlements/$dutyBill/confirm" $null $cashierToken
Check 'duty.cashier.confirm.403' $r.Status 403

$r = Invoke-Api 'POST' '/api/v2/insurance/items' @{
    insuranceCode = "I-DUTY-$suffix"; name = 'Duty Separation Item'; category = 'SERVICE'
    payCategory = 'C'; selfPayRatio = 0
} $cashierToken
Check 'duty.cashier.catalog.403' $r.Status 403

# officer: can maintain catalog and confirm, cannot generate
$r = Invoke-Api 'POST' '/api/v2/insurance/items' @{
    insuranceCode = "I-DUTY-$suffix"; name = 'Duty Separation Item'; category = 'SERVICE'
    payCategory = 'C'; selfPayRatio = 0
} $officerToken
Check 'duty.officer.catalog.ok' $r.Status 200

$r = Invoke-Api 'POST' "/api/v2/insurance/settlements/$dutyBill/confirm" $null $officerToken
Check 'duty.officer.confirm.ok' $r.Status 200
Check 'duty.officer.confirm.by' (Res $r).bill.confirmedBy "yb$suffix"

$r = Invoke-Api 'POST' '/api/v2/insurance/settlements' @{ visitId = $dutyVisit } $officerToken
Check 'duty.officer.generate.403' $r.Status 403

Write-Output "-----"
Write-Output "BILL_ID=$billId"
Write-Output "VISIT_ID=$visitId"
if ($script:fail -gt 0) { Write-Output "RESULT FAIL ($script:fail)"; exit 1 }
Write-Output 'RESULT PASS'
exit 0
