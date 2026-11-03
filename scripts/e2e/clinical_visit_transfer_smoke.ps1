# ============================================================================
# MedAssist visit transfer (dept change) smoke test (through gateway).
#
# Covers:
#   * create two inpatient wards (one bed each) under two inpatient departments
#   * admit a patient to the first ward
#   * open a consultation from dept A to dept B (source of cross-dept read access)
#   * transfer the visit to dept B + bed in dept B's ward  -> 200
#   * the in-progress consultation is cancelled automatically
#   * admission now points at the new ward/bed; old bed is FREE, new bed OCCUPIED
#   * transfer history is append-only and readable afterwards
#   * same-dept transfer, missing target bed and non-inpatient target -> 422
#
# ASCII only on purpose: Windows PowerShell 5.1 reads BOM-less UTF-8 as ANSI.
#
# Prerequisites: auth :8081, empi :8082, clinical :8083, gateway :8080
# Usage (repo root):
#   powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\e2e\clinical_visit_transfer_smoke.ps1
# ============================================================================
param(
    [string]$BaseUrl = "http://127.0.0.1:8080",
    [string]$Username = "admin01",
    [string]$Password = "Med@2026",
    [string]$DeptA = "33333333-3333-3333-3333-333333333333",
    [string]$DeptB = "44444444-4444-4444-4444-444444444444",
    [string]$OutpatientDept = "11111111-1111-1111-1111-111111111111"
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

$suffix = Get-Random -Minimum 1000 -Maximum 9999

# ---------------------------------------------------------------- 1. login
$r = Invoke-Api 'POST' '/api/v2/auth/login' @{ username = $Username; password = $Password }
Check 'login' $r.Status 200
$token = (Res $r).accessToken
if (-not $token) { Write-Output "FATAL no token: $($r.Body)"; exit 1 }

# ---------------------------------------------------------------- 2. patient
$r = Invoke-Api 'POST' '/api/v2/empi/patients' @{
    name = "Transfer Patient $suffix"; gender = 'MALE'; birthDate = '1975-03-12'
    idType = 'ID_CARD'; idNo = "11010119750312$suffix"
} $token
Check 'patient.create' $r.Status 200
$patientId = (Res $r).id

# ---------------------------------------------------------------- 3. wards + beds
$r = Invoke-Api 'POST' '/api/v2/clinical/wards' @{
    code = "W-TRF-A-$suffix"; name = "Transfer Ward A $suffix"; deptId = $DeptA
} $token
Check 'wardA.create' $r.Status 200
$wardA = (Res $r).id

$r = Invoke-Api 'POST' "/api/v2/clinical/wards/$wardA/beds" @{ bedNos = @('A1') } $token
Check 'bedA.create' $r.Status 200
$bedA = (Res $r)[0].id

$r = Invoke-Api 'POST' '/api/v2/clinical/wards' @{
    code = "W-TRF-B-$suffix"; name = "Transfer Ward B $suffix"; deptId = $DeptB
} $token
Check 'wardB.create' $r.Status 200
$wardB = (Res $r).id

$r = Invoke-Api 'POST' "/api/v2/clinical/wards/$wardB/beds" @{ bedNos = @('B1') } $token
Check 'bedB.create' $r.Status 200
$bedB = (Res $r)[0].id

# ---------------------------------------------------------------- 4. admit to ward A
$r = Invoke-Api 'POST' '/api/v2/clinical/inpatient/admissions' @{
    patientId = $patientId; wardId = $wardA; bedId = $bedA; attendingDoctor = 'doctor01'
} $token
Check 'admit' $r.Status 200
$admission = Res $r
$admissionId = $admission.id
$visitId = $admission.visitId
Check 'admit.ward' $admission.wardId $wardA

# ---------------------------------------------------------------- 5. consultation A -> B
$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/consultations" @{
    toDeptId = $DeptB; reason = 'transfer smoke: need surgical care'
} $token
Check 'consultation.request' $r.Status 200
$consultationId = (Res $r).id
Check 'consultation.status' (Res $r).status 'REQUESTED'

# ---------------------------------------------------------------- 6. transfer to dept B
$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/transfers" @{
    toDeptId = $DeptB; bedId = $bedB; reason = 'need surgical care'
} $token
Check 'transfer' $r.Status 200
$transfer = Res $r
Check 'transfer.fromDept' $transfer.fromDeptId $DeptA
Check 'transfer.toDept' $transfer.toDeptId $DeptB
Check 'transfer.reason' $transfer.reason 'need surgical care'

# ---------------------------------------------------------------- 7. history
$r = Invoke-Api 'GET' "/api/v2/clinical/visits/$visitId/transfers" $null $token
Check 'transfer.list' $r.Status 200
$history = @(Res $r)
Check 'transfer.list.count' $history.Count 1
Check 'transfer.list.toDept' $history[0].toDeptId $DeptB

# ---------------------------------------------------------------- 8. consultation auto-cancelled
$r = Invoke-Api 'GET' "/api/v2/clinical/visits/$visitId/consultations" $null $token
Check 'consultation.list' $r.Status 200
$consultations = @(Res $r)
$cancelled = $consultations | Where-Object { $_.id -eq $consultationId }
Check 'consultation.cancelled' $cancelled.status 'CANCELLED'
Check 'consultation.cancelReason.contains' ($cancelled.cancelledReason -like '*need surgical care*') True

# ---------------------------------------------------------------- 9. admission + beds
$r = Invoke-Api 'GET' "/api/v2/clinical/inpatient/admissions/$admissionId" $null $token
Check 'admission.after.ward' (Res $r).wardId $wardB
Check 'admission.after.bedNo' (Res $r).bedNo 'B1'

$r = Invoke-Api 'GET' "/api/v2/clinical/wards/$wardA/beds" $null $token
$oldBed = @(Res $r) | Where-Object { $_.id -eq $bedA }
Check 'oldBed.free' $oldBed.status 'FREE'

$r = Invoke-Api 'GET' "/api/v2/clinical/wards/$wardB/beds" $null $token
$newBed = @(Res $r) | Where-Object { $_.id -eq $bedB }
Check 'newBed.occupied' $newBed.status 'OCCUPIED'

# ---------------------------------------------------------------- 10. rejected transfers
$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/transfers" @{
    toDeptId = $DeptB; bedId = $null; reason = 'same department'
} $token
Check 'transfer.sameDept.422' $r.Status 422

$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/transfers" @{
    toDeptId = $DeptA; bedId = $null; reason = 'missing target bed'
} $token
Check 'transfer.noBed.422' $r.Status 422

$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$visitId/transfers" @{
    toDeptId = $OutpatientDept; bedId = $bedA; reason = 'outpatient target'
} $token
Check 'transfer.outpatientDept.422' $r.Status 422

Write-Output "-----"
Write-Output "VISIT_ID=$visitId"
Write-Output "ADMISSION_ID=$admissionId"
if ($script:fail -gt 0) { Write-Output "RESULT FAIL ($script:fail)"; exit 1 }
Write-Output 'RESULT PASS'
exit 0
