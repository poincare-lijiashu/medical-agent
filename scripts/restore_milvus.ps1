# Milvus triple-volume restore (from a backup_milvus.ps1 snapshot)
#
# Guardrails (learned from the 2026-10-30 incident):
# 1) Validate snapshot dir and all three packs BEFORE touching any volume; abort on miss
# 2) Wipe + unpack inside ONE docker run; the whole sh -c payload is single-quoted
# 3) Stop milvus AND etcd/minio first so nothing writes during the swap
# 4) Print post-restore verification hints (collection stats + eval baseline)
#
# Usage:
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts/restore_milvus.ps1 -Snapshot data\backups\milvus_20261030_131500
param(
    [Parameter(Mandatory = $true)][string]$Snapshot
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$snapDir = if ([System.IO.Path]::IsPathRooted($Snapshot)) { $Snapshot } else { Join-Path $root $Snapshot }

# --- Pre-flight validation: abort without touching volumes if anything is off ---
if (-not (Test-Path $snapDir)) { throw "[restore] snapshot dir not found: $snapDir" }
foreach ($n in @('etcd.tgz', 'minio.tgz', 'milvus.tgz')) {
    $p = Join-Path $snapDir $n
    if (-not (Test-Path $p)) { throw "[restore] missing pack: $p (refusing to wipe volumes)" }
    if ((Get-Item $p).Length -lt 1KB) { throw "[restore] pack too small: $p" }
}
Write-Host "[restore] snapshot validated: $snapDir"

# --- Stop all three services (no writes during swap) ---
# docker progress goes to stderr; route via cmd.exe so PowerShell 5.1 does not treat it
# as a terminating NativeCommandError under ErrorActionPreference=Stop.
cmd /c "docker compose stop milvus etcd minio attu >nul 2>nul"
Write-Host "[restore] stopped milvus/etcd/minio/attu"

# --- Wipe + unpack in one container (single quotes: no interpolation) ---
$hostSnap = $snapDir -replace '\\', '/'
docker run --rm `
    -v medagent_medagent_etcd:/e `
    -v medagent_medagent_minio:/m `
    -v medagent_medagent_milvus-db:/v `
    -v "${hostSnap}:/in" `
    alpine:3.20 sh -c 'set -e; for d in /e /m /v; do find "$d" -mindepth 1 -maxdepth 1 -exec rm -rf {} + ; done; cd /e && tar xzf /in/etcd.tgz; cd /m && tar xzf /in/minio.tgz; cd /v && tar xzf /in/milvus.tgz; echo RESTORED'
if ($LASTEXITCODE -ne 0) { throw "[restore] unpack failed (exit=$LASTEXITCODE); containers left stopped for manual inspection" }

# --- Bring services back ---
cmd /c "docker compose up -d etcd minio milvus attu >nul 2>nul"
Write-Host "[restore] etcd/minio/milvus/attu restarted"
Write-Host "[restore] verify with:"
Write-Host "  - MilvusClient get_collection_stats('medical_kb') after ~20s"
Write-Host "  - MEDICAL_EVAL_USER=doctor01 MEDICAL_EVAL_PASS=<demo> python scripts/run_eval.py"
