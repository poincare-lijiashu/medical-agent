# ============================================================================
# 单院逻辑备份（第 286 批 / ADR-001）
#
# 定位：控制面负责"建桶 + 登记备份计划 + 校验 WAL 归档"；本脚本负责**单院可执行的全量备份**，
# 用于本地 Docker 形态的交付验收与恢复演练（生产由 pgBackRest 在 Cell 内执行，
# 配置模板见 deploy/backup/pgbackrest-cell.conf.tmpl）。
#
# 为什么保留逻辑备份：物理备份/PITR 依赖 archive_mode 与 pgBackRest 环境；
# 在院方尚未开启归档的窗口期，逻辑全量备份是唯一"今天就能恢复"的底线。
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File scripts\backup\cell_backup.ps1 `
#       -Container medagent_pg_tenant003 -Database medagent -BackupDir D:\backups\tenant-003
#
# 产物：
#   <BackupDir>\<db>-YYYYmmdd-HHmmss.dump        自定义格式（支持 pg_restore 选择性/并行恢复）
#   <BackupDir>\<db>-YYYYmmdd-HHmmss.dump.sha256 完整性校验
#   <BackupDir>\<db>-YYYYmmdd-HHmmss.json        备份清单（供恢复演练与审计对账）
# ============================================================================
param(
    [Parameter(Mandatory = $true)][string]$Container,
    [string]$Database = "medagent",
    [string]$User = "medagent",
    [string]$BackupDir = "$PSScriptRoot\..\..\artifacts\backup",
    [int]$Keep = 14
)

$ErrorActionPreference = "Stop"
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$name = "$Database-$stamp"
$dir = [System.IO.Path]::GetFullPath($BackupDir)
New-Item -ItemType Directory -Force -Path $dir | Out-Null

$dumpInContainer = "/tmp/$name.dump"
$hashInContainer = "$dumpInContainer.sha256"

Write-Host "[1/5] 检查容器与归档状态：$Container"
$state = docker exec $Container psql -U $User -d $Database -Atc "select current_setting('archive_mode') || '|' || current_setting('wal_level')"
if ($LASTEXITCODE -ne 0) { throw "无法连接容器内的 PostgreSQL：$Container" }
$archiver = docker exec $Container psql -U $User -d $Database -Atc `
    "select archived_count || '|' || failed_count || '|' || coalesce(last_archived_time::text,'') from pg_stat_archiver"
$parts = $state.Split("|")
$aparts = $archiver.Split("|")
$archiveMode = $parts[0]; $walLevel = $parts[1]
$archivedCount = [int]$aparts[0]; $failedCount = [int]$aparts[1]; $lastArchived = $aparts[2]
$pitrReady = ($archiveMode -eq "on") -and ($walLevel -in @("replica", "logical"))

Write-Host "[2/5] pg_dump -Fc（自定义格式）"
docker exec $Container pg_dump -U $User -Fc -d $Database -f $dumpInContainer
if ($LASTEXITCODE -ne 0) { throw "pg_dump 失败，未产生可用备份" }

Write-Host "[3/5] 立即校验归档可列出（防止写了半个文件也算成功）"
docker exec $Container pg_restore --list $dumpInContainer | Out-Null
if ($LASTEXITCODE -ne 0) { throw "备份产物校验失败：pg_restore --list 无法读取" }

docker exec $Container sh -c "sha256sum $dumpInContainer > $hashInContainer"
$sizeBytes = [int64](docker exec $Container stat -c %s $dumpInContainer)

Write-Host "[4/5] 复制到宿主机并写入清单"
docker cp "${Container}:$dumpInContainer" "$dir\$name.dump" | Out-Null
docker cp "${Container}:$hashInContainer" "$dir\$name.dump.sha256" | Out-Null
$manifest = [ordered]@{
    tenantContainer = $Container
    database        = $Database
    takenAt         = (Get-Date).ToString("o")
    sizeBytes       = $sizeBytes
    archiveMode     = $archiveMode
    walLevel        = $walLevel
    pitrReady       = $pitrReady
    archivedCount   = $archivedCount
    archiveFailures = $failedCount
    lastArchivedAt  = $lastArchived
    dump            = "$name.dump"
    sha256File      = "$name.dump.sha256"
    restoreCommand  = "docker exec -i $Container pg_restore -U $User -d <target-db> --no-owner --no-privileges < $name.dump"
}
$manifest | ConvertTo-Json -Depth 6 | Set-Content -Path "$dir\$name.json" -Encoding UTF8

docker exec $Container rm -f $dumpInContainer $hashInContainer | Out-Null

Write-Host "[5/5] 轮转（保留最近 $Keep 份）"
Get-ChildItem -Path $dir -Filter "$Database-*.dump" |
    Sort-Object Name -Descending |
    Select-Object -Skip $Keep |
    ForEach-Object {
        $base = $_.FullName
        Remove-Item $base -Force
        Remove-Item "$base.sha256" -Force -ErrorAction SilentlyContinue
        $json = [System.IO.Path]::ChangeExtension($base, ".json")
        Remove-Item $json -Force -ErrorAction SilentlyContinue
    }

if (-not $pitrReady) {
    Write-Warning "WAL 归档未就绪（archive_mode=$archiveMode, wal_level=$walLevel）：当前只能恢复到本次全备，RPO≈备份间隔。"
}
Write-Host "BACKUP OK: $dir\$name.dump ($([math]::Round($sizeBytes / 1MB, 1)) MB, PITR=$pitrReady)"
