export const money = (value) =>
  value === null || value === undefined
    ? "—"
    : `$${Number(value).toFixed(2)}`;

export const percent = (value, digits = 1) =>
  value === null || value === undefined ? "—" : `${Number(value).toFixed(digits)}%`;

export const signedPercent = (value) => {
  const n = Number(value);
  if (!Number.isFinite(n)) return "—";
  if (n === 0) return "no change";
  return `${n > 0 ? "+" : ""}${n.toFixed(1)}%`;
};

/** Gross margin as a percentage of the selling price, or null when cost is unknown. */
export const marginPct = (product) => {
  const price = Number(product.currentPrice);
  const cost = Number(product.costPrice);
  if (!Number.isFinite(cost) || cost <= 0 || price <= 0) return null;
  return ((price - cost) / price) * 100;
};

/** Days of stock left at the current sales rate. Null when nothing is selling. */
export const daysOfCover = (product) =>
  product.demandVelocity > 0 ? product.stockLevel / product.demandVelocity : null;

export const relativeTime = (iso) => {
  if (!iso) return "";
  const seconds = Math.max(0, Math.round((Date.now() - new Date(iso).getTime()) / 1000));
  if (seconds < 10) return "just now";
  if (seconds < 60) return `${seconds}s ago`;
  const minutes = Math.round(seconds / 60);
  if (minutes < 60) return `${minutes}m ago`;
  return `${Math.round(minutes / 60)}h ago`;
};

export const titleCase = (value) =>
  String(value ?? "")
    .toLowerCase()
    .replace(/_/g, " ")
    .replace(/\b\w/g, (c) => c.toUpperCase());
