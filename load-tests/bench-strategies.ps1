<#
.SYNOPSIS
  W6.D4 benchmark matrix: SeatLockingStrategy (redis-hold | optimistic) x request threads (platform | virtual),
  each measured with the hold-throughput loop (strategies.js) and the flash sale (flash-sale.js).
.DESCRIPTION
  Per config: recreate app-1/app-2 with the bench overlay, verify the config is really live (strategy tag on the hold
  counters in Prometheus; thread dump: no http-nio-8080-exec-* threads = virtual threads), one discarded warm-up of
  each workload, then -Runs measured runs of each. Medians per config go to the console as a markdown table; every run
  goes to load-tests\results\bench-strategies-<time>.csv. At the end the normal stack (no bench overlay) is restored.
.EXAMPLE
  .\load-tests\bench-strategies.ps1                      # full matrix, 3 runs each (~35-40 min)
  .\load-tests\bench-strategies.ps1 -Runs 1 -Configs 'redis-hold/platform'   # quick smoke
.NOTES
  Local only (decision #69). Rate limiting is off in the bench overlay (same for every config).
#>
param(
    [int]$Runs = 3,
    [string[]]$Configs = @('redis-hold/platform', 'optimistic/platform', 'redis-hold/virtual', 'optimistic/virtual'),
    [int]$Vus = 50,
    [string]$Duration = '60s',
    [int]$SaleUsers = 500,
    [int]$SaleSeats = 10
)
$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '..')
$base = @('compose', '--env-file', '.env', '-f', 'infra/docker-compose.local.yml', '-f', 'infra/docker-compose.scale.yml')
$bench = $base + @('-f', 'infra/docker-compose.bench.yml')
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$csv = "load-tests\results\bench-strategies-$stamp.csv"
New-Item -ItemType Directory -Force -Path 'load-tests\results' | Out-Null
$rows = New-Object System.Collections.Generic.List[object]

function Invoke-Prom([string]$query) {
    (Invoke-RestMethod "http://localhost:9090/api/v1/query?query=$([uri]::EscapeDataString($query))").data.result
}

function Set-Config([string]$strategy, [string]$threads) {
    $env:BENCH_STRATEGY = $strategy
    $env:BENCH_VIRTUAL_THREADS = if ($threads -eq 'virtual') { 'true' } else { 'false' }
    Write-Host "`n########## $strategy / $threads ##########" -ForegroundColor Cyan
    docker @bench up -d --force-recreate app-1 app-2 | Out-Null
}

function Confirm-Config([string]$strategy, [string]$threads) {
    # Health first (run-k6.ps1 also waits), then one Prometheus scrape after startup.
    $deadline = (Get-Date).AddMinutes(3)
    do {
        Start-Sleep 5
        $ok = 0
        foreach ($i in 1..4) { try { if ((Invoke-WebRequest -UseBasicParsing 'http://localhost:8088/actuator/health' -TimeoutSec 5).StatusCode -eq 200) { $ok++ } } catch {} }
    } while ($ok -lt 4 -and (Get-Date) -lt $deadline)
    if ($ok -lt 4) { throw "Stack not healthy for $strategy/$threads" }
    Start-Sleep 20
    $seen = @(Invoke-Prom 'count by (instance, strategy) (seatlock_holds_total{instance=~"app-.*"})' |
        ForEach-Object { "$($_.metric.instance)=$($_.metric.strategy)" })
    $wrong = @($seen | Where-Object { $_ -notmatch "=$strategy$" })
    if ($seen.Count -lt 2 -or $wrong.Count -gt 0) { throw "Strategy check failed: expected $strategy on both, Prometheus says: $($seen -join ', ')" }
    $ErrorActionPreference = 'Continue'   # docker logs passes container stderr through; not an error here
    $limiter = @(docker logs seatlock-app-1 2>&1 | Select-String 'Rate limiting DISABLED').Count
    if ($limiter -eq 0) { throw 'Rate limiting is not DISABLED on app-1: is infra/docker-compose.bench.yml applied?' }
    $since = (Get-Date).ToUniversalTime().AddSeconds(-2).ToString('yyyy-MM-ddTHH:mm:ssZ')
    docker kill -s QUIT seatlock-app-1 | Out-Null
    Start-Sleep 3
    $execThreads = @(docker logs --since $since seatlock-app-1 2>&1 | Select-String '"http-nio-8080-exec-').Count
    $isVirtual = ($execThreads -eq 0)
    if (($threads -eq 'virtual') -ne $isVirtual) { throw "Thread check failed: wanted $threads, thread dump shows $execThreads platform request threads" }
    Write-Host "Verified: strategy $strategy on app-1 + app-2; request threads $threads ($execThreads http-nio-8080-exec threads); rate limiting off" -ForegroundColor Green
}

function Invoke-K6([string]$script, [string[]]$k6Args, [string]$summary) {
    $all = $k6Args + @('-e', "SUMMARY_FILE=$summary")
    & .\load-tests\run-k6.ps1 -Script $script -K6Args $all | Out-Null
    $code = $LASTEXITCODE
    $data = Get-Content $summary -Raw | ConvertFrom-Json
    return @{ code = $code; data = $data }
}

