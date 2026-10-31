# ============================================================================
# MedAssist prescription pre-audit smoke test (through the gateway).
#
# Covers (prescription safety rules):
#   * daily dose above catalog limit => 422 (BLOCK)
#   * pediatric contraindication for a child patient => 422 (BLOCK)
#   * special-use antibiotic => WARN recorded, prescription still approvable
#   * structured dose must be complete (singleDose/doseUnit/timesPerDay)
#   * GET /pharmacy/prescriptions/{id}/audit returns findings
#
# ASCII only on purpose: Windows PowerShell 5.1 reads BOM-less UTF-8 as ANSI.
#
# Prerequisites: auth :8081, empi :8082, pharmacy :8084, gateway
# Usage (repo root):
#   powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\e2e\pharmacy_audit_smoke.ps1
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

function Res($r) { return (($r.Body | ConvertFrom-Json).data) }
function Body($r) { return ($r.Body | ConvertFrom-Json) }

$suffix = Get-Random -Minimum 1000 -Maximum 9999

# ---------------------------------------------------------------- 1. login
$r = Invoke-Api 'POST' '/api/v2/auth/login' @{ username = $Username; password = $Password }
Check 'login' $r.Status 200
$token = (Res $r).accessToken
if (-not $token) { Write-Output "FATAL no token: $($r.Body)"; exit 1 }

# ---------------------------------------------------------------- 2. patients (adult + child)
$r = Invoke-Api 'POST' '/api/v2/empi/patients' @{
    name = "Audit Adult $suffix"; gender = 'MALE'; birthDate = '1990-06-01'
    idType = 'ID_CARD'; idNo = "11010119900601$suffix"
} $token
Check 'patient.adult' $r.Status 200
$adultId = (Res $r).id

$r = Invoke-Api 'POST' '/api/v2/empi/patients' @{
    name = "Audit Child $suffix"; gender = 'FEMALE'; birthDate = '2020-01-15'
    idType = 'ID_CARD'; idNo = "11010120200115$suffix"
} $token
Check 'patient.child' $r.Status 200
$childId = (Res $r).id

# ---------------------------------------------------------------- 3. drugs
$r = Invoke-Api 'POST' '/api/v2/pharmacy/drugs' @{
    drugCode = "DRUG-AUD-$suffix"; name = 'Audit Drug A'; spec = '0.5g'
    dosageForm = 'TABLET'; unit = 'box'; price = 10.00
    maxDailyDose = 2.0; pediatricContraindicated = $true
} $token
Check 'drug.create.doseRule' $r.Status 200
$drugA = (Res $r).drug

$r = Invoke-Api 'POST' '/api/v2/pharmacy/drugs' @{
    drugCode = "DRUG-ABX-$suffix"; name = 'Audit Special Antibiotic'; spec = '1g'
    dosageForm = 'INJECTION'; unit = 'vial'; price = 88.00
    antibioticLevel = 3; insuranceCategory = 'B'
} $token
Check 'drug.create.specialAntibiotic' $r.Status 200
$drugB = (Res $r).drug

$far = (Get-Date).AddDays(300).ToString('yyyy-MM-dd')
$r = Invoke-Api 'POST' "/api/v2/pharmacy/drugs/$($drugA.id)/batches" @{
    batchNo = "B-AUD-A-$suffix"; expireDate = $far; quantity = 20
} $token
Check 'stock.drugA' $r.Status 200
$r = Invoke-Api 'POST' "/api/v2/pharmacy/drugs/$($drugB.id)/batches" @{
    batchNo = "B-AUD-B-$suffix"; expireDate = $far; quantity = 20
} $token
Check 'stock.drugB' $r.Status 200

# ---------------------------------------------------------------- 4. dose rule (BLOCK)
$r = Invoke-Api 'POST' '/api/v2/pharmacy/prescriptions' @{
    patientId = $adultId
    items = @(@{ drugCode = "DRUG-AUD-$suffix"; quantity = 1; singleDose = 1.5; doseUnit = 'g'; timesPerDay = 2 })
} $token
Check 'audit.doseExceeded.422' $r.Status 422
Check 'audit.doseExceeded.message' ((Body $r).message -like '*3g*') $true

# within limit => created
$r = Invoke-Api 'POST' '/api/v2/pharmacy/prescriptions' @{
    patientId = $adultId
    items = @(@{ drugCode = "DRUG-AUD-$suffix"; quantity = 1; singleDose = 1.0; doseUnit = 'g'; timesPerDay = 2 })
} $token
Check 'audit.doseWithin.ok' $r.Status 200

# incomplete structured dose => 422
$r = Invoke-Api 'POST' '/api/v2/pharmacy/prescriptions' @{
    patientId = $adultId
    items = @(@{ drugCode = "DRUG-AUD-$suffix"; quantity = 1; singleDose = 1.0 })
} $token
Check 'audit.doseIncomplete.422' $r.Status 422

# ---------------------------------------------------------------- 5. pediatric rule (BLOCK)
$r = Invoke-Api 'POST' '/api/v2/pharmacy/prescriptions' @{
    patientId = $childId
    items = @(@{ drugCode = "DRUG-AUD-$suffix"; quantity = 1; singleDose = 0.5; doseUnit = 'g'; timesPerDay = 2 })
} $token
Check 'audit.pediatric.422' $r.Status 422
Check 'audit.pediatric.message' ((Body $r).message -like '*14*') $true

# ---------------------------------------------------------------- 6. WARN recorded but approvable
$r = Invoke-Api 'POST' '/api/v2/pharmacy/prescriptions' @{
    patientId = $adultId
    items = @(@{ drugCode = "DRUG-ABX-$suffix"; quantity = 1 })
} $token
Check 'audit.warn.create' $r.Status 200
$warnRxId = (Res $r).prescription.id

$r = Invoke-Api 'GET' "/api/v2/pharmacy/prescriptions/$warnRxId/audit" $null $token
Check 'audit.list' $r.Status 200
$audit = Res $r
Check 'audit.warned' $audit.warned $true
Check 'audit.notBlocked' $audit.blocked $false
$rules = @($audit.audits | ForEach-Object { $_.ruleCode })
Check 'audit.rule.abx' ($rules -contains 'R-ABX-001') $true

$r = Invoke-Api 'POST' "/api/v2/pharmacy/prescriptions/$warnRxId/review" @{ approve = $true } $token
Check 'audit.warn.review.ok' $r.Status 200
Check 'audit.warn.status' (Res $r).prescription.status 'APPROVED'

# ---------------------------------------------------------------- 7. audit trail also written on review
$r = Invoke-Api 'GET' "/api/v2/pharmacy/prescriptions/$warnRxId/audit" $null $token
$phases = @((Res $r).audits | ForEach-Object { $_.phase } | Select-Object -Unique)
Check 'audit.phase.create' ($phases -contains 'CREATE') $true
Check 'audit.phase.review' ($phases -contains 'REVIEW') $true

Write-Output "-----"
Write-Output "ADULT_ID=$adultId"
Write-Output "CHILD_ID=$childId"
Write-Output "WARN_RX_ID=$warnRxId"
if ($script:fail -gt 0) { Write-Output "RESULT FAIL ($script:fail)"; exit 1 }
Write-Output 'RESULT PASS'
exit 0
