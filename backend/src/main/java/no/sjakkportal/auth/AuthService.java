package no.sjakkportal.auth;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import no.sjakkportal.common.AuditLog;
import no.sjakkportal.common.error.ChessException;
import no.sjakkportal.common.error.ErrorCode;
import no.sjakkportal.common.text.Sanitizer;
import no.sjakkportal.invite.InviteService;
import no.sjakkportal.security.JwtService;
import no.sjakkportal.security.Role;
import no.sjakkportal.user.AppUser;
import no.sjakkportal.user.UserRepository;
import no.sjakkportal.user.UserService;
import no.sjakkportal.user.UserView;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private static final Duration RESET_TOKEN_TTL = Duration.ofHours(24);

    /** @param refreshToken raw value for the cookie; never placed in a response body */
    public record Session(String accessToken, long expiresIn, UserView user, String refreshToken) {
    }

    public enum ResetMode {
        TEMPORARY_PASSWORD, RESET_LINK
    }

    public record ResetIssued(String temporaryPassword, String resetToken, Instant expiresAt) {
    }

    private final UserRepository users;
    private final UserService userService;
    private final InviteService inviteService;
    private final RefreshTokens refreshTokens;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;
    private final JdbcClient jdbc;
    private final AuditLog auditLog;
    /** Compared against when the user does not exist, so both failure paths cost one BCrypt check. */
    private final String dummyHash;

    public AuthService(UserRepository users, UserService userService, InviteService inviteService, RefreshTokens refreshTokens,
                       JwtService jwtService, PasswordEncoder passwordEncoder, JdbcClient jdbc, AuditLog auditLog) {
        this.users = users;
        this.userService = userService;
        this.inviteService = inviteService;
        this.refreshTokens = refreshTokens;
        this.jwtService = jwtService;
        this.passwordEncoder = passwordEncoder;
        this.jdbc = jdbc;
        this.auditLog = auditLog;
        this.dummyHash = passwordEncoder.encode(Tokens.random().substring(0, 20));
    }

    public static String normalizeUsername(String username) {
        return username == null ? "" : username.strip().toLowerCase(Locale.ROOT);
    }

    /**
     * Invite-only registration. Claiming the invite and creating the account are one transaction:
     * any failure (name taken, weak password, lost race) rolls the invite use back.
     */
    @Transactional
    public UserView register(String inviteCode, String rawUsername, String rawDisplayName, String password) {
        String username = normalizeUsername(rawUsername);
        String displayName = Sanitizer.plainText(rawDisplayName, 64);
        if (displayName == null || displayName.isBlank()) {
            throw new ChessException(ErrorCode.VALIDATION_FAILED);
        }
        PasswordPolicy.check(username, password);
        if (users.existsByUsername(username)) {
            throw new ChessException(ErrorCode.AUTH_USERNAME_TAKEN);
        }
        long inviteId = inviteService.claim(inviteCode);
        // Two requests can both pass the existence check; ON CONFLICT lets the loser fail cleanly
        // (a plain unique-violation would abort the PostgreSQL transaction mid-flight).
        Optional<Long> userId = jdbc.sql("""
                        INSERT INTO app_user (username, display_name, password_hash, role)
                        VALUES (?, ?, ?, 'PLAYER') ON CONFLICT (username) DO NOTHING RETURNING id""")
                .params(username, displayName, passwordEncoder.encode(password)).query(Long.class).optional();
        if (userId.isEmpty()) {
            throw new ChessException(ErrorCode.AUTH_USERNAME_TAKEN);
        }
        jdbc.sql("INSERT INTO invite_redemption (invite_code_id, user_id) VALUES (?, ?)").params(inviteId, userId.get()).update();
        return userService.view(userId.get());
    }

    @Transactional
    public Session login(String rawUsername, String password) {
        Optional<AppUser> found = users.findByUsername(normalizeUsername(rawUsername));
        String hash = found.map(AppUser::getPasswordHash).orElse(null);
        boolean matches = password != null && !PasswordPolicy.tooLong(password)
                && passwordEncoder.matches(password, hash == null ? dummyHash : hash) && hash != null;
        if (!matches) {
            throw new ChessException(ErrorCode.AUTH_INVALID_CREDENTIALS);
        }
        AppUser user = found.get();
        // Only someone who knows the password learns that the account is disabled.
        if (!user.isEnabled()) {
            throw new ChessException(ErrorCode.AUTH_ACCOUNT_DISABLED);
        }
        return session(user, refreshTokens.issue(user.getId()));
    }

    /** Not transactional on purpose: a rejected rotation must commit its family revocation. */
    public Session refresh(String refreshToken) {
        RefreshTokens.Rotation rotation = refreshTokens.rotate(refreshToken);
        AppUser user = rotation.userId().flatMap(users::findById).filter(AppUser::isEnabled)
                .orElseThrow(() -> new ChessException(ErrorCode.AUTH_REFRESH_TOKEN_INVALID));
        return session(user, rotation.newToken());
    }

    public void logout(String refreshToken) {
        refreshTokens.revoke(refreshToken);
    }

    @Transactional
    public void changePassword(long userId, String currentPassword, String newPassword) {
        AppUser user = users.findById(userId).orElseThrow(() -> new ChessException(ErrorCode.AUTH_TOKEN_INVALID));
        if (currentPassword == null || PasswordPolicy.tooLong(currentPassword)
                || !passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new ChessException(ErrorCode.AUTH_INVALID_CREDENTIALS);
        }
        PasswordPolicy.check(user.getUsername(), newPassword);
        if (newPassword.equals(currentPassword)) {
            throw new ChessException(ErrorCode.AUTH_WEAK_PASSWORD, "Det nye passordet må være forskjellig fra det gamle.");
        }
        user.changePassword(passwordEncoder.encode(newPassword), false);
        refreshTokens.revokeAllFor(userId);
    }

    /**
     * Admin action: shut an account out (or let it back in). Disabling ends every session at once:
     * access tokens through the token version, refresh tokens by revocation.
     */
    @Transactional
    public UserView setEnabled(long adminId, long userId, boolean enabled) {
        AppUser user = users.findById(userId).orElseThrow(() -> new ChessException(ErrorCode.USER_NOT_FOUND));
        if (!enabled && userId == adminId) {
            throw new ChessException(ErrorCode.USER_CANNOT_DISABLE);
        }
        if (user.isEnabled() != enabled) {
            user.setEnabled(enabled);
            users.saveAndFlush(user);
            if (!enabled) {
                refreshTokens.revokeAllFor(userId);
            }
            auditLog.record(adminId, enabled ? "USER_ENABLED" : "USER_DISABLED", "user:" + userId, null);
        }
        return userService.toView(user);
    }

    /** Admin action: either a temporary password (must be changed at next login) or a one-time reset token. */
    @Transactional
    public ResetIssued issueReset(long adminId, long userId, ResetMode mode) {
        AppUser user = users.findById(userId).orElseThrow(() -> new ChessException(ErrorCode.USER_NOT_FOUND));
        // Any earlier, unused reset token for this user stops working now.
        jdbc.sql("UPDATE password_reset_token SET used_at = now() WHERE user_id = ? AND used_at IS NULL").param(userId).update();
        auditLog.record(adminId, "PASSWORD_RESET_" + mode.name(), "user:" + userId, null);
        if (mode == ResetMode.TEMPORARY_PASSWORD) {
            String temporary = Tokens.temporaryPassword();
            user.changePassword(passwordEncoder.encode(temporary), true);
            refreshTokens.revokeAllFor(userId);
            return new ResetIssued(temporary, null, null);
        }
        String token = Tokens.random();
        Instant expiresAt = Instant.now().plus(RESET_TOKEN_TTL);
        jdbc.sql("INSERT INTO password_reset_token (user_id, token_hash, created_by, expires_at) VALUES (?, ?, ?, ?)")
                .params(userId, Tokens.sha256(token), adminId, Timestamp.from(expiresAt)).update();
        return new ResetIssued(null, token, expiresAt);
    }

    private record ResetRow(long id, long userId, Timestamp expiresAt, Timestamp usedAt) {
    }

    @Transactional
    public void resetPassword(String token, String newPassword) {
        if (token == null || token.isBlank() || token.length() > 200) {
            throw new ChessException(ErrorCode.RESET_TOKEN_INVALID);
        }
        // Row lock: of several simultaneous uses of one token, exactly one finds it unused.
        ResetRow row = jdbc.sql("SELECT id, user_id, expires_at, used_at FROM password_reset_token WHERE token_hash = ? FOR UPDATE")
                .param(Tokens.sha256(token)).query(ResetRow.class).optional()
                .orElseThrow(() -> new ChessException(ErrorCode.RESET_TOKEN_INVALID));
        if (row.usedAt() != null) {
            throw new ChessException(ErrorCode.RESET_TOKEN_USED);
        }
        if (!row.expiresAt().toInstant().isAfter(Instant.now())) {
            throw new ChessException(ErrorCode.RESET_TOKEN_EXPIRED);
        }
        AppUser user = users.findById(row.userId()).orElseThrow(() -> new ChessException(ErrorCode.RESET_TOKEN_INVALID));
        // Checked before the token is marked used: a rejected password must not burn the token.
        PasswordPolicy.check(user.getUsername(), newPassword);
        user.changePassword(passwordEncoder.encode(newPassword), false);
        jdbc.sql("UPDATE password_reset_token SET used_at = now() WHERE id = ?").param(row.id()).update();
        refreshTokens.revokeAllFor(user.getId());
    }

    private Session session(AppUser user, String refreshToken) {
        return new Session(jwtService.issue(user.getId(), user.getTokenVersion()), jwtService.accessTtlSeconds(),
                userService.toView(user), refreshToken);
    }
}
