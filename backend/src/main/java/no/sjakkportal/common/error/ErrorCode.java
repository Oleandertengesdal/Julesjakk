package no.sjakkportal.common.error;

import org.springframework.http.HttpStatus;

/**
 * The complete catalog of application error codes.
 *
 * <p>Every error the API can return maps to exactly one entry here. Codes are stable:
 * never renumber or reuse one, only append. {@code docs/API_AND_ERRORS.md} documents each code.
 * {@code title} is a short English summary for developers; {@code defaultDetail} is the
 * Norwegian (bokmål) message shown to end users.
 */
public enum ErrorCode {

    // --- Authentication & authorization -------------------------------------------------
    AUTH_INVALID_CREDENTIALS("CHESS-AUTH-001", HttpStatus.UNAUTHORIZED, "Invalid credentials",
            "Feil brukernavn eller passord."),
    AUTH_TOKEN_INVALID("CHESS-AUTH-002", HttpStatus.UNAUTHORIZED, "Access token invalid or expired",
            "Innloggingen din er utløpt. Logg inn på nytt."),
    AUTH_ACCESS_DENIED("CHESS-AUTH-003", HttpStatus.FORBIDDEN, "Access denied",
            "Du har ikke tilgang til dette."),
    AUTH_PASSWORD_CHANGE_REQUIRED("CHESS-AUTH-004", HttpStatus.FORBIDDEN, "Password change required",
            "Du må velge et nytt passord før du kan fortsette."),
    AUTH_REFRESH_TOKEN_INVALID("CHESS-AUTH-005", HttpStatus.UNAUTHORIZED, "Refresh token invalid, expired or reused",
            "Økten din er ikke lenger gyldig. Logg inn på nytt."),
    AUTH_ACCOUNT_DISABLED("CHESS-AUTH-006", HttpStatus.FORBIDDEN, "Account disabled",
            "Kontoen din er deaktivert. Ta kontakt med en administrator."),
    AUTH_WEAK_PASSWORD("CHESS-AUTH-007", HttpStatus.UNPROCESSABLE_ENTITY, "Password does not meet the policy",
            "Passordet må ha minst 8 tegn og kan ikke være likt brukernavnet."),
    AUTH_REQUIRED("CHESS-AUTH-008", HttpStatus.UNAUTHORIZED, "Authentication required",
            "Du må logge inn for å fortsette."),
    AUTH_USERNAME_TAKEN("CHESS-AUTH-009", HttpStatus.CONFLICT, "Username already taken",
            "Brukernavnet er allerede i bruk."),

    // --- Invite codes -------------------------------------------------------------------
    INVITE_NOT_FOUND("CHESS-INVITE-001", HttpStatus.NOT_FOUND, "Invite code not found",
            "Invitasjonskoden finnes ikke."),
    INVITE_EXPIRED("CHESS-INVITE-002", HttpStatus.GONE, "Invite code expired",
            "Invitasjonskoden er utløpt."),
    INVITE_EXHAUSTED("CHESS-INVITE-003", HttpStatus.GONE, "Invite code has no uses left",
            "Invitasjonskoden er brukt opp."),
    INVITE_REVOKED("CHESS-INVITE-004", HttpStatus.GONE, "Invite code revoked",
            "Invitasjonskoden er trukket tilbake."),
    INVITE_CODE_TAKEN("CHESS-INVITE-005", HttpStatus.CONFLICT, "Invite code already exists",
            "Det finnes allerede en invitasjon med denne koden."),
    INVITE_INVALID_CONFIG("CHESS-INVITE-006", HttpStatus.UNPROCESSABLE_ENTITY, "Invalid invite configuration",
            "Ugyldig oppsett for invitasjonskoden."),

