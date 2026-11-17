package no.sjakkportal.invite;

import java.time.Instant;
import java.util.List;

/** {@code maxUses}, {@code remainingUses} and {@code expiresAt} are {@code null} for "unlimited" / "never". */
public record InviteView(long id, String code, String note, Integer maxUses, int useCount, Integer remainingUses,
                         Instant expiresAt, Instant revokedAt, Instant createdAt, String status,
                         List<Redemption> redemptions) {

    public record Redemption(long userId, String username, String displayName, Instant redeemedAt) {
    }
}
