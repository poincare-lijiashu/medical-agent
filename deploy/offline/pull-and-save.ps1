# ============================================================================
# Step 1 (on an INTERNET-CONNECTED machine): pull every image and save to tar.
#
# ASCII only on purpose: Windows PowerShell 5.1 reads BOM-less UTF-8 as ANSI,
# non-ASCII characters break parsing.
#
# Usage (PowerShell, from repo root):
#   .\deploy\offline\pull-and-save.ps1                 # default output .\offline-bundle
#   .\deploy\offline\pull-and-save.ps1 -OutDir D:\bundle -Split 4G
#
# Notes:
#   * docker pull may go through your local mirror/proxy; the RESULT has no
#     network dependency, which is what the hospital needs.
#   * Each image becomes <safe-name>.tar, plus MANIFEST.txt with digests.
# ============================================================================
param(
    [string]$OutDir = ".\offline-bundle",
    [string]$ImagesFile = "$PSScriptRoot\images.txt",
    [string]$Split = ""
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path $ImagesFile)) {
    throw "images.txt not found: $ImagesFile"
}
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

# Parse images: strip comments / blank lines
$images = Get-Content $ImagesFile |
    ForEach-Object { $_.Trim() } |
    Where-Object { $_ -ne "" -and -not $_.StartsWith("#") }

if ($images.Count -eq 0) { throw "no images listed in $ImagesFile" }

$manifest = @()
$failed = @()

foreach ($img in $images) {
    # Business images (medassist/*) are built locally, never pulled from a registry.
    # If the image is already present locally we save it directly; otherwise we try a pull
    # (third-party images) and report a clear failure instead of aborting the whole bundle.
    $localExists = $false
    docker image inspect $img *> $null
    if ($LASTEXITCODE -eq 0) { $localExists = $true }

    if (-not $localExists) {
        Write-Host "== pull $img" -ForegroundColor Cyan
        docker pull $img
        if ($LASTEXITCODE -ne 0) {
            $failed += $img
            Write-Host "!! image not present locally and pull failed: $img" -ForegroundColor Red
            Write-Host "   (business images must be built first: see README section 1.1)" -ForegroundColor Yellow
            continue
        }
    } else {
        Write-Host "== local image found, skip pull: $img" -ForegroundColor DarkGray
    }

    $safe = ($img -replace '[^A-Za-z0-9._-]', '_')
    $tar = Join-Path $OutDir "$safe.tar"

    Write-Host "== save $img -> $tar" -ForegroundColor Cyan
    docker save -o $tar $img
    if ($LASTEXITCODE -ne 0) {
        $failed += $img
        continue
    }

    if ($Split -ne "") {
        # Optional: split large tars for FAT32/USB transfer
        & tar -cf - -C $OutDir "$safe.tar" | & split -b $Split - "$tar.part."
    }

    $digest = (docker inspect --format '{{index .RepoDigests 0}}' $img) 2>$null
    $size = (Get-Item $tar).Length
    $manifest += ("{0}`t{1}`t{2}`t{3}" -f $img, $digest, $size, (Split-Path $tar -Leaf))
}

$manifestFile = Join-Path $OutDir "MANIFEST.txt"
@(
    "# image`tdigest`tbytes`ttar",
    $manifest
) | Set-Content -Path $manifestFile -Encoding ASCII

Write-Host ""
Write-Host "saved $($manifest.Count) image(s) to $OutDir" -ForegroundColor Green
Write-Host "manifest: $manifestFile"
if ($failed.Count -gt 0) {
    Write-Host "FAILED ($($failed.Count)):" -ForegroundColor Red
    $failed | ForEach-Object { Write-Host "  $_" -ForegroundColor Red }
    exit 1
}
Write-Host ""
Write-Host "Next: copy the whole folder to the hospital jump host and run load-and-push.sh"
