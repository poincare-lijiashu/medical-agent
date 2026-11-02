# ============================================================================
# MedAssist concurrency smoke (multi-agent review P2-12).
#
# The other 30+ E2E scripts are sequential: they prove "happy path works" but
# never prove "two windows pressing the button at the same time is safe".
# This script fires truly concurrent requests (shared HttpClient + Task.WaitAll)
# and asserts the invariants that make money/inventory correct:
#
#   1. slot booking (total=1): N concurrent registrations -> exactly one wins
#   2. settlement: N concurrent settles -> one charge only, total not doubled
#   3. ward receipt: N concurrent receipts -> never more than the daily request
#
# ASCII only on purpose: Windows PowerShell 5.1 reads BOM-less UTF-8 as ANSI.
#
# Prerequisites: auth :8081, empi :8082, clinical :8083, pharmacy :8084, gateway
# Usage (repo root):
#   powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\e2e\concurrency_smoke.ps1
# ============================================================================
param(
    [string]$BaseUrl = "http://127.0.0.1:8080",
    [string]$Username = "admin01",
    [string]$Password = "Med@2026",
    [string]$DeptId = "11111111-1111-1111-1111-111111111111",
    [int]$Fanout = 6
)

$ErrorActionPreference = "Stop"
# PS 5.1 does not preload System.Net.Http (needed for the shared HttpClient below).
Add-Type -AssemblyName System.Net.Http
$script:fail = 0

function Invoke-Api {
    param([string]$Method, [string]$Path, $Body = $null, [string]$Token = $null)
    $out = [IO.Path]::GetTempFileName()
    $args = @('--noproxy', '*', '-s', '-o', $out, '-w', '%{http_code}', '-X', $Method, ($BaseUrl + $Path))
    if ($Token) { $args += @('-H', "Authorization: Bearer $Token") }
    if ($null -ne $Body) {
        $json = $Body | ConvertTo-Json -Depth 8 -Compress
        $bf = [IO.Path]::GetTempFileName()
        [IO.File]::WriteAllText($bf, $json, (New-Object System.Text.UTF8Encoding($false)))
        $args += @('-H', 'Content-Type: application/json; charset=utf-8', '--data-binary', "@$bf")
    }
    $code = & curl.exe @args
    $text = Get-Content -Raw -Path $out -Encoding UTF8
    Remove-Item $out -Force
    return [pscustomobject]@{ Status = [int]$code; Body = $text }
}

function Check($name, $actual, $expected) {
    if ("$actual" -eq "$expected") { Write-Output "PASS $name = $actual" }
    else { Write-Output "FAIL $name expected=$expected actual=$actual"; $script:fail++ }
}

function CheckTrue($name, $condition) {
    if ($condition) { Write-Output "PASS $name" }
    else { Write-Output "FAIL $name"; $script:fail++ }
}

function Res($r) { return (($r.Body | ConvertFrom-Json).data) }

# ---------------------------------------------------------------- concurrency
# One shared HttpClient: SendAsync returns immediately, so all requests are
# really in flight before we wait. No process/job startup jitter.
function Invoke-Concurrent {
    param([string]$Method, [string]$Path, [scriptblock]$BodyFactory, [int]$Count, [string]$Token)
    $client = New-Object System.Net.Http.HttpClient
    $client.Timeout = [TimeSpan]::FromSeconds(60)
    $tasks = New-Object System.Collections.Generic.List[System.Threading.Tasks.Task]
    try {
        for ($i = 0; $i -lt $Count; $i++) {
            $req = New-Object System.Net.Http.HttpRequestMessage
            $req.Method = [System.Net.Http.HttpMethod]::new($Method)
            $req.RequestUri = [Uri]::new($BaseUrl + $Path)
            $req.Headers.TryAddWithoutValidation('Authorization', "Bearer $Token") | Out-Null
            $payload = & $BodyFactory $i
            if ($null -ne $payload) {
                $json = $payload | ConvertTo-Json -Depth 8 -Compress
                $req.Content = New-Object System.Net.Http.StringContent($json, ([Text.Encoding]::UTF8), 'application/json')
            }
            $tasks.Add($client.SendAsync($req))
        }
        [System.Threading.Tasks.Task]::WaitAll($tasks.ToArray())
        $results = @()
        foreach ($task in $tasks) {
            $resp = $task.Result
            $text = $resp.Content.ReadAsStringAsync().Result
            $results += [pscustomobject]@{ Status = [int]$resp.StatusCode; Body = $text }
            $resp.Dispose()
        }
        return $results
    } finally {
        $client.Dispose()
    }
}

