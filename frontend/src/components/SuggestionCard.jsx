import { ConfidenceMeter, SourceBadge, TriggerBadge } from "./Badges";
import { money, relativeTime, signedPercent } from "../format";

/**
 * One recommendation awaiting a decision. Pricing and reorder share this shell because a
 * merchandiser is doing the same job in both cases - read the reasoning, judge the number, decide -
 * and only the headline figures differ.
 */
export function SuggestionCard({ suggestion, kind, busy, onDecide }) {
  const isPricing = kind === "pricing";
  const disabled = Boolean(busy);

  return (
    <article className={`suggestion suggestion-${kind}`}>
      <header className="suggestion-head">
        <div className="suggestion-kind">
          <span className={`kind-dot kind-${kind}`} aria-hidden="true" />
          {isPricing ? "Price change" : "Replenishment"}
        </div>
        <div className="suggestion-badges">
          <TriggerBadge trigger={suggestion.triggerReason} autoTriggered={suggestion.autoTriggered} />
          <SourceBadge generatedBy={suggestion.generatedBy} />
        </div>
      </header>

      {isPricing ? (
        <div className="figures">
          <div className="figure">
            <span className="figure-label">Current</span>
            <span className="figure-value">{money(suggestion.currentPrice)}</span>
          </div>
          <span className="figure-arrow" aria-hidden="true">
            →
          </span>
          <div className="figure">
            <span className="figure-label">Recommended</span>
            <span className="figure-value figure-strong">{money(suggestion.recommendedPrice)}</span>
          </div>
          <div className={`delta delta-${(suggestion.direction ?? "HOLD").toLowerCase()}`}>
            {signedPercent(suggestion.changePct)}
          </div>
        </div>
      ) : (
        <div className="figures">
          <div className="figure">
            <span className="figure-label">Stock now</span>
            <span className="figure-value">{suggestion.currentStock} units</span>
          </div>
          <span className="figure-arrow" aria-hidden="true">
            →
          </span>
          <div className="figure">
            <span className="figure-label">Order</span>
            <span className="figure-value figure-strong">{suggestion.recommendedQuantity} units</span>
          </div>
          <div className="delta delta-neutral">{suggestion.suggestedLeadTimeDays}d lead time</div>
        </div>
      )}

      <p className="reasoning">{suggestion.reasoning}</p>

      <footer className="suggestion-foot">
        <ConfidenceMeter value={suggestion.confidence} />
        <div className="suggestion-actions">
          <span className="timestamp">{relativeTime(suggestion.createdAt)}</span>
          <button
            type="button"
            className="btn btn-ghost"
            disabled={disabled}
            onClick={() => onDecide(suggestion, "REJECTED")}
          >
            Reject
          </button>
          <button
            type="button"
            className="btn btn-primary"
            disabled={disabled}
            onClick={() => onDecide(suggestion, "ACCEPTED")}
          >
            {busy ? "Working…" : isPricing ? "Accept & publish price" : "Accept & receive stock"}
          </button>
        </div>
      </footer>
    </article>
  );
}