function Get-Trend($data, [string]$name, [string]$stat) {
    $m = $data.metrics.PSObject.Properties[$name]
    if ($m) { return [math]::Round([double]$m.Value.values.$stat, 1) } else { return $null }
}
function Get-Count($data, [string]$name) {
    $m = $data.metrics.PSObject.Properties[$name]
    if ($m) { return [int]$m.Value.values.count } else { return 0 }
}

function Add-Row([string]$config, [string]$mode, [int]$run, $result) {
    $d = $result.data
    $seconds = [double]$d.state.testRunDurationMs / 1000
    $hold = 'http_req_duration{name:POST /shows/{id}/holds}'
    if ($mode -eq 'throughput') {
        $ok = Get-Count $d 'holds_ok'
        $rate = [math]::Round($ok / [double]($Duration.TrimEnd('s')), 1)
    } else {
        $ok = Get-Count $d 'holds_won'
        $rate = [math]::Round([double]$d.metrics.http_reqs.values.rate, 1)
    }
    $row = [pscustomobject]@{
        config = $config; mode = $mode; run = $run
        holds_ok = $ok; per_sec = $rate
        hold_p50 = Get-Trend $d $hold 'med'; hold_p95 = Get-Trend $d $hold 'p(95)'; hold_p99 = Get-Trend $d $hold 'p(99)'
        conflicts = Get-Count $d 'hold_conflicts'; unexpected = Get-Count $d 'unexpected_responses'
        seats_won = Get-Count $d 'seats_won'; exit = $result.code; seconds = [math]::Round($seconds, 1)
    }
    $rows.Add($row)
    $row | Export-Csv -Path $csv -NoTypeInformation -Append
    Write-Host ("  {0,-22} {1,-10} run {2}: ok {3}, {4}/s, hold p50/p95/p99 {5}/{6}/{7} ms, conflicts {8}, unexpected {9}, exit {10}" -f `
        $config, $mode, $run, $ok, $rate, $row.hold_p50, $row.hold_p95, $row.hold_p99, $row.conflicts, $row.unexpected, $result.code)
}

function Get-Median([double[]]$values) {
    $v = @($values | Sort-Object)
    if ($v.Count -eq 0) { return $null }
    if ($v.Count % 2 -eq 1) { return $v[[int][math]::Floor($v.Count / 2)] }
    return ($v[$v.Count / 2 - 1] + $v[$v.Count / 2]) / 2
}

try {
    foreach ($config in $Configs) {
        $strategy, $threads = $config -split '/'
        Set-Config $strategy $threads
        Confirm-Config $strategy $threads
        $tp = @('-e', "VUS=$Vus", '-e', "DURATION=$Duration")
        $sale = @('-e', "USERS=$SaleUsers", '-e', "SEATS=$SaleSeats")
        # Warm-up as long as a measured run: with 20 s, run 1 of every config was ~30 % slower than runs 2-3 (W6.D4).
        Write-Host "  warm-up (discarded): $Duration throughput + 1 sale"
        Invoke-K6 'strategies' $tp "load-tests\results\warmup-$stamp.json" | Out-Null
        Invoke-K6 'flash-sale' $sale "load-tests\results\warmup-$stamp.json" | Out-Null
        foreach ($r in 1..$Runs) {
            Add-Row $config 'throughput' $r (Invoke-K6 'strategies' $tp "load-tests\results\bench-$stamp-$($strategy)-$threads-tp-$r.json")
            Add-Row $config 'sale' $r (Invoke-K6 'flash-sale' $sale "load-tests\results\bench-$stamp-$($strategy)-$threads-sale-$r.json")
        }
    }
} finally {
    Remove-Item Env:BENCH_STRATEGY, Env:BENCH_VIRTUAL_THREADS -ErrorAction SilentlyContinue
    Write-Host "`nRestoring the normal stack (no bench overlay, rate limiting back on)..."
    docker @base up -d --force-recreate app-1 app-2 | Out-Null
}

Write-Host "`n### Medians of $Runs runs (throughput: $Vus VUs x $Duration hold+release; sale: $SaleUsers users -> $SaleSeats seats)`n"
Write-Host '| Config | Workload | Holds OK | Holds/s (tp) or req/s (sale) | Hold p50 | Hold p95 | Hold p99 | Conflicts | Unexpected | Runs failed |'
Write-Host '|---|---|---|---|---|---|---|---|---|---|'
foreach ($g in ($rows | Group-Object config, mode)) {
    $r = $g.Group
    $failed = @($r | Where-Object { $_.exit -ne 0 }).Count
    Write-Host ("| {0} | {1} | {2} | {3} | {4} ms | {5} ms | {6} ms | {7} | {8} | {9}/{10} |" -f `
        $r[0].config, $r[0].mode, (Get-Median $r.holds_ok), (Get-Median $r.per_sec), (Get-Median $r.hold_p50),
        (Get-Median $r.hold_p95), (Get-Median $r.hold_p99), (Get-Median $r.conflicts), (Get-Median $r.unexpected), $failed, $r.Count)
}
Write-Host "`nAll runs: $csv"
