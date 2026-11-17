package no.sjakkportal.auth;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import no.sjakkportal.config.SjakkportalProperties;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Refresh tokens with rotation and reuse detection. Every refresh replaces the token; all tokens
 * descending from one login share a family. If a token that was already rotated shows up again,
 * someone holds a copy, so the whole family is revoked and the user has to log in again.
 */
@Component
public class RefreshTokens {

    /** @param userId empty when the token was rejected */
    public record Rotation(Optional<Long> userId, String newToken) {

        static Rotation rejected() {
            return new Rotation(Optional.empty(), null);
        }
    }

    private record Row(long id, long userId, UUID familyId, Timestamp expiresAt, Timestamp revokedAt) {
    }

    private final JdbcClient jdbc;
    private final SjakkportalProperties properties;

    public RefreshTokens(JdbcClient jdbc, SjakkportalProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    public long ttlSeconds() {
        return properties.jwt().refreshTtl().toSeconds();
    }

    @Transactional
    public String issue(long userId) {
        return insert(userId, UUID.randomUUID());
    }

    /**
     * Exchanges a token for its successor. Rejections commit their side effects (the family
     * revocation), which is why this reports the outcome instead of throwing.
     */
    @Transactional
    public Rotation rotate(String rawToken) {
        if (rawToken == null || rawToken.isBlank() || rawToken.length() > 200) {
            return Rotation.rejected();
        }
        // The row lock serialises concurrent refreshes of the same token: exactly one sees it un-revoked.
        Optional<Row> found = jdbc.sql("""
                        SELECT id, user_id, family_id, expires_at, revoked_at
                        FROM refresh_token WHERE token_hash = ? FOR UPDATE""")
                .param(Tokens.sha256(rawToken)).query(Row.class).optional();
        if (found.isEmpty()) {
            return Rotation.rejected();
        }
        Row row = found.get();
        if (row.revokedAt() != null) {
            jdbc.sql("UPDATE refresh_token SET revoked_at = now() WHERE family_id = ? AND revoked_at IS NULL")
                    .param(row.familyId()).update();
            return Rotation.rejected();
        }
        if (!row.expiresAt().toInstant().isAfter(Instant.now())) {
            return Rotation.rejected();
        }
        jdbc.sql("UPDATE refresh_token SET revoked_at = now() WHERE id = ?").param(row.id()).update();
        return new Rotation(Optional.of(row.userId()), insert(row.userId(), row.familyId()));
    }

    @Transactional
    public void revoke(String rawToken) {
        if (rawToken != null && !rawToken.isBlank() && rawToken.length() <= 200) {
            jdbc.sql("UPDATE refresh_token SET revoked_at = now() WHERE token_hash = ? AND revoked_at IS NULL")
                    .param(Tokens.sha256(rawToken)).update();
        }
    }

    @Transactional
    public void revokeAllFor(long userId) {
        jdbc.sql("UPDATE refresh_token SET revoked_at = now() WHERE user_id = ? AND revoked_at IS NULL").param(userId).update();
    }

    private String insert(long userId, UUID family) {
        String raw = Tokens.random();
        jdbc.sql("INSERT INTO refresh_token (user_id, token_hash, family_id, expires_at) VALUES (?, ?, ?, ?)")
                .params(userId, Tokens.sha256(raw), family, Timestamp.from(Instant.now().plus(properties.jwt().refreshTtl())))
                .update();
        return raw;
    }
}
