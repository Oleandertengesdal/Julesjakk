package no.sjakkportal.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RateLimiterTest {

    private static final RateLimitPolicy FIVE_PER_MINUTE = new RateLimitPolicy("login", 5, Duration.ofMinutes(1));

    private final AtomicLong nanos = new AtomicLong(1_000_000_000L);
    private RateLimiter limiter;

    @BeforeEach
    void setUp() {
        limiter = new RateLimiter(nanos::get);
    }

    private void advance(Duration duration) {
        nanos.addAndGet(duration.toNanos());
    }

    @Test
    void allowsExactlyCapacityRequestsInABurst() {
        for (int i = 1; i <= 5; i++) {
            RateLimitDecision decision = limiter.tryConsume(FIVE_PER_MINUTE, "ola");
            assertThat(decision.allowed()).as("request %d", i).isTrue();
            assertThat(decision.remainingTokens()).isEqualTo(5 - i);
            assertThat(decision.retryAfterSeconds()).isZero();
        }
        RateLimitDecision sixth = limiter.tryConsume(FIVE_PER_MINUTE, "ola");
        assertThat(sixth.allowed()).isFalse();
        assertThat(sixth.remainingTokens()).isZero();
    }

    @Test
    void rejectionCarriesARetryAfterThatIsNeverZeroAndNeverLongerThanOneRefill() {
        for (int i = 0; i < 5; i++) {
            limiter.tryConsume(FIVE_PER_MINUTE, "ola");
        }
        // One token refills every 12 s.
        assertThat(limiter.tryConsume(FIVE_PER_MINUTE, "ola").retryAfterSeconds()).isEqualTo(12);

        advance(Duration.ofMillis(11_900));
        RateLimitDecision almost = limiter.tryConsume(FIVE_PER_MINUTE, "ola");
        assertThat(almost.allowed()).isFalse();
        // 100 ms left must round UP to 1, not down to "Retry-After: 0".
        assertThat(almost.retryAfterSeconds()).isEqualTo(1);
    }

    @Test
    void refillsOneTokenAtATimeNotTheWholeBucket() {
        for (int i = 0; i < 5; i++) {
            limiter.tryConsume(FIVE_PER_MINUTE, "ola");
        }
        advance(Duration.ofSeconds(12));
        assertThat(limiter.tryConsume(FIVE_PER_MINUTE, "ola").allowed()).isTrue();
        assertThat(limiter.tryConsume(FIVE_PER_MINUTE, "ola").allowed()).isFalse();
    }

    @Test
    void neverAccumulatesMoreThanCapacityHoweverLongTheKeyIsIdle() {
        limiter.tryConsume(FIVE_PER_MINUTE, "ola");
        advance(Duration.ofDays(30));
        int allowed = 0;
        for (int i = 0; i < 50; i++) {
            if (limiter.tryConsume(FIVE_PER_MINUTE, "ola").allowed()) {
                allowed++;
            }
        }
        assertThat(allowed).isEqualTo(5);
    }

    @Test
    void keysAndPoliciesAreIndependent() {
        RateLimitPolicy other = new RateLimitPolicy("invite", 5, Duration.ofMinutes(1));
        for (int i = 0; i < 5; i++) {
            limiter.tryConsume(FIVE_PER_MINUTE, "ola");
        }
        assertThat(limiter.tryConsume(FIVE_PER_MINUTE, "ola").allowed()).isFalse();
        assertThat(limiter.tryConsume(FIVE_PER_MINUTE, "kari").allowed()).isTrue();
        assertThat(limiter.tryConsume(other, "ola").allowed()).isTrue();
    }

    @Test
    void concurrentBurstNeverLetsMoreThanCapacityThrough() throws Exception {
        RateLimitPolicy policy = new RateLimitPolicy("burst", 20, Duration.ofHours(1));
        int threads = 64;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger allowed = new AtomicInteger();
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int j = 0; j < 10; j++) {
                            if (limiter.tryConsume(policy, "shared").allowed()) {
                                allowed.incrementAndGet();
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(20, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }
        assertThat(allowed.get()).isEqualTo(20);
    }

    /** An attacker rotating usernames or spoofed addresses must not be able to exhaust the heap. */
    @Test
    void bucketStoreIsBoundedAgainstKeyFlooding() {
        for (int i = 0; i < RateLimiter.MAX_BUCKETS + 5_000; i++) {
            limiter.tryConsume(FIVE_PER_MINUTE, "attacker-" + i);
        }
        assertThat(limiter.trackedBuckets()).isLessThanOrEqualTo(RateLimiter.MAX_BUCKETS);
    }

    @Test
    void nullOrBlankKeysShareOneBucketInsteadOfBypassingTheLimit() {
        for (int i = 0; i < 5; i++) {
            limiter.tryConsume(FIVE_PER_MINUTE, i % 2 == 0 ? null : "  ");
        }
        assertThat(limiter.tryConsume(FIVE_PER_MINUTE, "").allowed()).isFalse();
    }
}
