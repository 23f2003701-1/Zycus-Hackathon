const BASE = (import.meta.env.VITE_API_BASE ?? "http://localhost:8080").replace(/\/$/, "");

export class ApiError extends Error {
  constructor(message, status) {
    super(message);
    this.name = "ApiError";
    this.status = status;
  }
}

async function request(path, { method = "GET", body } = {}) {
  let response;
  try {
    response = await fetch(`${BASE}${path}`, {
      method,
      headers: body === undefined ? undefined : { "Content-Type": "application/json" },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  } catch {
    // A network-level failure has no status, and "Failed to fetch" tells a user nothing useful.
    throw new ApiError(`Cannot reach the API at ${BASE}. Is the backend running?`, 0);
  }

  if (!response.ok) {
    // The backend speaks RFC 7807, so the useful sentence is in `detail`.
    let message = `${response.status} ${response.statusText}`;
    try {
      const problem = await response.json();
      message = problem.detail || problem.title || message;
    } catch {
      /* not a problem document; keep the status line */
    }
    throw new ApiError(message, response.status);
  }

  return response.status === 204 ? null : response.json();
}

/**
 * Splits a `text/event-stream` body into `{ event, data }` frames.
 *
 * Hand-rolled rather than using `EventSource`, because the streaming endpoint is a POST - it
 * creates a suggestion - and `EventSource` can only issue GETs. Modelling a write as a GET to suit
 * a browser API would have been the wrong trade.
 *
 * A frame is terminated by a blank line, and a chunk boundary can fall anywhere, so the buffer is
 * only drained up to the last complete frame it contains.
 */
async function* readEventStream(body) {
  const reader = body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";

  while (true) {
    const { value, done } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });

    let split;
    while ((split = buffer.indexOf("\n\n")) !== -1) {
      const frame = buffer.slice(0, split);
      buffer = buffer.slice(split + 2);

      let event = "message";
      let data = "";
      for (const line of frame.split("\n")) {
        if (line.startsWith("event:")) event = line.slice(6).trim();
        else if (line.startsWith("data:")) data += line.slice(5).trim();
      }
      if (data) yield { event, data };
    }
  }
}

export const api = {
  base: BASE,

  products: () => request("/products"),
  pricingSuggestions: () => request("/pricing-suggestions"),
  reorderSuggestions: () => request("/reorder-suggestions"),
  strategy: () => request("/admin/commerce-strategy"),

  placeOrder: (productId, quantity) =>
    request(`/products/${productId}/orders`, { method: "POST", body: { quantity } }),

  setStock: (productId, stockLevel) =>
    request(`/products/${productId}/stock`, { method: "PATCH", body: { stockLevel } }),

  requestPricing: (productId) =>
    request(`/products/${productId}/suggest-pricing`, { method: "POST" }),

  requestReorder: (productId) =>
    request(`/products/${productId}/suggest-reorder`, { method: "POST" }),

  decidePricing: (id, status) =>
    request(`/pricing-suggestions/${id}`, { method: "PATCH", body: { status } }),

  decideReorder: (id, status) =>
    request(`/reorder-suggestions/${id}`, { method: "PATCH", body: { status } }),

  setStrategy: (activeStrategy) =>
    request("/admin/commerce-strategy", { method: "PATCH", body: { activeStrategy } }),

  /**
   * Requests a price opinion and reports the model's reasoning as it is written.
   *
   * Resolves once the stream closes. Handlers are optional; an unhandled event is skipped rather
   * than treated as an error, so the backend can add event types without breaking this client.
   */
  streamPricing: async (productId, handlers = {}, signal) => {
    let response;
    try {
      response = await fetch(`${BASE}/products/${productId}/suggest-pricing/stream`, {
        method: "POST",
        headers: { Accept: "text/event-stream" },
        signal,
      });
    } catch (err) {
      if (err.name === "AbortError") return;
      throw new ApiError(`Cannot reach the API at ${BASE}. Is the backend running?`, 0);
    }

    // Failures before the stream opens are ordinary problem documents - a 404 for an unknown
    // product arrives here, not as an error event.
    if (!response.ok) {
      let message = `${response.status} ${response.statusText}`;
      try {
        const problem = await response.json();
        message = problem.detail || problem.title || message;
      } catch {
        /* not a problem document; keep the status line */
      }
      throw new ApiError(message, response.status);
    }

    for await (const { event, data } of readEventStream(response.body)) {
      const handler = handlers[event];
      if (!handler) continue;
      try {
        handler(JSON.parse(data));
      } catch {
        /* a frame we cannot parse is not worth abandoning the stream over */
      }
    }
  },
};
