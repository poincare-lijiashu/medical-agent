# Milvus triple-volume backup (etcd metadata + minio segments + milvus local store)
#
# Why this exists: on 2026-10-30 an inline `sh -c` string in PowerShell interpolated
# `\$p` as an undefined variable (empty), so the container actually ran `rm -rf //*`
# and wiped six volumes - 14,049 knowledge-base chunks were lost permanently.
# This script provides volume-level snapshots so the same class of accident is recoverable.
#
# Design notes:
# - Three volumes are packed separately (busybox tar has no --transform, mixed packs collide)
# - milvus is stopped before packing so segments are flushed (etcd/minio keep running)
# - Every sh -c payload uses SINGLE quotes: PowerShell does not interpolate single-quoted
#   strings, which is the direct defence against the accident above
# - Keeps the newest N snapshots (default 3), older ones are pruned
#
# Usage:
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts/backup_milvus.ps1
#   powershell ... -File scripts/backup_milvus.ps1 -Keep 5
# NOTE: all output strings are ASCII on purpose - PowerShell 5.1 reads BOM-less UTF-8
#       files as ANSI, and non-ASCII strings inside quotes break the parser.
param([int]$Keep = 3)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$ts = Get-Date -Format 'yyyyMMdd_HHmmss'
$outDir = Join-Path $root "data\backups\milvus_$ts"

Write-Host "[backup] target: $outDir"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

# 1) Stop milvus to flush segments (remember prior state, restore afterwards)
# NOTE: docker writes progress lines to stderr; under ErrorActionPreference=Stop
# PowerShell 5.1 treats them as terminating NativeCommandError. Route native calls
# through cmd.exe so stderr is discarded before PowerShell sees it.
$statusLine = cmd /c 'docker compose ps milvus --format "{{.Status}}" 2>nul'
$wasUp = $statusLine -match 'Up'
if ($wasUp) {
    Write-Host "[backup] stopping milvus (flush segments)..."
    cmd /c "docker compose stop milvus >nul 2>nul"
} else {
    Write-Host "[backup] milvus not running, packing volumes directly"
}

# 2) Pack each volume separately (single-quoted payload: no interpolation)
$hostOut = $outDir -replace '\\', '/'
docker run --rm `
    -v medagent_medagent_etcd:/e `
    -v medagent_medagent_minio:/m `
    -v medagent_medagent_milvus-db:/v `
    -v "${hostOut}:/out" `
    alpine:3.20 sh -c 'cd /e && tar czf /out/etcd.tgz . && cd /m && tar czf /out/minio.tgz . && cd /v && tar czf /out/milvus.tgz . && echo PACKED'
if ($LASTEXITCODE -ne 0) { throw "[backup] packing failed (exit=$LASTEXITCODE)" }

# 3) Verify artefacts and write metadata
$files = Get-ChildItem $outDir -Filter *.tgz
if ($files.Count -ne 3) { throw "[backup] expected 3 packs, got $($files.Count)" }
$total = ($files | Measure-Object Length -Sum).Sum
$milvusImage = cmd /c 'docker inspect medagent_milvus --format "{{.Config.Image}}" 2>nul'
$meta = @{
    created_at = (Get-Date).ToString('o')
    milvus     = ($milvusImage -join '').Trim()
    total_mb   = [math]::Round($total / 1MB, 1)
} | ConvertTo-Json
Set-Content -Path (Join-Path $outDir 'meta.json') -Value $meta -Encoding utf8
Write-Host "[backup] done: $([math]::Round($total / 1MB, 1)) MB"

# 4) Restore milvus running state
if ($wasUp) {
    Write-Host "[backup] restarting milvus..."
    cmd /c "docker compose start milvus >nul 2>nul"
}

# 5) Retention: keep newest $Keep snapshots
$all = Get-ChildItem (Join-Path $root 'data\backups') -Directory -Filter 'milvus_*' |
       Sort-Object Name -Descending
if ($all.Count -gt $Keep) {
    $all | Select-Object -Skip $Keep | ForEach-Object {
        Remove-Item -Recurse -Force $_.FullName
        Write-Host "[backup] pruned: $($_.Name)"
    }
}
