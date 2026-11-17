package no.sjakkportal.common.error;

/** Raised when a token bucket is empty; carries the value for the {@code Retry-After} header. */
public class RateLimitedException extends ChessException {

    private final long retryAfterSeconds;

    public RateLimitedException(long retryAfterSeconds) {
        super(ErrorCode.RATE_LIMITED);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
