# G46 smoke: campus dictionary maintenance + clinical campus isolation.
#
# Prereq: gateway 8080 + auth 8081 + clinical 8083 running against the dev DB,
#         demo accounts admin01/doctor01 with password Med@2026.
# Usage : powershell -NoProfile -ExecutionPolicy Bypass -File scripts\e2e\campus_isolation_smoke.ps1
# Notes : creates WEST campus (disabled at the end), two EAST-only accounts,
#         one EAST ward and one EAST admission per run; safe to re-run.
# ASCII only: PowerShell 5.1 reads BOM-less UTF-8 as ANSI.
$ErrorActionPreference = "Stop"
$base = "http://127.0.0.1:8080/api/v2"
$EAST = "a0000000-0000-0000-0000-000000000002"
$MAIN = "a0000000-0000-0000-0000-000000000001"
$DEPT_CLINIC = "11111111-1111-1111-1111-111111111111"
$DEPT_WARD = "33333333-3333-3333-3333-333333333333"
$script:pass = 0
$script:fail = 0

function Check($name, $cond, $detail) {
    if ($cond) {
        $script:pass++
        Write-Output ("PASS " + $name)
    } else {
        $script:fail++
        Write-Output ("FAIL " + $name + " :: " + $detail)
    }
}

function Call($method, $path, $token, $body) {
    $headers = @{}
    if ($token) { $headers["Authorization"] = "Bearer " + $token }
    $params = @{ Method = $method; Uri = ($base + $path); Headers = $headers; UseBasicParsing = $true }
    if ($body -ne $null) {
        $params["ContentType"] = "application/json"
        $params["Body"] = ($body | ConvertTo-Json -Depth 8)
    }
    try {
        $r = Invoke-WebRequest @params
        return @{ code = [int]$r.StatusCode; body = ($r.Content | ConvertFrom-Json); raw = $r.Content }
    } catch {
        $code = -1
        $text = $_.Exception.Message
        $resp = $_.Exception.Response
        if ($resp -ne $null) {
            $code = [int]$resp.StatusCode
            $sr = New-Object System.IO.StreamReader($resp.GetResponseStream())
            $text = $sr.ReadToEnd()
        }
        $obj = $null
        try { $obj = $text | ConvertFrom-Json } catch { }
        return @{ code = $code; body = $obj; raw = $text }
    }
}

function Login($u, $p) {
    $r = Call "POST" "/auth/login" $null @{ username = $u; password = $p }
    if ($r.code -ne 200) { throw ("login failed for " + $u + " code=" + $r.code + " " + $r.raw) }
    return $r.body.data.accessToken
}

function CampusClaim($token) {
    $parts = $token.Split(".")
    $p = $parts[1].Replace("-", "+").Replace("_", "/")
    switch ($p.Length % 4) {
        2 { $p = $p + "==" }
        3 { $p = $p + "=" }
        1 { $p = $p + "===" }
    }
    $json = [System.Text.Encoding]::UTF8.GetString([System.Convert]::FromBase64String($p))
    $obj = $json | ConvertFrom-Json
    if ($obj.campuses -eq $null) { return "" }
    return ($obj.campuses -join ",")
}

function SqlOne($sql) {
    $out = docker exec -e PGPASSWORD=medagent123 medagent_pg psql -h 127.0.0.1 -U medagent -d medagent -t -A -c $sql
    return ($out | Out-String).Trim()
}

# ---------------------------------------------------------------- 1) auth baseline
$adminToken = Login "admin01" "Med@2026"
$doctorToken = Login "doctor01" "Med@2026"

$adminClaim = CampusClaim $adminToken
Check "admin token carries MAIN+EAST" ($adminClaim.Contains($MAIN) -and $adminClaim.Contains($EAST)) $adminClaim
$docClaim = CampusClaim $doctorToken
Check "doctor token carries MAIN only" ($docClaim.Contains($MAIN) -and (-not $docClaim.Contains($EAST))) $docClaim

