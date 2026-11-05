# PostgreSQL backup/restore drill (G43; G63 rework per review 5.3). ASCII only.
#
# WHY THIS CHANGED:
#   The old drill produced a fresh pg_dump on the spot and restored that - it proved
#   "pg_dump works right now", not "the artifact kept in BACKUP_DIR can be restored".
#   Disk corruption, flag drift and accidental deletion of retained dumps were invisible.
#
# WHAT IT DOES NOW:
#   1) pick the NEWEST real retained artifact in -BackupDir (backup_*.tar.gz from
#      scripts/backup.py, or medagent-*.dump from the k8s CronJob), unless -Artifact is given;
#   2) verify integrity before touching the database:
#        - .tar.gz : required sidecar .sha256 must match;
#        - .dump   : pg_restore --list must parse it (and .sha256 is checked when present);
#   3) restore into a scratch database with the SAME flags production uses
#      (--no-owner --no-privileges --exit-on-error for custom format);
#   4) compare row counts of core tables, plus sequence and foreign-key counts;
#   5) always drop the scratch database and remove temp files.
#
# Usage:
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\dr\pg_restore_drill.ps1
#   ... -BackupDir D:\backups -Container medagent_pg -Database medagent
#   ... -Artifact D:\backups\medagent-20261105-020000.dump
param(
    [string]$BackupDir = "",
    [string]$Artifact = "",
    [string]$Container = "medagent_pg",
    [string]$Database = "medagent",
    [string]$DbUser = "medagent",
    [string]$DbPassword = "medagent123",
    [string]$Scratch = "medagent_drill"
)

$ErrorActionPreference = "Stop"
$script:fail = 0

# Core tables per service schema. Missing tables are SKIPped (older dumps).
$probes = @(
    'auth_svc.app_user',
    'auth_svc.login_attempt',
    'empi_svc.patient',
    'clinical_svc.visit',
    'clinical_svc.admission',
    'clinical_svc.charge',
    'emr_svc.medical_record',
    'emr_svc.record_homepage',
    'pharmacy_svc.prescription',
    'insurance_svc.settlement_bill',
    'material_svc.consumable_issue',
    'tech_svc.exam_application'
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

function Invoke-DockerInContainer {
    param([string[]]$DockerArgs)
    $prev = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $out = & docker @DockerArgs 2>&1
        $code = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $prev
    }
    return @{ Code = $code; Out = (($out | Out-String).Trim()) }
}

function Check($name, $actual, $expected) {
    if ("$actual" -eq "$expected") { Write-Output "PASS $name = $actual" }
    else { Write-Output "FAIL $name expected=$expected actual=$actual"; $script:fail++ }
}

function Skip($name, $reason) {
    Write-Output "SKIP $name :: $reason"
}

# ---------------------------------------------------------------- 0) container
$running = (& docker inspect -f '{{.State.Running}}' $Container 2>$null | Out-String).Trim()
Check 'container.running' $running 'true'
if ($running -ne 'true') { Write-Output 'RESULT FAIL'; exit 1 }

# ---------------------------------------------------------------- 1) pick artifact
if (-not $BackupDir) {
    $BackupDir = Join-Path (Join-Path $PSScriptRoot '..\..') 'data\backups'
}

$chosen = $null
if ($Artifact) {
    if (-not (Test-Path $Artifact)) { Write-Output "FAIL artifact.missing :: $Artifact"; Write-Output 'RESULT FAIL'; exit 1 }
    $chosen = Get-Item $Artifact
} else {
    if (-not (Test-Path $BackupDir)) {
        Write-Output "FAIL backup.dir.missing :: $BackupDir (run scripts/backup.py first)"
        Write-Output 'RESULT FAIL'
        exit 1
    }
    $candidates = @()
    $candidates += Get-ChildItem -Path $BackupDir -Filter 'backup_*.tar.gz' -File -ErrorAction SilentlyContinue
    $candidates += Get-ChildItem -Path $BackupDir -Filter '*.dump' -File -ErrorAction SilentlyContinue
    if ($candidates.Count -eq 0) {
        Write-Output "FAIL backup.artifact.missing :: no backup_*.tar.gz or *.dump in $BackupDir"
        Write-Output 'RESULT FAIL'
        exit 1
    }
    $chosen = ($candidates | Sort-Object LastWriteTime -Descending | Select-Object -First 1)
}
Write-Output ("artifact = {0}" -f $chosen.FullName)
Write-Output ("artifact.size_bytes = {0}" -f $chosen.Length)
Write-Output ("artifact.age_hours = {0:N1}" -f ((Get-Date) - $chosen.LastWriteTime).TotalHours)

# ---------------------------------------------------------------- 2) integrity
$isArchive = $chosen.Name -like '*.tar.gz'
$shaFile = Join-Path $chosen.DirectoryName ($chosen.Name + '.sha256')

