import { relativeTime } from "../format";

/**
 * Header doubles as the systems-status strip: which engine is reasoning, whether polling is live,
 * and how stale the data is. The strategy selector is the runtime switch from the commerce engine -
 * changing it here affects both the on-demand endpoints and the background loop, with no restart.
 */
export function Header({ strategy, pendingCount, loading, error, lastUpdated, paused, onTogglePause, onRefresh, onStrategyChange, busy }) {
  const live = !paused && !error;

  return (
    <header className="topbar">
      <div className="brand">
        <div className="brand-mark" aria-hidden="true">
          SP
        </div>
        <div>
          <h1>StockPulse</h1>
          <p>Merchandising console</p>
        </div>
      </div>

      <div className="topbar-stats">
        <div className="stat">
          <span className="stat-value">{pendingCount}</span>
          <span className="stat-label">awaiting approval</span>
        </div>

        <label className="engine">
          <span className="engine-label">Commerce engine</span>
          <select
            value={strategy?.activeStrategy ?? ""}
            disabled={!strategy || busy}
            onChange={(e) => onStrategyChange(e.target.value)}
          >
            {(strategy?.availableStrategies ?? []).map((name) => (
              <option key={name} value={name}>
                {name === "aiAdvisor" ? "AI advisor (LLM)" : "Rule-based"}
              </option>
            ))}
          </select>
        </label>

        <div className="poll">
          <span className={`live-dot ${live ? "live" : "idle"}`} aria-hidden="true" />
          <span className="poll-text">
            {loading
              ? "Loading…"
              : error
                ? "Disconnected"
                : paused
                  ? "Paused"
                  : `Updated ${relativeTime(lastUpdated?.toISOString())}`}
          </span>
          <button type="button" className="btn btn-quiet" onClick={onTogglePause}>
            {paused ? "Resume" : "Pause"}
          </button>
          <button type="button" className="btn btn-quiet" onClick={onRefresh}>
            Refresh
          </button>
        </div>
      </div>
    </header>
  );
}
