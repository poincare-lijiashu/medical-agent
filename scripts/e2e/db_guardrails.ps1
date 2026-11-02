# ============================================================================
# MedAssist database guardrails regression (negative tests, real PostgreSQL).
#
# Every case below MUST be rejected by the database itself. They are the
# constraints that protect money / legal records / audit trails, so they are
# regression-tested here instead of being "verified once by hand in HANDOFF".
#
# ASCII only. Requires the local container medagent_pg (host 5433).
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\e2e\db_guardrails.ps1
# ============================================================================
param(
    [string]$Container = "medagent_pg",
    [string]$DbUser = "medagent",
    [string]$Database = "medagent"
)

$ErrorActionPreference = "Continue"
$script:fail = 0
$script:pass = 0

function Invoke-Sql {
    param([string]$Sql)
    $out = & docker exec $Container psql -U $DbUser -d $Database -v ON_ERROR_STOP=1 -c $Sql 2>&1 | Out-String
    return [pscustomobject]@{ Exit = $LASTEXITCODE; Output = $out }
}

function Expect-Fail {
    param([string]$Name, [string]$Sql, [string]$Pattern)
    $r = Invoke-Sql $Sql
    if ($r.Exit -eq 0) {
        Write-Output "FAIL $Name expected=rejected actual=accepted"
        $script:fail++
        return
    }
    if ($Pattern -and ($r.Output -notmatch $Pattern)) {
        Write-Output "FAIL $Name expected-pattern=$Pattern actual=$($r.Output.Trim())"
        $script:fail++
        return
    }
    Write-Output "PASS $Name rejected ($Pattern)"
    $script:pass++
}

# ---------------------------------------------------------------- 0. container alive
$r = Invoke-Sql "SELECT 1"
if ($r.Exit -ne 0) {
    Write-Output "FATAL cannot connect to $Container : $($r.Output)"
    exit 1
}

# ---------------------------------------------------------------- 1. KB document guards
Expect-Fail 'kb.sourceType.invalid' @"
INSERT INTO kb_svc.kb_document (id, space_id, title, source_type, content, content_hash, created_by)
VALUES (gen_random_uuid(), gen_random_uuid(), 'guard', 'PDF', 'x', 'guard-hash-1', 'tester');
"@ 'ck_kb_document_source_type'

Expect-Fail 'kb.rawCharCount.negative' @"
INSERT INTO kb_svc.kb_document
    (id, space_id, title, source_type, content, content_hash, created_by, raw_char_count)
VALUES (gen_random_uuid(), gen_random_uuid(), 'guard', 'TEXT', 'x', 'guard-hash-2', 'tester', -1);
"@ 'ck_kb_document_raw_chars'

Expect-Fail 'kb.chunk.range.invalid' @"
INSERT INTO kb_svc.kb_chunk (id, document_id, seq, content, char_start, char_end, terms)
VALUES (gen_random_uuid(), gen_random_uuid(), 0, 'x', 10, 5, 'x');
"@ 'ck_kb_chunk_range'

Expect-Fail 'kb.space.visibility.invalid' @"
INSERT INTO kb_svc.kb_space (id, code, name, visibility, created_by)
VALUES (gen_random_uuid(), 'GUARD-VIS', 'guard', 'EVERYONE', 'tester');
"@ 'kb_space_visibility_check'

# ---------------------------------------------------------------- 2. Insurance export batch guards
Expect-Fail 'insurance.batch.billCount.zero' @"
INSERT INTO insurance_svc.settlement_export_batch
    (id, batch_no, period_from, period_to, bill_count, total_amount, insurance_amount,
     self_pay_amount, payload, payload_hash, created_by)
VALUES (gen_random_uuid(), 'GUARD-1', current_date, current_date, 0, 0, 0, 0, '<x/>', 'h', 'tester');
"@ 'bill_count_check'

Expect-Fail 'insurance.batch.period.inverted' @"
INSERT INTO insurance_svc.settlement_export_batch
    (id, batch_no, period_from, period_to, bill_count, total_amount, insurance_amount,
     self_pay_amount, payload, payload_hash, created_by)
VALUES (gen_random_uuid(), 'GUARD-2', current_date, current_date - 1, 1, 0, 0, 0, '<x/>', 'h', 'tester');
"@ 'ck_export_period'

Expect-Fail 'insurance.batch.status.invalid' @"
INSERT INTO insurance_svc.settlement_export_batch
    (id, batch_no, period_from, period_to, bill_count, total_amount, insurance_amount,
     self_pay_amount, payload, payload_hash, status, created_by)
VALUES (gen_random_uuid(), 'GUARD-3', current_date, current_date, 1, 0, 0, 0, '<x/>', 'h', 'SENT', 'tester');
"@ ''

# ---------------------------------------------------------------- 3. Export item: one bill, one active batch (V13 trigger)
$bill = '99999999-9999-9999-9999-999999999999'
$r = Invoke-Sql @"
INSERT INTO insurance_svc.settlement_export_batch
    (id, batch_no, period_from, period_to, bill_count, total_amount, insurance_amount,
     self_pay_amount, payload, payload_hash, status, created_by)
