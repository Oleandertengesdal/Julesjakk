package no.sjakkportal.security;

import jakarta.servlet.http.HttpServletRequest;
import no.sjakkportal.common.error.ChessException;
import no.sjakkportal.common.error.ErrorCode;
import no.sjakkportal.common.error.RateLimitedException;
import no.sjakkportal.config.SjakkportalProperties;
import no.sjakkportal.ratelimit.RateLimitDecision;
import no.sjakkportal.ratelimit.RateLimitPolicy;
import no.sjakkportal.ratelimit.RateLimiter;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

/** Request-level checks shared by controllers: rate limits and the Origin check for cookie endpoints. */
@Component
public class RequestGuards {

    private final RateLimiter rateLimiter;
    private final SjakkportalProperties properties;
    private final ClientAddressResolver addresses;

    public RequestGuards(RateLimiter rateLimiter, SjakkportalProperties properties) {
        this.rateLimiter = rateLimiter;
        this.properties = properties;
        SjakkportalProperties.ClientIp clientIp = properties.clientIp();
        this.addresses = clientIp == null ? new ClientAddressResolver(null, 1)
                : new ClientAddressResolver(clientIp.header(), clientIp.trustedHops());
    }

    public SjakkportalProperties.RateLimits limits() {
        return properties.rateLimit();
    }

    /** @throws RateLimitedException when the bucket for this key is empty */
    public void limit(RateLimitPolicy policy, String key) {
        RateLimitDecision decision = rateLimiter.tryConsume(policy, key);
        if (!decision.allowed()) {
            throw new RateLimitedException(decision.retryAfterSeconds());
        }
    }

    /**
     * The client's address for rate limiting. Forwarding headers are only read when configured
     * (SJAKKPORTAL_CLIENT_IP_HEADER), and then only the entry our own proxies appended.
     */
    public String clientAddress(HttpServletRequest request) {
        return addresses.resolve(request);
    }

    /**
     * Endpoints that act on the refresh cookie must only be callable from our own frontend.
     * Browsers always send Origin on cross-site POSTs, so a foreign Origin is refused.
     */
    public void requireTrustedOrigin(HttpServletRequest request) {
        String origin = request.getHeader(HttpHeaders.ORIGIN);
        if (origin != null && !properties.cors().allowedOrigins().contains(origin)) {
            throw new ChessException(ErrorCode.AUTH_ACCESS_DENIED);
        }
    }
}
