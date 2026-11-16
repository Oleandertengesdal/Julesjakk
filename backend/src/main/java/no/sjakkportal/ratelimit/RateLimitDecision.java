package no.sjakkportal.ratelimit;

/** @param retryAfterSeconds 0 when allowed; otherwise whole seconds (rounded up, at least 1) until a token is available */
public record RateLimitDecision(boolean allowed, long remainingTokens, long retryAfterSeconds) {
}
