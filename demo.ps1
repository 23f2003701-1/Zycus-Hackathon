# StockPulse demo walkthrough.
#
# Nothing in sections 2-4 asks for a recommendation. A sale is simulated and the agentic loop
# produces suggestions on its own. Run with the backend already up on :8080.
#
#   .\demo.ps1
#
# Note on style: Invoke-RestMethod emits a JSON array as a single pipeline item on PowerShell 5.1,
# so responses are always assigned to a variable before being iterated or indexed. Piping the call
# directly would collapse the array into one object.

$ErrorActionPreference = "Stop"
$base = "http://localhost:8080"
$spikeMultiplier = 3.0   # commerce.demand-spike-multiplier

function Section($title) {
    Write-Host ""
    Write-Host ("=" * 78) -ForegroundColor DarkGray
    Write-Host "  $title" -ForegroundColor Cyan
    Write-Host ("=" * 78) -ForegroundColor DarkGray
}

function Get-Json($path) {
    Invoke-RestMethod -Uri "$base$path" -Method Get
}

function Send-Json($method, $path, $body) {
    Invoke-RestMethod -Uri "$base$path" -Method $method -ContentType "application/json" `
        -Body ($body | ConvertTo-Json -Compress)
}

function Show-Pricing($suggestions) {
    $items = @($suggestions)
    if ($items.Count -eq 0) { Write-Host "  (none)" -ForegroundColor DarkGray; return }
    foreach ($s in $items) {
        Write-Host ("  #{0}  {1}  {2} -> {3}  ({4:+0.0;-0.0}%)  [{5}]  by {6}  conf {7}" -f `
            $s.id, $s.productId, $s.currentPrice, $s.recommendedPrice, $s.changePct, `
            $s.triggerReason, $s.generatedBy, $s.confidence) -ForegroundColor Yellow
        Write-Host ("      {0}" -f $s.reasoning) -ForegroundColor Gray
    }
}

function Show-Reorder($suggestions) {
    $items = @($suggestions)
    if ($items.Count -eq 0) { Write-Host "  (none)" -ForegroundColor DarkGray; return }
    foreach ($s in $items) {
        Write-Host ("  #{0}  {1}  stock {2} -> order {3} units, lead {4}d  [{5}]  by {6}" -f `
            $s.id, $s.productId, $s.currentStock, $s.recommendedQuantity, `
            $s.suggestedLeadTimeDays, $s.triggerReason, $s.generatedBy) -ForegroundColor Yellow
        Write-Host ("      {0}" -f $s.reasoning) -ForegroundColor Gray
    }
}

function Expect-Failure($label, $action) {
    Write-Host "  $label" -NoNewline
    try {
        & $action | Out-Null
        Write-Host " NO ERROR - guardrail is missing" -ForegroundColor Red
    } catch {
        $code = if ($_.Exception.Response) { [int]$_.Exception.Response.StatusCode } else { "?" }
        Write-Host " HTTP $code as expected" -ForegroundColor Green
    }
}

# ---------------------------------------------------------------------------
Section "0. Which engine is active?"
$strategy = Get-Json "/admin/commerce-strategy"
Write-Host "  active    : $($strategy.activeStrategy)"
Write-Host "  available : $($strategy.availableStrategies -join ', ')"
if ($env:LLM_API_KEY) {
    Write-Host "  LLM key   : present - expect generatedBy=aiAdvisor" -ForegroundColor Green
} else {
    Write-Host "  LLM key   : absent - expect fallback to generatedBy=ruleBased" -ForegroundColor DarkYellow
}

# ---------------------------------------------------------------------------
Section "1. Seeded catalog"
$catalog = Get-Json "/products"
foreach ($p in $catalog) {
    $flag = if ($p.belowReorderThreshold) { "LOW " } else { "    " }
    Write-Host ("  {0} {1,-9} {2,-28} {3,8}  stock {4,4}/{5,-4} vel {6,3}  {7}" -f `
        $flag, $p.id, $p.name, $p.currentPrice, $p.stockLevel, $p.reorderThreshold, `
        $p.demandVelocity, $p.status)
}

# ---------------------------------------------------------------------------
Section "2. INVENTORY_LOW - one sale on PRD-003 (stock 8, threshold 15)"
$before = Get-Json "/products/PRD-003"
Write-Host "  before: stock $($before.stockLevel), price $($before.currentPrice), status $($before.status)"

Send-Json POST "/products/PRD-003/orders" @{ quantity = 1 } | Out-Null
Write-Host "  order accepted, endpoint returned immediately" -ForegroundColor Green
Write-Host "  waiting for the loop..." -ForegroundColor DarkGray
Start-Sleep -Seconds 4

$after = Get-Json "/products/PRD-003"
Write-Host "  after : stock $($after.stockLevel), price $($after.currentPrice), status $($after.status)"
Write-Host "  the price has NOT moved - suggestions are proposals, not actions" -ForegroundColor DarkGray
Write-Host "  pricing suggestions generated unasked:"
Show-Pricing (Get-Json "/products/PRD-003/pricing-suggestions")
Write-Host "  reorder suggestions generated unasked:"
Show-Reorder (Get-Json "/products/PRD-003/reorder-suggestions")

# ---------------------------------------------------------------------------
Section "3. Idempotency - five more sales must not create five more suggestions"
$countBefore = @(Get-Json "/products/PRD-003/pricing-suggestions").Count
1..5 | ForEach-Object { Send-Json POST "/products/PRD-003/orders" @{ quantity = 1 } | Out-Null }
Start-Sleep -Seconds 4
$countAfter = @(Get-Json "/products/PRD-003/pricing-suggestions").Count
Write-Host "  pricing suggestions before: $countBefore, after 5 more sales: $countAfter"
if ($countBefore -eq $countAfter) {
    Write-Host "  no duplicates - dedupe on (product, trigger, type, PENDING) held" -ForegroundColor Green
} else {
    Write-Host "  DUPLICATES APPEARED" -ForegroundColor Red
}

# ---------------------------------------------------------------------------
Section "4. DEMAND_SPIKE - push PRD-008 past 3x its peer average"
# The bar is relative and section 3 just sold six PRD-003 units, which lifted the APPAREL peer
# average. So compute the bar now rather than assuming the fresh-boot value of 21.
$catalog = Get-Json "/products"
$hoodie = Get-Json "/products/PRD-008"
$peers = @($catalog | Where-Object { $_.category -eq "APPAREL" -and $_.id -ne "PRD-008" })
$peerAvg = ($peers | Measure-Object -Property demandVelocity -Average).Average
$bar = [math]::Floor($peerAvg * $spikeMultiplier)
$needed = [math]::Max(1, $bar + 1 - $hoodie.demandVelocity)

Write-Host ("  APPAREL peers of PRD-008: {0}" -f (($peers | ForEach-Object { "$($_.id)=$($_.demandVelocity)" }) -join ", "))
Write-Host ("  peer average {0:N1} x {1} = bar of {2}; PRD-008 velocity is {3}, so {4} units are needed" -f `
    $peerAvg, $spikeMultiplier, $bar, $hoodie.demandVelocity, $needed)

