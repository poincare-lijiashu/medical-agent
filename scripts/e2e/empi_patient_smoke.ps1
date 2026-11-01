# ============================================================================
# MedAssist EMPI patient master index smoke test.
#
# Covers: create patient (PHI masked in response) -> duplicate idNo 409
#         -> detail masked by default -> reveal needs empi:patient:read_phi
#         (doctor 200 / pharmacist 403) -> update with stale version 409
#         -> merge (source becomes MERGED, crosswalk moved) -> reveal on merged 409
#         -> update merged 409 -> crosswalk add/list -> crosswalk on merged 409.
#
# ASCII only (Windows PowerShell 5.1 reads BOM-less UTF-8 as ANSI).
# Prerequisites: auth :8081, empi :8082, gateway :8080
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\e2e\empi_patient_smoke.ps1
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

function CheckTrue($name, $actual) {
    if ("$actual" -eq 'True') { Write-Output "PASS $name = True" }
    else { Write-Output "FAIL $name expected=True actual=$actual"; $script:fail++ }
}

function Res($r) { return (($r.Body | ConvertFrom-Json).data) }

$suffix = (Get-Date).ToString('HHmmss')
# 18-digit synthetic id card, unique per run
$idNo = "11010119900101" + $suffix.Substring(0, 4)

# ---------------------------------------------------------------- 1. login
$r = Invoke-Api 'POST' '/api/v2/auth/login' @{ username = $Username; password = $Password }
Check 'login.admin' $r.Status 200
$token = (Res $r).accessToken
if (-not $token) { Write-Output "FATAL no token: $($r.Body)"; exit 1 }

$r = Invoke-Api 'POST' '/api/v2/auth/login' @{ username = 'doctor01'; password = $Password }
Check 'login.doctor' $r.Status 200
$doctorToken = (Res $r).accessToken

$r = Invoke-Api 'POST' '/api/v2/auth/login' @{ username = 'pharm01'; password = $Password }
Check 'login.pharmacist' $r.Status 200
$pharmToken = (Res $r).accessToken

# ---------------------------------------------------------------- 2. create
$r = Invoke-Api 'POST' '/api/v2/empi/patients' @{
    name = "Smoke A $suffix"; gender = 'MALE'; birthDate = '1990-01-01'
    idType = 'ID_CARD'; idNo = $idNo; phone = '13800001111'; address = 'Test address 1'
} $token
Check 'patient.create' $r.Status 200
$created = Res $r
$patientA = $created.id
Check 'patient.create.masked' $created.phiMasked 'True'
Check 'patient.create.idNoMasked' $created.idNo ($idNo.Substring(0, 4) + '****' + $idNo.Substring($idNo.Length - 4))
Check 'patient.create.status' $created.status 'ACTIVE'

$r = Invoke-Api 'POST' '/api/v2/empi/patients' @{
    name = "Smoke A dup $suffix"; idType = 'ID_CARD'; idNo = $idNo
} $token
Check 'patient.duplicate.409' $r.Status 409

# ---------------------------------------------------------------- 3. masked vs reveal
$r = Invoke-Api 'GET' "/api/v2/empi/patients/$patientA" $null $token
Check 'detail.ok' $r.Status 200
Check 'detail.masked' (Res $r).phiMasked 'True'

$r = Invoke-Api 'GET' "/api/v2/empi/patients/$patientA`?reveal=true" $null $doctorToken
Check 'detail.reveal.doctor' $r.Status 200
Check 'detail.reveal.plain' (Res $r).idNo $idNo
Check 'detail.reveal.notMasked' (Res $r).phiMasked 'False'

$r = Invoke-Api 'GET' "/api/v2/empi/patients/$patientA`?reveal=true" $null $pharmToken
Check 'detail.reveal.pharmacist.403' $r.Status 403

# ---------------------------------------------------------------- 4. update + optimistic lock
$r = Invoke-Api 'PUT' "/api/v2/empi/patients/$patientA" @{
    name = "Smoke A renamed $suffix"; gender = 'MALE'; version = 0
} $token
Check 'update.ok' $r.Status 200
Check 'update.name' (Res $r).name "Smoke A renamed $suffix"

$r = Invoke-Api 'PUT' "/api/v2/empi/patients/$patientA" @{
    name = "Smoke A stale $suffix"; gender = 'MALE'; version = 0
} $token
Check 'update.stale.409' $r.Status 409

# ---------------------------------------------------------------- 5. merge
$r = Invoke-Api 'POST' '/api/v2/empi/patients' @{ name = "Smoke B $suffix"; gender = 'FEMALE' } $token
Check 'patientB.create' $r.Status 200
$patientB = (Res $r).id

$r = Invoke-Api 'POST' "/api/v2/empi/patients/$patientB/crosswalk" @{
    systemCode = 'HIS'; externalId = "HIS-$suffix"
} $token
Check 'crosswalk.addB' $r.Status 200

$r = Invoke-Api 'POST' '/api/v2/empi/patients/merge' @{
    fromId = $patientB; toId = $patientA; reason = 'duplicate registration in smoke test'
} $token
Check 'merge.ok' $r.Status 200
Check 'merge.target' (Res $r).id $patientA

$r = Invoke-Api 'GET' "/api/v2/empi/patients/$patientB" $null $token
Check 'mergedB.status' (Res $r).status 'MERGED'
Check 'mergedB.mergedInto' (Res $r).mergedInto $patientA

$r = Invoke-Api 'GET' "/api/v2/empi/patients/$patientB`?reveal=true" $null $token
Check 'mergedB.reveal.409' $r.Status 409

$r = Invoke-Api 'PUT' "/api/v2/empi/patients/$patientB" @{
    name = "Smoke B edit $suffix"; gender = 'FEMALE'; version = 0
} $token
Check 'mergedB.update.409' $r.Status 409

$r = Invoke-Api 'POST' "/api/v2/empi/patients/$patientB/crosswalk" @{
    systemCode = 'LIS'; externalId = "LIS-$suffix"
} $token
Check 'mergedB.crosswalk.409' $r.Status 409

# ---------------------------------------------------------------- 6. crosswalk moved to target
$r = Invoke-Api 'GET' "/api/v2/empi/patients/$patientA/crosswalk" $null $token
Check 'crosswalk.listA' $r.Status 200
$items = @((Res $r))
CheckTrue 'crosswalk.moved' (($items | ForEach-Object { "$($_.systemCode)=$($_.externalId)" }) -contains "HIS=HIS-$suffix")

# ---------------------------------------------------------------- 7. merge self 422
$r = Invoke-Api 'POST' '/api/v2/empi/patients/merge' @{
    fromId = $patientA; toId = $patientA; reason = 'self merge'
} $token
Check 'merge.self.422' $r.Status 422

Write-Output "-----"
Write-Output "PATIENT_A=$patientA"
Write-Output "PATIENT_B=$patientB"
if ($script:fail -gt 0) { Write-Output "RESULT FAIL ($script:fail)"; exit 1 }
Write-Output 'RESULT PASS'
exit 0