$list = Call "GET" "/auth/campuses" $adminToken $null
Check "campus list 200" ($list.code -eq 200) $list.raw
Check "MAIN+EAST seeded" (($list.body.data | Measure-Object).Count -ge 2) $list.raw

# ---------------------------------------------------------------- 2) campus maintenance
$west = $list.body.data | Where-Object { $_.code -eq "WEST" } | Select-Object -First 1
if ($west -eq $null) {
    $created = Call "POST" "/auth/campuses" $adminToken @{ code = "WEST"; name = "West Campus"; sortNo = 30 }
    Check "create campus WEST" ($created.code -eq 200) $created.raw
    $west = $created.body.data
} else {
    Check "create campus WEST (already existed)" $true ""
}
$westId = $west.id

if ($west.status -ne "ACTIVE") {
    $re = Call "PUT" ("/auth/campuses/" + $westId) $adminToken @{ name = $west.name; status = "ACTIVE"; sortNo = $west.sortNo; version = $west.version }
    $west = $re.body.data
}
Check "campus WEST is ACTIVE baseline" ($west.status -eq "ACTIVE") ($west | ConvertTo-Json -Compress)

$dup = Call "POST" "/auth/campuses" $adminToken @{ code = "west"; name = "Dup"; sortNo = 40 }
Check "duplicate code rejected 409" ($dup.code -eq 409) $dup.raw

$disabled = Call "PUT" ("/auth/campuses/" + $westId) $adminToken @{ name = "West Campus"; status = "DISABLED"; sortNo = 30; version = $west.version }
Check "disable campus WEST" ($disabled.code -eq 200 -and $disabled.body.data.status -eq "DISABLED") $disabled.raw

$conflict = Call "PUT" ("/auth/campuses/" + $westId) $adminToken @{ name = "West Campus 2"; status = "ACTIVE"; sortNo = 30; version = $west.version }
Check "stale version rejected 409" ($conflict.code -eq 409) $conflict.raw

$noperm = Call "GET" "/auth/campuses" $doctorToken $null
Check "doctor without campus permission 403" ($noperm.code -eq 403) $noperm.raw

# ---------------------------------------------------------------- 3) EAST-only accounts
$stamp = Get-Date -Format "HHmmss"
$eastUser = "east" + $stamp
$eastAdminUser = "eastadm" + $stamp
$pwd = "East@2026x"

$u1 = Call "POST" "/auth/users" $adminToken @{
    username = $eastUser; password = $pwd; displayName = "East Doctor";
    deptIds = @($DEPT_CLINIC, $DEPT_WARD); roles = @("DOCTOR")
}
Check "create EAST-only doctor" ($u1.code -eq 200) $u1.raw
$c1 = Call "PUT" ("/auth/users/" + $u1.body.data.id + "/campuses") $adminToken @{ campusIds = @($EAST) }
Check "assign EAST campus to doctor" ($c1.code -eq 200) $c1.raw

$u2 = Call "POST" "/auth/users" $adminToken @{
    username = $eastAdminUser; password = $pwd; displayName = "East Admin";
    deptIds = @($DEPT_CLINIC, $DEPT_WARD); roles = @("ADMIN")
}
Check "create EAST-only admin" ($u2.code -eq 200) $u2.raw
$c2 = Call "PUT" ("/auth/users/" + $u2.body.data.id + "/campuses") $adminToken @{ campusIds = @($EAST) }
Check "assign EAST campus to admin" ($c2.code -eq 200) $c2.raw

$eastToken = Login $eastUser $pwd
$eastClaim = CampusClaim $eastToken
Check "east doctor token carries EAST only" ($eastClaim.Contains($EAST) -and (-not $eastClaim.Contains($MAIN))) $eastClaim

$eastAdminToken = Login $eastAdminUser $pwd

