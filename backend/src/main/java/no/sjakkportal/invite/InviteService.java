package no.sjakkportal.invite;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import no.sjakkportal.auth.Tokens;
import no.sjakkportal.common.AuditLog;
import no.sjakkportal.common.error.ChessException;
import no.sjakkportal.common.error.ErrorCode;
import no.sjakkportal.common.text.Sanitizer;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InviteService {

    private static final Pattern CODE_FORMAT = Pattern.compile("[A-Z0-9-]{6,64}");
    /** No 0/O or 1/I: codes get read out across a living room. */
    private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int MAX_EXPIRY_HOURS = 24 * 365;

    public record CreateCommand(String code, Integer maxUses, Integer expiresInHours, Instant expiresAt, String note) {
    }

    private final InviteCodeRepository invites;
    private final JdbcClient jdbc;
    private final AuditLog auditLog;

    public InviteService(InviteCodeRepository invites, JdbcClient jdbc, AuditLog auditLog) {
        this.invites = invites;
        this.jdbc = jdbc;
        this.auditLog = auditLog;
    }

    public static String normalize(String code) {
        return code == null ? "" : code.strip().toUpperCase(Locale.ROOT);
    }

    @Transactional
    public InviteView create(long adminId, CreateCommand command) {
        Instant now = Instant.now();
        if ((command.maxUses() != null && command.maxUses() < 1)
                || (command.expiresInHours() != null && (command.expiresInHours() < 1 || command.expiresInHours() > MAX_EXPIRY_HOURS))
                || (command.expiresInHours() != null && command.expiresAt() != null)
                || (command.expiresAt() != null && !command.expiresAt().isAfter(now))) {
            throw new ChessException(ErrorCode.INVITE_INVALID_CONFIG);
        }
        String code;
        if (command.code() == null || command.code().isBlank()) {
            code = generateCode();
        } else {
            code = normalize(command.code());
            if (!CODE_FORMAT.matcher(code).matches()) {
                throw new ChessException(ErrorCode.INVITE_INVALID_CONFIG);
            }
            if (invites.existsByCode(code)) {
                throw new ChessException(ErrorCode.INVITE_CODE_TAKEN);
            }
        }
        Instant expiresAt = command.expiresInHours() != null ? now.plus(Duration.ofHours(command.expiresInHours())) : command.expiresAt();
        InviteCode invite;
        try {
            invite = invites.saveAndFlush(new InviteCode(code, Sanitizer.plainText(command.note(), 200), adminId, command.maxUses(), expiresAt));
        } catch (DataIntegrityViolationException e) {
            throw new ChessException(ErrorCode.INVITE_CODE_TAKEN);   // lost a race for the same custom code
        }
        auditLog.record(adminId, "INVITE_CREATED", "invite:" + invite.getId(), null);
        return view(invite, List.of(), now);
    }

    private String generateCode() {
        for (int attempt = 0; attempt < 5; attempt++) {
            String code = Tokens.randomFrom(CODE_ALPHABET, 4) + "-" + Tokens.randomFrom(CODE_ALPHABET, 4) + "-"
                    + Tokens.randomFrom(CODE_ALPHABET, 4) + "-" + Tokens.randomFrom(CODE_ALPHABET, 4);
            if (!invites.existsByCode(code)) {
                return code;
            }
        }
        throw new ChessException(ErrorCode.INVITE_CODE_TAKEN);
    }

    @Transactional(readOnly = true)
    public List<InviteView> list() {
        Map<Long, List<InviteView.Redemption>> redemptions = new HashMap<>();
        jdbc.sql("""
                SELECT r.invite_code_id, u.id, u.username, u.display_name, r.redeemed_at
                FROM invite_redemption r JOIN app_user u ON u.id = r.user_id ORDER BY r.redeemed_at""")
                .query((rs, row) -> redemptions.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>()).add(
                        new InviteView.Redemption(rs.getLong(2), rs.getString(3), rs.getString(4), rs.getTimestamp(5).toInstant())))
                .list();
        Instant now = Instant.now();
        return invites.findAll(Sort.by(Sort.Direction.DESC, "createdAt")).stream()
                .map(invite -> view(invite, redemptions.getOrDefault(invite.getId(), List.of()), now)).toList();
    }

    @Transactional
    public void revoke(long adminId, long inviteId) {
        // One atomic statement rather than load-modify-save: a revocation must not lose to a redemption
        // that bumps the row version at the same moment.
        int updated = jdbc.sql("UPDATE invite_code SET revoked_at = COALESCE(revoked_at, now()), version = version + 1 WHERE id = ?")
                .param(inviteId).update();
        if (updated == 0) {
            throw new ChessException(ErrorCode.INVITE_NOT_FOUND);
        }
        auditLog.record(adminId, "INVITE_REVOKED", "invite:" + inviteId, null);
    }

    /**
     * Takes one use of the code, or explains why it cannot be used. Must run inside the registration
     * transaction: if creating the account fails afterwards, the use is rolled back with it.
     *
     * <p>The check and the increment are one UPDATE statement, so concurrent redemptions queue on the
     * row lock and each sees the count left by the previous one. A read-then-write would let several
     * requests pass the check before any of them writes.
     *
     * @return the invite id
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public long claim(String rawCode) {
        String code = normalize(rawCode);
        Optional<Long> claimed = jdbc.sql("""
                        UPDATE invite_code SET use_count = use_count + 1, version = version + 1
                        WHERE code = ? AND revoked_at IS NULL
                          AND (expires_at IS NULL OR expires_at > now())
                          AND (max_uses IS NULL OR use_count < max_uses)
                        RETURNING id""")
                .param(code).query(Long.class).optional();
        if (claimed.isPresent()) {
            return claimed.get();
        }
        InviteCode.Status status = jdbc.sql("SELECT revoked_at, expires_at, max_uses, use_count FROM invite_code WHERE code = ?")
                .param(code)
                .query((rs, row) -> {
                    if (rs.getTimestamp("revoked_at") != null) {
                        return InviteCode.Status.REVOKED;
                    }
                    if (rs.getTimestamp("expires_at") != null && !rs.getTimestamp("expires_at").toInstant().isAfter(Instant.now())) {
                        return InviteCode.Status.EXPIRED;
                    }
                    return InviteCode.Status.EXHAUSTED;
                }).optional().orElseThrow(() -> new ChessException(ErrorCode.INVITE_NOT_FOUND));
        throw new ChessException(switch (status) {
            case REVOKED -> ErrorCode.INVITE_REVOKED;
            case EXPIRED -> ErrorCode.INVITE_EXPIRED;
            default -> ErrorCode.INVITE_EXHAUSTED;
        });
    }

    private static InviteView view(InviteCode invite, List<InviteView.Redemption> redemptions, Instant now) {
        Integer remaining = invite.getMaxUses() == null ? null : Math.max(0, invite.getMaxUses() - invite.getUseCount());
        return new InviteView(invite.getId(), invite.getCode(), invite.getNote(), invite.getMaxUses(), invite.getUseCount(),
                remaining, invite.getExpiresAt(), invite.getRevokedAt(), invite.getCreatedAt(), invite.status(now).name(), redemptions);
    }
}
