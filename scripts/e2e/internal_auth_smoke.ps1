# ============================================================================
# MedAssist internal-auth (MF-07) smoke test: forged identity headers must be
# rejected when hitting a business port directly, while gateway traffic (signed)
# still works. Also verifies client-supplied signature/permission headers are
# stripped at the gateway and that rejections are counted in metrics.
#
# ASCII only (Windows PowerShell 5.1 reads BOM-less UTF-8 as ANSI).
#
# Prerequisites:
#   - gateway :8080 and emr :8086 running with
#       MEDASSIST_INTERNALAUTH_MODE=enforce
#       MEDASSIST_INTERNALAUTH_KEYSJSON={"active":"gw-dev","keys":{"gw-dev":"<base64 32B>"}}
#     (the same keys-json on both sides; note Spring drops hyphens in env var names,
#      so it is MEDASSIST_INTERNALAUTH_* and NOT MEDASSIST_INTERNAL_AUTH_*)
#   - auth :8081, empi :8082 are up
#
# Usage:
#   powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\e2e\internal_auth_smoke.ps1
# ============================================================================
param(
    [string]$BaseUrl = "http://127.0.0.1:8080",
    [string]$EmrUrl = "http://127.0.0.1:8086",
    [string]$Username = "admin01",
    [string]$Password = "Med@2026"
)

$ErrorActionPreference = "Stop"
$script:fail = 0

function Invoke-Curl {
    param([string]$Method, [string]$Url, $Body = $null,
          [hashtable]$Headers = @{}, [string]$Token = $null)
    $out = [IO.Path]::GetTempFileName()
    $args = @('--noproxy', '*', '-s', '-o', $out, '-w', '%{http_code}', '-X', $Method, $Url)
    if ($Token) { $args += @('-H', "Authorization: Bearer $Token") }
    foreach ($k in $Headers.Keys) { $args += @('-H', "$k`:$($Headers[$k])") }
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

# ---------------------------------------------------------------- 1. login via gateway
$r = Invoke-Curl 'POST' "$BaseUrl/api/v2/auth/login" @{ username = $Username; password = $Password }
Check 'login' $r.Status 200
$token = (Res $r).accessToken
if (-not $token) { Write-Output "FATAL no token: $($r.Body)"; exit 1 }

# ---------------------------------------------------------------- 2. gateway traffic is signed and works
$r = Invoke-Curl 'GET' "$BaseUrl/api/v2/emr/templates" $null @{} $token
Check 'gateway.templates' $r.Status 200

# client-forged internal signature must not disturb gateway signing
$r = Invoke-Curl 'GET' "$BaseUrl/api/v2/emr/templates" $null @{
    'X-Internal-Sig' = 'forged'; 'X-Internal-Key-Id' = 'attacker'; 'X-Internal-Sig-Ver' = 'v0'
} $token
Check 'gateway.forgedSigIgnored' $r.Status 200

# ---------------------------------------------------------------- 3. direct access must be rejected (enforce)
$r = Invoke-Curl 'GET' "$EmrUrl/emr/templates" $null @{ 'X-Permissions' = 'emr:record:read' }
Check 'direct.forgedPermissions' $r.Status 403
Check 'direct.forgedPermissions.code' ((($r.Body | ConvertFrom-Json).code)) 40300

$r = Invoke-Curl 'GET' "$EmrUrl/emr/templates"
Check 'direct.noHeaders' $r.Status 403

$r = Invoke-Curl 'GET' "$EmrUrl/emr/templates" $null @{
    'X-Actor' = 'attacker'; 'X-Permissions' = 'emr:record:read'
    'X-Internal-Sig-Ver' = 'v1'; 'X-Internal-Key-Id' = 'k1'
    'X-Internal-Ts' = ([DateTimeOffset]::UtcNow.ToUnixTimeSeconds())
    'X-Internal-Nonce' = 'forged-nonce'; 'X-Internal-Sig' = 'AAAA'
}
Check 'direct.forgedSignature' $r.Status 403

# exempt probe path stays reachable (no signature)
$r = Invoke-Curl 'GET' "$EmrUrl/actuator/health"
Check 'direct.healthExempt' $r.Status 200

# ---------------------------------------------------------------- 4. metrics
$r = Invoke-Curl 'GET' "$EmrUrl/actuator/prometheus"
Check 'direct.prometheus' $r.Status 200
Check 'metrics.internalAuth' ($r.Body -match 'medassist_internal_auth_total') 'True'
Check 'metrics.rejected' ($r.Body -match 'result="missing"|result="bad_sig"') 'True'

# ---------------------------------------------------------------- 5. client cannot self-grant permissions via gateway
$r = Invoke-Curl 'POST' "$BaseUrl/api/v2/auth/login" @{ username = 'pharm01'; password = 'Med@2026' }
if ($r.Status -eq 200) {
    $pharmToken = (Res $r).accessToken
    $r = Invoke-Curl 'GET' "$BaseUrl/api/v2/emr/templates" $null @{ 'X-Permissions' = 'emr:record:read' } $pharmToken
    Check 'gateway.selfGrantBlocked' $r.Status 403
} else {
    Write-Output "SKIP pharmacist login (status=$($r.Status))"
}

if ($script:fail -gt 0) { Write-Output "FAILED: $script:fail assertion(s)"; exit 1 }
Write-Output "ALL PASSED"
