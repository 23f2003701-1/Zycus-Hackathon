import { titleCase } from "../format";

/**
 * The badge the rubric asks for: it has to be obvious at a glance whether the system raised this
 * recommendation on its own or a merchandiser asked for it. Auto-triggered badges get a filled,
 * saturated treatment; requested ones stay outlined and quiet.
 */
const TRIGGER_COPY = {
  INVENTORY_LOW: { label: "Inventory low", tone: "amber", hint: "Auto-triggered: stock fell below its reorder threshold" },
  DEMAND_SPIKE: { label: "Demand spike", tone: "violet", hint: "Auto-triggered: velocity passed 3x its category peers" },
  MANUAL: { label: "Requested", tone: "slate", hint: "A merchandiser asked for this one" },
  INITIAL: { label: "Initial review", tone: "blue", hint: "Seeded at startup for the demo" },
};

export function TriggerBadge({ trigger, autoTriggered }) {
  const copy = TRIGGER_COPY[trigger] ?? { label: titleCase(trigger), tone: "slate", hint: trigger };
  return (
    <span
      className={`badge badge-${copy.tone} ${autoTriggered ? "badge-solid" : ""}`}
      title={copy.hint}
    >
      {autoTriggered && <span className="badge-pulse" aria-hidden="true" />}
      {copy.label}
    </span>
  );
}

/**
 * Which engine produced this. Worth surfacing rather than hiding: when the LLM times out or
 * returns something out of bounds the system falls back to rules, and a merchandiser deserves to
 * know they are reading a formula instead of a model.
 */
export function SourceBadge({ generatedBy }) {
  const isAi = generatedBy === "aiAdvisor";
  return (
    <span
      className={`badge badge-${isAi ? "teal" : "grey"}`}
      title={
        isAi
          ? "Reasoned by the LLM advisor"
          : "Produced by the deterministic rule engine — either it is the active strategy, or the AI call failed and this is the fallback"
      }
    >
      {isAi ? "AI" : "Rules"}
    </span>
  );
}

export function StatusPill({ status }) {
  const tone = {
    ACTIVE: "green",
    PRICE_REVIEW_PENDING: "amber",
    OUT_OF_STOCK: "red",
    PENDING: "amber",
    ACCEPTED: "green",
    REJECTED: "grey",
  }[status] ?? "slate";
  return <span className={`pill pill-${tone}`}>{titleCase(status)}</span>;
}

export function ConfidenceMeter({ value }) {
  const pct = Math.round((Number(value) || 0) * 100);
  const tone = pct >= 75 ? "green" : pct >= 50 ? "amber" : "red";
  return (
    <div className="confidence" title={`Model confidence ${pct}%`}>
      <span className="confidence-label">Confidence</span>
      <div className="confidence-track">
        <div className={`confidence-fill confidence-${tone}`} style={{ width: `${pct}%` }} />
      </div>
      <span className="confidence-value">{pct}%</span>
    </div>
  );
}