# ---------------------------------------------------------------- 4) outpatient registration pinned to EAST
$patientId = SqlOne "select p.id from empi_svc.patient p where p.status='ACTIVE' and not exists (select 1 from clinical_svc.admission a where a.patient_id=p.id and a.status in ('ADMITTED','IN_HOSPITAL')) and not exists (select 1 from clinical_svc.visit v where v.patient_id=p.id and v.status in ('REGISTERED','IN_TREATMENT')) limit 1;"
Check "free patient found" ($patientId.Length -gt 0) "none"

$reg = Call "POST" "/clinical/registrations" $eastAdminToken @{
    patientId = $patientId; deptId = $DEPT_WARD; doctorId = $eastAdminUser;
    slotId = $null; visitType = "OUTPATIENT"; visitDate = $null
}
Check "east admin registers visit" ($reg.code -eq 200) $reg.raw
$visitId = ""
if ($reg.code -eq 200) { $visitId = $reg.body.data.id }
$visitCampus = ""
if ($visitId.Length -gt 0) { $visitCampus = SqlOne ("select campus_id from clinical_svc.visit where id='" + $visitId + "';") }
Check "visit campus persisted EAST" ($visitCampus -eq $EAST) ($visitCampus + " " + $reg.raw)

$detailMain = Call "GET" ("/clinical/visits/" + $visitId) $doctorToken $null
Check "MAIN doctor cannot see EAST visit (404)" ($detailMain.code -eq 404) $detailMain.raw
$detailAdmin = Call "GET" ("/clinical/visits/" + $visitId) $adminToken $null
Check "cross-campus admin sees EAST visit" ($detailAdmin.code -eq 200) $detailAdmin.raw

$dailyMain = Call "GET" ("/clinical/registrations/daily?deptId=" + $DEPT_WARD) $doctorToken $null
$found = $false
if ($dailyMain.code -eq 200) { $found = (@($dailyMain.body.data) | Where-Object { $_.id -eq $visitId } | Measure-Object).Count -gt 0 }
Check "MAIN daily list excludes EAST visit" (($dailyMain.code -eq 200) -and (-not $found)) $dailyMain.raw

$dailyEast = Call "GET" ("/clinical/registrations/daily?deptId=" + $DEPT_WARD) $eastAdminToken $null
$foundEast = $false
if ($dailyEast.code -eq 200) { $foundEast = (@($dailyEast.body.data) | Where-Object { $_.id -eq $visitId } | Measure-Object).Count -gt 0 }
Check "EAST daily list includes EAST visit" (($dailyEast.code -eq 200) -and $foundEast) $dailyEast.raw

# ---------------------------------------------------------------- 5) inpatient ward campus
$ward = Call "POST" "/clinical/wards" $eastAdminToken @{ code = ("EW" + $stamp); name = "East Ward"; deptId = $DEPT_WARD }
Check "east admin creates ward" ($ward.code -eq 200) $ward.raw
$wardId = $ward.body.data.id
$wardCampus = SqlOne ("select campus_id from clinical_svc.ward where id='" + $wardId + "';")
Check "ward campus persisted EAST" ($wardCampus -eq $EAST) $wardCampus

$patient2 = SqlOne "select p.id from empi_svc.patient p where p.status='ACTIVE' and not exists (select 1 from clinical_svc.admission a where a.patient_id=p.id and a.status in ('ADMITTED','IN_HOSPITAL')) and not exists (select 1 from clinical_svc.visit v where v.patient_id=p.id and v.status in ('REGISTERED','IN_TREATMENT')) order by p.id limit 1 offset 1;"
Check "second free patient found" ($patient2.Length -gt 0) "none"

