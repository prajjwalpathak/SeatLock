<#
  W8·D2: JVM startup time of the local 2-instance stack (prod profile), before/after the AOT cache.
  Restarts app-1 and app-2 -Runs times, reads Spring Boot's "Started SeatLockApplication in X seconds
  (process running for Y)" line from each start, prints the median and appends every sample to
  load-tests/results/startup.csv.

  Usage (stack already up via docker-compose.local.yml + docker-compose.scale.yml):
    .\load-tests\measure-startup.ps1 -Label baseline -Runs 5
    .\load-tests\measure-startup.ps1 -Label aot-cache -Runs 5
#>
param(
    [Parameter(Mandatory = $true)][string]$Label,
    [int]$Runs = 5,
    [string[]]$Containers = @('seatlock-app-1', 'seatlock-app-2'),
    [int]$TimeoutSeconds = 180
)

# Continue, not Stop: PowerShell 5.1 turns a native command's stderr (the JVM prints "Picked up JAVA_TOOL_OPTIONS"
# there) into a terminating NativeCommandError under Stop with 2>&1 (same as run-k6.ps1, W6·D3). Failures throw explicitly.
$ErrorActionPreference = 'Continue'
$resultsDir = Join-Path $PSScriptRoot 'results'
New-Item -ItemType Directory -Force -Path $resultsDir | Out-Null
$csv = Join-Path $resultsDir 'startup.csv'
if (-not (Test-Path $csv)) { 'label,run,container,started_s,process_s,image,timestamp' | Out-File -Encoding utf8 $csv }

$image = (docker inspect --format '{{.Config.Image}}' $Containers[0]).Trim()
$imageSize = [math]::Round(((docker image inspect --format '{{.Size}}' $image).Trim() -as [double]) / 1MB, 1)
Write-Host "Image: $image ($imageSize MB)"

# Optional: did the JVM map an AOT cache? (only meaningful once the image has one)
$aotLine = docker logs $Containers[0] 2>&1 | ForEach-Object { "$_" } | Select-String -Pattern 'AOTCache|AOT cache' | Select-Object -Last 1
if ($aotLine) { Write-Host "AOT log line: $aotLine" }

$pattern = 'Started SeatLockApplication in ([0-9.]+) seconds \(process running for ([0-9.]+)\)'
$samples = @()

for ($run = 1; $run -le $Runs; $run++) {
    foreach ($c in $Containers) {
        # Count the "Started" lines before the restart, then wait for one more and take the newest.
        # (No "docker logs --since": a timestamp without a zone is read as local time and returned the whole log.)
        $before = [regex]::Matches((docker logs $c 2>&1 | ForEach-Object { "$_" } | Out-String), $pattern).Count
        docker restart $c | Out-Null
        $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
        $match = $null
        while (-not $match -and (Get-Date) -lt $deadline) {
            Start-Sleep -Seconds 2
            $all = [regex]::Matches((docker logs $c 2>&1 | ForEach-Object { "$_" } | Out-String), $pattern)
            if ($all.Count -gt $before) { $match = $all[$all.Count - 1] }
        }
        if (-not $match) { throw "$c did not log a new 'Started SeatLockApplication' within $TimeoutSeconds s" }
        $started = [double]$match.Groups[1].Value
        $process = [double]$match.Groups[2].Value
        $samples += [pscustomobject]@{ Run = $run; Container = $c; Started = $started; Process = $process }
        "$Label,$run,$c,$started,$process,$image,$((Get-Date).ToString('s'))" | Out-File -Append -Encoding utf8 $csv
        Write-Host ("run {0} {1}: started in {2:N2} s (process {3:N2} s)" -f $run, $c, $started, $process)
        # let it settle (health check, ingestion) before the next restart
        Start-Sleep -Seconds 20
    }
}

function Median([double[]]$xs) {
    $s = $xs | Sort-Object
    $n = $s.Count
    if ($n % 2) { return $s[[int][math]::Floor($n / 2)] }
    return ($s[$n / 2 - 1] + $s[$n / 2]) / 2
}
$medStarted = Median ($samples | ForEach-Object Started)
$medProcess = Median ($samples | ForEach-Object Process)
Write-Host ""
Write-Host ("{0}: median started {1:N2} s, median process {2:N2} s over {3} starts; image {4} MB" -f `
        $Label, $medStarted, $medProcess, $samples.Count, $imageSize)
