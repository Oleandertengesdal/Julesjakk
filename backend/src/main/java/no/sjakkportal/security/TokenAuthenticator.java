package no.sjakkportal.security;

import no.sjakkportal.common.error.ChessException;
import no.sjakkportal.common.error.ErrorCode;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Turns an access token into the current user, checking signature, expiry, token version and account
 * state against the database. Shared by the HTTP filter and the WebSocket handshake.
 */
@Component
public class TokenAuthenticator {

    private record Account(long id, String username, String role, boolean enabled, boolean mustChangePassword, int tokenVersion) {
    }

    private final JwtService jwtService;
    private final JdbcClient jdbc;

    public TokenAuthenticator(JwtService jwtService, JdbcClient jdbc) {
        this.jwtService = jwtService;
        this.jdbc = jdbc;
    }

    /** @throws ChessException CHESS-AUTH-002 for a bad token, CHESS-AUTH-006 for a disabled account */
    public CurrentUser authenticate(String token) {
        JwtService.AccessClaims claims = jwtService.verify(token).orElseThrow(() -> new ChessException(ErrorCode.AUTH_TOKEN_INVALID));
        Account account = jdbc.sql("""
                        SELECT id, username, role, enabled, must_change_password, token_version
                        FROM app_user WHERE id = ? AND NOT managed""")
                .param(claims.userId()).query(Account.class).optional()
                .filter(a -> a.tokenVersion() == claims.tokenVersion())
                .orElseThrow(() -> new ChessException(ErrorCode.AUTH_TOKEN_INVALID));
        if (!account.enabled()) {
            throw new ChessException(ErrorCode.AUTH_ACCOUNT_DISABLED);
        }
        return new CurrentUser(account.id(), account.username(), Role.valueOf(account.role()), account.mustChangePassword());
    }
}
