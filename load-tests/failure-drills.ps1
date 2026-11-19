<#
.SYNOPSIS
  Failure drills on the local 2-instance stack (W5·D6). Each drill breaks one thing on purpose and measures,
  through the load balancer, what users would have seen and how long recovery took.

.DESCRIPTION
  -Drill instance   stop app-2 for 20 s while requests flow through the LB, then start it again
  -Drill booking    hold + book through app-1 directly, stop app-1, then read/cancel that booking through the LB (app-2)
  -Drill redis      restart Redis while the seat map is polled; then wait until the cross-instance relay works again
  -Drill postgres   stop Postgres for 15 s while the seat map is polled, then start it

  A background probe calls GET /api/v1/shows/{id}/seats through http://localhost:8088 every 200 ms and records
  status, latency, instance and holdsLive. The summary shows errors, the longest error streak and when service returned.
  Needs: docker compose -f infra/docker-compose.local.yml -f infra/docker-compose.scale.yml up -d (both apps healthy).

.EXAMPLE
  .\load-tests\failure-drills.ps1 -Drill instance
#>
param(
    [Parameter(Mandatory)][ValidateSet('instance', 'booking', 'redis', 'postgres')][string]$Drill,
    [string]$Lb = 'http://localhost:8088',
    [string]$App1 = 'http://localhost:8081',
    [string]$Email = 'demo.user@seatlock.test',
    [string]$Password = 'seatlock-demo'
)
$ErrorActionPreference = 'Stop'

function Get-ShowId([string]$base) {
    $soon = (Get-Date).ToUniversalTime().AddHours(1)
    foreach ($e in (Invoke-RestMethod "$base/api/v1/events?limit=10").items) {
        $d = Invoke-RestMethod "$base/api/v1/events/$($e.id)"
        $s = $d.upcomingShows | Where-Object { ([datetime]$_.startsAt).ToUniversalTime() -gt $soon } | Select-Object -First 1
        if ($s) { return $s.id }
    }
    throw 'No show starting more than 1 h from now'
}

function Start-Probe([string]$url, [int]$seconds) {
    Start-Job -ArgumentList $url, $seconds -ScriptBlock {
        param($url, $seconds)
        $end = (Get-Date).AddSeconds($seconds)
        while ((Get-Date) -lt $end) {
            $t = Get-Date
            $code = 0; $inst = ''; $live = $null
            try {
                $r = Invoke-WebRequest -UseBasicParsing -TimeoutSec 5 $url
                $code = [int]$r.StatusCode
                $inst = "$($r.Headers['X-SeatLock-Instance'])"
                $live = $r.Content -match '"holdsLive":true'
            } catch {
                if ($_.Exception.Response) { $code = [int]$_.Exception.Response.StatusCode }
            }
            [pscustomobject]@{ at = $t; ms = [int]((Get-Date) - $t).TotalMilliseconds; code = $code; inst = $inst; live = $live }
            Start-Sleep -Milliseconds 200
        }
    }
}

