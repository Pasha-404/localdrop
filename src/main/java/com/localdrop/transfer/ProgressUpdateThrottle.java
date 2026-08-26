package com.localdrop.transfer;

import java.util.function.LongSupplier;

/** Limits UI progress notifications without affecting transfer throughput. */
public final class ProgressUpdateThrottle {
    static final long UPDATE_INTERVAL_NANOS = 100_000_000L;

    private final LongSupplier clockNanos;
    private boolean hasPublishedProgress;
    private long lastPublishedAtNanos;

    public ProgressUpdateThrottle() {
        this(System::nanoTime);
    }

    ProgressUpdateThrottle(LongSupplier clockNanos) {
        this.clockNanos = clockNanos;
    }

    public synchronized void reset() {
        hasPublishedProgress = false;
    }

    public synchronized boolean shouldPublish(double progress) {
        long now = clockNanos.getAsLong();
        if (progress >= 1.0 || !hasPublishedProgress || now - lastPublishedAtNanos >= UPDATE_INTERVAL_NANOS) {
            hasPublishedProgress = true;
            lastPublishedAtNanos = now;
            return true;
        }
        return false;
    }
}