    // --- Password reset -----------------------------------------------------------------
    RESET_TOKEN_INVALID("CHESS-RESET-001", HttpStatus.BAD_REQUEST, "Password reset token invalid",
            "Lenken for tilbakestilling av passord er ugyldig."),
    RESET_TOKEN_EXPIRED("CHESS-RESET-002", HttpStatus.GONE, "Password reset token expired",
            "Lenken for tilbakestilling av passord er utløpt."),
    RESET_TOKEN_USED("CHESS-RESET-003", HttpStatus.GONE, "Password reset token already used",
            "Lenken for tilbakestilling av passord er allerede brukt."),

    // --- PGN / FEN ----------------------------------------------------------------------
    PGN_MALFORMED("CHESS-PGN-001", HttpStatus.UNPROCESSABLE_ENTITY, "PGN could not be parsed",
            "PGN-teksten kunne ikke leses."),
    PGN_ILLEGAL_MOVE("CHESS-PGN-002", HttpStatus.UNPROCESSABLE_ENTITY, "PGN contains an illegal move",
            "PGN-teksten inneholder et ulovlig trekk."),
    PGN_TOO_LARGE("CHESS-PGN-003", HttpStatus.PAYLOAD_TOO_LARGE, "PGN too large",
            "PGN-teksten er for lang."),
    PGN_RESULT_MISMATCH("CHESS-PGN-004", HttpStatus.UNPROCESSABLE_ENTITY, "Result contradicts the PGN",
            "Resultatet stemmer ikke med trekkene i PGN-teksten."),
    PGN_MULTIPLE_GAMES("CHESS-PGN-005", HttpStatus.UNPROCESSABLE_ENTITY, "PGN must contain exactly one game",
            "PGN-teksten må inneholde nøyaktig ett parti."),
    FEN_MALFORMED("CHESS-FEN-001", HttpStatus.UNPROCESSABLE_ENTITY, "FEN position invalid",
            "FEN-stillingen er ugyldig."),

    // --- Games --------------------------------------------------------------------------
    GAME_NOT_FOUND("CHESS-GAME-001", HttpStatus.NOT_FOUND, "Game not found",
            "Partiet finnes ikke."),
    GAME_SAME_PLAYER("CHESS-GAME-002", HttpStatus.UNPROCESSABLE_ENTITY, "A player cannot play themselves",
            "Hvit og svart må være to forskjellige spillere."),
    GAME_INVALID_STATE("CHESS-GAME-003", HttpStatus.CONFLICT, "Game is not in a state that allows this",
            "Dette kan ikke gjøres med partiet slik det står nå."),
    GAME_ILLEGAL_MOVE("CHESS-GAME-004", HttpStatus.UNPROCESSABLE_ENTITY, "Illegal move",
            "Trekket er ikke lovlig i denne stillingen."),
    GAME_CONCURRENT_UPDATE("CHESS-GAME-005", HttpStatus.CONFLICT, "Game was modified concurrently",
            "Partiet ble endret av noen andre samtidig. Last inn på nytt og prøv igjen."),
    GAME_DUPLICATE_IMPORT("CHESS-GAME-006", HttpStatus.CONFLICT, "Game already imported",
            "Dette partiet er allerede importert."),
    GAME_NOT_PARTICIPANT("CHESS-GAME-007", HttpStatus.FORBIDDEN, "Only participants or admins may modify a game",
            "Bare spillerne i partiet eller en administrator kan gjøre dette."),

    // --- Analysis -----------------------------------------------------------------------
    ANALYSIS_INVALID("CHESS-ANALYSIS-001", HttpStatus.UNPROCESSABLE_ENTITY, "Analysis payload invalid",
            "Analysen kunne ikke lagres fordi dataene er ugyldige."),
    ANALYSIS_GAME_NOT_FINISHED("CHESS-ANALYSIS-002", HttpStatus.CONFLICT, "Game has no moves to analyse or is not finished",
            "Partiet må være ferdig og ha trekk før analysen kan lagres."),

