package com.rhythm.los.integration;

/** A vendor call failed. retryable=false sends it straight to the dead-letter queue. */
public class VendorException extends RuntimeException {
    private final boolean retryable;

    public VendorException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }

    public boolean isRetryable() { return retryable; }
}
