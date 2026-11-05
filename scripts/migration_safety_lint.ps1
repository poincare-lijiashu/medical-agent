# ============================================================================
# Migration safety lint (review section 6.15).
#
# Why: every campus migration so far added a NOT NULL column and enforced it in
# one shot. On an empty database that is harmless, but the same pattern applied
# later to a large table takes ACCESS EXCLUSIVE locks and rewrites rows, i.e. a
# deploy-time outage. The risky statements are:
#   R1 ALTER COLUMN ... SET NOT NULL   -> full table scan under ACCESS EXCLUSIVE
#      safe pattern: ADD CONSTRAINT ... CHECK (col IS NOT NULL) NOT VALID;
#                    VALIDATE CONSTRAINT ...;  (then) SET NOT NULL;
#                    DROP CONSTRAINT ...
#   R2 ADD COLUMN ... NOT NULL         -> fails outright on a non-empty table
#      safe pattern: add nullable, backfill in batches, then enforce as in R1
#
# Migrations that already shipped are listed in migration_safety_baseline.txt;
# only NEW files (or new risky statements in changed files) fail the gate.
# Keep this file ASCII-only: PS 5.1 reads BOM-less UTF-8 as ANSI.
#
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File scripts\migration_safety_lint.ps1
#        [-IncludeBaseline]   also report the already-shipped migrations
# Exit:  0 = no new findings, 1 = at least one new risky statement
# ============================================================================
param(
    [switch]$IncludeBaseline,
    [string]$Root = (Split-Path -Parent $PSScriptRoot)
)

$ErrorActionPreference = "Stop"
$baselinePath = Join-Path $PSScriptRoot "migration_safety_baseline.txt"

$baseline = @{}
if (Test-Path $baselinePath) {
    Get-Content $baselinePath | ForEach-Object {
        $line = $_.Trim()
        if ($line -and -not $line.StartsWith("#")) { $baseline[$line] = $true }
    }
}

$rules = @(
    @{ Name = "SET_NOT_NULL"; Regex = "ALTER\s+COLUMN\s+\w+\s+SET\s+NOT\s+NULL" }
)

$migrationRoot = Join-Path $Root "platform"
$files = Get-ChildItem -Path $migrationRoot -Recurse -Filter *.sql |
    Where-Object { $_.FullName -match "db[\\/]migration" -and $_.FullName -notmatch "[\\/]target[\\/]" } |
    Sort-Object FullName

$newFindings = 0
$knownCount = 0
$scanned = 0

function Add-Finding([string]$fileName, [string]$ruleName) {
    if ($baseline.ContainsKey($fileName)) {
        $script:knownCount++
        if ($IncludeBaseline) { Write-Output ("KNOWN  {0}  {1}" -f $ruleName, $fileName) }
    } else {
        $script:newFindings++
        Write-Output ("RISK   {0}  {1}" -f $ruleName, $fileName)
    }
}

foreach ($file in $files) {
    $scanned++
    $relative = $file.FullName.Substring($Root.Length).TrimStart("\", "/") -replace "\\", "/"
    $text = Get-Content -Raw $file.FullName
    foreach ($rule in $rules) {
        if ([regex]::IsMatch($text, $rule.Regex, "IgnoreCase")) {
            Add-Finding $relative $rule.Name
        }
    }
    # ADD COLUMN ... NOT NULL is metadata-only when the statement also carries a
    # constant DEFAULT (PG11+); only the no-default form rewrites or fails
    foreach ($m in [regex]::Matches($text, "ADD\s+COLUMN[^;]*", "IgnoreCase")) {
        $statement = $m.Value
        if ($statement -match "NOT\s+NULL" -and $statement -notmatch "DEFAULT") {
            Add-Finding $relative "ADD_COLUMN_NOT_NULL"
        }
    }
}

Write-Output ("scanned={0} known={1} new={2}" -f $scanned, $knownCount, $newFindings)
if ($newFindings -gt 0) {
    Write-Output "FAIL new migration uses a blocking NOT NULL pattern; see the header for the safe pattern"
    exit 1
}
Write-Output "PASS no new blocking NOT NULL patterns"
exit 0