if ($isArchive) {
    if (-not (Test-Path $shaFile)) {
        Write-Output "FAIL artifact.sha256.missing :: $shaFile"
        Write-Output 'RESULT FAIL'
        exit 1
    }
    $expected = ((Get-Content $shaFile -Raw).Trim() -split '\s+')[0]
    $actual = (Get-FileHash -Path $chosen.FullName -Algorithm SHA256).Hash.ToLower()
    Check 'artifact.sha256' $actual $expected

    # extract data/pg_dump.sql from the tar (host side)
    $work = Join-Path ([System.IO.Path]::GetTempPath()) ("medagent_drill_" + [Guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $work -Force | Out-Null
    tar -xzf $chosen.FullName -C $work "data/pg_dump.sql"
    if ($LASTEXITCODE -ne 0) { Write-Output "FAIL artifact.extract :: tar exit $LASTEXITCODE"; Write-Output 'RESULT FAIL'; exit 1 }
    $sqlPath = Join-Path $work 'data/pg_dump.sql'
    if (-not (Test-Path $sqlPath)) { Write-Output 'FAIL artifact.sql.missing :: data/pg_dump.sql not in archive'; Write-Output 'RESULT FAIL'; exit 1 }
    Write-Output ("artifact.sql_size_bytes = {0}" -f (Get-Item $sqlPath).Length)
} else {
    $sqlPath = $null
    $work = $null
    if (Test-Path $shaFile) {
        $expected = ((Get-Content $shaFile -Raw).Trim() -split '\s+')[0]
        $actual = (Get-FileHash -Path $chosen.FullName -Algorithm SHA256).Hash.ToLower()
        Check 'artifact.sha256' $actual $expected
    } else {
        Skip 'artifact.sha256' 'no sidecar checksum (CronJob dump)'
    }
}

$cleanupPaths = @()
if ($work) { $cleanupPaths += $work }

$total = [Diagnostics.Stopwatch]::StartNew()
$restoreStopwatch = $null
try {
    # ------------------------------------------------------------ 3) scratch db
    Invoke-Psql 'postgres' "select pg_terminate_backend(pid) from pg_stat_activity where datname='$Scratch'" | Out-Null
    Invoke-Psql 'postgres' "drop database if exists $Scratch" | Out-Null
    Invoke-Psql 'postgres' "create database $Scratch" | Out-Null

    $stamp = Get-Date -Format 'yyyyMMddHHmmss'

    # ------------------------------------------------------------ 4) restore
    $restoreStopwatch = [Diagnostics.Stopwatch]::StartNew()
    if ($isArchive) {
        # plain SQL: psql stops at first error
        $containerSql = "/tmp/drill_$stamp.sql"
        & docker cp $sqlPath "${Container}:$containerSql" | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "docker cp failed (exit $LASTEXITCODE)" }
        $cleanupPaths += $containerSql
        $r = Invoke-DockerInContainer @('exec', '-e', "PGPASSWORD=$DbPassword", $Container,
            'psql', '-h', '127.0.0.1', '-U', $DbUser, '-d', $Scratch, '-v', 'ON_ERROR_STOP=1', '-q', '-f', $containerSql)
        if ($r.Code -ne 0) { throw "psql restore failed (exit $($r.Code)) :: $($r.Out)" }
    } else {
        # custom format: same flags as production CronJob restore path
        $containerDump = "/tmp/drill_$stamp.dump"
        & docker cp $chosen.FullName "${Container}:$containerDump" | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "docker cp failed (exit $LASTEXITCODE)" }
        $cleanupPaths += $containerDump
        $list = Invoke-DockerInContainer @('exec', $Container, 'pg_restore', '--list', $containerDump)
        if ($list.Code -ne 0) { throw "pg_restore --list failed (artifact corrupt) :: $($list.Out)" }
        Check 'artifact.pg_restore_list' 'ok' 'ok'
        $r = Invoke-DockerInContainer @('exec', '-e', "PGPASSWORD=$DbPassword", $Container,
            'pg_restore', '-h', '127.0.0.1', '-U', $DbUser, '-d', $Scratch,
            '--no-owner', '--no-privileges', '--exit-on-error', $containerDump)
        if ($r.Code -ne 0) { throw "pg_restore failed (exit $($r.Code)) :: $($r.Out)" }
    }
    $restoreStopwatch.Stop()

    # ------------------------------------------------------------ 5) verify
    foreach ($table in $probes) {
        $exists = Invoke-Psql $Scratch "select to_regclass('$table') is not null"
        if ($exists -ne 't') { Skip ("restored." + $table) 'table not present in artifact'; continue }
        $src = Invoke-Psql $Database "select count(*) from $table"
        $dst = Invoke-Psql $Scratch "select count(*) from $table"
        Check ("restored." + $table) $dst $src
    }

    # 序列与约束：证明结构也一致，而不只是行数
    $seqSql = "select count(*) from information_schema.sequences where sequence_schema like '%\_svc'"
    Check 'schema.sequences' (Invoke-Psql $Scratch $seqSql) (Invoke-Psql $Database $seqSql)

    $fkSql = "select count(*) from information_schema.table_constraints where constraint_type = 'FOREIGN KEY' and table_schema like '%\_svc'"
    Check 'schema.foreign_keys' (Invoke-Psql $Scratch $fkSql) (Invoke-Psql $Database $fkSql)

    $total.Stop()
    Write-Output ("rto_restore_seconds = {0:N1}" -f $restoreStopwatch.Elapsed.TotalSeconds)
    Write-Output ("drill_total_seconds = {0:N1}" -f $total.Elapsed.TotalSeconds)
} finally {
    if ($restoreStopwatch -and $restoreStopwatch.IsRunning) { $restoreStopwatch.Stop() }
    try { Invoke-Psql 'postgres' "select pg_terminate_backend(pid) from pg_stat_activity where datname='$Scratch'" | Out-Null } catch { }
    try { Invoke-Psql 'postgres' "drop database if exists $Scratch" | Out-Null } catch { }
    foreach ($p in $cleanupPaths) {
        try {
            if ($p -like '/*') { & docker exec $Container rm -f $p | Out-Null }
            elseif (Test-Path $p) { Remove-Item -Recurse -Force $p }
        } catch { }
    }
}

Write-Output "-----"
if ($script:fail -gt 0) { Write-Output "RESULT FAIL ($script:fail)"; exit 1 }
Write-Output 'RESULT PASS'
exit 0
