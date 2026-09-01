import { useCallback, useEffect, useRef, useState } from "react";
import { api } from "./api";

const IDLE = { product: null, phase: "idle", advisor: null, reasoning: "", fallback: null, suggestion: null, error: null };

/**
 * Drives one live pricing stream at a time.
 *
 * Single-flight on purpose. The panel is a modal, and two streams writing into one transcript
 * would produce interleaved reasoning that reads like the model contradicting itself. Opening a
 * new stream aborts the previous one.
 */
export function usePricingStream({ onFinished, onError }) {
  const [state, setState] = useState(IDLE);
  const abortRef = useRef(null);

  // A stream outlives the click that started it, so an unmount mid-flight must not leave the
  // request running and calling setState into a component that is gone.
  useEffect(() => () => abortRef.current?.abort(), []);

  const close = useCallback(() => {
    abortRef.current?.abort();
    abortRef.current = null;
    setState(IDLE);
  }, []);

  const start = useCallback(
    async (product) => {
      abortRef.current?.abort();
      const controller = new AbortController();
      abortRef.current = controller;

      setState({ ...IDLE, product, phase: "connecting" });

      try {
        await api.streamPricing(
          product.id,
          {
            status: ({ phase, advisor, streaming }) =>
              setState((s) => ({ ...s, phase: streaming ? "reasoning" : "computing", advisor, serverPhase: phase })),

            // Appending rather than replacing is the whole point: each frame is a fragment of a
            // sentence, not a new version of one.
            token: ({ text }) => setState((s) => ({ ...s, reasoning: s.reasoning + text })),

            fallback: ({ reason, advisor }) =>
              setState((s) => ({ ...s, phase: "fallback", advisor, fallback: reason })),

            suggestion: (suggestion) => setState((s) => ({ ...s, phase: "done", suggestion })),

            error: ({ message }) => setState((s) => ({ ...s, phase: "error", error: message })),
          },
          controller.signal
        );

        if (!controller.signal.aborted) {
          // The connection closing without a suggestion means the server gave up mid-stream.
          setState((s) =>
            s.suggestion || s.error ? s : { ...s, phase: "error", error: "The stream ended without a recommendation." }
          );
          onFinished?.();
        }
      } catch (err) {
        if (controller.signal.aborted) return;
        setState((s) => ({ ...s, phase: "error", error: err.message }));
        onError?.(err);
      }
    },
    [onFinished, onError]
  );

  return { stream: state, startStream: start, closeStream: close };
}