    // --- Tournaments --------------------------------------------------------------------
    TOURNAMENT_NOT_FOUND("CHESS-TOURN-001", HttpStatus.NOT_FOUND, "Tournament not found",
            "Turneringen finnes ikke."),
    TOURNAMENT_INVALID_STATE("CHESS-TOURN-002", HttpStatus.CONFLICT, "Tournament is not in a state that allows this",
            "Dette kan ikke gjøres med turneringen slik den står nå."),
    TOURNAMENT_TOO_FEW_PLAYERS("CHESS-TOURN-003", HttpStatus.UNPROCESSABLE_ENTITY, "Not enough players",
            "Turneringen har for få spillere."),
    TOURNAMENT_ROUND_INCOMPLETE("CHESS-TOURN-004", HttpStatus.CONFLICT, "Current round still has unfinished games",
            "Runden er ikke ferdig. Alle partier må ha et resultat først."),
    TOURNAMENT_ALREADY_REGISTERED("CHESS-TOURN-005", HttpStatus.CONFLICT, "Player already registered",
            "Spilleren er allerede påmeldt."),
    TOURNAMENT_PAIRING_CONFLICT("CHESS-TOURN-006", HttpStatus.CONFLICT, "Pairing not found or result does not fit it",
            "Resultatet passer ikke med oppsettet i turneringen."),
    TOURNAMENT_ARENA_CLOSED("CHESS-TOURN-007", HttpStatus.CONFLICT, "Arena window is not open",
            "Arenaen er ikke åpen akkurat nå."),

    // --- Users, imports, rate limiting --------------------------------------------------
    USER_NOT_FOUND("CHESS-USER-001", HttpStatus.NOT_FOUND, "User not found",
            "Brukeren finnes ikke."),
    IMPORT_UNSUPPORTED_SOURCE("CHESS-IMPORT-001", HttpStatus.UNPROCESSABLE_ENTITY, "Unsupported import link",
            "Lenken gjenkjennes ikke som et parti fra Chess.com eller Lichess."),
    IMPORT_UPSTREAM_FAILED("CHESS-IMPORT-002", HttpStatus.BAD_GATEWAY, "Could not fetch the game from the external site",
            "Partiet kunne ikke hentes fra den eksterne tjenesten. Prøv igjen senere, eller lim inn PGN."),
    RATE_LIMITED("CHESS-RATE-001", HttpStatus.TOO_MANY_REQUESTS, "Too many requests",
            "For mange forsøk. Vent litt og prøv igjen."),

    // --- Generic ------------------------------------------------------------------------
    VALIDATION_FAILED("CHESS-GEN-001", HttpStatus.BAD_REQUEST, "Request validation failed",
            "Noen av feltene er ugyldige."),
    NOT_FOUND("CHESS-GEN-002", HttpStatus.NOT_FOUND, "Resource not found",
            "Fant ikke det du lette etter."),
    METHOD_NOT_ALLOWED("CHESS-GEN-003", HttpStatus.METHOD_NOT_ALLOWED, "Method not allowed",
            "Denne handlingen støttes ikke her."),
    MALFORMED_REQUEST("CHESS-GEN-004", HttpStatus.BAD_REQUEST, "Malformed request",
            "Forespørselen kunne ikke leses."),
    CONFLICT("CHESS-GEN-005", HttpStatus.CONFLICT, "Conflicting concurrent update",
            "Noen andre endret dette samtidig. Last inn på nytt og prøv igjen."),
    INTERNAL_ERROR("CHESS-GEN-999", HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected server error",
            "Noe gikk galt hos oss. Prøv igjen, og gi feilkoden til en administrator hvis det fortsetter.");

    private final String code;
    private final HttpStatus status;
    private final String title;
    private final String defaultDetail;

    ErrorCode(String code, HttpStatus status, String title, String defaultDetail) {
        this.code = code;
        this.status = status;
        this.title = title;
        this.defaultDetail = defaultDetail;
    }

    public String code() {
        return code;
    }

    public HttpStatus status() {
        return status;
    }

    public String title() {
        return title;
    }

    public String defaultDetail() {
        return defaultDetail;
    }
}