# Stock it up first so this is a pure demand spike with no low-stock signal mixed in
Send-Json PATCH "/products/PRD-008/stock" @{ stockLevel = ($needed + 60) } | Out-Null
Send-Json POST "/products/PRD-008/orders" @{ quantity = $needed } | Out-Null
Write-Host "  viral sale of $needed units placed" -ForegroundColor Green
Start-Sleep -Seconds 4

$hoodie = Get-Json "/products/PRD-008"
Write-Host "  velocity now $($hoodie.demandVelocity), stock $($hoodie.stockLevel) (above threshold)"
Show-Pricing (Get-Json "/products/PRD-008/pricing-suggestions")

# ---------------------------------------------------------------------------
Section "5. Human checkpoint - accept a suggestion and watch the price move"
$pending = Get-Json "/pricing-suggestions?status=PENDING"
$target = @($pending)[0]
if (-not $target) {
    Write-Host "  nothing pending to accept" -ForegroundColor DarkGray
} else {
    $product = Get-Json "/products/$($target.productId)"
    Write-Host "  accepting #$($target.id) on $($target.productId): $($product.currentPrice) -> $($target.recommendedPrice)"

    Send-Json PATCH "/pricing-suggestions/$($target.id)" @{ status = "ACCEPTED" } | Out-Null
    $product = Get-Json "/products/$($target.productId)"
    Write-Host "  price is now $($product.currentPrice), status $($product.status)" -ForegroundColor Green

    Expect-Failure "re-deciding a settled suggestion:" {
        Send-Json PATCH "/pricing-suggestions/$($target.id)" @{ status = "REJECTED" }
    }
}

# ---------------------------------------------------------------------------
Section "6. Runtime strategy switch, no restart"
Write-Host "  switching to ruleBased..."
Send-Json PATCH "/admin/commerce-strategy" @{ activeStrategy = "ruleBased" } | Out-Null
$onDemand = Send-Json POST "/products/PRD-001/suggest-pricing" @{}
Show-Pricing $onDemand

Write-Host "  switching back to aiAdvisor..."
Send-Json PATCH "/admin/commerce-strategy" @{ activeStrategy = "aiAdvisor" } | Out-Null
Write-Host "  active now: $((Get-Json '/admin/commerce-strategy').activeStrategy)" -ForegroundColor Green

Expect-Failure "an unknown strategy must be refused:" {
    Send-Json PATCH "/admin/commerce-strategy" @{ activeStrategy = "definitelyNotReal" }
}

# ---------------------------------------------------------------------------
Section "7. Guardrails on input"
Expect-Failure "overselling PRD-004:" {
    Send-Json POST "/products/PRD-004/orders" @{ quantity = 999999 }
}
Expect-Failure "unknown product:" {
    Get-Json "/products/PRD-NOPE"
}
Expect-Failure "negative price on create:" {
    Send-Json POST "/products" @{
        sku = "SKU-BAD-1"; name = "Bad"; category = "ELECTRONICS"
        currentPrice = -5; stockLevel = 10; reorderThreshold = 5
    }
}
Expect-Failure "unknown category on create:" {
    Send-Json POST "/products" @{
        sku = "SKU-BAD-2"; name = "Bad"; category = "TELEPORTERS"
        currentPrice = 5; stockLevel = 10; reorderThreshold = 5
    }
}

# ---------------------------------------------------------------------------
Section "Pending queues the console would render"
Show-Pricing (Get-Json "/pricing-suggestions?status=PENDING")
Show-Reorder (Get-Json "/reorder-suggestions?status=PENDING")
Write-Host ""
