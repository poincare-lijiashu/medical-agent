# ============================================================================
# MedAssist DRG/DIP rate versioning smoke test (G84).
#
# Covers: scheme + group + rule -> rate history starts with the creation rate
#         -> add a newer effective-dated rate -> duplicate date 409
#         -> simulation uses the rate effective on serviceDate (old vs new)
#         -> serviceDate before the first rate 422 (no silent fallback)
#         -> PUT scheme adds/corrects the same effective date (no extra row)
#         -> stale version 409 -> doctor cannot manage rates (403)
#
# ASCII only (Windows PowerShell 5.1 reads BOM-less UTF-8 as ANSI).
# Prerequisites: auth :8081, insurance :8087, gateway :8080
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\e2e\insurance_rate_version_smoke.ps1
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

function CheckNum($name, $actual, $expected) {
    if ([decimal]$actual -eq [decimal]$expected) { Write-Output "PASS $name = $actual" }
    else { Write-Output "FAIL $name expected=$expected actual=$actual"; $script:fail++ }
}

function Res($r) { return (($r.Body | ConvertFrom-Json).data) }

function Day($offset) { return (Get-Date).AddDays($offset).ToString('yyyy-MM-dd') }

$suffix = (Get-Date).ToString('HHmmssff')
$schemeCode = "RATE-$suffix"
$visitId = [guid]::NewGuid().ToString()

# ---------------------------------------------------------------- 1. login
$r = Invoke-Api 'POST' '/api/v2/auth/login' @{ username = $Username; password = $Password }
Check 'login.admin' $r.Status 200
$token = (Res $r).accessToken
if (-not $token) { Write-Output "FATAL no admin token: $($r.Body)"; exit 1 }

$r = Invoke-Api 'POST' '/api/v2/auth/login' @{ username = 'doctor01'; password = $Password }
Check 'login.doctor' $r.Status 200
$doctorToken = (Res $r).accessToken

# ---------------------------------------------------------------- 2. scheme (rate from 1 year ago)
$r = Invoke-Api 'POST' '/api/v2/insurance/grouping/schemes' @{
    code = $schemeCode; name = 'Rate version scheme'; type = 'DRG'
    rateAmount = 100.00; rateEffectiveFrom = (Day -365); enabled = $true
} $token
Check 'scheme.create' $r.Status 200

$r = Invoke-Api 'GET' "/api/v2/insurance/grouping/schemes/$schemeCode/rates" $null $token
Check 'rates.initial' $r.Status 200
Check 'rates.initial.count' (@(Res $r)).Count 1

$r = Invoke-Api 'POST' "/api/v2/insurance/grouping/schemes/$schemeCode/groups/import" @{
    groups = @(@{ groupCode = 'G1'; groupName = 'Respiratory'; weight = 2.0 })
} $token
Check 'groups.import' $r.Status 200

$r = Invoke-Api 'POST' "/api/v2/insurance/grouping/schemes/$schemeCode/rules/import" @{
    rules = @(@{ priority = 1; matchType = 'DIAGNOSIS'; diagPrefix = 'J18'; groupCode = 'G1' })
} $token
Check 'rules.import' $r.Status 200

$r = Invoke-Api 'POST' '/api/v2/insurance/grouping/simulate' @{
    visitId = $visitId; schemeCode = $schemeCode; diagnoses = @('J18.900'); procedures = @()
    serviceDate = (Day -100)
} $token
Check 'simulate.oldDate' $r.Status 200
CheckNum 'simulate.oldDate.rate' (Res $r).rateAmount 100.00
CheckNum 'simulate.oldDate.payment' (Res $r).paymentStandard 200.00

# ---------------------------------------------------------------- 3. add a newer rate tier
$r = Invoke-Api 'POST' "/api/v2/insurance/grouping/schemes/$schemeCode/rates" @{
    effectiveFrom = (Day -30); rateAmount = 120.00
} $token
Check 'rate.add' $r.Status 200
CheckNum 'rate.add.value' (Res $r).rateAmount 120.00

$r = Invoke-Api 'POST' "/api/v2/insurance/grouping/schemes/$schemeCode/rates" @{
    effectiveFrom = (Day -30); rateAmount = 125.00
} $token
Check 'rate.add.duplicate.409' $r.Status 409

