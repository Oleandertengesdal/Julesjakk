package no.sjakkportal.ratelimit;

import java.util.function.LongSupplier;
import no.sjakkportal.common.NotImplementedYet;

/**
 * In-memory Bucket4j token buckets, one per (policy, key). Thread-safe.
 * Keys are client-controlled (IP addresses, usernames), so the bucket store must be bounded.
 */
public class RateLimiter {

    /** Upper bound on tracked buckets; least-recently-used entries are evicted beyond this. */
    public static final int MAX_BUCKETS = 10_000;

    public RateLimiter() {
        this(System::nanoTime);
    }

    /** @param nanoClock monotonic clock, injectable so tests can control time */
    public RateLimiter(LongSupplier nanoClock) {
    }

    public RateLimitDecision tryConsume(RateLimitPolicy policy, String key) {
        throw new NotImplementedYet();
    }

    /** Number of buckets currently tracked. */
    public int trackedBuckets() {
        throw new NotImplementedYet();
    }
}
