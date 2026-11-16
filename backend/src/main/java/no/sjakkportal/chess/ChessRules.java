package no.sjakkportal.chess;

import java.util.List;
import no.sjakkportal.common.NotImplementedYet;

/**
 * The single entry point for chess-rule questions on the server. Everything that arrives from
 * a client (PGN, FEN, a live move) goes through here before it is stored or broadcast.
 * All failures are {@link no.sjakkportal.common.error.ChessException}s with a CHESS-PGN/FEN/GAME code;
 * no input, however malformed, may cause any other exception.
 */
public final class ChessRules {

    public static final String START_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";
    /** Maximum accepted PGN size in characters. */
    public static final int MAX_PGN_LENGTH = 100_000;

    private ChessRules() {
    }

    /** Parses and fully validates a single-game PGN. */
    public static ParsedGame parsePgn(String pgn) {
        throw new NotImplementedYet();
    }

    /** Validates a FEN string and returns it normalised (single spaces, trimmed). */
    public static String validateFen(String fen) {
        throw new NotImplementedYet();
    }

    /**
     * Applies {@code move} (SAN such as {@code Nf3}/{@code e8=Q}, or UCI such as {@code g1f3}/{@code e7e8q})
     * to the position reached from {@code initialFen} after {@code sanMovesSoFar}.
     * The full history is required so threefold repetition can be detected.
     */
    public static MoveOutcome applyMove(String initialFen, List<String> sanMovesSoFar, String move) {
        throw new NotImplementedYet();
    }
}
