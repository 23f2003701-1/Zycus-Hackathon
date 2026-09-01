import { money } from "../format";

/**
 * Price history, reconstructed from accepted pricing suggestions.
 *
 * There is no price-history table in the schema, and adding one for a sparkline would be the wrong
 * trade. It is also unnecessary: an accepted pricing suggestion *is* a price change, with a
 * timestamp and the reasoning that justified it, so the approval trail already contains the
 * history. That does mean the series only shows approved moves - a price edited by some other
 * route would not appear, which is fine here because no such route exists.
 */
export function buildPriceHistory(product, pricingSuggestions) {
  const accepted = pricingSuggestions
    .filter((s) => s.productId === product.id && s.status === "ACCEPTED" && s.decidedAt)
    .sort((a, b) => new Date(a.decidedAt) - new Date(b.decidedAt));

  if (accepted.length === 0) return [];

  return [
    { price: Number(accepted[0].currentPrice), at: accepted[0].createdAt, label: "Original" },
    ...accepted.map((s) => ({
      price: Number(s.recommendedPrice),
      at: s.decidedAt,
      label: s.triggerReason,
    })),
  ];
}

export function PriceHistory({ points }) {
  if (points.length < 2) {
    return <span className="sparkline-empty">No approved changes yet</span>;
  }

  const width = 96;
  const height = 26;
  const prices = points.map((p) => p.price);
  const min = Math.min(...prices);
  const max = Math.max(...prices);
  const span = max - min || 1;

  const coords = points.map((point, i) => {
    const x = (i / (points.length - 1)) * (width - 4) + 2;
    const y = height - 3 - ((point.price - min) / span) * (height - 6);
    return { x, y, ...point };
  });

  const path = coords.map((c, i) => `${i === 0 ? "M" : "L"}${c.x.toFixed(1)},${c.y.toFixed(1)}`).join(" ");
  const first = prices[0];
  const last = prices[prices.length - 1];
  const rising = last >= first;

  return (
    <div className="sparkline" title={points.map((p) => money(p.price)).join("  →  ")}>
      <svg width={width} height={height} role="img" aria-label="Approved price changes over time">
        <path d={path} className={`spark-line ${rising ? "spark-up" : "spark-down"}`} />
        {coords.map((c, i) => (
          <circle
            key={i}
            cx={c.x}
            cy={c.y}
            r={i === coords.length - 1 ? 2.6 : 1.6}
            className={`spark-dot ${rising ? "spark-up" : "spark-down"}`}
          />
        ))}
      </svg>
      <span className={`spark-delta ${rising ? "text-up" : "text-down"}`}>
        {rising ? "▲" : "▼"} {money(first)} → {money(last)}
      </span>
    </div>
  );
}