$adm = Call "POST" "/clinical/inpatient/admissions" $eastAdminToken @{
    patientId = $patient2; wardId = $wardId; bedId = $null; attendingDoctor = $eastAdminUser
}
Check "east admin admits patient" ($adm.code -eq 200) $adm.raw
$admissionId = ""
if ($adm.code -eq 200) { $admissionId = $adm.body.data.id }
$admCampus = ""
if ($admissionId.Length -gt 0) { $admCampus = SqlOne ("select v.campus_id from clinical_svc.admission a join clinical_svc.visit v on v.id=a.visit_id where a.id='" + $admissionId + "';") }
Check "admission visit campus EAST" ($admCampus -eq $EAST) ($admCampus + " " + $adm.raw)

$admMain = Call "GET" ("/clinical/inpatient/admissions/" + $admissionId) $doctorToken $null
Check "MAIN doctor cannot see EAST admission (404)" ($admMain.code -eq 404) $admMain.raw
$admAdmin = Call "GET" ("/clinical/inpatient/admissions/" + $admissionId) $adminToken $null
Check "cross-campus admin sees EAST admission" ($admAdmin.code -eq 200) $admAdmin.raw

$wardsMain = Call "GET" "/clinical/wards" $doctorToken $null
$wardVisible = $true
if ($wardsMain.code -eq 200) { $wardVisible = (@($wardsMain.body.data) | Where-Object { $_.id -eq $wardId } | Measure-Object).Count -gt 0 }
Check "MAIN ward list excludes EAST ward" (($wardsMain.code -eq 200) -and (-not $wardVisible)) $wardsMain.raw

# ---------------------------------------------------------------- 6) audit + db counts
$audit = SqlOne "select count(*) from auth_svc.change_log where action in ('CAMPUS_CREATE','CAMPUS_UPDATE') and actor='admin01';"
Check "campus audit events recorded" ([int]$audit -ge 3) $audit

$visitByCampus = SqlOne "select campus_id::text || ':' || count(*) from clinical_svc.visit group by campus_id order by 1;"
Write-Output ("INFO visit by campus -> " + ($visitByCampus -replace "`n", " | "))

# ---------------------------------------------------------------- 7) pharmacy campus (G47)
$pharmToken = Login "pharm01" "Med@2026"
$drugCode = SqlOne "select d.drug_code from pharmacy_svc.md_drug d join pharmacy_svc.drug_batch b on b.drug_id=d.id where d.enabled and b.quantity > 0 group by d.drug_code order by d.drug_code limit 1;"
Check "stocked drug found" ($drugCode.Length -gt 0) "none"

$rx = Call "POST" "/pharmacy/prescriptions" $eastAdminToken @{
    patientId = $patientId; visitId = $visitId; visitNo = $null; deptId = $DEPT_WARD;
    billingMode = "ORDER";
    items = @(@{ drugCode = $drugCode; quantity = 1; dosage = "1 tab"; frequency = "qd"; days = 1; singleDose = $null; doseUnit = $null; timesPerDay = $null })
}
Check "east admin creates prescription" ($rx.code -eq 200) $rx.raw
$rxId = ""
if ($rx.code -eq 200) { $rxId = $rx.body.data.prescription.id }
$rxCampus = ""
if ($rxId.Length -gt 0) { $rxCampus = SqlOne ("select campus_id from pharmacy_svc.prescription where id='" + $rxId + "';") }
Check "prescription campus persisted EAST" ($rxCampus -eq $EAST) ($rxCampus + " " + $rx.raw)

$rxListMain = Call "GET" "/pharmacy/prescriptions?limit=200" $pharmToken $null
$rxSeenMain = $false
if ($rxListMain.code -eq 200) { $rxSeenMain = (@($rxListMain.body.data) | Where-Object { $_.id -eq $rxId } | Measure-Object).Count -gt 0 }
Check "MAIN pharmacist list excludes EAST prescription" (($rxListMain.code -eq 200) -and (-not $rxSeenMain)) $rxListMain.raw

$rxDetailMain = Call "GET" ("/pharmacy/prescriptions/" + $rxId) $pharmToken $null
Check "MAIN pharmacist cannot open EAST prescription (404)" ($rxDetailMain.code -eq 404) $rxDetailMain.raw

