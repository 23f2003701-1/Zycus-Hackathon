package com.stockpulse.api;

import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.stockpulse.config.AsyncConfig;
import com.stockpulse.service.PricingStreamService;
import com.stockpulse.service.ProductService;

/**
 * The streaming counterpart to {@code POST /products/{id}/suggest-pricing}.
 *
 * <p>Same outcome as the blocking endpoint - one persisted, still-pending pricing suggestion - but
 * the model's reasoning is delivered while it is being written rather than after. That distinction
 * is the point: a merchandiser deciding whether to trust a price wants the argument, and an
 * argument that appears all at once several seconds later reads like an assertion.
 *
 * <p>POST rather than GET, so the browser {@code EventSource} API cannot be used and the console
 * reads the body with {@code fetch} instead. That is the correct trade: this call has a side
 * effect, and modelling it as a GET to satisfy a client API would misrepresent the operation.
 */
@RestController
public class PricingStreamController {

    private static final Duration STREAM_TIMEOUT = Duration.ofSeconds(90);

    private static final Logger log = LoggerFactory.getLogger(PricingStreamController.class);

    private final PricingStreamService streamService;
    private final ProductService productService;
    private final Executor executor;

    public PricingStreamController(PricingStreamService streamService,
                                   ProductService productService,
                                   @Qualifier(AsyncConfig.COMMERCE_EXECUTOR) Executor executor) {
        this.streamService = streamService;
        this.productService = productService;
        this.executor = executor;
    }

    @PostMapping(value = "/products/{id}/suggest-pricing/stream",
                 produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamPricing(@PathVariable String id) {
        // Resolved before the emitter is returned so an unknown product is an ordinary 404
        // ProblemDetail. Once the response commits as an event stream, the status is already sent
        // and the only way left to report a bad request is an error event nobody expects.
        productService.get(id);

        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT.toMillis());
        emitter.onTimeout(() -> {
            log.warn("Pricing stream for {} timed out after {}s", id, STREAM_TIMEOUT.toSeconds());
            emitter.complete();
        });

        try {
            executor.execute(() -> streamService.streamPricing(id, new SseEmitterSink(emitter)));
        } catch (RejectedExecutionException ex) {
            // Saturated pool. Better to fail the stream immediately than leave the console
            // watching a connection that will never produce an event.
            emitter.completeWithError(ex);
        }
        return emitter;
    }
}
