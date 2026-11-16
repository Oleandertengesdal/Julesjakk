package no.sjakkportal.ratelimit;

import java.time.Duration;

/**
 * A token bucket holding {@code capacity} tokens that refills continuously at
 * {@code capacity} tokens per {@code period}.
 */
public record RateLimitPolicy(String name, int capacity, Duration period) {

    public RateLimitPolicy {
        if (capacity < 1 || period.isZero() || period.isNegative()) {
            throw new IllegalArgumentException("capacity and period must be positive");
        }
    }
}
