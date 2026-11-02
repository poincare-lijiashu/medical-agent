# ============================================================================
# Price drift audit (read-only) - P1-9 companion.
#
# Why: drug orders are priced from clinical_svc.md_charge_item while pharmacy
# prices the same drug from pharmacy_svc.md_drug. Two sources can drift, and a
# refund then exceeds what was actually charged. The service layer now caps
# cumulative refunds per order, so a drift shows up as a business rejection
# instead of a negative charge; this audit finds the drift before it hurts.
#
# ASCII only. Requires the local container medagent_pg (host 5433).
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\price_drift_audit.ps1
# Exit code: 0 = ran successfully (drift may exist), 1 = cannot query.
# ============================================================================
param(
    [string]$Container = "medagent_pg",
    [string]$DbUser = "medagent",
    [string]$Database = "medagent",
    [int]$Limit = 50
)

$ErrorActionPreference = "Continue"

$countSql = @"
SELECT count(*)
  FROM clinical_svc.md_charge_item c
  JOIN pharmacy_svc.md_drug d ON d.drug_code = c.item_code
 WHERE c.enabled AND d.enabled AND d.price <> c.price;
"@

$rowsSql = @"
SELECT c.item_code,
       c.name,
       c.price AS charge_price,
       d.price AS drug_price,
       (d.price - c.price) AS diff
  FROM clinical_svc.md_charge_item c
  JOIN pharmacy_svc.md_drug d ON d.drug_code = c.item_code
 WHERE c.enabled AND d.enabled AND d.price <> c.price
 ORDER BY abs(d.price - c.price) DESC
 LIMIT $Limit;
"@

$countOut = & docker exec $Container psql -U $DbUser -d $Database -t -A -c $countSql 2>&1 | Out-String
if ($LASTEXITCODE -ne 0) {
    Write-Output "FATAL cannot query price drift: $($countOut.Trim())"
    exit 1
}

$drift = 0
[int]::TryParse($countOut.Trim(), [ref]$drift) | Out-Null
Write-Output "DRIFT_ROWS=$drift"

if ($drift -gt 0) {
    & docker exec $Container psql -U $DbUser -d $Database -c $rowsSql 2>&1 | Out-String | Write-Output
    Write-Output "RESULT DRIFT (fix md_charge_item/md_drug before refunds hit the new cap)"
} else {
    Write-Output "RESULT CLEAN"
}
exit 0
