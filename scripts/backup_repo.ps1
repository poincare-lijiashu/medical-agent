# Repo cross-disk backup (mirror + bundle), companion to W3.5/W4.4.
# INCIDENT 2026-10-30: an earlier revision of this script deleted the target
# and then failed to clone -- with a scripting bug the "target" variables
# resolved wrongly and the SOURCE working tree was destroyed (repo content
# survived only because a bundle from minutes earlier existed). Rules baked
# into this revision:
#   1) NEVER pre-delete an existing backup. Build into a temp name, verify,
#      then swap atomically.
#   2) Refuse to run if the source repo looks unhealthy (.git missing).
#   3) Keep this file ASCII-only (PS 5.1 parses BOM-less UTF-8 as ANSI and
#      Chinese comments corrupt parsing -- that is how the variables went
#      null and the destructive path fired). Git stderr under
#      ErrorActionPreference=Stop also aborts, so silence stderr per call
#      and check $LASTEXITCODE explicitly.
#   4) Run from a NORMAL terminal; nested sandboxed shells can break git
#      child-process cwd ("repository '.' does not exist").
# Usage:  powershell -File scripts\backup_repo.ps1   (weekly / after big commits)
# Restore: git clone <backup-dir>\medical_agent_YYYYMMDD.bundle <dir>
#          (a bundle clone needs no existing repo -- it rescued this project)
$ErrorActionPreference = "Continue"
$dst = "<backup-dir>"
$stamp = Get-Date -Format "yyyyMMdd"
$src = "<repo>"

# -- 0) source sanity: refuse to "back up" from a broken tree
if (-not (Test-Path (Join-Path $src ".git"))) {
    throw "ABORT: source .git missing at $src -- investigate before any backup action"
}
git -C $src rev-parse --short HEAD 2>$null | Out-Null
if ($LASTEXITCODE -ne 0) { throw "ABORT: git cannot read source repo (exit $LASTEXITCODE)" }

New-Item -ItemType Directory -Force -Path $dst | Out-Null
$head_src = git -C $src rev-parse --short HEAD 2>$null

# -- 1) mirror built into temp name, swapped in only after verification
$tmpMirror = Join-Path $dst ("medical_agent.git.tmp_" + $stamp)
if (Test-Path $tmpMirror) { Remove-Item -Recurse -Force $tmpMirror }
git clone --mirror $src $tmpMirror 2>$null | Out-Null
if ($LASTEXITCODE -ne 0) { throw "mirror clone failed (exit $LASTEXITCODE) -- existing backup untouched" }
$head_m = git --git-dir $tmpMirror rev-parse --short HEAD 2>$null
if ($head_m -ne $head_src) { throw "mirror HEAD mismatch ($head_m != $head_src) -- existing backup untouched" }
$mirror = Join-Path $dst "medical_agent.git"
if (Test-Path $mirror) { Remove-Item -Recurse -Force $mirror }
Rename-Item $tmpMirror "medical_agent.git"

# -- 2) dated bundle snapshot (this is what rescued the project; keep dates)
# NOTE: git bundle can crash (0xC0000005) when invoked through nested/sandboxed
# shells -- failure here is a WARNING, not fatal: the verified mirror above is
# the primary backup. Run from a normal terminal for the bundle copy.
$bundle = Join-Path $dst "medical_agent_$stamp.bundle"
# NOTE: `git bundle create` has no --force; an existing file makes it fail.
# Remove today's file first (older dated bundles stay untouched).
if (Test-Path $bundle) { Remove-Item -Force $bundle }
git -C $src bundle create $bundle --all 2>$null | Out-Null
if ($LASTEXITCODE -ne 0) {
    Write-Output "WARN: bundle create failed (exit $LASTEXITCODE) -- mirror backup still valid"
}

Write-Output "BACKUP OK: mirror=$mirror bundle=$bundle HEAD=$head_src"
