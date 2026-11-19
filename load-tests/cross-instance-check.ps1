<#
.SYNOPSIS
  Proves a seat change made on one instance reaches a STOMP viewer connected to the other (W5·D4).

.DESCRIPTION
  1. Signs in the demo user on the acting instance and picks a free seat of an upcoming show.
  2. Opens a raw STOMP-over-WebSocket viewer (System.Net.WebSockets) on the listening instance and subscribes.
  3. Holds the seat on the acting instance -> expects HELD on the viewer; releases it -> expects AVAILABLE, version + 1.
  Exit code 0 = PASS, 1 = FAIL. Needs the scaled stack (infra/docker-compose.scale.yml).

.EXAMPLE
  .\load-tests\cross-instance-check.ps1                                   # act on app-1 (8081), listen on app-2 (8082)
  .\load-tests\cross-instance-check.ps1 -Act http://localhost:8082 -Listen http://localhost:8081
#>
param(
    [string]$Act = 'http://localhost:8081',
    [string]$Listen = 'http://localhost:8082',
    [string]$Email = 'demo.user@seatlock.test',
    [string]$Password = 'seatlock-demo',
    [int]$TimeoutSeconds = 5
)
$ErrorActionPreference = 'Stop'

function Get-Instance([string]$base) {
    (Invoke-RestMethod "$base/api/v1/system/version").instance
}

function Send-Frame($ws, [string]$frame) {
    $bytes = [Text.Encoding]::UTF8.GetBytes($frame + [char]0)
    $segment = New-Object 'System.ArraySegment[byte]' -ArgumentList (, $bytes)
    $ws.SendAsync($segment, [Net.WebSockets.WebSocketMessageType]::Text, $true, [Threading.CancellationToken]::None).Wait()
}

# One STOMP frame (Spring sends one per WebSocket message); $null on timeout.
function Receive-Frame($ws, [int]$timeoutMs) {
    $buffer = New-Object byte[] 65536
    $text = New-Object Text.StringBuilder
    $cts = New-Object Threading.CancellationTokenSource($timeoutMs)
    try {
        do {
            $segment = New-Object 'System.ArraySegment[byte]' -ArgumentList (, $buffer)
            $result = $ws.ReceiveAsync($segment, $cts.Token).GetAwaiter().GetResult()
            [void]$text.Append([Text.Encoding]::UTF8.GetString($buffer, 0, $result.Count))
        } while (-not $result.EndOfMessage)
    } catch {
        return $null   # timeout (cancelled) or connection closed; PowerShell wraps .NET exceptions, so no typed catch
    }
    $frame = $text.ToString().TrimEnd([char]0)
    if ($frame.Trim().Length -eq 0) { return Receive-Frame $ws $timeoutMs }   # heart-beat
    return $frame
}

