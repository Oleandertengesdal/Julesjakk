package no.sjakkportal.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Optional;
import javax.crypto.SecretKey;
import no.sjakkportal.config.SjakkportalProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Short-lived HS256 access tokens. The token carries only the user id ({@code sub}) and the user's
 * token version ({@code ver}); role and account state are read from the database on each request,
 * so a demotion, a disabled account or a password change takes effect immediately.
 */
@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);

    private final SecretKey key;
    private final Duration accessTtl;

    public record AccessClaims(long userId, int tokenVersion) {
    }

    public JwtService(SjakkportalProperties properties) {
        String secret = properties.jwt().secret();
        if (secret == null || secret.isBlank()) {
            // Usable for local development only: every restart invalidates all access tokens.
            byte[] random = new byte[48];
            new SecureRandom().nextBytes(random);
            secret = Base64.getEncoder().encodeToString(random);
            log.warn("SJAKKPORTAL_JWT_SECRET is not set; using a random signing key for this run");
        }
        this.key = Keys.hmacShaKeyFor(decodeKey(secret.strip()));
        this.accessTtl = properties.jwt().accessTtl();
    }

    /** Accepts standard or URL-safe Base64 (hosting dashboards generate either) of at least 256 bits. */
    private static byte[] decodeKey(String secret) {
        byte[] bytes;
        try {
            bytes = secret.contains("-") || secret.contains("_") ? Decoders.BASE64URL.decode(secret) : Decoders.BASE64.decode(secret);
        } catch (RuntimeException e) {
            throw new IllegalStateException("SJAKKPORTAL_JWT_SECRET must be Base64; generate one with: openssl rand -base64 48");
        }
        if (bytes.length < 32) {
            throw new IllegalStateException("SJAKKPORTAL_JWT_SECRET must decode to at least 256 bits; generate one with: openssl rand -base64 48");
        }
        return bytes;
    }

    public String issue(long userId, int tokenVersion) {
        Instant now = Instant.now();
        return Jwts.builder().subject(String.valueOf(userId)).claim("ver", tokenVersion)
                .issuedAt(Date.from(now)).expiration(Date.from(now.plus(accessTtl)))
                .signWith(key, Jwts.SIG.HS256).compact();
    }

    public long accessTtlSeconds() {
        return accessTtl.toSeconds();
    }

    /** @return the claims if the token is signed with our key, unexpired and well-formed */
    public Optional<AccessClaims> verify(String token) {
        if (token == null || token.isBlank() || token.length() > 2048) {
            return Optional.empty();
        }
        try {
            // parseSignedClaims rejects unsigned ("alg":"none") tokens outright.
            Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
            Number version = claims.get("ver", Number.class);
            if (claims.getSubject() == null || version == null || claims.getExpiration() == null) {
                return Optional.empty();
            }
            return Optional.of(new AccessClaims(Long.parseLong(claims.getSubject()), version.intValue()));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
