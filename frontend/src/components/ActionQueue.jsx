import { StatusPill } from "./Badges";
import { SuggestionCard } from "./SuggestionCard";
import { daysOfCover, money } from "../format";

/**
 * The reason this page exists: everything waiting on a human decision, grouped by product.
 *
 * Grouping rather than listing two flat tables is deliberate. The brief asks for "products in
 * PRICE_REVIEW_PENDING or with pending suggestions", and a merchandiser decides per product - when
 * one order both drained stock and spiked demand, the price and reorder questions for that product
 * are one conversation, not two rows in different tables.
 */
export function ActionQueue({ groups, busyKey, onDecide, onRequest }) {
  if (groups.length === 0) {
    return (
      <div className="empty">
        <div className="empty-title">Nothing awaiting approval</div>
        <p>
          Nothing is pending. Simulate a sale on a low-stock product in the catalog below and the
          agentic loop will queue pricing and reorder recommendations here on its own — no button in
          this section triggers them.
        </p>
      </div>
    );
  }

  return (
    <div className="queue">
      {groups.map((group) => (
        <section className="queue-group" key={group.product.id}>
          <header className="queue-group-head">
            <div>
              <div className="queue-product">
                <h3>{group.product.name}</h3>
                <StatusPill status={group.product.status} />
              </div>
              <div className="queue-meta">
                <span>{group.product.sku}</span>
                <span className="dot" />
                <span>{group.product.category}</span>
                <span className="dot" />
                <span>{money(group.product.currentPrice)}</span>
                <span className="dot" />
                <span className={group.product.belowReorderThreshold ? "text-warn" : undefined}>
                  {group.product.stockLevel} / {group.product.reorderThreshold} in stock
                </span>
                <span className="dot" />
                <span>{group.product.demandVelocity} sold /24h</span>
                {daysOfCover(group.product) !== null && (
                  <>
                    <span className="dot" />
                    <span className={daysOfCover(group.product) < 3 ? "text-warn" : undefined}>
                      {daysOfCover(group.product).toFixed(1)}d cover
                    </span>
                  </>
                )}
              </div>
            </div>
            <div className="queue-group-actions">
              <button
                type="button"
                className="btn btn-quiet"
                disabled={busyKey === `pricing-req-${group.product.id}`}
                onClick={() => onRequest(group.product, "pricing")}
              >
                Ask for a price opinion
              </button>
              <button
                type="button"
                className="btn btn-quiet"
                disabled={busyKey === `reorder-req-${group.product.id}`}
                onClick={() => onRequest(group.product, "reorder")}
              >
                Ask for a reorder plan
              </button>
            </div>
          </header>

          {group.pricing.length + group.reorder.length === 0 ? (
            <p className="queue-drift">
              This product is marked <strong>price review pending</strong> but has no open
              suggestion, which means the product status and the suggestion queue have drifted apart.
            </p>
          ) : null}

          <div className="queue-cards">
            {group.pricing.map((s) => (
              <SuggestionCard
                key={`p${s.id}`}
                suggestion={s}
                kind="pricing"
                busy={busyKey === `pricing-${s.id}`}
                onDecide={onDecide}
              />
            ))}
            {group.reorder.map((s) => (
              <SuggestionCard
                key={`r${s.id}`}
                suggestion={s}
                kind="reorder"
                busy={busyKey === `reorder-${s.id}`}
                onDecide={onDecide}
              />
            ))}
          </div>
        </section>
      ))}
    </div>
  );
}
