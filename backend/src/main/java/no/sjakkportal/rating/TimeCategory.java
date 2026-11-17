package no.sjakkportal.rating;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import no.sjakkportal.common.error.ChessException;
import no.sjakkportal.common.error.ErrorCode;

/**
 * Rating categories. Each player has an independent Elo rating in every category.
 *
 * <p>A time control is written {@code minutes+incrementSeconds} (e.g. {@code 5+3}, {@code 15+10}, or just {@code 10}).
 * Its category follows the estimated length of a 60-move game per player, {@code minutes + increment}:
 * under 10 minutes is blitz, 10 up to (not including) 60 is rapid, 60 or more is classical.
 * Games played without a clock are classical.
 */
public enum TimeCategory {
    BLITZ, RAPID, CLASSICAL;

    public static final int MAX_MINUTES = 600;
    public static final int MAX_INCREMENT_SECONDS = 180;

    // [0-9], not \d: only ASCII digits are accepted.
    private static final Pattern FORMAT = Pattern.compile("([0-9]{1,3})(?:\\+([0-9]{1,3}))?");

    /**
     * @param timeControl e.g. {@code "3+2"}; {@code null} or blank means no clock
     * @throws ChessException CHESS-GAME-009 when the text is not a valid time control
     */
    public static TimeCategory fromTimeControl(String timeControl) {
        String normalized = normalize(timeControl);
        if (normalized == null) {
            return CLASSICAL;
        }
        Matcher matcher = FORMAT.matcher(normalized);
        if (!matcher.matches()) {
            throw new ChessException(ErrorCode.GAME_INVALID_TIME_CONTROL);
        }
        int minutes = Integer.parseInt(matcher.group(1));
        int increment = matcher.group(2) == null ? 0 : Integer.parseInt(matcher.group(2));
        if (minutes < 1 || minutes > MAX_MINUTES || increment > MAX_INCREMENT_SECONDS) {
            throw new ChessException(ErrorCode.GAME_INVALID_TIME_CONTROL);
        }
        int estimatedMinutes = minutes + increment;
        if (estimatedMinutes < 10) {
            return BLITZ;
        }
        return estimatedMinutes < 60 ? RAPID : CLASSICAL;
    }

    /**
     * Resolves the category for a game from what the client sent.
     *
     * @throws ChessException CHESS-GAME-009 when both are given and disagree
     */
    public static TimeCategory resolve(TimeCategory requested, String timeControl) {
        if (normalize(timeControl) == null) {
            return requested == null ? CLASSICAL : requested;
        }
        TimeCategory derived = fromTimeControl(timeControl);
        if (requested != null && requested != derived) {
            throw new ChessException(ErrorCode.GAME_INVALID_TIME_CONTROL);
        }
        return derived;
    }

    /** Canonical form ({@code "5+3"}) or {@code null} when there is no clock. */
    public static String normalize(String timeControl) {
        if (timeControl == null) {
            return null;
        }
        String compact = timeControl.replaceAll("[ \\t]", "");
        return compact.isEmpty() ? null : compact;
    }
}
