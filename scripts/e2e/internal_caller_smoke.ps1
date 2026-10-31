# ============================================================================
# MedAssist internal caller signature (MF-07 T5) smoke test.
#
# Verifies the service-to-service channel on emr (:8086):
#   - unsigned /internal/**               -> 403
#   - valid caller signature (v1i)        -> 200
#   - wrong caller secret                 -> 403 (bad_sig)
#   - unknown caller                      -> 403 (unknown_caller)
#   - replayed nonce                      -> 403 (replay)
#   - signature bound to another path     -> 403 (bad_sig)
#   - rejection counted in metrics
#
# ASCII only (Windows PowerShell 5.1 reads BOM-less UTF-8 as ANSI).
#
# Prerequisites: emr :8086 running with
#     MEDASSIST_INTERNALAUTH_INTERNALPATHSSIGNATUREREQUIRED=true
#     MEDASSIST_INTERNALAUTH_KEYSJSON={"...","internal":{"clinical":"<base64>"}}
#   The CLINICAL_KEY below must match the "clinical" entry in that secret.
#
# Usage:
#   powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\e2e\internal_caller_smoke.ps1
# ============================================================================
param(
    [string]$EmrUrl = "http://127.0.0.1:8086",
    [string]$Caller = "clinical",
    [string]$CallerKey = "bWVkYXNzaXN0LWRldi1jbGluaWNhbC0tLS0tLS0tLS0=",
    [string]$WrongKey = "bWVkYXNzaXN0LWRldi1lbXItLS0tLS0tLS0tLS0tLS0="
)

$ErrorActionPreference = "Stop"
$script:fail = 0

function Invoke-Curl {
    param([string]$Method, [string]$Url, [hashtable]$Headers = @{})
    $out = [IO.Path]::GetTempFileName()
    $args = @('--noproxy', '*', '-s', '-o', $out, '-w', '%{http_code}', '-X', $Method, $Url)
    foreach ($k in $Headers.Keys) { $args += @('-H', "$k`:$($Headers[$k])") }
    $code = & curl.exe @args
    $text = Get-Content -Raw -Path $out -Encoding UTF8
    Remove-Item $out -Force
    return [pscustomobject]@{ Status = [int]$code; Body = $text }
}

function Check($name, $actual, $expected) {
    if ("$actual" -eq "$expected") { Write-Output "PASS $name = $actual" }
    else { Write-Output "FAIL $name expected=$expected actual=$actual"; $script:fail++ }
}

function New-SignatureHeaders {
    param([string]$Method, [string]$Path, [string]$CallerId, [string]$Key,
          [long]$Timestamp, [string]$Nonce)
    $canonical = @(
        'v1i',
        $Method.ToUpper(),
        $Path,
        '',
        $CallerId,
        "$Timestamp",
        $Nonce,
        '-'
    ) -join "`n"
    $hmac = [System.Security.Cryptography.HMACSHA256]::new([Convert]::FromBase64String($Key))
    $hash = $hmac.ComputeHash([Text.Encoding]::UTF8.GetBytes($canonical))
    $sig = [Convert]::ToBase64String($hash).TrimEnd('=').Replace('+', '-').Replace('/', '_')
    return @{
        'X-Internal-Sig-Ver' = 'v1i'
        'X-Internal-Caller'  = $CallerId
        'X-Internal-Ts'      = "$Timestamp"
        'X-Internal-Nonce'   = $Nonce
        'X-Internal-Sig'     = $sig
    }
}

$ts = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
$nonce = "caller-$([Guid]::NewGuid().ToString('N').Substring(0,16))"

# ---------------------------------------------------------------- 1. unsigned
$r = Invoke-Curl 'GET' "$EmrUrl/internal/health"
Check 'caller.unsigned' $r.Status 403

# ---------------------------------------------------------------- 2. valid signature
$h = New-SignatureHeaders 'GET' '/internal/health' $Caller $CallerKey $ts $nonce
$r = Invoke-Curl 'GET' "$EmrUrl/internal/health" $h
Check 'caller.valid' $r.Status 200

# ---------------------------------------------------------------- 3. replay (same nonce+ts)
$r = Invoke-Curl 'GET' "$EmrUrl/internal/health" $h
Check 'caller.replay' $r.Status 403

# ---------------------------------------------------------------- 4. wrong secret
$h = New-SignatureHeaders 'GET' '/internal/health' $Caller $WrongKey $ts "wrong-$nonce"
$r = Invoke-Curl 'GET' "$EmrUrl/internal/health" $h
Check 'caller.wrongKey' $r.Status 403

# ---------------------------------------------------------------- 5. unknown caller
$h = New-SignatureHeaders 'GET' '/internal/health' 'billing' $CallerKey $ts "unknown-$nonce"
$r = Invoke-Curl 'GET' "$EmrUrl/internal/health" $h
Check 'caller.unknown' $r.Status 403

# ---------------------------------------------------------------- 6. signature bound to another path
$h = New-SignatureHeaders 'GET' '/internal/other' $Caller $CallerKey $ts "path-$nonce"
$r = Invoke-Curl 'GET' "$EmrUrl/internal/health" $h
Check 'caller.pathBound' $r.Status 403

# ---------------------------------------------------------------- 7. metrics
$r = Invoke-Curl 'GET' "$EmrUrl/actuator/prometheus"
Check 'caller.metrics' ($r.Body -match 'medassist_internal_auth_total') 'True'
Check 'caller.metricsRejected' ($r.Body -match 'result="bad_sig"|result="replay"|result="unknown_caller"') 'True'

if ($script:fail -gt 0) { Write-Output "FAILED: $script:fail assertion(s)"; exit 1 }
Write-Output "ALL PASSED"