function Read-SeatEvent($ws) {
    $frame = Receive-Frame $ws ($TimeoutSeconds * 1000)
    if ($null -eq $frame) { return $null }
    if (-not $frame.StartsWith('MESSAGE')) { throw "Expected MESSAGE, got: $($frame.Split("`n")[0])" }
    $body = $frame.Substring($frame.IndexOf("`n`n") + 2)
    return $body | ConvertFrom-Json
}

$fail = $false
$actId = Get-Instance $Act
$listenId = Get-Instance $Listen
Write-Host "Acting on $actId ($Act), listening on $listenId ($Listen)"
if ($actId -eq $listenId) { Write-Warning 'Both URLs reach the same instance: this proves nothing about the relay.' }

# --- sign in + pick a free seat of a show starting > 1 h from now ---
$login = Invoke-RestMethod -Method Post "$Act/api/v1/auth/login" -ContentType 'application/json' `
    -Body (@{ email = $Email; password = $Password } | ConvertTo-Json)
$auth = @{ Authorization = "Bearer $($login.accessToken)" }

$showId = $null
$soon = (Get-Date).ToUniversalTime().AddHours(1)
foreach ($event in (Invoke-RestMethod "$Act/api/v1/events?limit=10").items) {
    $detail = Invoke-RestMethod "$Act/api/v1/events/$($event.id)"
    $show = $detail.upcomingShows | Where-Object { ([datetime]$_.startsAt).ToUniversalTime() -gt $soon } | Select-Object -First 1
    if ($show) { $showId = $show.id; break }
}
if (-not $showId) { throw 'No show starting more than 1 h from now' }
$seat = (Invoke-RestMethod "$Act/api/v1/shows/$showId/seats").seats |
    Where-Object { $_.status -eq 'AVAILABLE' } | Select-Object -Last 1
Write-Host "Show $showId, seat $($seat.rowLabel)$($seat.seatNumber) (id $($seat.seatId))"

# --- viewer on the other instance ---
$ws = New-Object Net.WebSockets.ClientWebSocket
$wsUrl = ($Listen -replace '^http', 'ws') + '/ws'
$ws.ConnectAsync([Uri]$wsUrl, [Threading.CancellationToken]::None).Wait()
$hostName = ([Uri]$Listen).Host
Send-Frame $ws "CONNECT`naccept-version:1.2`nhost:$hostName`nheart-beat:0,0`n`n"
$connected = Receive-Frame $ws ($TimeoutSeconds * 1000)
if ($null -eq $connected -or -not $connected.StartsWith('CONNECTED')) { throw "No CONNECTED frame: $connected" }
Send-Frame $ws "SUBSCRIBE`nid:sub-0`ndestination:/topic/shows/$showId/seats`n`n"
Start-Sleep -Milliseconds 500   # SUBSCRIBE has no receipt on the simple broker

$holdId = $null
try {
    # --- hold on the acting instance ---
    $headers = $auth + @{ 'Idempotency-Key' = [guid]::NewGuid().ToString() }
    $started = Get-Date
    $hold = Invoke-WebRequest -UseBasicParsing -Method Post "$Act/api/v1/shows/$showId/holds" -Headers $headers `
        -ContentType 'application/json' -Body (@{ seatIds = @($seat.seatId) } | ConvertTo-Json)
    $holdId = ($hold.Content | ConvertFrom-Json).holdId
    $held = Read-SeatEvent $ws
    $heldMs = [int]((Get-Date) - $started).TotalMilliseconds
    if ($null -eq $held -or $held.status -ne 'HELD' -or $held.seatIds -notcontains $seat.seatId) {
        Write-Host "FAIL  no HELD event on $listenId within $TimeoutSeconds s" -ForegroundColor Red; $fail = $true
    } else {
        Write-Host "OK    HELD      v$($held.version) on $listenId, $heldMs ms after the hold on $($hold.Headers['X-SeatLock-Instance'])" -ForegroundColor Green
    }

    # --- release on the acting instance ---
    $started = Get-Date
    $release = Invoke-WebRequest -UseBasicParsing -Method Delete "$Act/api/v1/holds/$holdId" -Headers $auth
    $holdId = $null
    $freed = Read-SeatEvent $ws
    $freedMs = [int]((Get-Date) - $started).TotalMilliseconds
    if ($null -eq $freed -or $freed.status -ne 'AVAILABLE') {
        Write-Host "FAIL  no AVAILABLE event on $listenId within $TimeoutSeconds s" -ForegroundColor Red; $fail = $true
    } elseif ($held -and $freed.version -ne $held.version + 1) {
        Write-Host "FAIL  version $($freed.version), expected $($held.version + 1)" -ForegroundColor Red; $fail = $true
    } else {
        Write-Host "OK    AVAILABLE v$($freed.version) on $listenId, $freedMs ms after the release on $($release.Headers['X-SeatLock-Instance'])" -ForegroundColor Green
    }
} finally {
    if ($holdId) { try { Invoke-WebRequest -UseBasicParsing -Method Delete "$Act/api/v1/holds/$holdId" -Headers $auth | Out-Null } catch {} }
    if ($ws.State -eq 'Open') {
        Send-Frame $ws "DISCONNECT`n`n"
        try { $ws.CloseAsync([Net.WebSockets.WebSocketCloseStatus]::NormalClosure, 'done', [Threading.CancellationToken]::None).Wait() } catch {}
    }
    $ws.Dispose()
}

if ($fail) { Write-Host 'RESULT: FAIL' -ForegroundColor Red; exit 1 }
Write-Host 'RESULT: PASS (cross-instance relay works)' -ForegroundColor Green
exit 0
