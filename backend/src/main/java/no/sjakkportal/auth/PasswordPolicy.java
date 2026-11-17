package no.sjakkportal.auth;

import java.nio.charset.StandardCharsets;
import no.sjakkportal.common.error.ChessException;
import no.sjakkportal.common.error.ErrorCode;

/**
 * Password rules: at least 8 characters, not blank, not the username, and at most 72 bytes.
 * The upper bound is BCrypt's: it silently ignores everything past byte 72, so two different
 * long passwords would otherwise open the same account.
 */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 8;
    public static final int MAX_BYTES = 72;

    private PasswordPolicy() {
    }

    /** @throws ChessException CHESS-AUTH-007 */
    public static void check(String username, String password) {
        if (password == null || password.isBlank() || password.length() < MIN_LENGTH
                || tooLong(password) || password.equalsIgnoreCase(username)) {
            throw new ChessException(ErrorCode.AUTH_WEAK_PASSWORD);
        }
    }

    public static boolean tooLong(String password) {
        return password.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES;
    }
}
