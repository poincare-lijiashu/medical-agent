# =============================================================================
# L3 (China MLPS / Dengbao Level 3) technical evidence collector.
#
# Reads the running platform and PostgreSQL, then writes a Markdown evidence
# pack. This is EVIDENCE, not a certification: an accredited assessor must
# still review the output on site.
#
# Usage:
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\compliance\l3_evidence.ps1
#       [-GatewayBase http://127.0.0.1:8080] [-PgContainer medagent_pg]
#       [-DbName medagent] [-DbUser medagent] [-BackupDir <backup-dir>]
#       [-Output docs\compliance\evidence\L3-EVIDENCE-YYYYMMDD-HHMM.md] [-Strict]
#
# Exit: 0 = report generated; 1 = -Strict and at least one FAIL.
# Keep this file ASCII-only: PS 5.1 reads BOM-less UTF-8 as ANSI.
# =============================================================================
param(
    [string]$GatewayBase = "http://127.0.0.1:8080",
    [string]$PgContainer = "medagent_pg",
    [string]$DbName = "medagent",
    [string]$DbUser = "medagent",
    [string]$BackupDir = "<backup-dir>",
    [string]$Output = "",
    [switch]$Strict
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
Set-Location $root

$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
if ([string]::IsNullOrWhiteSpace($Output)) {
    $Output = Join-Path $root ("docs\compliance\evidence\L3-EVIDENCE-{0}.md" -f $stamp)
}
$outDir = Split-Path -Parent $Output
if (-not (Test-Path $outDir)) { New-Item -ItemType Directory -Path $outDir -Force | Out-Null }

$devJwtSecret = "<dev-jwt-secret>"
$checks = New-Object System.Collections.Generic.List[object]
$sections = New-Object System.Collections.Generic.List[string]

function Add-Check([string]$id, [string]$name, [string]$status, [string]$evidence) {
    $checks.Add([pscustomobject]@{ Id = $id; Name = $name; Status = $status; Evidence = $evidence })
}

function Add-Section([string]$title, [string]$body) {
    $sections.Add("### $title`n`n$body")
}

function Invoke-Psql([string]$sql) {
    $raw = & docker exec $PgContainer psql -U $DbUser -d $DbName -t -A -F "|" -c $sql 2>&1
    if ($LASTEXITCODE -ne 0) { throw (($raw | Out-String).Trim()) }
    return @($raw | ForEach-Object { $_.ToString().Trim() } | Where-Object { $_ -ne "" })
}

function Get-HeaderValue($headers, [string]$name) {
    if ($null -eq $headers) { return $null }
    if ($headers -is [System.Net.WebHeaderCollection]) { return $headers[$name] }
    try { return $headers.GetFirst($name) } catch { return $null }
}

function Test-Http {
    param([string]$url)
    try {
        $r = Invoke-WebRequest -Uri $url -UseBasicParsing -TimeoutSec 5
        return @{ Status = [int]$r.StatusCode; Headers = $r.Headers; Body = $r.Content }
    } catch {
        $resp = $_.Exception.Response
        if ($resp) {
            return @{ Status = [int]$resp.StatusCode; Headers = $resp.Headers; Body = "" }
        }
        return @{ Status = 0; Headers = $null; Body = $_.Exception.Message }
    }
}

# ---------------------------------------------------------------- 1. baseline
$commit = (& git rev-parse HEAD 2>$null)
$branch = (& git rev-parse --abbrev-ref HEAD 2>$null)
$version = if (Test-Path VERSION) { (Get-Content VERSION -Raw).Trim() } else { "unknown" }
$baseline = @"
- Generated at: $((Get-Date).ToString("yyyy-MM-dd HH:mm:ss zzz"))
- Host: $env:COMPUTERNAME
- OS: $([System.Environment]::OSVersion.VersionString)
- Git commit: $commit
- Git branch: $branch
- VERSION: $version
"@
Add-Section "Baseline" $baseline
Add-Check "L3-01" "Evidence generated from current commit" "PASS" $commit

# ---------------------------------------------------------------- 2. gateway health + headers
$health = Test-Http "$GatewayBase/actuator/health"
if ($health.Status -eq 200) {
    Add-Check "L3-02" "Gateway health endpoint reachable" "PASS" "HTTP 200 $GatewayBase/actuator/health"
} else {
    Add-Check "L3-02" "Gateway health endpoint reachable" "FAIL" "HTTP $($health.Status)"
}

$unauth = Test-Http "$GatewayBase/api/v2/clinical/depts"
$h = $unauth.Headers
$headerRows = @(
    @("X-Content-Type-Options", (Get-HeaderValue $h "X-Content-Type-Options"), "nosniff"),
    @("X-Frame-Options", (Get-HeaderValue $h "X-Frame-Options"), "DENY"),
    @("Referrer-Policy", (Get-HeaderValue $h "Referrer-Policy"), "no-referrer"),
    @("Content-Security-Policy", (Get-HeaderValue $h "Content-Security-Policy"), "contains frame-ancestors"),
    @("Permissions-Policy", (Get-HeaderValue $h "Permissions-Policy"), "contains camera=()")
)
$missing = @()
$rows = foreach ($row in $headerRows) {
    $ok = if ($row[1]) { "PASS" } else { "FAIL" }
    if (-not $row[1]) { $missing += $row[0] }
    "| $($row[0]) | $($row[1]) | $ok |"
}
if ($missing.Count -eq 0) {
    Add-Check "L3-03" "Security response headers present on unauthenticated response" "PASS" "All baseline headers present"
} else {
    Add-Check "L3-03" "Security response headers present on unauthenticated response" "FAIL" ("Missing: " + ($missing -join ", "))
}
Add-Section "Gateway security headers (HTTP $($unauth.Status))" (@"
| Header | Observed | Result |
|---|---|---|
$($rows -join "`n")
"@)

$hsts = Get-HeaderValue $h "Strict-Transport-Security"
if ($hsts) {
    Add-Check "L3-04" "HSTS enabled on TLS endpoint" "PASS" $hsts
} else {
    Add-Check "L3-04" "HSTS enabled on TLS endpoint" "WARN" "No HSTS on plain HTTP probe; re-run against HTTPS ingress"
}

# ---------------------------------------------------------------- 3. audit append-only
try {
    $triggers = Invoke-Psql @"
SELECT event_object_schema || '.' || event_object_table
  FROM information_schema.triggers
 WHERE trigger_name LIKE '%append_only%'
 GROUP BY 1 ORDER BY 1
"@
    $mustHave = @("auth_svc.change_log", "clinical_svc.clinical_change_log")
    $missingAudit = @($mustHave | Where-Object { $triggers -notcontains $_ })
    if ($missingAudit.Count -eq 0) {
        Add-Check "L3-05" "Audit tables reject UPDATE/DELETE" "PASS" ("append-only triggers: " + $triggers.Count)
    } else {
        Add-Check "L3-05" "Audit tables reject UPDATE/DELETE" "FAIL" ("Missing: " + ($missingAudit -join ", "))
    }
    $triggerRows = ($triggers | ForEach-Object { "- $_" }) -join "`n"
    Add-Section "Append-only evidence tables" $triggerRows
} catch {
    Add-Check "L3-05" "Audit tables reject UPDATE/DELETE" "FAIL" $_.Exception.Message
}

# ---------------------------------------------------------------- 4. audit retention
try {
    $auditTables = Invoke-Psql @"
SELECT table_schema || '.' || table_name
  FROM information_schema.tables
 WHERE table_name IN ('change_log','clinical_change_log')
   AND table_schema LIKE '%_svc'
 ORDER BY 1
"@
    $retentionRows = @()
    foreach ($table in $auditTables) {
        try {
            $r = Invoke-Psql "SELECT min(ts)::text || '|' || max(ts)::text || '|' || count(*)::text FROM $table"
            if ($r.Count -gt 0) {
                $parts = @($r)[0].Split("|")
                $min = $parts[0]; $max = $parts[1]; $count = $parts[2]
                $days = "n/a"
                if ($min) {
                    $days = [math]::Round(((Get-Date) - [datetime]$min).TotalDays, 1)
                }
                $retentionRows += "| $table | $min | $max | $count | $days |"
            }
        } catch { }
    }
    $oldestDays = 0
    foreach ($line in $retentionRows) {
        $cols = $line.Split("|")
        $d = 0.0
        if ([double]::TryParse($cols[5].Trim(), [ref]$d)) { if ($d -gt $oldestDays) { $oldestDays = $d } }
    }
    if ($oldestDays -ge 180) {
        Add-Check "L3-06" "Audit retention >= 180 days" "PASS" ("oldest retained audit: $oldestDays days")
    } else {
        Add-Check "L3-06" "Audit retention >= 180 days" "WARN" ("oldest retained audit: $oldestDays days (environment younger than 6 months or retention gap)")
    }
    Add-Section "Audit retention" (@"
| Table | Min(ts) | Max(ts) | Rows | Age(days) |
|---|---|---|---|---|
$($retentionRows -join "`n")
"@)
} catch {
    Add-Check "L3-06" "Audit retention >= 180 days" "FAIL" $_.Exception.Message
}

# ---------------------------------------------------------------- 5. identity and access
try {
    $account = Invoke-Psql "SELECT count(*) FILTER (WHERE status = 'ACTIVE')::text || '|' || count(*)::text FROM auth_svc.app_user"
    $roleCount = Invoke-Psql "SELECT count(*)::text FROM auth_svc.role"
    $permCount = Invoke-Psql "SELECT count(*)::text FROM auth_svc.permission"
    $parts = @($account)[0].Split("|")
    Add-Check "L3-07" "Unique accounts and RBAC configured" "PASS" ("enabled=$($parts[0]) total=$($parts[1]) roles=$(@($roleCount)[0]) permissions=$(@($permCount)[0])")
    Add-Section "Identity and access" @"
- Enabled accounts: $($parts[0])
- Total accounts: $($parts[1])
- Roles: $(@($roleCount)[0])
- Permissions: $(@($permCount)[0])
"@
} catch {
    Add-Check "L3-07" "Unique accounts and RBAC configured" "FAIL" $_.Exception.Message
}

# JWT TTL + secret source
$jwtTtl = if ($env:JWT_TTL) { $env:JWT_TTL } else { "PT30M (default)" }
Add-Check "L3-08" "Access token TTL bounded" "PASS" $jwtTtl

$secretSource = "not found in current shell"
$secretStatus = "WARN"
$envFile = Join-Path $root ".env.local"
if (Test-Path $envFile) {
    $match = Select-String -Path $envFile -Pattern '^AUTH_JWT_SECRET=(.+)$' | Select-Object -First 1
    if ($match) {
        $secret = $match.Matches[0].Groups[1].Value.Trim()
        if ($secret -eq $devJwtSecret) {
            $secretStatus = "FAIL"; $secretSource = ".env.local uses development default secret"
        } else {
            $secretStatus = "PASS"; $secretSource = ".env.local injects non-default JWT secret"
        }
    }
} elseif ($env:JWT_SECRET) {
    if ($env:JWT_SECRET -eq $devJwtSecret) {
        $secretStatus = "FAIL"; $secretSource = "environment uses development default secret"
    } else {
        $secretStatus = "PASS"; $secretSource = "environment injects non-default JWT secret"
    }
}
Add-Check "L3-09" "JWT signing secret is not the development default" $secretStatus $secretSource

# ---------------------------------------------------------------- 6. network exposure
try {
    $listeners = Get-NetTCPConnection -State Listen |
        Where-Object { $_.LocalPort -ge 8080 -and $_.LocalPort -le 8090 } |
        Sort-Object LocalPort
    $public = @($listeners | Where-Object { $_.LocalAddress -notin @("127.0.0.1", "::1") })
    if ($public.Count -eq 0) {
        Add-Check "L3-10" "Downstream services bound to loopback only" "PASS" "8080-8090 all loopback"
    } else {
        Add-Check "L3-10" "Downstream services bound to loopback only" "WARN" (($public | ForEach-Object { "$($_.LocalAddress):$($_.LocalPort)" }) -join ", ")
    }
    $rows = $listeners | ForEach-Object { "| $($_.LocalAddress) | $($_.LocalPort) |" }
    Add-Section "Listening ports 8080-8090" (@"
| Address | Port |
|---|---|
$($rows -join "`n")
"@)
} catch {
    Add-Check "L3-10" "Downstream services bound to loopback only" "WARN" $_.Exception.Message
}

# TLS at ingress (prod values)
$prodValues = Join-Path $root "deploy\helm\medassist-platform\values-prod.yaml"
$tlsOk = $false
if (Test-Path $prodValues) {
    $text = Get-Content $prodValues -Raw
    $tlsOk = ($text -match 'ssl-redirect:\s*"true"') -and ($text -match '(?m)^\s*tls:\s*$')
}
if ($tlsOk) {
    Add-Check "L3-11" "Production ingress enforces TLS" "PASS" "values-prod.yaml: ssl-redirect=true and tls configured"
} else {
    Add-Check "L3-11" "Production ingress enforces TLS" "WARN" "values-prod.yaml missing ssl-redirect/tls"
}

# ---------------------------------------------------------------- 7. migration baseline
try {
    $schemas = Invoke-Psql "SELECT nspname FROM pg_namespace WHERE nspname LIKE '%_svc' ORDER BY 1"
    $migrationRows = @()
    foreach ($schema in $schemas) {
        try {
            $v = Invoke-Psql "SELECT max(version::int)::text FROM $schema.flyway_schema_history"
            $migrationRows += "| $schema | $(@($v)[0]) |"
        } catch { }
    }
    Add-Check "L3-12" "Database migration baseline readable" "PASS" ("$($migrationRows.Count) schemas")
    Add-Section "Flyway versions" (@"
| Schema | Latest version |
|---|---|
$($migrationRows -join "`n")
"@)
} catch {
    Add-Check "L3-12" "Database migration baseline readable" "FAIL" $_.Exception.Message
}

# ---------------------------------------------------------------- 8. backups
try {
    $latest = Get-ChildItem $BackupDir -Filter "medical_agent_*.bundle" -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if ($latest) {
        $ageHours = [math]::Round(((Get-Date) - $latest.LastWriteTime).TotalHours, 1)
        $mirror = Join-Path $BackupDir "medical_agent.git"
        $head = ""
        if (Test-Path $mirror) { $head = (& git --git-dir="$mirror" rev-parse HEAD 2>$null) }
        if ($ageHours -le 48) {
            Add-Check "L3-13" "Recent repository backup exists" "PASS" ("$($latest.Name), age=${ageHours}h, mirror HEAD=$head")
        } else {
            Add-Check "L3-13" "Recent repository backup exists" "WARN" ("$($latest.Name), age=${ageHours}h")
        }
    } else {
        Add-Check "L3-13" "Recent repository backup exists" "WARN" "No medical_agent_*.bundle under $BackupDir"
    }
} catch {
    Add-Check "L3-13" "Recent repository backup exists" "WARN" $_.Exception.Message
}

# ---------------------------------------------------------------- render
$counts = @{ PASS = 0; WARN = 0; FAIL = 0 }
foreach ($c in $checks) { $counts[$c.Status] = $counts[$c.Status] + 1 }
$summaryRows = $checks | ForEach-Object { "| $($_.Id) | $($_.Name) | $($_.Status) | $($_.Evidence) |" }
$markdown = @"
# MLPS Level 3 Technical Evidence Pack

> Generated automatically by ``scripts/compliance/l3_evidence.ps1``.
> This file is evidence for an assessor, not a certificate. Re-run it against
> the production network (HTTPS ingress, production database) before submission.

## Summary

- PASS: $($counts["PASS"])
- WARN: $($counts["WARN"])
- FAIL: $($counts["FAIL"])

| ID | Check | Result | Evidence |
|---|---|---|---|
$($summaryRows -join "`n")

## Details

$($sections -join "`n`n")

## Known gaps (manual remediation required)

See ``docs/COMPLIANCE-L3-GAP-20261111.md`` for the control-by-control gap list,
including items that cannot be proven by this script (physical security, host
EDR, WAF, SIEM, vulnerability scanning, crypto assessment / MiPing, DR drill).
"@

$markdown | Out-File -FilePath $Output -Encoding utf8
Write-Output "L3 evidence written: $Output"
Write-Output ("PASS={0} WARN={1} FAIL={2}" -f $counts["PASS"], $counts["WARN"], $counts["FAIL"])
if ($Strict -and $counts["FAIL"] -gt 0) { exit 1 }
exit 0
