package no.sjakkportal.chess;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import no.sjakkportal.rating.GameResult;

/**
 * A fully validated game.
 *
 * @param sanMoves       moves in Standard Algebraic Notation, one entry per ply
 * @param uciMoves       the same moves in UCI notation (e.g. {@code e7e8q}), for Stockfish
 * @param fens           position after each ply; {@code fens.size() == sanMoves.size()}
 * @param initialFen     starting position (the standard one unless the PGN has a FEN tag)
 * @param declaredResult result from the PGN {@code Result} tag / terminator; empty for {@code *}
 * @param boardOutcome   result forced by the final position (checkmate or an automatic draw), if any
 * @param termination    how the final position ended the game, if it did
 * @param tags           sanitised PGN tag pairs
 * @param normalizedPgn  re-serialised PGN, safe to store and render
 */
public record ParsedGame(List<String> sanMoves,
                         List<String> uciMoves,
                         List<String> fens,
                         String initialFen,
                         Optional<GameResult> declaredResult,
                         Optional<GameResult> boardOutcome,
                         Optional<Termination> termination,
                         Map<String, String> tags,
                         String normalizedPgn) {

    public int plyCount() {
        return sanMoves.size();
    }

    public String finalFen() {
        return fens.isEmpty() ? initialFen : fens.get(fens.size() - 1);
    }
}
