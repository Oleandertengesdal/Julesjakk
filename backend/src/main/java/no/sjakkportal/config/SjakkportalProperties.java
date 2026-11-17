package no.sjakkportal.config;

import java.time.Duration;
import java.util.List;
import no.sjakkportal.ratelimit.RateLimitPolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** All application settings, bound from {@code sjakkportal.*}. See the environment table in the README. */
@ConfigurationProperties("sjakkportal")
public record SjakkportalProperties(Jwt jwt, Cookie cookie, Cors cors, BootstrapAdmin bootstrapAdmin, RateLimits rateLimit,
                                    ClientIp clientIp) {

    /**
     * Where the client's address comes from behind proxies. {@code header} blank: the connection address.
     * Otherwise the entry {@code trustedHops} from the right of that header. See docs/SECURITY.md.
     */
    public record ClientIp(String header, @DefaultValue("1") int trustedHops) {
    }

    /** @param secret Base64-encoded HMAC key of at least 256 bits */
    public record Jwt(String secret, @DefaultValue("15m") Duration accessTtl, @DefaultValue("30d") Duration refreshTtl) {
    }

    public record Cookie(@DefaultValue("Lax") String sameSite) {
    }

    public record Cors(@DefaultValue("http://localhost:5173") List<String> allowedOrigins) {
    }

    /** Creates the first admin on startup when the database has none. Leave blank to disable. */
    public record BootstrapAdmin(String username, String password) {
    }

    public record Limit(int capacity, @DefaultValue("1m") Duration period) {
    }

    public record RateLimits(Limit login, Limit loginAddress, Limit invite, Limit passwordReset, Limit gameSubmission) {

        public RateLimitPolicy loginPolicy() {
            return new RateLimitPolicy("login", login.capacity(), login.period());
        }

        public RateLimitPolicy loginAddressPolicy() {
            return new RateLimitPolicy("login-address", loginAddress.capacity(), loginAddress.period());
        }

        public RateLimitPolicy invitePolicy() {
            return new RateLimitPolicy("invite", invite.capacity(), invite.period());
        }

        public RateLimitPolicy passwordResetPolicy() {
            return new RateLimitPolicy("password-reset", passwordReset.capacity(), passwordReset.period());
        }

        public RateLimitPolicy gameSubmissionPolicy() {
            return new RateLimitPolicy("game-submission", gameSubmission.capacity(), gameSubmission.period());
        }
    }
}
