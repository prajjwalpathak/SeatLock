<#
.SYNOPSIS
  Runs a k6 load test (W6.D3) against the local 2-instance stack and, for the flash sale, checks the
  oversell invariant directly in Postgres afterwards.
.EXAMPLE
  .\load-tests\run-k6.ps1 -Script browse
  .\load-tests\run-k6.ps1 -Script flash-sale
  .\load-tests\run-k6.ps1 -Script flash-sale -K6Args '-e','USERS=500','-e','SEATS=10'
.NOTES
  Local only, never the VM (burst credits, decision #69). Results (JSON) go to load-tests\results (git-ignored).
#>
param(
    [Parameter(Mandatory = $true)][ValidateSet('browse', 'flash-sale')][string]$Script,
    [string]$BaseUrl = 'http://localhost:8088',
    [string[]]$K6Args = @(),
    [string]$EnvFile = (Join-Path $PSScriptRoot '..\.env')
)
$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '..')     # repo root: handleSummary writes load-tests/results/...

if (-not (Get-Command k6 -ErrorAction SilentlyContinue)) { throw 'k6 not found: winget install k6 --source winget' }
New-Item -ItemType Directory -Force -Path 'load-tests\results' | Out-Null

# Pre-flight: both instances healthy through the LB (a cold or half-started stack gives meaningless numbers).
# Waits up to 2 min, so it can follow a container recreate directly. 4 OKs in a row = both round-robin targets up.
$deadline = (Get-Date).AddMinutes(2)
do {
    $ok = 0
    foreach ($i in 1..4) {
        try { $r = Invoke-WebRequest -UseBasicParsing "$BaseUrl/actuator/health" -TimeoutSec 5; if ($r.StatusCode -eq 200) { $ok++ } } catch {}
    }
    if ($ok -lt 4) { Write-Host "Waiting for $BaseUrl ($ok/4 health checks OK)..."; Start-Sleep 5 }
} while ($ok -lt 4 -and (Get-Date) -lt $deadline)
if ($ok -lt 4) { throw "Stack at $BaseUrl is not healthy ($ok/4 health checks OK). Start it: docs/local-scale.md" }

Write-Host "k6 run $Script.js against $BaseUrl $($K6Args -join ' ')" -ForegroundColor Cyan
# k6 logs to stderr; with 'Stop' (and 2>&1 in PowerShell 5.1) its first INFO line would end this script.
$ErrorActionPreference = 'Continue'
& k6 run -e "BASE_URL=$BaseUrl" @K6Args "load-tests\$Script.js"
$k6Exit = $LASTEXITCODE

if ($Script -eq 'flash-sale') {
    function Get-EnvValue([string]$name, [string]$default) {
        if (Test-Path $EnvFile) {
            $line = Get-Content $EnvFile | Where-Object { $_ -match "^$name=" } | Select-Object -First 1
            if ($line) { return ($line -split '=', 2)[1].Trim() }
        }
        return $default
    }
    $db = Get-EnvValue 'POSTGRES_DB' 'seatlock'
    $user = Get-EnvValue 'POSTGRES_USER' 'seatlock'
    $sql = 'SELECT count(*) FROM (SELECT 1 FROM booking_seats WHERE active GROUP BY show_id, seat_id HAVING count(*) > 1) d;'
    $doubleSold = (docker exec seatlock-postgres psql -U $user -d $db -tA -c $sql).Trim()
    if ($doubleSold -eq '0') {
        Write-Host "DB invariant: double-sold seats = 0 (oversells = 0)" -ForegroundColor Green
    } else {
        Write-Host "DB invariant BROKEN: $doubleSold seats have more than one live booking" -ForegroundColor Red
        $k6Exit = 99
    }
}
exit $k6Exit
