<#
.SYNOPSIS
  Writes (or removes) fake seat-hold keys for one show straight into local Redis, so the seat map
  can be benchmarked with HELD seats before the real hold API exists (W3·D2).

.EXAMPLE
  .\load-tests\seed-holds.ps1 -ShowId 34 -Count 50          # hold the first 50 AVAILABLE seats for 10 min
  .\load-tests\seed-holds.ps1 -ShowId 34 -Clear             # delete every hold key of show 34

.NOTES
  Local stack only. Uses the same key schema as HoldKeys: hold:show:{showId}:seat:{seatId} -> holdId.
#>
param(
    [Parameter(Mandatory)] [long]$ShowId,
    [int]$Count = 50,
    [int]$TtlSeconds = 600,
    [switch]$Clear,
    [string]$BaseUrl = "http://localhost:8080",
    [string]$Container = "seatlock-redis"   # container_name in infra\docker-compose.local.yml
)

$ErrorActionPreference = "Stop"
if ((docker inspect -f "{{.State.Running}}" $Container 2>$null) -ne "true") {
    throw "Redis container '$Container' isn't running (docker start $Container)"
}

if ($Clear) {
    $keys = docker exec $Container redis-cli --scan --pattern "hold:show:${ShowId}:seat:*"
    if ($keys) { $keys | ForEach-Object { "DEL $_" } | docker exec -i $Container redis-cli | Out-Null }
    Write-Host "Removed $(@($keys).Count) hold keys for show $ShowId"
    return
}

$seats = (Invoke-RestMethod "$BaseUrl/api/v1/shows/$ShowId/seats").seats |
        Where-Object status -eq 'AVAILABLE' | Select-Object -First $Count
$commands = $seats | ForEach-Object { "SET hold:show:${ShowId}:seat:$($_.seatId) bench-hold EX $TtlSeconds" }
$commands | docker exec -i $Container redis-cli | Out-Null
Write-Host "Held $(@($seats).Count) seats of show $ShowId for $TtlSeconds s"
