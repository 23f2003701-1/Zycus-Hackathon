import { useCallback, useEffect, useRef, useState } from "react";
import { api } from "./api";

/**
 * Polls the backend, because suggestions arrive asynchronously and nothing pushes them.
 *
 * Two behaviours matter more than they look:
 *   - `loading` is only true for the very first fetch. Setting it on every poll would flash the
 *     whole console every few seconds and make buttons unclickable.
 *   - a failed poll keeps the last good data on screen and shows a banner, rather than blanking
 *     the queue. A merchandiser losing the backend should still see what they were looking at.
 */
export function useConsoleData(intervalMs = 4000) {
  const [data, setData] = useState({
    products: [],
    pricing: [],
    reorder: [],
    strategy: null,
  });
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [lastUpdated, setLastUpdated] = useState(null);
  const [paused, setPaused] = useState(false);

  const inFlight = useRef(false);

  const refresh = useCallback(async () => {
    if (inFlight.current) return;
    inFlight.current = true;
    try {
      const [products, pricing, reorder, strategy] = await Promise.all([
        api.products(),
        api.pricingSuggestions(),
        api.reorderSuggestions(),
        api.strategy(),
      ]);
      setData({ products, pricing, reorder, strategy });
      setError(null);
      setLastUpdated(new Date());
    } catch (err) {
      setError(err.message);
    } finally {
      inFlight.current = false;
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    refresh();
    if (paused) return undefined;
    const timer = setInterval(refresh, intervalMs);
    return () => clearInterval(timer);
  }, [refresh, paused, intervalMs]);

  return { ...data, loading, error, lastUpdated, paused, setPaused, refresh };
}
