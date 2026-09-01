# Exercises the AI path and every failure mode against tools/fake-llm.js, with no real API key.
#
# Start the backend pointed at the stub first:
#   node tools\fake-llm.js ok
#   cd backend
#   mvn spring-boot:run "-Dspring-boot.run.arguments=--llm.provider=ollama --llm.base-url=http://localhost:11434"
#
# Then, from the repo root:  .\tools\verify-ai.ps1

$ErrorActionPreference = "Stop"
$base = "http://localhost:8080"
$stub = Join-Path $PSScriptRoot "fake-llm.js"

function Restart-Stub($mode) {
    Get-NetTCPConnection -LocalPort 11434 -State Listen -ErrorAction SilentlyContinue |
        ForEach-Object { Stop-Process -Id $_.OwningProcess -Force -ErrorAction SilentlyContinue }
    Start-Sleep -Milliseconds 700
    Start-Process node -ArgumentList $stub, $mode -WindowStyle Hidden
    Start-Sleep -Seconds 1
}

function Suggest($productId) {
    Invoke-RestMethod "$base/products/$productId/suggest-pricing" -Method Post `
        -ContentType application/json -Body "{}"
}

function Check($mode, $expectedSource, $note) {
    Restart-Stub $mode
    $s = Suggest "PRD-001"
    $ok = $s.generatedBy -eq $expectedSource
    $colour = if ($ok) { "Green" } else { "Red" }
    Write-Host ("{0,-8} -> generatedBy={1,-10} {2}" -f $mode, $s.generatedBy, $note) -ForegroundColor $colour
    Write-Host ("           {0} -> {1}  |  {2}" -f $s.currentPrice, $s.recommendedPrice, $s.reasoning) -ForegroundColor Gray
    if (-not $ok) { Write-Host "           EXPECTED $expectedSource" -ForegroundColor Red }
}

Write-Host ""
Write-Host "Each row calls the AI advisor. Anything the model gets wrong must degrade to ruleBased," -ForegroundColor Cyan
Write-Host "never to a missing suggestion and never to a bad number reaching the merchandiser." -ForegroundColor Cyan
Write-Host ""

Check "ok"     "aiAdvisor" "valid JSON within bounds - AI answer is used"
Check "fenced" "aiAdvisor" "markdown fences and chatter - parsed anyway"
Check "prose"  "ruleBased" "no JSON at all - falls back"
Check "absurd" "ruleBased" "price 40x current - rejected by bounds, falls back"
Check "error"  "ruleBased" "HTTP 429 quota - falls back"
Check "slow"   "ruleBased" "provider hangs - read timeout, falls back"

Write-Host ""
Restart-Stub "ok"
Write-Host "stub returned to ok mode" -ForegroundColor DarkGray
