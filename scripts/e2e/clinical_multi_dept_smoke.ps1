# ============================================================================
# MedAssist multi-department (part-time attending) smoke test (through gateway).
#
# Covers:
#   * create a DOCTOR account bound to one clinical department (deptIds)
#   * login -> JWT carries depts -> gateway injects X-Org -> DataScope resolves it
#   * the doctor can read a visit in the bound department -> 200
#   * the same doctor cannot read a visit in another department -> 404
#   * re-bind to two departments, re-login: both visits become readable -> 200
#   * DOCTOR without any deptIds is rejected at account creation -> 422
#
# ASCII only on purpose: Windows PowerShell 5.1 reads BOM-less UTF-8 as ANSI.
#
# Prerequisites: auth :8081, empi :8082, clinical :8083, gateway :8080
# Usage (repo root):
#   powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\e2e\clinical_multi_dept_smoke.ps1
# ============================================================================
param(
    [string]$BaseUrl = "http://127.0.0.1:8080",
    [string]$AdminUsername = "admin01",
    [string]$AdminPassword = "Med@2026",
    [string]$DeptA = "33333333-3333-3333-3333-333333333333",
    [string]$DeptB = "44444444-4444-4444-4444-444444444444"
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

function Res($r) { return (($r.Body | ConvertFrom-Json).data) }

function LoginAs($username, $password) {
    $r = Invoke-Api 'POST' '/api/v2/auth/login' @{ username = $username; password = $password }
    return [pscustomobject]@{ Status = $r.Status; Body = $r.Body; Token = (Res $r).accessToken }
}

$suffix = Get-Random -Minimum 1000 -Maximum 9999

# ---------------------------------------------------------------- 1. admin login
$admin = LoginAs $AdminUsername $AdminPassword
Check 'admin.login' $admin.Status 200
$adminToken = $admin.Token
if (-not $adminToken) { Write-Output "FATAL no admin token: $($admin.Body)"; exit 1 }

# ---------------------------------------------------------------- 2. doctor bound to dept A
$doctorName = "deptdoc$suffix"
$r = Invoke-Api 'POST' '/api/v2/auth/users' @{
    username = $doctorName; password = 'Med@2026'; displayName = "Dept Doctor $suffix"
    employeeNo = "D$suffix"; orgCode = $null; deptIds = @($DeptA); roles = @('DOCTOR')
} $adminToken
Check 'doctor.create' $r.Status 200
$doctorId = (Res $r).id
Check 'doctor.create.deptCount' @((Res $r).deptIds).Count 1
Check 'doctor.create.deptId' (Res $r).deptIds[0] $DeptA
$doctorVersion = (Res $r).version

# ---------------------------------------------------------------- 3. two visits in different departments
$r = Invoke-Api 'POST' '/api/v2/empi/patients' @{
    name = "Multi Dept A $suffix"; gender = 'MALE'; birthDate = '1981-04-09'
    idType = 'ID_CARD'; idNo = "11010119810409$suffix"
} $adminToken
Check 'patientA.create' $r.Status 200
$patientA = (Res $r).id

$r = Invoke-Api 'POST' '/api/v2/empi/patients' @{
    name = "Multi Dept B $suffix"; gender = 'FEMALE'; birthDate = '1982-06-11'
    idType = 'ID_CARD'; idNo = "11010119820611$suffix"
} $adminToken
Check 'patientB.create' $r.Status 200
$patientB = (Res $r).id

$r = Invoke-Api 'POST' '/api/v2/clinical/registrations' @{
    patientId = $patientA; deptId = $DeptA; doctorId = 'doctor01'
} $adminToken
Check 'visitA.create' $r.Status 200
$visitA = (Res $r).id
Check 'visitA.dept' (Res $r).deptId $DeptA

$r = Invoke-Api 'POST' '/api/v2/clinical/registrations' @{
    patientId = $patientB; deptId = $DeptB; doctorId = 'doctor02'
} $adminToken
Check 'visitB.create' $r.Status 200
$visitB = (Res $r).id

# ---------------------------------------------------------------- 4. single-department scope
$doctor = LoginAs $doctorName 'Med@2026'
Check 'doctor.login' $doctor.Status 200
Check 'doctor.me.deptCount' @((Res (Invoke-Api 'GET' '/api/v2/auth/me' $null $doctor.Token)).deptIds).Count 1
$doctorToken = $doctor.Token

$r = Invoke-Api 'GET' "/api/v2/clinical/visits/$visitA" $null $doctorToken
Check 'doctor.visitA.200' $r.Status 200

$r = Invoke-Api 'GET' "/api/v2/clinical/visits/$visitB" $null $doctorToken
Check 'doctor.visitB.404' $r.Status 404

$r = Invoke-Api 'GET' "/api/v2/clinical/visits?patientId=$patientB" $null $doctorToken
Check 'doctor.patientB.list.empty' @(Res $r).Count 0

# ---------------------------------------------------------------- 5. re-bind to two departments
$r = Invoke-Api 'PUT' "/api/v2/auth/users/$doctorId" @{
    displayName = "Dept Doctor $suffix"; employeeNo = "D$suffix"; orgCode = $null
    deptIds = @($DeptA, $DeptB); version = $doctorVersion
} $adminToken
Check 'doctor.rebind' $r.Status 200
Check 'doctor.rebind.deptCount' @((Res $r).deptIds).Count 2

# The JWT is a snapshot at issue time: only a fresh login carries both departments.
$doctor2 = LoginAs $doctorName 'Med@2026'
Check 'doctor.relogin' $doctor2.Status 200
Check 'doctor.me.deptCount2' @((Res (Invoke-Api 'GET' '/api/v2/auth/me' $null $doctor2.Token)).deptIds).Count 2

$r = Invoke-Api 'GET' "/api/v2/clinical/visits/$visitA" $null $doctor2.Token
Check 'doctor2.visitA.200' $r.Status 200
$r = Invoke-Api 'GET' "/api/v2/clinical/visits/$visitB" $null $doctor2.Token
Check 'doctor2.visitB.200' $r.Status 200
$r = Invoke-Api 'GET' "/api/v2/clinical/visits?patientId=$patientB" $null $doctor2.Token
Check 'doctor2.patientB.list.one' @(Res $r).Count 1

# ---------------------------------------------------------------- 6. clinical role must be bound
$r = Invoke-Api 'POST' '/api/v2/auth/users' @{
    username = "nodeptdoc$suffix"; password = 'Med@2026'; displayName = "No Dept $suffix"
    employeeNo = $null; orgCode = $null; deptIds = @(); roles = @('DOCTOR')
} $adminToken
Check 'doctor.noDept.422' $r.Status 422

Write-Output "-----"
Write-Output "DOCTOR_ID=$doctorId"
Write-Output "VISIT_A=$visitA"
Write-Output "VISIT_B=$visitB"
if ($script:fail -gt 0) { Write-Output "RESULT FAIL ($script:fail)"; exit 1 }
Write-Output 'RESULT PASS'
exit 0
