package no.sjakkportal.ratelimit;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.TimeMeter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * In-memory Bucket4j token buckets, one per (policy, key). Thread-safe.
 * Keys are client-controlled (IP addresses, usernames), so the bucket store must be bounded.
 */
public class RateLimiter {

    /** Upper bound on tracked buckets; least-recently-used entries are evicted beyond this. */
    public static final int MAX_BUCKETS = 10_000;

    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    private final TimeMeter timeMeter;
    private final Map<String, Bucket> buckets = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Bucket> eldest) {
            return size() > MAX_BUCKETS;
        }
    };

    public RateLimiter() {
        this(System::nanoTime);
    }

    /** @param nanoClock monotonic clock, injectable so tests can control time */
    public RateLimiter(LongSupplier nanoClock) {
        this.timeMeter = new TimeMeter() {
            @Override
            public long currentTimeNanos() {
                return nanoClock.getAsLong();
            }

            @Override
            public boolean isWallClockBased() {
                return false;
            }
        };
    }

    public RateLimitDecision tryConsume(RateLimitPolicy policy, String key) {
        ConsumptionProbe probe = bucketFor(policy, key).tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            return new RateLimitDecision(true, probe.getRemainingTokens(), 0);
        }
        // Round up: telling a client "Retry-After: 0" invites an immediate retry that is still rejected.
        long seconds = (probe.getNanosToWaitForRefill() + NANOS_PER_SECOND - 1) / NANOS_PER_SECOND;
        return new RateLimitDecision(false, 0, Math.max(1, seconds));
    }

    /** Number of buckets currently tracked. */
    public int trackedBuckets() {
        synchronized (buckets) {
            return buckets.size();
        }
    }

    /** Forgets every bucket. For tests and for an operator who needs to lift limits after an incident. */
    public void clear() {
        synchronized (buckets) {
            buckets.clear();
        }
    }

    private Bucket bucketFor(RateLimitPolicy policy, String key) {
        // A missing key must not become "no limit": all such requests share one bucket.
        String normalizedKey = key == null ? "" : key.strip();
        String storeKey = policy.name() + '\u0000' + normalizedKey;
        synchronized (buckets) {
            return buckets.computeIfAbsent(storeKey, ignored -> Bucket.builder()
                    .addLimit(Bandwidth.builder().capacity(policy.capacity())
                            .refillGreedy(policy.capacity(), policy.period()).build())
                    .withCustomTimePrecision(timeMeter)
                    .build());
        }
    }
}