function CountSuccess($results) {
    return @($results | Where-Object { $_.Status -ge 200 -and $_.Status -lt 300 }).Count
}

function CountStatus($results, [int]$code) {
    return @($results | Where-Object { $_.Status -eq $code }).Count
}

function Count5xx($results) {
    return @($results | Where-Object { $_.Status -ge 500 }).Count
}

$suffix = Get-Random -Minimum 1000 -Maximum 9999
$today = (Get-Date).ToString('yyyy-MM-dd')

# ---------------------------------------------------------------- login
$r = Invoke-Api 'POST' '/api/v2/auth/login' @{ username = $Username; password = $Password }
Check 'login' $r.Status 200
$token = (Res $r).accessToken
if (-not $token) { Write-Output "FATAL no token: $($r.Body)"; exit 1 }

# ============================================================================
# 1. Slot booking: total=1, N concurrent registrations
# ============================================================================
# doctorId is random per run: slot uniqueness is (dept, doctor, date, period).
$raceDoctor = "race-doc-$suffix"
$r = Invoke-Api 'POST' '/api/v2/clinical/slots' @{
    deptId = $DeptId; doctorId = $raceDoctor; workDate = $today; period = 'AM'; total = 1
} $token
Check 'slot.create' $r.Status 200
$slotId = (Res $r).id
CheckTrue 'slot.id.present' ($null -ne $slotId -and "$slotId" -ne '')

$r = Invoke-Api 'POST' '/api/v2/empi/patients' @{
    name = "Race Slot $suffix"; gender = 'MALE'; birthDate = '1985-05-05'
    idType = 'ID_CARD'; idNo = "11010119850505$suffix"
} $token
Check 'slot.patient' $r.Status 200
$slotPatientId = (Res $r).id

$slotResults = Invoke-Concurrent -Method 'POST' -Path '/api/v2/clinical/registrations' `
    -BodyFactory { param($i) @{ patientId = $slotPatientId; deptId = $DeptId; doctorId = $raceDoctor; slotId = $slotId } } `
    -Count $Fanout -Token $token

Check 'slot.winners' (CountSuccess $slotResults) 1
$accounted = (CountSuccess $slotResults) + (CountStatus $slotResults 409) + (CountStatus $slotResults 422)
Check 'slot.losers.rejected' $accounted $Fanout
CheckTrue 'slot.no.5xx' ((Count5xx $slotResults) -eq 0)

# ============================================================================
# 2. Ward admission (inpatient visit: orders/settlement need IN_TREATMENT)
# ============================================================================
$r = Invoke-Api 'POST' '/api/v2/empi/patients' @{
    name = "Race Ward $suffix"; gender = 'MALE'; birthDate = '1968-08-08'
    idType = 'ID_CARD'; idNo = "11010119680808$suffix"
} $token
Check 'ward.patient' $r.Status 200
$wardPatientId = (Res $r).id

$r = Invoke-Api 'POST' '/api/v2/clinical/wards' @{
    code = "W-RACE-$suffix"; name = "Race Ward $suffix"; deptId = $DeptId
} $token
Check 'ward.create' $r.Status 200
$wardId = (Res $r).id
$r = Invoke-Api 'POST' "/api/v2/clinical/wards/$wardId/beds" @{ bedNos = @('01') } $token
Check 'ward.bed' $r.Status 200
$bedId = @(Res $r)[0].id
$r = Invoke-Api 'POST' '/api/v2/clinical/inpatient/admissions' @{
    patientId = $wardPatientId; wardId = $wardId; bedId = $bedId; attendingDoctor = 'doctor01'
} $token
Check 'ward.admit' $r.Status 200
$wardVisitId = (Res $r).visitId

# ============================================================================
# 3. Settlement: N concurrent settles on the same inpatient visit
# ============================================================================
$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$wardVisitId/orders" @{
    items = @(@{ itemCode = 'TRT-IV'; quantity = 1 })
} $token
Check 'settle.order' $r.Status 200

