<#
.SYNOPSIS
  Prints cache hits, misses and hit rate per cache from /actuator/metrics (ADMIN only).
  Reads SEATLOCK_ADMIN_EMAIL / SEATLOCK_ADMIN_PASSWORD from the repo-root .env, so the
  password never has to be typed or pasted.

.EXAMPLE
  .\load-tests\cache-stats.ps1
#>
param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$EnvFile = (Join-Path $PSScriptRoot "..\.env")
)

$ErrorActionPreference = "Stop"

function Get-EnvValue([string]$name) {
    $line = Get-Content $EnvFile | Where-Object { $_ -match "^$name=" } | Select-Object -First 1
    if (-not $line) { throw "$name not found in $EnvFile" }
    return $line.Substring($name.Length + 1).Trim()
}

$body = @{ email = (Get-EnvValue "SEATLOCK_ADMIN_EMAIL"); password = (Get-EnvValue "SEATLOCK_ADMIN_PASSWORD") } | ConvertTo-Json
try {
    $token = (Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1/auth/login" -ContentType "application/json" -Body $body).accessToken
} catch {
    throw "Admin login failed. Is the app running with the repo-root .env loaded (startup log: 'Admin account created/already exists')? $_"
}
$headers = @{ Authorization = "Bearer $token" }

function Get-CacheGets([string]$cache, [string]$result) {
    $uri = "$BaseUrl/actuator/metrics/cache.gets?tag=cache:$cache&tag=result:$result"
    return [double](Invoke-RestMethod -Uri $uri -Headers $headers).measurements[0].value
}

# A foreach *statement* can't be piped directly in PowerShell: collect the rows first
$rows = foreach ($cache in "eventSearch", "eventDetail") {
    $hits = Get-CacheGets $cache "hit"
    $misses = Get-CacheGets $cache "miss"
    $total = $hits + $misses
    $rate = if ($total -gt 0) { "{0:P1}" -f ($hits / $total) } else { "n/a" }
    [pscustomobject]@{ Cache = $cache; Hits = $hits; Misses = $misses; HitRate = $rate }
}
$rows | Format-Table -AutoSize