function Show-Probe($job, $faultAt, $recoverAt) {   # untyped: [datetime] can't be `$null (W5·D6 bug)
    $rows = @(Receive-Job $job -Wait -AutoRemoveJob)
    $errors = @($rows | Where-Object { $_.code -ne 200 })
    Write-Host ''
    Write-Host "Probe: $($rows.Count) requests, $($errors.Count) not 200"
    $rows | Group-Object code | Sort-Object Name | ForEach-Object { Write-Host ("  status {0,-3}: {1}" -f $_.Name, $_.Count) }
    $rows | Where-Object { $_.inst } | Group-Object inst | ForEach-Object { Write-Host ("  served by {0}: {1}" -f $_.Name, $_.Count) }
    $notLive = @($rows | Where-Object { $_.code -eq 200 -and $_.live -eq $false })
    if ($notLive.Count) { Write-Host "  200 but holdsLive=false (degraded): $($notLive.Count)" }
    # longest run of consecutive failures, in wall-clock time
    $longest = 0; $streakStart = $null
    foreach ($r in $rows) {
        if ($r.code -ne 200) { if (-not $streakStart) { $streakStart = $r.at } }
        elseif ($streakStart) { $len = ($r.at - $streakStart).TotalMilliseconds; if ($len -gt $longest) { $longest = $len }; $streakStart = $null }
    }
    if ($streakStart) { $longest = [math]::Max($longest, (($rows[-1].at) - $streakStart).TotalMilliseconds) }
    Write-Host ("  longest error streak: {0:N0} ms" -f $longest)
    $slow = ($rows | Measure-Object ms -Maximum).Maximum
    Write-Host "  slowest request: $slow ms"
    if ($recoverAt) {
        $back = $rows | Where-Object { $_.at -gt $recoverAt -and $_.code -eq 200 -and $_.live -ne $false } | Select-Object -First 1
        if ($back) { Write-Host ("  fully back {0:N0} ms after the recovery step" -f ($back.at - $recoverAt).TotalMilliseconds) }
        else { Write-Host '  NOT fully back by the end of the probe' -ForegroundColor Red }
    }
}

function Wait-Healthy([string]$container) {
    $start = Get-Date
    while (((Get-Date) - $start).TotalSeconds -lt 150) {
        $status = docker inspect -f '{{.State.Health.Status}}' $container 2>$null
        if ($status -eq 'healthy') { return ((Get-Date) - $start).TotalSeconds }
        Start-Sleep -Seconds 1
    }
    throw "$container not healthy after 150 s"
}

# Pre-flight: both instances must be healthy first (e.g. right after 'up -d --build' they need ~20-60 s)
foreach ($c in 'seatlock-app-1', 'seatlock-app-2') {
    $status = docker inspect -f '{{.State.Health.Status}}' $c 2>$null
    if ($status -ne 'healthy') {
        Write-Host "waiting for $c (now: $(if ($status) { $status } else { 'not running' }))..."
        Write-Host ("  {0} healthy after {1:N0} s" -f $c, (Wait-Healthy $c))
    }
}

$showId = Get-ShowId $Lb
$seatsUrl = "$Lb/api/v1/shows/$showId/seats"
Write-Host "Drill '$Drill' on show $showId"

