<#
.SYNOPSIS
  Sequential latency benchmark of the three public catalog reads (event list, event detail, seat map).
  Run it twice against the same local stack, cache off then on, and compare the tables.

.EXAMPLE
  # Terminal 1 (backend):  $env:SEATLOCK_CACHE_ENABLED="false"; .\mvnw.cmd spring-boot:run
  # Terminal 2:            .\load-tests\bench-catalog.ps1 -Label cache-off
  # Restart backend with $env:SEATLOCK_CACHE_ENABLED="true", then:
  #                        .\load-tests\bench-catalog.ps1 -Label cache-on

.NOTES
  One client, one request at a time: this measures per-request latency, not throughput
  (k6 load tests with many users come on W6·D3). Times are client-side, end to end over localhost.
  Fair runs: start the backend with $env:LOGGING_LEVEL_COM_SEATLOCK="INFO" (the local profile logs
  every request at DEBUG, which adds console I/O noise), close other heavy apps, keep the laptop
  plugged in, and run every variant the same way. Full procedure: docs/benchmarks.md.
#>
param(
    [string]$BaseUrl = "http://localhost:8080",
    [int]$Requests = 500,
    [int]$Warmup = 200,
    [string]$Label = "run"
)

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Net.Http
$client = [System.Net.Http.HttpClient]::new()
$client.DefaultRequestHeaders.Add("Accept", "application/json")

function Get-Json([string]$path) {
    $response = $client.GetAsync("$BaseUrl$path").GetAwaiter().GetResult()
    if (-not $response.IsSuccessStatusCode) { throw "GET $path -> $([int]$response.StatusCode)" }
    return $response.Content.ReadAsStringAsync().GetAwaiter().GetResult() | ConvertFrom-Json
}

function Get-Percentile([double[]]$sorted, [double]$q) {
    $index = [math]::Max(0, [math]::Ceiling($q * $sorted.Length) - 1)
    return [math]::Round($sorted[$index], 2)
}

function Measure-Endpoint([string]$name, [string]$path) {
    for ($i = 0; $i -lt $Warmup; $i++) {   # warm JIT, connection pool and (when on) the cache
        $r = $client.GetAsync("$BaseUrl$path").GetAwaiter().GetResult()
        $null = $r.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult()
    }
    $times = New-Object 'System.Collections.Generic.List[double]'
    for ($i = 0; $i -lt $Requests; $i++) {
        $sw = [System.Diagnostics.Stopwatch]::StartNew()
        $r = $client.GetAsync("$BaseUrl$path").GetAwaiter().GetResult()
        $null = $r.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult()   # include reading the body
        $sw.Stop()
        if (-not $r.IsSuccessStatusCode) { throw "GET $path -> $([int]$r.StatusCode)" }
        $times.Add($sw.Elapsed.TotalMilliseconds)
    }
    $sorted = [double[]]($times | Sort-Object)
    [pscustomobject]@{
        Label    = $Label
        Endpoint = $name
        p50_ms   = Get-Percentile $sorted 0.50
        p95_ms   = Get-Percentile $sorted 0.95
        p99_ms   = Get-Percentile $sorted 0.99
        mean_ms  = [math]::Round(($times | Measure-Object -Average).Average, 2)
        n        = $Requests
    }
}

$eventId = (Get-Json "/api/v1/events?limit=1").items[0].id
$showId = (Get-Json "/api/v1/events/$eventId").upcomingShows[0].id
Write-Host "Benchmarking '$Label' against $BaseUrl (event $eventId, show $showId), $Requests requests each..."

$results = @(
    Measure-Endpoint "GET /events (list)"      "/api/v1/events"
    Measure-Endpoint "GET /events/{id}"        "/api/v1/events/$eventId"
    Measure-Endpoint "GET /shows/{id}/seats"   "/api/v1/shows/$showId/seats"
)
$results | Format-Table -AutoSize
$csv = Join-Path $PSScriptRoot "results\bench-catalog.csv"
New-Item -ItemType Directory -Force -Path (Split-Path $csv) | Out-Null
$results | Export-Csv -Path $csv -Append -NoTypeInformation
Write-Host "Appended to $csv"
