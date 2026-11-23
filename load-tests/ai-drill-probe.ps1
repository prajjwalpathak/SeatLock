<#
.SYNOPSIS
  AI failure-drill probe (W7-D6): after starting the app with one fault injected (see docs/FAILURES.md F14), shows
  what users get: AI status, N admin pings, one free-text Seat Finder request and (optionally) one Refund Assistant
  question, then the newest ai_requests rows. Every line should be a fallback or an answer, never an HTTP 5xx.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File .\load-tests\ai-drill-probe.ps1 -Pings 6
  powershell -ExecutionPolicy Bypass -File .\load-tests\ai-drill-probe.ps1 -RefundBookingId <uuid of a demo-user booking>
#>
param(
    [string]$BaseUrl = 'http://localhost:8080',
    [int]$Pings = 6,
    [string]$RefundBookingId = '',
    [string]$RefundUser = 'demo.user@seatlock.test',
    [string]$RefundPassword = 'seatlock-demo',
    [string]$EnvFile = (Join-Path $PSScriptRoot '..\.env')
)
$ErrorActionPreference = 'Stop'

function Call($method, $path, $headers, $body) {
    try {
        $req = @{ Method = $method; Uri = "$BaseUrl$path"; Headers = $headers; UseBasicParsing = $true }
        if ($body) {
            $req.ContentType = 'application/json; charset=utf-8'
            $req.Body = [Text.Encoding]::UTF8.GetBytes(($body | ConvertTo-Json -Compress))
        }
        $r = Invoke-WebRequest @req
        return [pscustomobject]@{ status = [int]$r.StatusCode; body = ($r.Content | ConvertFrom-Json) }
    } catch {
        $code = if ($_.Exception.Response) { [int]$_.Exception.Response.StatusCode } else { 0 }
        return [pscustomobject]@{ status = $code; body = $null }
    }
}

function Login($email, $password) {
    $r = Call 'Post' '/api/v1/auth/login' @{} @{ email = $email; password = $password }
    if ($r.status -ne 200) { throw "Login failed for $email (HTTP $($r.status))" }
    return @{ Authorization = "Bearer $($r.body.accessToken)" }
}

$envs = @{}
Get-Content $EnvFile | Where-Object { $_ -match '^[A-Z_]+=' } | ForEach-Object { $p = $_ -split '=', 2; $envs[$p[0]] = $p[1] }
$admin = Login $envs.SEATLOCK_ADMIN_EMAIL $envs.SEATLOCK_ADMIN_PASSWORD

$s = (Call 'Get' '/api/v1/admin/ai/status' $admin $null).body
Write-Host ("Status: AI enabled={0} model={1} circuit={2} | embeddings={3} chunks={4}" -f $s.enabled, $s.model, $s.circuit, $s.embeddingModel, $s.policyChunks)

for ($i = 1; $i -le $Pings; $i++) {
    $sw = [Diagnostics.Stopwatch]::StartNew()
    $r = Call 'Post' '/api/v1/admin/ai/ping' $admin @{ message = "drill ping $i" }
    $sw.Stop()
    $what = if ($r.status -eq 200) { $r.body.outcome } else { "HTTP $($r.status)" }
    Write-Host ("Ping {0}: {1,-14} {2,5} ms" -f $i, $what, $sw.ElapsedMilliseconds)
    Start-Sleep -Milliseconds 300
}

$show = (docker exec seatlock-postgres psql -U seatlock -d seatlock -tAc "SELECT s.id FROM shows s JOIN events e ON e.id = s.event_id WHERE e.status='PUBLISHED' AND s.status='SCHEDULED' AND s.starts_at > now() + interval '1 day' ORDER BY s.id LIMIT 1").Trim()
$text = "drill $(Get-Random): 2 seats near the aisle, front"   # random suffix: never served from the intent cache
$r = Call 'Post' "/api/v1/shows/$show/seat-finder/ask" $admin @{ text = $text }
if ($r.status -eq 200) {
    $result = if ($r.body.understood) { "understood, $(@($r.body.result.options).Count) options" } else { "fallback $($r.body.fallbackReason) (manual form)" }
    Write-Host "Seat Finder ask: HTTP 200, $result"
} else { Write-Host "Seat Finder ask: HTTP $($r.status)" }

if ($RefundBookingId) {
    $user = Login $RefundUser $RefundPassword
    $r = Call 'Post' "/api/v1/bookings/$RefundBookingId/assistant" $user @{ question = 'How much will I get back if I cancel?' }
    if ($r.status -eq 200) {
        $reason = if ($r.body.fallbackReason) { " ($($r.body.fallbackReason))" } else { '' }
        Write-Host ("Refund Assistant: HTTP 200, {0}{1}, {2} excerpts: {3}" -f $r.body.source, $reason, @($r.body.sources).Count, $r.body.answer)
    } else { Write-Host "Refund Assistant: HTTP $($r.status)" }
}

Write-Host ''
docker exec seatlock-postgres psql -U seatlock -d seatlock -c "SELECT feature, outcome, latency_ms, to_char(created_at AT TIME ZONE 'Asia/Kolkata', 'HH24:MI:SS') AS at_ist FROM ai_requests ORDER BY id DESC LIMIT 10;"
