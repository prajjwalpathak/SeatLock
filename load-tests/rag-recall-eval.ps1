<#
.SYNOPSIS
  RAG retrieval eval (W7-D4): asks the 30 questions of backend/src/test/resources/ai-eval/rag-questions.json through
  POST /api/v1/admin/ai/policy-search (k = 4) and reports recall@1, recall@4 and MRR against the expected section.

.NOTES
  Local embeddings only: no LLM, no quota, no pacing. Needs the app running with SEATLOCK_EMBEDDINGS=transformers
  and the policies indexed (startup log "Policy ingestion (all-MiniLM-L6-v2): ..."). Admin login from the repo-root .env.
#>
param(
    [string]$BaseUrl = 'http://localhost:8080',
    [int]$K = 4,
    [string]$EnvFile = (Join-Path $PSScriptRoot '..\.env'),
    [string]$CasesFile = (Join-Path $PSScriptRoot '..\backend\src\test\resources\ai-eval\rag-questions.json')
)
$ErrorActionPreference = 'Stop'

$envs = @{}
Get-Content $EnvFile | Where-Object { $_ -match '^[A-Z_]+=' } | ForEach-Object { $p = $_ -split '=', 2; $envs[$p[0]] = $p[1] }
$login = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1/auth/login" -ContentType 'application/json' `
    -Body (@{ email = $envs.SEATLOCK_ADMIN_EMAIL; password = $envs.SEATLOCK_ADMIN_PASSWORD } | ConvertTo-Json)
$headers = @{ Authorization = "Bearer $($login.accessToken)" }

$status = Invoke-RestMethod -Uri "$BaseUrl/api/v1/admin/ai/status" -Headers $headers
if ($status.embeddingModel -eq 'none') { throw "Embeddings are off (SEATLOCK_EMBEDDINGS). Nothing to evaluate." }
if ($status.policyChunks -eq 0) { throw "No policy chunks indexed yet for $($status.embeddingModel); wait for the startup ingestion." }
Write-Host "Embeddings $($status.embeddingModel), $($status.policyChunks) chunks indexed"

$cases = (Get-Content $CasesFile -Raw -Encoding UTF8 | ConvertFrom-Json).cases
$results = @()
foreach ($case in $cases) {
    $bytes = [Text.Encoding]::UTF8.GetBytes((@{ question = $case.question; k = $K } | ConvertTo-Json -Compress))
    $sw = [Diagnostics.Stopwatch]::StartNew()
    $found = @(Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1/admin/ai/policy-search" -Headers $headers `
            -ContentType 'application/json; charset=utf-8' -Body $bytes)
    $sw.Stop()
    $rank = 0
    for ($i = 0; $i -lt $found.Count; $i++) {
        if ($found[$i].doc -eq $case.expected.doc -and $found[$i].heading -eq $case.expected.heading) { $rank = $i + 1; break }
    }
    $top = if ($found.Count -gt 0) { "$($found[0].doc) / $($found[0].heading)" } else { '-' }
    $mark = if ($rank -gt 0) { "HIT@$rank" } else { 'MISS ' }
    Write-Host ("{0,2} {1}  {2,4} ms  {3}" -f $case.id, $mark, $sw.ElapsedMilliseconds, $case.question)
    if ($rank -ne 1) { Write-Host ("        expected {0} / {1}; top: {2}" -f $case.expected.doc, $case.expected.heading, $top) }
    $results += [pscustomobject]@{ id = $case.id; question = $case.question; rank = $rank; ms = $sw.ElapsedMilliseconds; top = $top }
}

$n = $results.Count
$at1 = @($results | Where-Object { $_.rank -eq 1 }).Count
$atK = @($results | Where-Object { $_.rank -ge 1 }).Count
$mrr = ($results | ForEach-Object { if ($_.rank -gt 0) { 1.0 / $_.rank } else { 0 } } | Measure-Object -Sum).Sum / $n
$ms = @($results | ForEach-Object { $_.ms } | Sort-Object)
Write-Host ''
Write-Host ("recall@1: {0}/{1}   recall@{2}: {3}/{1}   MRR: {4:N3}" -f $at1, $n, $K, $atK, $mrr)
Write-Host ("Latency (embed + search, client side): p50 {0} ms, p95 {1} ms" -f $ms[[math]::Floor(0.5 * $n)], $ms[[math]::Min($n - 1, [math]::Floor(0.95 * $n))])

$outDir = Join-Path $PSScriptRoot 'results'
New-Item -ItemType Directory -Force $outDir | Out-Null
$csv = Join-Path $outDir ("rag-recall-eval-{0:yyyyMMdd-HHmmss}.csv" -f (Get-Date))
$results | Export-Csv -NoTypeInformation -Encoding UTF8 $csv
Write-Host "CSV: $csv"
