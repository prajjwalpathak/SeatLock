<#
.SYNOPSIS
  Seat Finder intent eval (W7-D3): sends the 40 phrases of backend/src/test/resources/ai-eval/seat-intents.json
  through POST /api/v1/admin/ai/seat-intent (real model, no cache, no per-user quota) and scores the parsed
  filters against the expected ones. Prints x/40, fallbacks by reason, latency, and writes a CSV to results/.

.NOTES
  Costs one Gemini call per phrase (40 of the ~400/day global budget). Paced by -DelaySeconds because the
  global cap is 12 calls/minute (a faster run just collects QUOTA fallbacks). Run against the LOCAL app only.
  Admin credentials come from the repo-root .env (SEATLOCK_ADMIN_EMAIL / SEATLOCK_ADMIN_PASSWORD).
#>
param(
    [string]$BaseUrl = 'http://localhost:8080',
    [double]$DelaySeconds = 5.5,
    [string]$EnvFile = (Join-Path $PSScriptRoot '..\.env'),
    [string]$CasesFile = (Join-Path $PSScriptRoot '..\backend\src\test\resources\ai-eval\seat-intents.json')
)
$ErrorActionPreference = 'Stop'

$envs = @{}
Get-Content $EnvFile | Where-Object { $_ -match '^[A-Z_]+=' } | ForEach-Object { $p = $_ -split '=', 2; $envs[$p[0]] = $p[1] }
$login = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1/auth/login" -ContentType 'application/json' `
    -Body (@{ email = $envs.SEATLOCK_ADMIN_EMAIL; password = $envs.SEATLOCK_ADMIN_PASSWORD } | ConvertTo-Json)
$headers = @{ Authorization = "Bearer $($login.accessToken)" }

$status = Invoke-RestMethod -Uri "$BaseUrl/api/v1/admin/ai/status" -Headers $headers
if (-not $status.enabled) { throw "AI is disabled on $BaseUrl (SEATLOCK_AI_PROVIDER). Nothing to evaluate." }
Write-Host "Model $($status.model), circuit $($status.circuit)"

$cases = (Get-Content $CasesFile -Raw -Encoding UTF8 | ConvertFrom-Json).cases

function Same-Sections($a, $b) {
    $x = @($a | Sort-Object) -join ','
    $y = @($b | Sort-Object) -join ','
    return $x -eq $y
}

$results = @()
$i = 0
foreach ($case in $cases) {
    $i++
    # PowerShell 5.1 would send a string body as ISO-8859-1: send UTF-8 bytes so the rupee sign survives
    $bytes = [Text.Encoding]::UTF8.GetBytes((@{ text = $case.text } | ConvertTo-Json -Compress))
    $sw = [Diagnostics.Stopwatch]::StartNew()
    try {
        $r = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1/admin/ai/seat-intent" -Headers $headers `
            -ContentType 'application/json; charset=utf-8' -Body $bytes
        $httpError = $null
    } catch {
        $r = $null
        $httpError = "HTTP $($_.Exception.Response.StatusCode.value__)"
    }
    $sw.Stop()

    $e = $case.expected
    $outcome = if ($httpError) { $httpError } elseif ($r.understood) { 'PARSED' } else { "$($r.fallbackReason)" }
    $ok = $false
    $diff = ''
    if ($r -and $r.understood) {
        $f = $r.filters
        $checks = [ordered]@{
            count    = ($f.count -eq $e.count)
            price    = ("$($f.maxPricePaise)" -eq "$($e.maxPricePaise)")
            sections = (Same-Sections $f.sections $e.sections)
            aisle    = ([bool]$f.aisle -eq [bool]$e.aisle)
            rows     = ("$($f.rows)" -eq "$($e.rows)")
        }
        $ok = -not ($checks.Values -contains $false)
        $diff = ($checks.GetEnumerator() | Where-Object { -not $_.Value } | ForEach-Object {
                switch ($_.Key) {
                    'count'    { "count $($f.count) != $($e.count)" }
                    'price'    { "price $($f.maxPricePaise) != $($e.maxPricePaise)" }
                    'sections' { "sections [$(@($f.sections) -join ',')] != [$(@($e.sections) -join ',')]" }
                    'aisle'    { "aisle $($f.aisle) != $($e.aisle)" }
                    'rows'     { "rows $($f.rows) != $($e.rows)" }
                }
            }) -join '; '
    }
    $mark = if ($ok) { 'PASS' } else { 'FAIL' }
    Write-Host ("{0,2} {1} {2,-14} {3,5} ms  {4}  {5}" -f $case.id, $mark, $outcome, $sw.ElapsedMilliseconds, $case.text, $diff)
    $results += [pscustomobject]@{
        id = $case.id; text = $case.text; outcome = $outcome; correct = $ok; ms = $sw.ElapsedMilliseconds; diff = $diff
    }
    if ($i -lt $cases.Count) { Start-Sleep -Milliseconds ([int]($DelaySeconds * 1000)) }
}

$correct = @($results | Where-Object correct).Count
$parsed = @($results | Where-Object { $_.outcome -eq 'PARSED' })
$ms = @($parsed | ForEach-Object { $_.ms } | Sort-Object)
function Pct($sorted, $p) { if ($sorted.Count -eq 0) { return 'n/a' }; $sorted[[math]::Min($sorted.Count - 1, [math]::Floor($p * $sorted.Count))] }

Write-Host ''
Write-Host ("Correct: {0}/{1}   Parsed: {2}   Fallbacks: {3}" -f $correct, $results.Count, $parsed.Count, ($results.Count - $parsed.Count))
$results | Where-Object { $_.outcome -ne 'PARSED' } | Group-Object outcome | ForEach-Object { Write-Host "  $($_.Name): $($_.Count)" }
Write-Host ("Latency of parsed calls: p50 {0} ms, p95 {1} ms (client side, includes HTTP + validation)" -f (Pct $ms 0.5), (Pct $ms 0.95))

$outDir = Join-Path $PSScriptRoot 'results'
New-Item -ItemType Directory -Force $outDir | Out-Null
$csv = Join-Path $outDir ("ai-intent-eval-{0:yyyyMMdd-HHmmss}.csv" -f (Get-Date))
$results | Export-Csv -NoTypeInformation -Encoding UTF8 $csv
Write-Host "CSV: $csv"