$settleResults = Invoke-Concurrent -Method 'POST' -Path "/api/v2/clinical/visits/$wardVisitId/settle" `
    -BodyFactory { param($i) @{ paymentMethod = 'CASH' } } -Count $Fanout -Token $token

# 结算本身幂等（已结算再点返回同一张单），所以"成功数"不必须是 1；
# 真正的不变量是：只有一张收费单、金额没有被重复累加、没有 5xx。
$okCount = 0
$firstChargeNo = $null
$firstTotal = [decimal]0
foreach ($x in @($settleResults)) {
    $status = [int]$x.Status
    if ($status -eq 200) {
        $okCount = $okCount + 1
        $data = ($x.Body | ConvertFrom-Json).data
        if ($null -eq $firstChargeNo) {
            $firstChargeNo = $data.chargeNo
            $firstTotal = [decimal]$data.totalAmount
        }
    }
}
CheckTrue 'settle.at.least.one.success' ($okCount -ge 1)
if ($okCount -eq 0) {
    foreach ($x in @($settleResults)) { Write-Output "DEBUG settle status=$($x.Status) body=$($x.Body)" }
}
CheckTrue 'settle.no.5xx' ((Count5xx $settleResults) -eq 0)
CheckTrue 'settle.single.charge' ($null -ne $firstChargeNo)

$r = Invoke-Api 'GET' "/api/v2/clinical/visits/$wardVisitId/charge" $null $token
Check 'settle.charge.status' (Res $r).status 'PAID'
$finalTotal = [decimal](Res $r).totalAmount
Check 'settle.total.not.doubled' $finalTotal $firstTotal

# ============================================================================
# 4. Ward receipt: daily request = 2, N concurrent receipts of 1
# ============================================================================
$r = Invoke-Api 'GET' '/api/v2/pharmacy/drugs?keyword=DRUG-AMOX' $null $token
Check 'drug.list' $r.Status 200
$amoxId = @(Res $r)[0].drug.id
$far = (Get-Date).AddDays(300).ToString('yyyy-MM-dd')
$r = Invoke-Api 'POST' "/api/v2/pharmacy/drugs/$amoxId/batches" @{
    batchNo = "B-RACE-$suffix"; expireDate = $far; quantity = 20; supplier = 'GuoYao'
} $token
Check 'drug.stock' $r.Status 200

$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$wardVisitId/orders" @{
    items = @(@{ itemCode = 'DRUG-AMOX-025'; quantity = 2; dose = '0.5g'; frequency = 'bid'; orderMode = 'LONG' })
} $token
Check 'dispense.order' $r.Status 200
$wardOrderId = @(Res $r)[0].id

$r = Invoke-Api 'POST' "/api/v2/clinical/visits/$wardVisitId/daily-dispense-requests" @{} $token
Check 'dispense.request' $r.Status 200
$rxId = @(Res $r).items[0].prescriptionId
CheckTrue 'dispense.prescription' ($null -ne $rxId)

$r = Invoke-Api 'POST' "/api/v2/pharmacy/prescriptions/$rxId/review" @{ approve = $true } $token
Check 'dispense.approve' $r.Status 200
$r = Invoke-Api 'POST' "/api/v2/pharmacy/prescriptions/$rxId/dispense" @{} $token
Check 'dispense.done' $r.Status 200
$dispenseId = (Res $r).dispense.id

$receiptResults = Invoke-Concurrent -Method 'POST' `
    -Path "/api/v2/clinical/visits/$wardVisitId/orders/$wardOrderId/ward-receipts" `
    -BodyFactory { param($i) @{ dispenseId = $dispenseId; supplyDate = $today; quantity = 1 } } `
    -Count $Fanout -Token $token

# 收药按 (order, supply_date) 幂等：并发重复提交返回同一条收药记录，不会重复入库。
# 真正的不变量是"入库总量永远不超过当日请领量"，且并发下不出现 5xx。
CheckTrue 'receipt.no.5xx' ((Count5xx $receiptResults) -eq 0)
$business = (CountSuccess $receiptResults) + (CountStatus $receiptResults 409) + (CountStatus $receiptResults 422)
Check 'receipt.all.business.results' $business $Fanout

$r = Invoke-Api 'GET' "/api/v2/clinical/visits/$wardVisitId/ward-stock" $null $token
$received = [int](Res $r).totalBalance
CheckTrue 'receipt.stock.never.exceeds.daily' ($received -ge 1 -and $received -le 2)

Write-Output "-----"
Write-Output "SLOT_ID=$slotId"
Write-Output "WARD_VISIT_ID=$wardVisitId"
if ($script:fail -gt 0) { Write-Output "RESULT FAIL ($script:fail)"; exit 1 }
Write-Output 'RESULT PASS'
exit 0
