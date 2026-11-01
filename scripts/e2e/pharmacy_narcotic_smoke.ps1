# ============================================================================
# MedAssist narcotic control smoke test (pharmacy V6: five-专 closed loop).
#
# Covers: prescriber licence grant (permission guarded) -> narcotic Rx needs a
#         licensed prescriber -> stock receive -> Rx -> review -> double check
#         (missing / same checker) -> dispense -> register row PENDING_RETURN
#         -> second Rx blocked until ampoule returned -> wrong quantity 422
#         -> ampoule return -> Rx allowed again -> licence revoked -> Rx 422
#         -> re-grant -> register read guard (QC 403).
#
# ASCII only (Windows PowerShell 5.1 reads BOM-less UTF-8 as ANSI).
# Prerequisites: auth :8081, empi :8082, pharmacy :8084, gateway :8080
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\e2e\pharmacy_narcotic_smoke.ps1
# ============================================================================
param(
    [string]$BaseUrl = "http://127.0.0.1:8080",
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

function Res($r) { return (($r.Body | ConvertFrom-Json).data) }

$suffix = (Get-Date).ToString('HHmmssff')
$far = '2027-12-31'

# ---------------------------------------------------------------- 1. logins
$r = Invoke-Api 'POST' '/api/v2/auth/login' @{ username = 'admin01'; password = $Password }
Check 'login.admin' $r.Status 200
$admin = (Res $r).accessToken
if (-not $admin) { Write-Output "FATAL no admin token: $($r.Body)"; exit 1 }

$r = Invoke-Api 'POST' '/api/v2/auth/login' @{ username = 'pharm01'; password = $Password }
Check 'login.pharm' $r.Status 200
$pharm = (Res $r).accessToken

$r = Invoke-Api 'POST' '/api/v2/auth/login' @{ username = 'doctor01'; password = $Password }
Check 'login.doctor' $r.Status 200
$doctor = (Res $r).accessToken

$r = Invoke-Api 'POST' '/api/v2/auth/login' @{ username = 'qc01'; password = $Password }
Check 'login.qc' $r.Status 200
$qc = (Res $r).accessToken

# ---------------------------------------------------------------- 2. patient
$r = Invoke-Api 'POST' '/api/v2/empi/patients' @{
    name = "Narcotic Patient $suffix"; gender = 'MALE'; birthDate = '1966-07-07'
    idType = 'ID_CARD'; idNo = "11010119660707$suffix"
} $admin
Check 'patient.create' $r.Status 200
$patientId = (Res $r).id

# ---------------------------------------------------------------- 3. prescriber licence
$r = Invoke-Api 'POST' '/api/v2/pharmacy/narcotic/prescribers' @{
    prescriber = 'doctor01'; licenseNo = "MZ-$suffix"
} $admin
Check 'licence.grant' $r.Status 200
Check 'licence.grant.enabled' (Res $r).enabled 'True'
$licenceVersion = (Res $r).version

$r = Invoke-Api 'GET' '/api/v2/pharmacy/narcotic/prescribers?enabled=true' $null $admin
Check 'licence.list' $r.Status 200
Check 'licence.list.contains' (@((Res $r) | Where-Object { $_.prescriber -eq 'doctor01' }).Count) 1

$r = Invoke-Api 'POST' '/api/v2/pharmacy/narcotic/prescribers' @{ prescriber = 'qc01' } $doctor
Check 'licence.grant.doctor.403' $r.Status 403

# ---------------------------------------------------------------- 4. stock
$r = Invoke-Api 'GET' '/api/v2/pharmacy/drugs?keyword=DRUG-MOR-10' $null $admin
Check 'drug.find' $r.Status 200
$drugs = @(Res $r)
Check 'drug.find.count' $drugs.Count 1
$drugId = $drugs[0].drug.id

$r = Invoke-Api 'POST' "/api/v2/pharmacy/drugs/$drugId/batches" @{
    batchNo = "MOR-$suffix"; expireDate = $far; quantity = 10; supplier = 'E2E'
} $pharm
Check 'stock.receive' $r.Status 200

# ---------------------------------------------------------------- 5. prescribe -> review -> dispense
$r = Invoke-Api 'POST' '/api/v2/pharmacy/prescriptions' @{
    patientId = $patientId
    items = @(@{ drugCode = 'DRUG-MOR-10'; quantity = 1 })
} $doctor
Check 'rx.create' $r.Status 200
Check 'rx.create.prescriber' (Res $r).prescription.prescriber 'doctor01'
$rxId = (Res $r).prescription.id

$r = Invoke-Api 'POST' "/api/v2/pharmacy/prescriptions/$rxId/review" @{ approve = $true } $pharm
Check 'rx.approve' $r.Status 200

$r = Invoke-Api 'POST' "/api/v2/pharmacy/prescriptions/$rxId/dispense" @{} $pharm
Check 'dispense.noChecker.422' $r.Status 422

$r = Invoke-Api 'POST' "/api/v2/pharmacy/prescriptions/$rxId/dispense" @{ checker = 'pharm01' } $pharm
Check 'dispense.sameChecker.422' $r.Status 422

$r = Invoke-Api 'POST' "/api/v2/pharmacy/prescriptions/$rxId/dispense" @{ checker = 'admin01' } $pharm
Check 'dispense.ok' $r.Status 200
Check 'dispense.checker' (Res $r).dispense.checker 'admin01'
$dispenseId = (Res $r).dispense.id

# ---------------------------------------------------------------- 6. register
$r = Invoke-Api 'GET' "/api/v2/pharmacy/narcotic/register?patientId=$patientId" $null $pharm
Check 'register.list' $r.Status 200
$rows = @(Res $r)
Check 'register.count' $rows.Count 1
Check 'register.status' $rows[0].status 'PENDING_RETURN'
Check 'register.drug' $rows[0].drugCode 'DRUG-MOR-10'
Check 'register.prescriber' $rows[0].prescriber 'doctor01'
Check 'register.checker' $rows[0].checker 'admin01'
$registerId = $rows[0].id

$r = Invoke-Api 'POST' '/api/v2/pharmacy/prescriptions' @{
    patientId = $patientId
    items = @(@{ drugCode = 'DRUG-MOR-10'; quantity = 1 })
} $doctor
Check 'rx.second.blocked.422' $r.Status 422

# ---------------------------------------------------------------- 7. ampoule return
$r = Invoke-Api 'POST' "/api/v2/pharmacy/narcotic/register/$registerId/ampoule-return" @{
    quantity = 5; receiver = 'pharm01'
} $pharm
Check 'ampoule.wrongQty.422' $r.Status 422

$r = Invoke-Api 'POST' "/api/v2/pharmacy/narcotic/register/$registerId/ampoule-return" @{
    quantity = 1; receiver = 'pharm01'
} $pharm
Check 'ampoule.return' $r.Status 200
Check 'ampoule.return.status' (Res $r).status 'RETURNED'
Check 'ampoule.return.qty' (Res $r).ampouleReturned 1

$r = Invoke-Api 'POST' '/api/v2/pharmacy/prescriptions' @{
    patientId = $patientId
    items = @(@{ drugCode = 'DRUG-MOR-10'; quantity = 1 })
} $doctor
Check 'rx.afterReturn.ok' $r.Status 200

$r = Invoke-Api 'POST' "/api/v2/pharmacy/narcotic/register/$registerId/ampoule-return" @{
    quantity = 1; receiver = 'pharm01'
} $pharm
Check 'ampoule.repeat.422' $r.Status 422

# ---------------------------------------------------------------- 8. revoke licence
$r = Invoke-Api 'POST' '/api/v2/pharmacy/narcotic/prescribers/doctor01/revoke' @{
    version = $licenceVersion; reason = 'rotation'
} $admin
Check 'licence.revoke' $r.Status 200
Check 'licence.revoke.enabled' (Res $r).enabled 'False'
Check 'licence.revoke.reason' (Res $r).revokeReason 'rotation'

$r = Invoke-Api 'POST' '/api/v2/pharmacy/prescriptions' @{
    patientId = $patientId
    items = @(@{ drugCode = 'DRUG-MOR-10'; quantity = 1 })
} $doctor
Check 'rx.afterRevoke.422' $r.Status 422

$r = Invoke-Api 'POST' '/api/v2/pharmacy/narcotic/prescribers' @{ prescriber = 'doctor01' } $admin
Check 'licence.regrant' $r.Status 200
Check 'licence.regrant.enabled' (Res $r).enabled 'True'

# ---------------------------------------------------------------- 9. permissions
$r = Invoke-Api 'GET' "/api/v2/pharmacy/narcotic/register?patientId=$patientId" $null $qc
Check 'perm.qc.register.403' $r.Status 403

$r = Invoke-Api 'GET' '/api/v2/pharmacy/narcotic/prescribers' $null $qc
Check 'perm.qc.prescribers.403' $r.Status 403

Write-Output "-----"
Write-Output "PATIENT_ID=$patientId"
Write-Output "DRUG_ID=$drugId"
Write-Output "RX_ID=$rxId"
Write-Output "DISPENSE_ID=$dispenseId"
Write-Output "REGISTER_ID=$registerId"
if ($script:fail -gt 0) { Write-Output "RESULT FAIL ($script:fail)"; exit 1 }
Write-Output 'RESULT PASS'
exit 0