switch ($Drill) {
    'instance' {
        $probe = Start-Probe $seatsUrl 60
        Start-Sleep -Seconds 8                          # baseline, both instances
        $faultAt = Get-Date; Write-Host "$(Get-Date -f HH:mm:ss) docker stop seatlock-app-2 (graceful, SIGTERM)"
        docker stop seatlock-app-2 | Out-Null
        $stopped = Get-Date; Write-Host ("  stopped in {0:N1} s" -f ($stopped - $faultAt).TotalSeconds)
        Start-Sleep -Seconds 20
        $recoverAt = Get-Date; Write-Host "$(Get-Date -f HH:mm:ss) docker start seatlock-app-2"
        docker start seatlock-app-2 | Out-Null
        $healthy = Wait-Healthy 'seatlock-app-2'; Write-Host ("  healthy after {0:N0} s" -f $healthy)
        Show-Probe $probe $faultAt $null
        $after = 1..6 | ForEach-Object { (Invoke-RestMethod "$Lb/api/v1/system/version").instance }
        Write-Host "  after restart: $($after -join ', ')"
    }
    'booking' {
        $login = Invoke-RestMethod -Method Post "$App1/api/v1/auth/login" -ContentType 'application/json' `
            -Body (@{ email = $Email; password = $Password } | ConvertTo-Json)
        $auth = @{ Authorization = "Bearer $($login.accessToken)" }
        $seat = (Invoke-RestMethod "$App1/api/v1/shows/$showId/seats").seats | Where-Object { $_.status -eq 'AVAILABLE' } | Select-Object -Last 1
        $hold = Invoke-RestMethod -Method Post "$App1/api/v1/shows/$showId/holds" -ContentType 'application/json' `
            -Headers ($auth + @{ 'Idempotency-Key' = [guid]::NewGuid().ToString() }) -Body (@{ seatIds = @($seat.seatId) } | ConvertTo-Json)
        $booking = Invoke-RestMethod -Method Post "$App1/api/v1/holds/$($hold.holdId)/booking" -Headers $auth
        Write-Host "Booked seat $($seat.rowLabel)$($seat.seatNumber) on app-1: $($booking.publicId) ($($booking.status))"
        Write-Host "$(Get-Date -f HH:mm:ss) docker kill seatlock-app-1 (no graceful shutdown: a crash)"
        docker kill seatlock-app-1 | Out-Null
        Start-Sleep -Seconds 2
        $read = Invoke-WebRequest -UseBasicParsing "$Lb/api/v1/bookings/$($booking.publicId)" -Headers $auth
        Write-Host "  GET via LB -> $([int]$read.StatusCode) from $($read.Headers['X-SeatLock-Instance']): $((($read.Content | ConvertFrom-Json).status))"
        $map = Invoke-RestMethod $seatsUrl
        Write-Host "  seat map via LB: $((($map.seats | Where-Object { $_.seatId -eq $seat.seatId }).status))"
        $cancel = Invoke-WebRequest -UseBasicParsing -Method Post "$Lb/api/v1/bookings/$($booking.publicId)/cancel" -Headers $auth
        Write-Host "  cancel via LB -> $([int]$cancel.StatusCode) from $($cancel.Headers['X-SeatLock-Instance']): $((($cancel.Content | ConvertFrom-Json).status))"
        $map = Invoke-RestMethod $seatsUrl
        Write-Host "  seat after cancel: $((($map.seats | Where-Object { $_.seatId -eq $seat.seatId }).status))"
        Write-Host "$(Get-Date -f HH:mm:ss) docker start seatlock-app-1"
        docker start seatlock-app-1 | Out-Null
        Write-Host ("  app-1 healthy after {0:N0} s" -f (Wait-Healthy 'seatlock-app-1'))
    }
    'redis' {
        $probe = Start-Probe $seatsUrl 45
        Start-Sleep -Seconds 5
        $faultAt = Get-Date; Write-Host "$(Get-Date -f HH:mm:ss) docker restart seatlock-redis"
        docker restart seatlock-redis | Out-Null
        $recoverAt = Get-Date; Write-Host ("  Redis back in {0:N1} s" -f ($recoverAt - $faultAt).TotalSeconds)
        $relayStart = Get-Date; $ok = $false
        for ($i = 1; $i -le 10 -and -not $ok; $i++) {
            try {
                & "$PSScriptRoot\cross-instance-check.ps1" -TimeoutSeconds 3 *> $null
                $ok = ($LASTEXITCODE -eq 0)
            } catch {
                $ok = $false   # e.g. holds still 503 right after the restart
            }
            if (-not $ok) { Start-Sleep -Seconds 2 }
        }
        if ($ok) { Write-Host ("  cross-instance relay works again {0:N1} s after Redis came back (attempt {1})" -f ((Get-Date) - $relayStart).TotalSeconds, ($i - 1)) }
        else { Write-Host '  relay still failing after 10 attempts' -ForegroundColor Red }
        Show-Probe $probe $faultAt $recoverAt
    }
    'postgres' {
        $probe = Start-Probe $seatsUrl 60
        Start-Sleep -Seconds 5
        $faultAt = Get-Date; Write-Host "$(Get-Date -f HH:mm:ss) docker stop seatlock-postgres"
        docker stop seatlock-postgres | Out-Null
        Start-Sleep -Seconds 15
        Write-Host "  readiness while down: app-1 $(try { (Invoke-RestMethod "$App1/actuator/health/readiness").status } catch { [int]$_.Exception.Response.StatusCode })"
        $recoverAt = Get-Date; Write-Host "$(Get-Date -f HH:mm:ss) docker start seatlock-postgres"
        docker start seatlock-postgres | Out-Null
        Show-Probe $probe $faultAt $recoverAt
    }
}
