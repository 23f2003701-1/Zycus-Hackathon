import { useCallback, useMemo, useState } from "react";
import { api } from "./api";
import { useConsoleData } from "./useConsoleData";
import { usePricingStream } from "./usePricingStream";
import { ActionQueue } from "./components/ActionQueue";
import { CatalogBoard } from "./components/CatalogBoard";
import { Header } from "./components/Header";
import { ReasoningStream } from "./components/ReasoningStream";
import { Toasts } from "./components/Toasts";
import { money } from "./format";

const CATEGORIES = ["ELECTRONICS", "APPAREL", "HOME"];

export default function App() {
  const { products, pricing, reorder, strategy, loading, error, lastUpdated, paused, setPaused, refresh } =
    useConsoleData(4000);

  const [category, setCategory] = useState("ALL");
  const [onlyAttention, setOnlyAttention] = useState(false);
  const [busyKey, setBusyKey] = useState(null);
  const [toasts, setToasts] = useState([]);

  const notify = useCallback((message, tone = "ok") => {
    const id = Math.random().toString(36).slice(2);
    setToasts((current) => [...current, { id, message, tone }]);
    setTimeout(() => setToasts((current) => current.filter((t) => t.id !== id)), 6000);
  }, []);

  const dismiss = useCallback((id) => setToasts((c) => c.filter((t) => t.id !== id)), []);

  /**
   * A streamed suggestion is persisted like any other, so the queue below has to catch up once the
   * stream ends - otherwise the same recommendation would be on screen twice with only one of them
   * decidable.
   */
  const { stream, startStream, closeStream } = usePricingStream({
    onFinished: () => refresh(),
    onError: (err) => notify(err.message, "error"),
  });

  /**
   * Every mutation follows the same shape: mark the control busy so it cannot be double-fired,
   * report the outcome, then re-poll. The refresh matters because accepting a price mutates the
   * product too, and the catalog below must not keep showing the old figure.
   */
  const run = useCallback(
    async (key, action, describe) => {
      setBusyKey(key);
      try {
        const result = await action();
        notify(describe(result), "ok");
        await refresh();
      } catch (err) {
        notify(err.message, "error");
      } finally {
        setBusyKey(null);
      }
    },
    [notify, refresh]
  );

  const pendingPricing = useMemo(() => pricing.filter((s) => s.status === "PENDING"), [pricing]);
  const pendingReorder = useMemo(() => reorder.filter((s) => s.status === "PENDING"), [reorder]);

  const pendingByProduct = useMemo(() => {
    const counts = new Map();
    for (const s of [...pendingPricing, ...pendingReorder]) {
      counts.set(s.productId, (counts.get(s.productId) ?? 0) + 1);
    }
    return counts;
  }, [pendingPricing, pendingReorder]);

  /**
   * Groups the queue by product, and orders it so the most urgent work floats up: auto-triggered
   * recommendations before requested ones, then by how depleted the product is. A merchandiser
   * opening this page should not have to hunt for the thing the system flagged.
   */
  const queueGroups = useMemo(() => {
    const byId = new Map(products.map((p) => [p.id, p]));

    // Both halves of what the brief asks to surface. PRICE_REVIEW_PENDING should always imply an
    // open pricing suggestion, since the backend sets that status when one is raised and clears it
    // when the last one is decided - so including it here is a consistency check as much as a
    // filter. If a product ever shows up in review with an empty card list, the two have drifted.
    const ids = new Set(pendingByProduct.keys());
    for (const product of products) {
      if (product.status === "PRICE_REVIEW_PENDING") ids.add(product.id);
    }

    return [...ids]
      .map((id) => byId.get(id))
      .filter(Boolean)
      .map((product) => ({
        product,
        pricing: pendingPricing.filter((s) => s.productId === product.id),
        reorder: pendingReorder.filter((s) => s.productId === product.id),
      }))
      .sort((a, b) => {
        const autoA = [...a.pricing, ...a.reorder].some((s) => s.autoTriggered) ? 0 : 1;
        const autoB = [...b.pricing, ...b.reorder].some((s) => s.autoTriggered) ? 0 : 1;
        if (autoA !== autoB) return autoA - autoB;
        const depletionA = a.product.stockLevel / Math.max(1, a.product.reorderThreshold);
        const depletionB = b.product.stockLevel / Math.max(1, b.product.reorderThreshold);
        return depletionA - depletionB;
      });
  }, [products, pendingPricing, pendingReorder, pendingByProduct]);

  const visibleProducts = useMemo(
    () =>
      products.filter((p) => {
        if (category !== "ALL" && p.category !== category) return false;
        if (onlyAttention && !p.belowReorderThreshold && !pendingByProduct.has(p.id)) return false;
        return true;
      }),
    [products, category, onlyAttention, pendingByProduct]
  );

  const onDecide = (suggestion, status) => {
    const isPricing = "recommendedPrice" in suggestion;
    const kind = isPricing ? "pricing" : "reorder";
    return run(
      `${kind}-${suggestion.id}`,
      () =>
        isPricing
          ? api.decidePricing(suggestion.id, status)
          : api.decideReorder(suggestion.id, status),
      () => {
        if (status === "REJECTED") return `Rejected the ${kind} suggestion for ${suggestion.productSku}.`;
        return isPricing
          ? `Published ${money(suggestion.recommendedPrice)} for ${suggestion.productSku}.`
          : `Received ${suggestion.recommendedQuantity} units of ${suggestion.productSku}.`;
      }
    );
  };

  /** Deciding from the live panel is the same call as deciding from the queue; only the panel closes after. */
  const onDecideFromStream = async (suggestion, status) => {
    await onDecide(suggestion, status);
    closeStream();
  };

  const onRequest = (product, kind) =>
    run(
      `${kind}-req-${product.id}`,
      () => (kind === "pricing" ? api.requestPricing(product.id) : api.requestReorder(product.id)),
      () => `Requested a ${kind === "pricing" ? "price opinion" : "reorder plan"} for ${product.sku}.`
    );

  const onSell = (product, quantity) =>
    run(
      `sell-${quantity}-${product.id}`,
      () => api.placeOrder(product.id, quantity),
      (updated) =>
        `Sold ${quantity} × ${product.sku}. Stock now ${updated.stockLevel}, velocity ${updated.demandVelocity}. Watching for triggers…`
    );

  const onRestock = (product) =>
    run(
      `restock-${product.id}`,
      () => api.setStock(product.id, product.reorderThreshold * 3),
      (updated) => `${product.sku} restocked to ${updated.stockLevel} units.`
    );

  const onStrategyChange = (name) =>
    run("strategy", () => api.setStrategy(name), (s) => `Commerce engine switched to ${s.activeStrategy}.`);

  if (loading) {
    return (
      <div className="boot">
        <div className="spinner" aria-hidden="true" />
        <p>Connecting to {api.base}…</p>
      </div>
    );
  }

  return (
    <div className="app">
      <Header
        strategy={strategy}
        pendingCount={pendingPricing.length + pendingReorder.length}
        loading={loading}
        error={error}
        lastUpdated={lastUpdated}
        paused={paused}
        busy={busyKey === "strategy"}
        onTogglePause={() => setPaused((p) => !p)}
        onRefresh={refresh}
        onStrategyChange={onStrategyChange}
      />

      {error && (
        <div className="banner banner-error">
          <strong>Lost contact with the backend.</strong> {error} Showing the last data received
          {lastUpdated ? ` at ${lastUpdated.toLocaleTimeString()}` : ""}.
        </div>
      )}

      <main>
        <section className="panel">
          <div className="panel-head">
            <div>
              <h2>Awaiting approval</h2>
              <p className="panel-sub">
                Recommendations the system queued on its own, plus anything you asked for. No price
                changes and no stock arrives until you accept.
              </p>
            </div>
          </div>
          <ActionQueue groups={queueGroups} busyKey={busyKey} onDecide={onDecide} onRequest={onRequest} />
        </section>

        <section className="panel">
          <div className="panel-head">
            <div>
              <h2>Catalog</h2>
              <p className="panel-sub">
                Simulate sales here to drive the loop. Selling drains stock and raises demand
                velocity, which is what the backend reacts to.
              </p>
            </div>
            <div className="filters">
              <div className="segmented">
                <button
                  type="button"
                  className={category === "ALL" ? "active" : ""}
                  onClick={() => setCategory("ALL")}
                >
                  All
                </button>
                {CATEGORIES.map((c) => (
                  <button
                    key={c}
                    type="button"
                    className={category === c ? "active" : ""}
                    onClick={() => setCategory(c)}
                  >
                    {c.charAt(0) + c.slice(1).toLowerCase()}
                  </button>
                ))}
              </div>
              <label className="checkbox">
                <input
                  type="checkbox"
                  checked={onlyAttention}
                  onChange={(e) => setOnlyAttention(e.target.checked)}
                />
                Needs attention only
              </label>
            </div>
          </div>
          <CatalogBoard
            products={visibleProducts}
            pricingSuggestions={pricing}
            pendingByProduct={pendingByProduct}
            busyKey={busyKey}
            streaming={stream.product !== null}
            onSell={onSell}
            onRestock={onRestock}
            onStreamPricing={startStream}
          />
        </section>
      </main>

      {stream.product && (
        <ReasoningStream
          stream={stream}
          busy={busyKey === `pricing-${stream.suggestion?.id}`}
          onDecide={onDecideFromStream}
          onClose={closeStream}
        />
      )}

      <Toasts toasts={toasts} onDismiss={dismiss} />
    </div>
  );
}
