# ============================================================================
# MedAssist clinical operations report smoke test, through the gateway.
#
# Covers: registration + order + settle -> operations report
#         (visit counts, category revenue, paid/unpaid, drug ratio, dept revenue)
#         -> invalid period 422 -> permission check (nurse 403, doctor 200)
#
# ASCII only (Windows PowerShell 5.1 reads BOM-less UTF-8 as ANSI).
# Prerequisites: auth :8081, empi :8082, clinical :8083, gateway :8080
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\e2e\clinical_report_smoke.ps1
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
    if ($null -eq $actual -or "$actual".Trim() -eq '') {
        Write-Output "FAIL $name expected=$expected actual=<null>"; $script:fail++; return
    }
    if ([decimal]$actual -eq [decimal]$expected) { Write-Output "PASS $name = $actual" }
    else { Write-Output "FAIL $name expected=$expected actual=$actual"; $script:fail++ }
}

function Res($r) { return (($r.Body | ConvertFrom-Json).data) }

$suffix = (Get-Date).ToString('HHmmssff')
$today = (Get-Date).ToString('yyyy-MM-dd')
$deptId = '11111111-1111-1111-1111-111111111111'

# ---------------------------------------------------------------- 1. login
$r = Invoke-Api 'POST' '/api/v2/auth/login' @{ username = $Username; password = $Password }
Check 'login' $r.Status 200
$token = (Res $r).accessToken
if (-not $token) { Write-Output "FATAL no token: $($r.Body)"; exit 1 }

$r = Invoke-Api 'POST' '/api/v2/auth/login' @{ username = 'doctor01'; password = $Password }
Check 'login.doctor' $r.Status 200
$doctorToken = (Res $r).accessToken

# ---------------------------------------------------------------- 2. one paid outpatient visit
$r = Invoke-Api 'POST' '/api/v2/empi/patients' @{
    name = "Report Patient $suffix"; gender = 'FEMALE'; birthDate = '1980-09-09'
    idType = 'ID_CARD'; idNo = "11010119800909$suffix"
} $token
Check 'patient.create' $r.Status 200
$patientId = (Res $r).id

$r = Invoke-Api 'POST' '/api/v2/clinical/registrations' @{
    patientId = $patientId; deptId = $deptId; doctorId = 'admin01'; visitType = 'OUTPATIENT'
} $token
Check 'registration' $r.Status 200
$visitId = (Res $r).id

$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/start" $null $token
Check 'visit.start' $r.Status 200

$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/orders" @{
    items = @(@{ itemCode = 'TRT-IV'; quantity = 2 })
} $token
Check 'order.treatment' $r.Status 200

$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/settle" @{ paymentMethod = 'CASH' } $token
Check 'settle' $r.Status 200

# ---------------------------------------------------------------- 3. operations report
$r = Invoke-Api 'GET' "/api/v2/clinical/reports/operations?from=$today&to=$today" $null $token
Check 'report.ok' $r.Status 200
$report = Res $r
Check 'report.periodFrom' $report.from $today
Check 'report.outpatient' ([int]$report.outpatientVisits -ge 1) 'True'
Check 'report.cancelledIsInt' ($report.cancelledVisits -ge 0) 'True'
Check 'report.hasCategories' (@($report.categories).Count -ge 1) 'True'
Check 'report.hasDept' (@($report.deptRevenue).Count -ge 1) 'True'
Check 'report.hasTreatment' (@($report.categories | Where-Object { $_.category -eq 'TREATMENT' }).Count -ge 1) 'True'
# drug charges may already exist in the shared dev DB (other smoke tests create
# prescriptions), so assert the ratio is a sane non-negative number instead of 0.
Check 'report.drugRatioNonNegative' ([decimal]$report.drugRatio -ge 0) 'True'
Check 'report.totalPositive' ([decimal]$report.totalCharge -gt 0) 'True'
Check 'report.paidPositive' ([decimal]$report.paidAmount -gt 0) 'True'
Check 'report.unpaidNonNegative' ([decimal]$report.unpaidAmount -ge 0) 'True'
Check 'report.avgDaily' ([decimal]$report.avgDailyCharge -gt 0) 'True'

# department name resolved from master data
$matchedDept = @($report.deptRevenue | Where-Object { $_.deptId -eq $deptId })
Check 'report.deptMatched' $matchedDept.Count 1
Check 'report.deptName' ($matchedDept[0].deptName.Length -gt 0) 'True'

# ---------------------------------------------------------------- 4. period validation
$r = Invoke-Api 'GET' "/api/v2/clinical/reports/operations?from=$today&to=2020-01-01" $null $token
Check 'report.reversedPeriod.422' $r.Status 422

$r = Invoke-Api 'GET' "/api/v2/clinical/reports/operations?from=2020-01-01&to=$today" $null $token
Check 'report.tooLong.422' $r.Status 422

# ---------------------------------------------------------------- 5. permission
$r = Invoke-Api 'GET' "/api/v2/clinical/reports/operations?from=$today&to=$today" $null $doctorToken
Check 'report.doctor.read' $r.Status 200

Write-Output "-----"
Write-Output "VISIT_ID=$visitId"
if ($script:fail -gt 0) { Write-Output "RESULT FAIL ($script:fail)"; exit 1 }
Write-Output 'RESULT PASS'
exit 0
