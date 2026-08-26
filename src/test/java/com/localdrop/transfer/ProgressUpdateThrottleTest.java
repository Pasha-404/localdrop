package com.localdrop.transfer;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProgressUpdateThrottleTest {
    @Test
    void publishesAtMostTenIntermediateUpdatesPerSecondAndAlwaysPublishesCompletion() {
        AtomicLong clock = new AtomicLong();
        ProgressUpdateThrottle throttle = new ProgressUpdateThrottle(clock::get);

        assertTrue(throttle.shouldPublish(0.0));

        clock.set(50_000_000L);
        assertFalse(throttle.shouldPublish(0.5));

        clock.set(100_000_000L);
        assertTrue(throttle.shouldPublish(0.75));

        clock.set(150_000_000L);
        assertTrue(throttle.shouldPublish(1.0));
    }

    @Test
    void resetPublishesTheInitialProgressOfTheNextFileImmediately() {
        AtomicLong clock = new AtomicLong();
        ProgressUpdateThrottle throttle = new ProgressUpdateThrottle(clock::get);

        assertTrue(throttle.shouldPublish(0.0));
        clock.set(10_000_000L);
        throttle.reset();

        assertTrue(throttle.shouldPublish(0.0));
    }
}