VALUES ('aaaaaaa1-0000-0000-0000-000000000001','GUARD-A',current_date,current_date,1,0,0,0,'<x/>','h','GENERATED','tester'),
       ('aaaaaaa2-0000-0000-0000-000000000002','GUARD-B',current_date,current_date,1,0,0,0,'<x/>','h','GENERATED','tester');
INSERT INTO insurance_svc.settlement_export_item (batch_id, bill_id, bill_no)
VALUES ('aaaaaaa1-0000-0000-0000-000000000001','$bill','GUARD-BILL');
"@
if ($r.Exit -ne 0) {
    Write-Output "SKIP insurance.exportItem.singleActiveBatch (setup failed: $($r.Output))"
} else {
    Expect-Fail 'insurance.exportItem.singleActiveBatch' @"
INSERT INTO insurance_svc.settlement_export_item (batch_id, bill_id, bill_no)
VALUES ('aaaaaaa2-0000-0000-0000-000000000002','$bill','GUARD-BILL');
"@ ''

    # Pattern matches the PL/pgSQL CONTEXT line (ASCII) so we prove the rejection
    # came from our trigger, not from an unrelated constraint.
    Expect-Fail 'insurance.exportItem.updateBlocked' @"
UPDATE insurance_svc.settlement_export_item SET bill_no = 'TAMPERED'
 WHERE batch_id = 'aaaaaaa1-0000-0000-0000-000000000001';
"@ 'guard_export_item_immutable'

    Expect-Fail 'insurance.exportItem.deleteBlocked' @"
DELETE FROM insurance_svc.settlement_export_item
 WHERE batch_id = 'aaaaaaa1-0000-0000-0000-000000000001';
"@ 'guard_export_item_immutable'

    # REJECTED batches must not block re-export
    $r2 = Invoke-Sql @"
UPDATE insurance_svc.settlement_export_batch
   SET status = 'REJECTED', receipt_no = 'GUARD-R1', receipt_at = now()
 WHERE id = 'aaaaaaa2-0000-0000-0000-000000000002';
INSERT INTO insurance_svc.settlement_export_item (batch_id, bill_id, bill_no)
VALUES ('aaaaaaa2-0000-0000-0000-000000000002','$bill','GUARD-BILL');
"@
    if ($r2.Exit -eq 0) {
        Write-Output "PASS insurance.exportItem.rejectedAllowsReexport"
        $script:pass++
    } else {
        Write-Output "FAIL insurance.exportItem.rejectedAllowsReexport expected=accepted actual=$($r2.Output)"
        $script:fail++
    }

    # Cleanup: active batch must be cancelled first (its items are immutable while active)
    Invoke-Sql @"
UPDATE insurance_svc.settlement_export_batch
   SET status = 'CANCELLED', cancelled_at = now()
 WHERE id = 'aaaaaaa1-0000-0000-0000-000000000001'
   AND status = 'GENERATED';
DELETE FROM insurance_svc.settlement_export_item WHERE bill_id = '$bill';
DELETE FROM insurance_svc.settlement_export_batch
 WHERE batch_no IN ('GUARD-A','GUARD-B');
"@ | Out-Null
}

# ---------------------------------------------------------------- 4. Audit tables are append-only
# Only verified when the table already has rows: UPDATE on an empty table affects zero rows
# and would never fire the trigger (false negative).
function Expect-AuditLocked {
    param([string]$Name, [string]$Table, [string]$Sql)
    $c = & docker exec $Container psql -U $DbUser -d $Database -t -A -c "SELECT count(*) FROM $Table" 2>&1 | Out-String
    if ($LASTEXITCODE -ne 0) {
        Write-Output "SKIP $Name (cannot count $Table)"
        return
    }
    $n = 0
    [int]::TryParse($c.Trim(), [ref]$n) | Out-Null
    if ($n -le 0) {
        Write-Output "SKIP $Name ($Table is empty)"
        return
    }
    Expect-Fail $Name $Sql ''
}

Expect-AuditLocked 'kb.changeLog.update' 'kb_svc.kb_change_log' @"
UPDATE kb_svc.kb_change_log SET action = 'TAMPERED' WHERE id = (SELECT min(id) FROM kb_svc.kb_change_log);
"@

Expect-AuditLocked 'clinical.changeLog.update' 'clinical_svc.clinical_change_log' @"
UPDATE clinical_svc.clinical_change_log SET action = 'TAMPERED'
 WHERE id = (SELECT min(id) FROM clinical_svc.clinical_change_log);
"@

Expect-AuditLocked 'empi.changeLog.delete' 'empi_svc.change_log' @"
DELETE FROM empi_svc.change_log WHERE id = (SELECT min(id) FROM empi_svc.change_log);
"@

Expect-AuditLocked 'pharmacy.prescriptionAudit.update' 'pharmacy_svc.prescription_audit' @"
UPDATE pharmacy_svc.prescription_audit SET result = 'PASS'
 WHERE id = (SELECT min(id) FROM pharmacy_svc.prescription_audit);
"@

Write-Output "-----"
Write-Output "GUARDRAILS_PASS=$script:pass FAIL=$script:fail"
if ($script:fail -gt 0) { Write-Output "RESULT FAIL"; exit 1 }
Write-Output 'RESULT PASS'
exit 0