$rxDetailAdmin = Call "GET" ("/pharmacy/prescriptions/" + $rxId) $adminToken $null
Check "cross-campus admin opens EAST prescription" ($rxDetailAdmin.code -eq 200) $rxDetailAdmin.raw

$sumMain = Call "GET" "/pharmacy/prescriptions/summary" $pharmToken $null
$sumAdmin = Call "GET" "/pharmacy/prescriptions/summary" $adminToken $null
$totalMain = 0
$totalAdmin = 0
if ($sumMain.code -eq 200) { foreach ($p in $sumMain.body.data.PSObject.Properties) { $totalMain += [int]$p.Value } }
if ($sumAdmin.code -eq 200) { foreach ($p in $sumAdmin.body.data.PSObject.Properties) { $totalAdmin += [int]$p.Value } }
Check "summary counts exclude other campus" (($sumMain.code -eq 200) -and ($sumAdmin.code -eq 200) -and ($totalAdmin -gt $totalMain)) ("main=" + $totalMain + " admin=" + $totalAdmin)

# ---------------------------------------------------------------- 8) insurance campus (G48)
$mainBillId = SqlOne "select id from insurance_svc.settlement_bill where campus_id='a0000000-0000-0000-0000-000000000001' order by created_at desc limit 1;"
$mainBillVisit = SqlOne ("select visit_id from insurance_svc.settlement_bill where id='" + $mainBillId + "';")
Check "main-campus bill found" ($mainBillId.Length -gt 0) "none"

$billMainDetail = Call "GET" ("/insurance/settlements/" + $mainBillId) $eastAdminToken $null
Check "EAST-only account cannot open MAIN bill (404)" ($billMainDetail.code -eq 404) $billMainDetail.raw
$billAdminDetail = Call "GET" ("/insurance/settlements/" + $mainBillId) $adminToken $null
Check "cross-campus admin opens MAIN bill" ($billAdminDetail.code -eq 200) $billAdminDetail.raw

$billListEast = Call "GET" "/insurance/settlements?limit=200" $eastAdminToken $null
$billSeenEast = $false
if ($billListEast.code -eq 200) { $billSeenEast = (@($billListEast.body.data) | Where-Object { $_.id -eq $mainBillId } | Measure-Object).Count -gt 0 }
Check "EAST bill list excludes MAIN bill" (($billListEast.code -eq 200) -and (-not $billSeenEast)) $billListEast.raw

$billByVisitEast = Call "GET" ("/insurance/settlements/by-visit/" + $mainBillVisit) $eastAdminToken $null
Check "EAST by-visit blocked for MAIN visit" ($billByVisitEast.code -eq 404) $billByVisitEast.raw

$realtimeId = SqlOne "select r.id from insurance_svc.realtime_settlement r join insurance_svc.settlement_bill b on b.id=r.bill_id where b.campus_id='a0000000-0000-0000-0000-000000000001' limit 1;"
if ($realtimeId.Length -gt 0) {
    $rtEast = Call "GET" ("/insurance/realtime-settlements/" + $realtimeId) $eastAdminToken $null
    $rtBlocked = ($rtEast.code -eq 404) -or ($rtEast.body -ne $null -and $rtEast.body.code -eq 48416)
    Check "EAST cannot open MAIN realtime settlement (404/48416)" $rtBlocked $rtEast.raw
    $rtAdmin = Call "GET" ("/insurance/realtime-settlements/" + $realtimeId) $adminToken $null
    Check "cross-campus admin opens MAIN realtime settlement" ($rtAdmin.code -eq 200) $rtAdmin.raw
} else {
    Check "realtime settlement sample exists" $false "no realtime rows"
}

Write-Output ("RESULT pass=" + $script:pass + " fail=" + $script:fail)
if ($script:fail -gt 0) { exit 1 }
