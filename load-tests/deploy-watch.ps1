<#
.SYNOPSIS
  Watches the live site during a deploy (W5·D6): every 250 ms GET /api/v1/system/version, recording status,
  instance and revision. Prints failures, the longest failure streak and when each instance switched revision.
.EXAMPLE
  .\load-tests\deploy-watch.ps1 -Minutes 4      # start it, then trigger the deploy
#>
param(
    [string]$Base = 'https://seatlock-prajjwal.duckdns.org',
    [double]$Minutes = 4
)
$end = (Get-Date).AddMinutes($Minutes)
$rows = New-Object System.Collections.Generic.List[object]
$last = @{}
Write-Host "Watching $Base for $Minutes min (start the deploy now)..."
while ((Get-Date) -lt $end) {
    $t = Get-Date; $code = 0; $inst = ''; $rev = ''
    try {
        $r = Invoke-WebRequest -UseBasicParsing -TimeoutSec 5 "$Base/api/v1/system/version"
        $code = [int]$r.StatusCode
        $body = $r.Content | ConvertFrom-Json
        $inst = $body.instance; $rev = $body.revision
    } catch {
        if ($_.Exception.Response) { $code = [int]$_.Exception.Response.StatusCode }
    }
    $rows.Add([pscustomobject]@{ at = $t; code = $code; inst = $inst; rev = $rev; ms = [int]((Get-Date) - $t).TotalMilliseconds })
    if ($inst -and $last[$inst] -ne $rev) {
        Write-Host ("{0:HH:mm:ss} {1} now on {2}" -f $t, $inst, $rev.Substring(0, [math]::Min(12, $rev.Length)))
        $last[$inst] = $rev
    }
    if ($code -ne 200) { Write-Host ("{0:HH:mm:ss} status {1}" -f $t, $code) -ForegroundColor Yellow }
    Start-Sleep -Milliseconds 250
}
$fails = @($rows | Where-Object { $_.code -ne 200 })
Write-Host ''
Write-Host "Requests: $($rows.Count), not 200: $($fails.Count)"
$rows | Group-Object code | ForEach-Object { Write-Host ("  status {0}: {1}" -f $_.Name, $_.Count) }
$rows | Where-Object inst | Group-Object inst | ForEach-Object { Write-Host ("  {0}: {1}" -f $_.Name, $_.Count) }
$longest = 0; $start = $null
foreach ($r in $rows) {
    if ($r.code -ne 200) { if (-not $start) { $start = $r.at } }
    elseif ($start) { $longest = [math]::Max($longest, ($r.at - $start).TotalMilliseconds); $start = $null }
}
Write-Host ("  longest failure streak: {0:N0} ms; slowest request: {1} ms" -f $longest, ($rows | Measure-Object ms -Maximum).Maximum)
