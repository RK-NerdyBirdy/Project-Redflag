package edu.redflag.core;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Three-state Circuit Breaker (CLOSED -> OPEN -> HALF_OPEN).
 * Protects edge pipeline from native JNI / memory panics, enabling smooth fallback to heuristic inference.
 */
public final class CircuitBreaker {
    private final int failureThreshold;
    private final long cooldownMs;
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private final AtomicLong openedTimestamp = new AtomicLong(0L);

    public CircuitBreaker(int failureThreshold, long cooldownMs) {
        this.failureThreshold = failureThreshold;
        this.cooldownMs = cooldownMs;
    }

    /**
     * Checks whether execution through the primary native path is allowed.
     */
    public boolean allow() {
        long opened = openedTimestamp.get();
        if (opened == 0L) {
            return true; // CLOSED state
        }
        long elapsed = System.currentTimeMillis() - opened;
        if (elapsed >= cooldownMs) {
            // Transition to HALF_OPEN probe
            if (openedTimestamp.compareAndSet(opened, 0L)) {
                consecutiveFailures.set(failureThreshold - 1);
                return true;
            }
        }
        return false; // OPEN state
    }

    /**
     * Called on successful execution through primary path to reset failure counters.
     */
    public void recordSuccess() {
        consecutiveFailures.set(0);
        openedTimestamp.set(0L);
    }

    /**
     * Called on native / timeout failures to trip the circuit if threshold is reached.
     */
    public void recordFailure() {
        int failures = consecutiveFailures.incrementAndGet();
        if (failures >= failureThreshold) {
            openedTimestamp.set(System.currentTimeMillis());
        }
    }

    public boolean isOpen() {
        return openedTimestamp.get() != 0L;
    }
}
