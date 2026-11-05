# ============================================================================
# MedAssist insurance export message template smoke (G85), through the gateway.
#
# Covers: list -> invalid schema 422 -> create draft -> duplicate code 409
#         -> update + stale CAS 409 -> publish -> publish twice 409
#         -> second publish 409 (single active template) -> disable -> disable twice 409
#         -> doctor (no read permission) 403.
#
# ASCII only (Windows PowerShell 5.1 reads BOM-less UTF-8 as ANSI).
# Prerequisites: auth :8081, insurance :8087, gateway :8080
#   (auth must have V77 applied, insurance must have V23 applied; restart both)
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\e2e\insurance_export_template_smoke.ps1
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

$suffix = (Get-Date).ToString('HHmmssff')

# ---------------------------------------------------------------- 1. login
$r = Invoke-Api 'POST' '/api/v2/auth/login' @{ username = $Username; password = $Password }
Check 'login' $r.Status 200
$token = (Res $r).accessToken
if (-not $token) { Write-Output "FATAL no token: $($r.Body)"; exit 1 }

$r = Invoke-Api 'POST' '/api/v2/auth/login' @{ username = 'doctor01'; password = $Password }
Check 'login.doctor' $r.Status 200
$doctorToken = (Res $r).accessToken

# ---------------------------------------------------------------- 2. list + cleanup
$r = Invoke-Api 'GET' '/api/v2/insurance/export/templates?limit=100' $null $token
Check 'list' $r.Status 200
# A previous failed run may have left a template PUBLISHED; publishing again would 409.
foreach ($t in @(Res $r)) {
    if ($t.status -eq 'PUBLISHED') {
        $r2 = Invoke-Api 'POST' "/api/v2/insurance/export/templates/$($t.id)/disable" $null $token
        Check "cleanup.disable.$($t.code)" $r2.Status 200
    }
}

# ---------------------------------------------------------------- 3. invalid schema
$r = Invoke-Api 'POST' '/api/v2/insurance/export/templates' @{
    code = "BAD-$suffix"; name = 'bad template'
    fields = @(
        @{ element = 'billNo'; source = 'billNo'; required = $true }
        @{ element = 'patientNo'; source = 'patientNo'; required = $true }
        @{ element = 'totalAmount'; source = 'totalAmount'; required = $true }
        @{ element = 'insuranceAmount'; source = 'insuranceAmount'; required = $true }
        @{ element = 'idCard'; source = 'patientIdCard'; required = $false }
    )
} $token
Check 'create.invalidSource.422' $r.Status 422

# ---------------------------------------------------------------- 4. create draft
$tplCode = "PROV-$suffix"
$fields = @(
    @{ element = 'settleNo'; source = 'billNo'; required = $true }
    @{ element = 'patientNo'; source = 'patientNo'; required = $true }
    @{ element = 'totalAmount'; source = 'totalAmount'; required = $true }
    @{ element = 'insuranceAmount'; source = 'insuranceAmount'; required = $true }
    @{ element = 'patientNoCopy'; source = 'patientNo'; required = $false }
)
$r = Invoke-Api 'POST' '/api/v2/insurance/export/templates' @{
    code = $tplCode; name = 'province template'; fields = $fields
} $token
Check 'create' $r.Status 200
$tplId = (Res $r).id
Check 'create.status' (Res $r).status 'DRAFT'
Check 'create.version' (Res $r).version 0
Check 'create.rowVersion' (Res $r).rowVersion 0
Check 'create.fieldCount' (@((Res $r).fields).Count) 5

# duplicate code -> 409
$r = Invoke-Api 'POST' '/api/v2/insurance/export/templates' @{
    code = $tplCode; name = 'dup'; fields = $fields
} $token
Check 'create.duplicate.409' $r.Status 409

# ---------------------------------------------------------------- 5. update + CAS
$r = Invoke-Api 'PUT' "/api/v2/insurance/export/templates/$tplId" @{
    name = 'province template v2'; fields = $fields; rowVersion = 0
} $token
Check 'update' $r.Status 200
Check 'update.rowVersion' (Res $r).rowVersion 1
Check 'update.name' (Res $r).name 'province template v2'

# stale rowVersion -> 409
$r = Invoke-Api 'PUT' "/api/v2/insurance/export/templates/$tplId" @{
    name = 'province template v3'; fields = $fields; rowVersion = 0
} $token
Check 'update.stale.409' $r.Status 409

# ---------------------------------------------------------------- 6. publish
$r = Invoke-Api 'POST' "/api/v2/insurance/export/templates/$tplId/publish" $null $token
Check 'publish' $r.Status 200
Check 'publish.status' (Res $r).status 'PUBLISHED'
Check 'publish.version' (Res $r).version 1
Check 'publish.publishedBy' (Res $r).publishedBy 'admin01'

# publish twice -> 409 (status gate)
$r = Invoke-Api 'POST' "/api/v2/insurance/export/templates/$tplId/publish" $null $token
Check 'publish.twice.409' $r.Status 409

# a second template cannot be published while one is active (DB unique index -> 409)
$r = Invoke-Api 'POST' '/api/v2/insurance/export/templates' @{
    code = "PROV2-$suffix"; name = 'second template'; fields = $fields
} $token
Check 'create.second' $r.Status 200
$secondId = (Res $r).id
$r = Invoke-Api 'POST' "/api/v2/insurance/export/templates/$secondId/publish" $null $token
Check 'publish.second.409' $r.Status 409

# published template is immutable
$r = Invoke-Api 'PUT' "/api/v2/insurance/export/templates/$tplId" @{
    name = 'mutate published'; fields = $fields; rowVersion = 2
} $token
Check 'update.published.409' $r.Status 409

# list must show the active template
$r = Invoke-Api 'GET' '/api/v2/insurance/export/templates?limit=100' $null $token
Check 'list.active.exists' (@((Res $r) | Where-Object { $_.code -eq $tplCode -and $_.status -eq 'PUBLISHED' }).Count) 1

# ---------------------------------------------------------------- 7. permission split
$r = Invoke-Api 'GET' '/api/v2/insurance/export/templates' $null $doctorToken
Check 'read.doctor.403' $r.Status 403

# ---------------------------------------------------------------- 8. disable
$r = Invoke-Api 'POST' "/api/v2/insurance/export/templates/$tplId/disable" $null $token
Check 'disable' $r.Status 200
Check 'disable.status' (Res $r).status 'DISABLED'
Check 'disable.version' (Res $r).version 1

$r = Invoke-Api 'POST' "/api/v2/insurance/export/templates/$tplId/disable" $null $token
Check 'disable.twice.409' $r.Status 409

$r = Invoke-Api 'GET' "/api/v2/insurance/export/templates/$tplId" $null $token
Check 'detail.disabled' (Res $r).status 'DISABLED'

Write-Output "----------------------------------------"
if ($script:fail -eq 0) {
    Write-Output 'RESULT PASS insurance_export_template_smoke'
    exit 0
} else {
    Write-Output "RESULT FAIL insurance_export_template_smoke ($script:fail)"
    exit 1
}
