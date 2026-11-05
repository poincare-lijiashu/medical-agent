# Repo cross-disk backup (mirror + bundle), companion to W3.5/W4.4.
# INCIDENT 2026-10-30: an earlier revision of this script deleted the target
# and then failed to clone -- with a scripting bug the "target" variables
# resolved wrongly and the SOURCE working tree was destroyed (repo content
# survived only because a bundle from minutes earlier existed). Rules baked
# into this revision:
#   1) NEVER pre-delete an existing backup. Build into a temp name, verify,
#      then swap: old -> <mirror>.old, new -> <mirror>, only then delete .old.
#      If the swap fails, .old is renamed back (review 5.6: the old code deleted
#      the mirror first, so a failed rename left ZERO mirrors -- the very
#      incident it was written to prevent).
#   2) Refuse to run if the source repo looks unhealthy (.git missing).
#   3) Keep this file ASCII-only (PS 5.1 parses BOM-less UTF-8 as ANSI and
#      Chinese comments corrupt parsing -- that is how the variables went
#      null and the destructive path fired). Git stderr under
#      ErrorActionPreference=Stop also aborts, so silence stderr per call
#      and check $LASTEXITCODE explicitly.
#   4) Run from a NORMAL terminal; nested sandboxed shells can break git
#      child-process cwd ("repository '.' does not exist").
#   5) Exit codes are honest: 0 = mirror + bundle OK, 2 = mirror OK but bundle
#      failed (degraded), 1 = hard failure (no usable new mirror).
# Usage:  powershell -File scripts\backup_repo.ps1 [-Src <repo>] [-Dst <dir>]
# Restore: git clone <Dst>\medical_agent_YYYYMMDD.bundle <dir>
#          (a bundle clone needs no existing repo -- it rescued this project)
param(
    [string]$Src = "<repo>",
    [string]$Dst = "<backup-dir>"
)

$ErrorActionPreference = "Continue"
$stamp = Get-Date -Format "yyyyMMdd"

# -- 0) source sanity: refuse to "back up" from a broken tree
if (-not (Test-Path (Join-Path $Src ".git"))) {
    throw "ABORT: source .git missing at $Src -- investigate before any backup action"
}
git -C $Src rev-parse --short HEAD 2>$null | Out-Null
if ($LASTEXITCODE -ne 0) { throw "ABORT: git cannot read source repo (exit $LASTEXITCODE)" }

New-Item -ItemType Directory -Force -Path $Dst | Out-Null
$head_src = git -C $Src rev-parse --short HEAD 2>$null

# -- 1) mirror built into temp name, swapped in only after verification
$tmpMirror = Join-Path $Dst ("medical_agent.git.tmp_" + $stamp)
if (Test-Path $tmpMirror) { Remove-Item -Recurse -Force $tmpMirror }
git clone --mirror $Src $tmpMirror 2>$null | Out-Null
if ($LASTEXITCODE -ne 0) { throw "mirror clone failed (exit $LASTEXITCODE) -- existing backup untouched" }
$head_m = git --git-dir $tmpMirror rev-parse --short HEAD 2>$null
if ($head_m -ne $head_src) { throw "mirror HEAD mismatch ($head_m != $head_src) -- existing backup untouched" }

$mirror = Join-Path $Dst "medical_agent.git"
$oldMirror = Join-Path $Dst "medical_agent.git.old"
if (Test-Path $oldMirror) { Remove-Item -Recurse -Force $oldMirror }
$hadOld = Test-Path $mirror
if ($hadOld) {
    Rename-Item -Path $mirror -NewName "medical_agent.git.old"
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path $oldMirror)) {
        throw "ABORT: could not move existing mirror aside -- original backup left in place"
    }
}
try {
    Rename-Item -Path $tmpMirror -NewName "medical_agent.git"
} catch {
    # rollback: put the previous mirror back so we never end up with zero mirrors
    if ($hadOld -and (Test-Path $oldMirror) -and -not (Test-Path $mirror)) {
        Rename-Item -Path $oldMirror -NewName "medical_agent.git"
        Write-Output "ROLLBACK: new mirror swap failed, previous mirror restored"
    }
    throw "ABORT: mirror swap failed -- see rollback status above"
}
if (-not (Test-Path $mirror)) { throw "ABORT: mirror missing after swap" }
if ($hadOld -and (Test-Path $oldMirror)) { Remove-Item -Recurse -Force $oldMirror }

# -- 2) dated bundle snapshot (this is what rescued the project; keep dates)
# NOTE: git bundle can crash (0xC0000005) when invoked through nested/sandboxed
# shells. Failure here is DEGRADED, not fatal: the verified mirror above is the
# primary backup. Exit code 2 tells the scheduler "investigate the bundle".
$bundle = Join-Path $Dst "medical_agent_$stamp.bundle"
$bundleCode = 0
# NOTE: `git bundle create` has no --force; an existing file makes it fail.
# Build to a temp name first, so a failed attempt never destroys today's older copy.
$tmpBundle = "$bundle.tmp"
if (Test-Path $tmpBundle) { Remove-Item -Force $tmpBundle }
git -C $Src bundle create $tmpBundle --all 2>$null | Out-Null
$bundleCode = $LASTEXITCODE
if ($bundleCode -eq 0) {
    if (Test-Path $bundle) { Remove-Item -Force $bundle }
    Rename-Item -Path $tmpBundle -NewName (Split-Path $bundle -Leaf)
    # bundle must be non-trivial and parseable
    $bundleSize = (Get-Item $bundle).Length
    git bundle verify $bundle 2>$null | Out-Null
    if ($LASTEXITCODE -ne 0 -or $bundleSize -lt 1024) {
        Write-Output "WARN: bundle verify failed (size=$bundleSize) -- mirror backup still valid"
        $bundleCode = 3
    }
} else {
    if (Test-Path $tmpBundle) { Remove-Item -Force $tmpBundle }
    Write-Output "WARN: bundle create failed (exit $bundleCode) -- mirror backup still valid"
}

if ($bundleCode -ne 0) {
    Write-Output "BACKUP DEGRADED: mirror=$mirror bundle=FAILED HEAD=$head_src"
    exit 2
}
Write-Output "BACKUP OK: mirror=$mirror bundle=$bundle HEAD=$head_src"
exit 0
