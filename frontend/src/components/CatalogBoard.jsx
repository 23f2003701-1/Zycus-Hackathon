import { StatusPill } from "./Badges";
import { PriceHistory, buildPriceHistory } from "./PriceHistory";
import { daysOfCover, marginPct, money, percent } from "../format";

/**
 * The catalog, and the only place in the console that changes inventory.
 *
 * "Simulate sale" exists so the whole agentic loop is demonstrable without curl: selling units
 * drains stock and raises demand velocity, which is exactly what the backend listens for.
 */
function StockBar({ product }) {
  const { stockLevel, reorderThreshold } = product;
  // Scaled against 3x the threshold, which is the restock target, so a full bar means healthy.
  const ceiling = Math.max(reorderThreshold * 3, stockLevel, 1);
  const fill = Math.min(100, (stockLevel / ceiling) * 100);
  const thresholdAt = Math.min(100, (reorderThreshold / ceiling) * 100);

  const heat =
    stockLevel === 0 ? "critical" : product.belowReorderThreshold ? "low" : fill < 55 ? "watch" : "healthy";

  return (
    <div className="stockbar" title={`${stockLevel} units, reorder threshold ${reorderThreshold}`}>
      <div className="stockbar-track">
        <div className={`stockbar-fill heat-${heat}`} style={{ width: `${fill}%` }} />
        <div className="stockbar-threshold" style={{ left: `${thresholdAt}%` }} aria-hidden="true" />
      </div>
      <span className={`stockbar-text heat-text-${heat}`}>
        {stockLevel} / {reorderThreshold}
      </span>
    </div>
  );
}

export function CatalogBoard({
  products,
  pricingSuggestions,
  pendingByProduct,
  busyKey,
  streaming,
  onSell,
  onRestock,
  onStreamPricing,
}) {
  if (products.length === 0) {
    return <div className="empty-inline">No products match these filters.</div>;
  }

  return (
    <div className="table-wrap">
      <table className="catalog">
        <thead>
          <tr>
            <th>Product</th>
            <th>Category</th>
            <th className="num">Price</th>
            <th className="num">Margin</th>
            <th>Stock vs threshold</th>
            <th className="num">Velocity</th>
            <th className="num">Cover</th>
            <th>Status</th>
            <th>Approved price moves</th>
            <th className="actions-col">Simulate</th>
          </tr>
        </thead>
        <tbody>
          {products.map((product) => {
            const margin = marginPct(product);
            const cover = daysOfCover(product);
            const pending = pendingByProduct.get(product.id) ?? 0;

            return (
              <tr key={product.id} className={product.belowReorderThreshold ? "row-warn" : undefined}>
                <td>
                  <div className="cell-name">{product.name}</div>
                  <div className="cell-sub">
                    {product.sku}
                    {pending > 0 && (
                      <span className="inline-count" title="Suggestions awaiting your decision">
                        {pending} pending
                      </span>
                    )}
                  </div>
                </td>
                <td>
                  <span className={`cat cat-${product.category.toLowerCase()}`}>{product.category}</span>
                </td>
                <td className="num">{money(product.currentPrice)}</td>
                <td className="num">
                  {margin === null ? (
                    <span className="muted">—</span>
                  ) : (
                    <span className={margin < 30 ? "text-warn" : undefined}>{percent(margin, 0)}</span>
                  )}
                </td>
                <td>
                  <StockBar product={product} />
                </td>
                <td className="num">{product.demandVelocity}</td>
                <td className="num">
                  {cover === null ? (
                    <span className="muted">—</span>
                  ) : (
                    <span className={cover < 3 ? "text-warn" : undefined}>{cover.toFixed(1)}d</span>
                  )}
                </td>
                <td>
                  <StatusPill status={product.status} />
                </td>
                <td>
                  <PriceHistory points={buildPriceHistory(product, pricingSuggestions)} />
                </td>
                <td className="actions-col">
                  <div className="row-actions">
                    <button
                      type="button"
                      className="btn btn-mini"
                      disabled={product.stockLevel < 1 || busyKey === `sell-1-${product.id}`}
                      onClick={() => onSell(product, 1)}
                      title="Sell one unit — drains stock and raises demand velocity"
                    >
                      Sell 1
                    </button>
                    <button
                      type="button"
                      className="btn btn-mini"
                      disabled={product.stockLevel < 5 || busyKey === `sell-5-${product.id}`}
                      onClick={() => onSell(product, 5)}
                      title="Sell five units at once — the fastest way to trip a trigger"
                    >
                      Sell 5
                    </button>
                    <button
                      type="button"
                      className="btn btn-mini btn-mini-alt"
                      disabled={busyKey === `restock-${product.id}`}
                      onClick={() => onRestock(product)}
                      title={`Set stock to ${product.reorderThreshold * 3} units`}
                    >
                      Restock
                    </button>
                    <button
                      type="button"
                      className="btn btn-mini btn-mini-ai"
                      disabled={streaming}
                      onClick={() => onStreamPricing(product)}
                      title="Ask for a price opinion and watch the reasoning arrive live"
                    >
                      Ask live
                    </button>
                  </div>
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
