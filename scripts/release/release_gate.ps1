# Release gate for the Java platform (G43).
# ASCII only: Windows PowerShell 5.1 reads BOM-less UTF-8 as ANSI.
#
# Usage:
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\release\release_gate.ps1
#   ... -BaseUrl http://127.0.0.1:8080 -Username admin01 -Password Med@2026
#
# Exit code 0 = gate passed; 1 = at least one check failed (safe to roll back).
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

Write-Output "release_gate base=$BaseUrl user=$Username"

# 1) gateway liveness (no token)
$r = Invoke-Api 'GET' '/actuator/health' $null $null
Check 'gateway.health.status' $r.Status 200
Check 'gateway.health.up' ($r.Body -match '"status":"UP"') 'True'

# 2) authentication and identity
$r = Invoke-Api 'POST' '/api/v2/auth/login' @{ username = $Username; password = $Password } $null $null
Check 'auth.login' $r.Status 200
$token = (Res $r).accessToken
Check 'auth.login.token' ($token.Length -gt 20) 'True'
if (-not $token) { Write-Output 'RESULT FAIL'; exit 1 }

$r = Invoke-Api 'GET' '/api/v2/auth/me' $null $token
Check 'auth.me' $r.Status 200
Check 'auth.me.user' (Res $r).username $Username

# 3) cross-domain read probes (EMPI / clinical / EMR / transfusion / tech)
$reads = @(
    @{ name = 'empi.patients'; path = '/api/v2/empi/patients?patientNo=P2026&page=1&size=1' },
    @{ name = 'clinical.departments'; path = '/api/v2/clinical/departments' },
    @{ name = 'emr.ca.board'; path = '/api/v2/emr/ca/board' },
    @{ name = 'clinical.transfusion.board'; path = '/api/v2/clinical/transfusion/board' },
    @{ name = 'tech.summary'; path = '/api/v2/tech/applications/summary' }
)
foreach ($probe in $reads) {
    $r = Invoke-Api 'GET' $probe.path $null $token
    Check $probe.name $r.Status 200
    Check ($probe.name + '.json') ($r.Body -match '"code":0') 'True'
}

# 4) authorization is still enforced (no token must be 401)
$r = Invoke-Api 'GET' '/api/v2/empi/patients?page=1&size=1' $null $null
Check 'authz.noToken.401' $r.Status 401

Write-Output "-----"
if ($script:fail -gt 0) { Write-Output "RESULT FAIL ($script:fail)"; exit 1 }
Write-Output 'RESULT PASS'
exit 0
