<#
.SYNOPSIS
  Local Razorpay webhook check (W4·D5): starts a cloudflared quick tunnel to the local backend, prints the webhook
  URL to paste into the Razorpay dashboard, and proves the path tunnel -> app -> signature check works by sending
  one signed synthetic event (expected: HTTP 200, app log "IGNORED_UNKNOWN_ORDER").

.EXAMPLE
  cd D:\Prajjwal\Code\SeatLock
  .\infra\scripts\razorpay-webhook-tunnel.ps1
#>
param(
    [int]$Port = 8080,
    [string]$EnvFile = (Join-Path $PSScriptRoot "..\..\.env")
)
$ErrorActionPreference = "Stop"

if (-not (Get-Command cloudflared -ErrorAction SilentlyContinue)) {
    throw "cloudflared not found. Install: winget install --id Cloudflare.cloudflared"
}
$secretLine = Get-Content $EnvFile | Where-Object { $_ -match '^\s*RAZORPAY_WEBHOOK_SECRET=' } | Select-Object -First 1
$secret = if ($secretLine) { ($secretLine -replace '^\s*RAZORPAY_WEBHOOK_SECRET=', '').Trim() } else { "" }
if (-not $secret) { throw "RAZORPAY_WEBHOOK_SECRET is empty in $EnvFile" }

try {
    Invoke-WebRequest -UseBasicParsing "http://localhost:$Port/actuator/health/readiness" | Out-Null
} catch {
    throw "Backend not reachable on http://localhost:$Port (start it first)"
}

$log = Join-Path $env:TEMP "seatlock-cloudflared.log"
Remove-Item $log -ErrorAction SilentlyContinue
$tunnel = Start-Process cloudflared -ArgumentList @("tunnel", "--url", "http://localhost:$Port", "--logfile", $log) `
    -PassThru -WindowStyle Hidden
try {
    $url = $null
    for ($i = 0; $i -lt 60 -and -not $url; $i++) {
        Start-Sleep -Seconds 1
        if (Test-Path $log) {
            $m = Select-String -Path $log -Pattern 'https://[a-z0-9-]+\.trycloudflare\.com' | Select-Object -First 1
            if ($m) { $url = $m.Matches[0].Value }
        }
    }
    if (-not $url) { throw "No tunnel URL after 60 s; see $log" }
    $webhook = "$url/api/v1/webhooks/razorpay"
    Write-Host ""
    Write-Host "Webhook URL (Razorpay dashboard > Test mode > Webhooks > Add):" -ForegroundColor Cyan
    Write-Host "  $webhook"
    Write-Host "  Secret: the value of RAZORPAY_WEBHOOK_SECRET in .env; events: payment.captured, order.paid, payment.failed"
    Write-Host ""

    # Signed synthetic event for an order the app doesn't know: proves tunnel + signature, changes nothing.
    $body = '{"entity":"event","event":"payment.captured","payload":{"payment":{"entity":{"id":"pay_TUNNELCHECK01","amount":100,"currency":"INR","status":"captured","order_id":"order_TUNNELCHECK01"}}}}'
    $hmac = New-Object System.Security.Cryptography.HMACSHA256 -ArgumentList (, [Text.Encoding]::UTF8.GetBytes($secret))
    $signature = -join ($hmac.ComputeHash([Text.Encoding]::UTF8.GetBytes($body)) | ForEach-Object { $_.ToString("x2") })
    $headers = @{ "X-Razorpay-Signature" = $signature; "X-Razorpay-Event-Id" = "evt_tunnelcheck_" + [guid]::NewGuid() }

    $status = $null
    for ($i = 0; $i -lt 20 -and -not $status; $i++) {   # a fresh trycloudflare name can take a few seconds to resolve
        try {
            $status = (Invoke-WebRequest -UseBasicParsing -Method Post -Uri $webhook -Headers $headers `
                -ContentType "application/json" -Body $body).StatusCode
        } catch {
            if ($_.Exception.Response) { $status = [int]$_.Exception.Response.StatusCode } else { Start-Sleep -Seconds 2 }
        }
    }
    if ($status -eq 200) {
        Write-Host "Tunnel check OK: signed event accepted (200). App log: 'Razorpay webhook evt_tunnelcheck_... : IGNORED_UNKNOWN_ORDER'" -ForegroundColor Green
    } elseif ($status -eq 400) {
        Write-Host "Tunnel works but the signature was rejected (400): the app's RAZORPAY_WEBHOOK_SECRET differs from .env (restart the app?)" -ForegroundColor Yellow
    } else {
        Write-Host "Tunnel check failed (status: $status). See $log" -ForegroundColor Red
    }

    Write-Host ""
    Read-Host "Tunnel is up. Make test payments now; press Enter to stop the tunnel (then delete the webhook in the dashboard)"
} finally {
    if ($tunnel -and -not $tunnel.HasExited) { Stop-Process -Id $tunnel.Id -Force }
}
