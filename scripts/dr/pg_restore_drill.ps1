# PostgreSQL backup/restore drill (G43). ASCII only.
#
# Proves that a full logical backup can be restored into a scratch database and that
# core business tables keep their row counts. Cleanup always drops the scratch DB.
#
# Usage:
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\dr\pg_restore_drill.ps1
#   ... -Container medagent_pg -Database medagent -Scratch medagent_drill
param(
    [string]$Container = "medagent_pg",
    [string]$Database = "medagent",
    [string]$DbUser = "medagent",
    [string]$DbPassword = "medagent123",
    [string]$Scratch = "medagent_drill"
)

$ErrorActionPreference = "Stop"
$script:fail = 0

$probes = @(
    'auth_svc.app_user',
    'empi_svc.patient',
    'clinical_svc.admission',
    'emr_svc.medical_record',
    'pharmacy_svc.prescription'
)

function Invoke-Psql {
    param([string]$Db, [string]$Sql)
    # psql writes NOTICE/DDL logs to stderr; PowerShell 5.1 turns that into an error
    # record when redirected, so relax the preference for the native call only.
    $prev = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $out = & docker exec -e "PGPASSWORD=$DbPassword" $Container psql -h 127.0.0.1 -U $DbUser -d $Db -t -A -c $Sql 2>&1
        $code = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $prev
    }
    if ($code -ne 0) { throw "psql failed on $Db :: $out" }
    return (($out | Where-Object { $_ -notmatch '^(NOTICE|WARNING):' }) | Out-String).Trim()
}

function Check($name, $actual, $expected) {
    if ("$actual" -eq "$expected") { Write-Output "PASS $name = $actual" }
    else { Write-Output "FAIL $name expected=$expected actual=$actual"; $script:fail++ }
}

$stamp = Get-Date -Format "yyyyMMddHHmmss"
$dumpPath = "/tmp/medagent_drill_$stamp.dump"

# 0) container must be running
$running = (& docker inspect -f '{{.State.Running}}' $Container 2>$null | Out-String).Trim()
Check 'container.running' $running 'true'
if ($running -ne 'true') { Write-Output 'RESULT FAIL'; exit 1 }

$sourceCounts = @{}
foreach ($table in $probes) {
    $sourceCounts[$table] = Invoke-Psql $Database "select count(*) from $table"
}

$restoreStopwatch = $null
$total = [Diagnostics.Stopwatch]::StartNew()
try {
    # 1) logical dump inside the container (binary format)
    $dumpSw = [Diagnostics.Stopwatch]::StartNew()
    & docker exec $Container pg_dump -U $DbUser -d $Database -Fc -f $dumpPath | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "pg_dump failed (exit $LASTEXITCODE)" }
    $dumpSw.Stop()
    Check 'dump.created' (Invoke-Psql 'postgres' "select case when exists (select 1 from pg_stat_file('$dumpPath')) then 'yes' else 'no' end") 'yes'
    Write-Output ("dump.size_bytes = {0}" -f (Invoke-Psql 'postgres' "select (pg_stat_file('$dumpPath')).size"))

    # 2) fresh scratch database
    Invoke-Psql 'postgres' "select pg_terminate_backend(pid) from pg_stat_activity where datname='$Scratch'" | Out-Null
    Invoke-Psql 'postgres' "drop database if exists $Scratch" | Out-Null
    Invoke-Psql 'postgres' "create database $Scratch" | Out-Null

    # 3) restore and verify counts (RTO starts when the dump is ready)
    $restoreStopwatch = [Diagnostics.Stopwatch]::StartNew()
    & docker exec $Container pg_restore -U $DbUser -d $Scratch --no-owner --no-privileges --exit-on-error $dumpPath | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "pg_restore failed (exit $LASTEXITCODE)" }

    foreach ($table in $probes) {
        $restored = Invoke-Psql $Scratch "select count(*) from $table"
        Check ("restored." + $table) $restored $sourceCounts[$table]
    }
    $restoreStopwatch.Stop()
    $total.Stop()

    Write-Output ("rto_restore_seconds = {0:N1}" -f $restoreStopwatch.Elapsed.TotalSeconds)
    Write-Output ("drill_total_seconds = {0:N1}" -f $total.Elapsed.TotalSeconds)
} finally {
    if ($restoreStopwatch -and $restoreStopwatch.IsRunning) { $restoreStopwatch.Stop() }
    try { Invoke-Psql 'postgres' "select pg_terminate_backend(pid) from pg_stat_activity where datname='$Scratch'" | Out-Null } catch { }
    try { Invoke-Psql 'postgres' "drop database if exists $Scratch" | Out-Null } catch { }
    try { & docker exec $Container rm -f $dumpPath | Out-Null } catch { }
}

Write-Output "-----"
if ($script:fail -gt 0) { Write-Output "RESULT FAIL ($script:fail)"; exit 1 }
Write-Output 'RESULT PASS'
exit 0