$r = Invoke-Api 'GET' "/api/v2/insurance/grouping/schemes/$schemeCode/rates" $null $token
$rates = @(Res $r)
Check 'rates.count2' ($rates.Count) 2
Check 'rates.newestFirst' ($rates[0].effectiveFrom) (Day -30)

$r = Invoke-Api 'POST' '/api/v2/insurance/grouping/simulate' @{
    visitId = $visitId; schemeCode = $schemeCode; diagnoses = @('J18.900'); procedures = @()
    serviceDate = (Day -60)
} $token
CheckNum 'simulate.betweenTiers.rate' (Res $r).rateAmount 100.00

$r = Invoke-Api 'POST' '/api/v2/insurance/grouping/simulate' @{
    visitId = $visitId; schemeCode = $schemeCode; diagnoses = @('J18.900'); procedures = @()
    serviceDate = (Day -10)
} $token
CheckNum 'simulate.newTier.rate' (Res $r).rateAmount 120.00
CheckNum 'simulate.newTier.payment' (Res $r).paymentStandard 240.00

$r = Invoke-Api 'POST' '/api/v2/insurance/grouping/simulate' @{
    visitId = $visitId; schemeCode = $schemeCode; diagnoses = @('J18.900'); procedures = @()
    serviceDate = (Day -500)
} $token
Check 'simulate.beforeFirstRate.422' $r.Status 422

# ---------------------------------------------------------------- 4. PUT scheme = correct today's tier
# addRate already synced the scheme to 120 and bumped its version, so v0 is stale now
$r = Invoke-Api 'PUT' "/api/v2/insurance/grouping/schemes/$schemeCode" @{
    name = 'Rate version scheme'; rateAmount = 130.00; rateEffectiveFrom = (Day 0)
    enabled = $true; version = 0
} $token
Check 'scheme.update.stale.409' $r.Status 409

$r = Invoke-Api 'PUT' "/api/v2/insurance/grouping/schemes/$schemeCode" @{
    name = 'Rate version scheme'; rateAmount = 130.00; rateEffectiveFrom = (Day 0)
    enabled = $true; version = 1
} $token
Check 'scheme.update' $r.Status 200
CheckNum 'scheme.update.rate' (Res $r).rateAmount 130.00

$r = Invoke-Api 'GET' "/api/v2/insurance/grouping/schemes/$schemeCode/rates" $null $token
Check 'rates.count3' (@(Res $r)).Count 3

$r = Invoke-Api 'PUT' "/api/v2/insurance/grouping/schemes/$schemeCode" @{
    name = 'Rate version scheme'; rateAmount = 140.00; rateEffectiveFrom = (Day 0)
    enabled = $true; version = 2
} $token
Check 'scheme.update.sameDate' $r.Status 200

$r = Invoke-Api 'GET' "/api/v2/insurance/grouping/schemes/$schemeCode/rates" $null $token
$rates = @(Res $r)
Check 'rates.count.still3' ($rates.Count) 3
CheckNum 'rates.today.value' ($rates[0].rateAmount) 140.00

$r = Invoke-Api 'POST' '/api/v2/insurance/grouping/simulate' @{
    visitId = $visitId; schemeCode = $schemeCode; diagnoses = @('J18.900'); procedures = @()
} $token
CheckNum 'simulate.today.rate' (Res $r).rateAmount 140.00
CheckNum 'simulate.today.payment' (Res $r).paymentStandard 280.00

# ---------------------------------------------------------------- 5. permission
$r = Invoke-Api 'POST' "/api/v2/insurance/grouping/schemes/$schemeCode/rates" @{
    effectiveFrom = (Day 30); rateAmount = 150.00
} $doctorToken
Check 'rate.add.doctor.403' $r.Status 403

$r = Invoke-Api 'GET' '/api/v2/insurance/grouping/schemes/NOPE/rates' $null $token
Check 'rates.unknownScheme.404' $r.Status 404

Write-Output "-----"
Write-Output "SCHEME=$schemeCode"
if ($script:fail -gt 0) { Write-Output "RESULT FAIL ($script:fail)"; exit 1 }
Write-Output 'RESULT PASS'
exit 0
