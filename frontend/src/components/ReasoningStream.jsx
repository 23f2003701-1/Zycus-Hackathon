import { useEffect, useRef } from "react";
import { ConfidenceMeter, SourceBadge } from "./Badges";
import { money, signedPercent } from "../format";

const PHASE_COPY = {
  connecting: { label: "Opening stream…", tone: "wait" },
  reasoning: { label: "Model is reasoning", tone: "live" },
  computing: { label: "Rule engine is computing", tone: "wait" },
  fallback: { label: "Model failed — falling back to rules", tone: "warn" },
  done: { label: "Recommendation ready", tone: "ok" },
  error: { label: "Could not produce a recommendation", tone: "bad" },
};

/**
 * Shows a price recommendation being formed rather than just its result.
 *
 * The value here is not the animation. A merchandiser deciding whether to trust a price wants the
 * argument for it, and an argument that lands all at once several seconds later reads like an
 * assertion. Watching it assemble also makes a fallback legible: the reasoning stops mid-thought,
 * the banner turns amber, and the rule-based answer that arrives is visibly a different voice.
 */
export function ReasoningStream({ stream, busy, onDecide, onClose }) {
  const { product, phase, advisor, reasoning, fallback, suggestion, error } = stream;
  const transcriptRef = useRef(null);

  // Follow the text as it grows, the way a terminal would.
  useEffect(() => {
    const el = transcriptRef.current;
    if (el) el.scrollTop = el.scrollHeight;
  }, [reasoning]);

  // Escape closes, because this sits over the queue a merchandiser was reading.
  useEffect(() => {
    const onKey = (e) => e.key === "Escape" && onClose();
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);

  if (!product) return null;

  const status = PHASE_COPY[phase] ?? PHASE_COPY.connecting;
  const streaming = phase === "reasoning" || phase === "connecting";

  return (
    <div className="stream-backdrop" role="dialog" aria-modal="true" aria-label={`Live price reasoning for ${product.sku}`}>
      <div className="stream-panel">
        <header className="stream-head">
          <div>
            <h3>{product.name}</h3>
            <p className="stream-sub">
              {product.sku} · {money(product.currentPrice)} · {product.stockLevel} in stock ·{" "}
              {product.demandVelocity}/24h
            </p>
          </div>
          <button type="button" className="btn btn-ghost" onClick={onClose} aria-label="Close">
            ✕
          </button>
        </header>

        <div className={`stream-status stream-status-${status.tone}`}>
          <span className={`stream-dot ${streaming ? "stream-dot-live" : ""}`} aria-hidden="true" />
          {status.label}
          {advisor && <code className="stream-advisor">{advisor}</code>}
        </div>

        <div className="stream-transcript" ref={transcriptRef} aria-live="polite" aria-busy={streaming}>
          {reasoning ? (
            <p>
              {reasoning}
              {streaming && <span className="caret" aria-hidden="true" />}
            </p>
          ) : (
            <p className="stream-placeholder">
              {phase === "computing"
                ? "The rule engine produces its answer in one step, so there is nothing to stream."
                : "Waiting for the first tokens…"}
              {streaming && <span className="caret" aria-hidden="true" />}
            </p>
          )}
        </div>

        {fallback && (
          <div className="stream-fallback">
            <strong>The model's answer was rejected.</strong> {fallback} The deterministic engine
            produced the recommendation below instead — any reasoning above it is discarded.
          </div>
        )}

        {error && <div className="stream-error">{error}</div>}

        {suggestion && (
          <div className="stream-result">
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
              <SourceBadge generatedBy={suggestion.generatedBy} />
            </div>

            <footer className="stream-foot">
              <ConfidenceMeter value={suggestion.confidence} />
              <div className="suggestion-actions">
                {/* Streaming changes when the argument is visible, never who decides. This is the
                    same checkpoint as every other card in the queue. */}
                <button type="button" className="btn btn-ghost" disabled={busy} onClick={() => onDecide(suggestion, "REJECTED")}>
                  Reject
                </button>
                <button type="button" className="btn btn-primary" disabled={busy} onClick={() => onDecide(suggestion, "ACCEPTED")}>
                  {busy ? "Working…" : "Accept & publish price"}
                </button>
              </div>
            </footer>
          </div>
        )}

        {!suggestion && !error && (
          <p className="stream-note">
            Nothing changes until you accept. This suggestion is already queued for approval, so
            closing now leaves it waiting rather than losing it.
          </p>
        )}
      </div>
    </div>
  );
}
