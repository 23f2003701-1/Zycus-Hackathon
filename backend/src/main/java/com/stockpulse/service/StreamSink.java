package com.stockpulse.service;

/**
 * Where a streaming service writes its events.
 *
 * <p>An interface rather than a direct {@code SseEmitter} dependency for one concrete reason: the
 * interesting behaviour of {@link PricingStreamService} is the <em>sequence</em> of events it emits
 * on the happy path versus the fallback path, and that is only cheaply assertable if a test can
 * substitute a recorder. It also keeps the servlet transport out of the service layer, so a future
 * WebSocket console reuses the orchestration untouched.
 */
public interface StreamSink {

    /**
     * Emits one named event. Implementations must swallow nothing: a client that hung up mid-stream
     * should surface here so the caller stops doing work nobody is waiting for.
     */
    void send(String event, Object payload);

    void complete();

    void completeWithError(Throwable error);
}
