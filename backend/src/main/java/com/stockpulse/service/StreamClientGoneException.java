package com.stockpulse.service;

/**
 * Raised by a {@link StreamSink} when the consumer has disconnected.
 *
 * <p>Part of the sink contract rather than a transport detail, because it is the signal that lets a
 * producing service abandon work nobody will read. It is not an application failure - a closed tab
 * is a normal end to a stream - so it is logged quietly and never reported as an error.
 */
public class StreamClientGoneException extends RuntimeException {

    public StreamClientGoneException(String message) {
        super(message);
    }
}
