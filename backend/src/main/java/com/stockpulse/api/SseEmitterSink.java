package com.stockpulse.api;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.stockpulse.service.StreamClientGoneException;
import com.stockpulse.service.StreamSink;

/**
 * Adapts {@link StreamSink} onto Spring MVC's SSE transport. The only class in the streaming path
 * that knows what a servlet is.
 *
 * <p>A client that closes the tab mid-stream makes {@code send} throw. That is translated into a
 * {@link StreamClientGoneException} so the producing service unwinds through its normal failure
 * path instead of continuing to reason on behalf of nobody.
 */
class SseEmitterSink implements StreamSink {

    private static final Logger log = LoggerFactory.getLogger(SseEmitterSink.class);

    private final SseEmitter emitter;
    private volatile boolean closed;

    SseEmitterSink(SseEmitter emitter) {
        this.emitter = emitter;
    }

    @Override
    public void send(String event, Object payload) {
        if (closed) {
            throw new StreamClientGoneException("stream already closed");
        }
        try {
            emitter.send(SseEmitter.event().name(event).data(payload));
        } catch (IOException | IllegalStateException ex) {
            closed = true;
            log.debug("SSE client went away during '{}': {}", event, ex.getMessage());
            throw new StreamClientGoneException("client disconnected during '" + event + "'");
        }
    }

    @Override
    public void complete() {
        if (!closed) {
            closed = true;
            emitter.complete();
        }
    }

    @Override
    public void completeWithError(Throwable error) {
        if (!closed) {
            closed = true;
            emitter.completeWithError(error);
        }
    }
}
