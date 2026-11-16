package no.sjakkportal.tournament.engine;

import java.util.List;
import java.util.Optional;
import no.sjakkportal.common.NotImplementedYet;

/**
 * Single- and double-elimination brackets with standard seeding (1 v N, 2 v N-1, ... arranged so the
 * top two seeds can only meet in the final) and byes for the top seeds when the field is not a power of two.
 *
 * <p>Chess games can be drawn; a bracket match needs a winner, so the caller reports the player who
 * advances (after a replay or armageddon). In double elimination the grand final is reset once if the
 * losers-bracket finalist wins it, so that every eliminated player has lost exactly twice.
 *
 * <p>The bracket is deterministic: replaying the same reports on the same seeds rebuilds the same state,
 * which is how it is restored from the database.
 */
public final class EliminationBracket {

    private EliminationBracket() {
    }

    public static EliminationBracket single(List<Long> seededPlayers) {
        throw new NotImplementedYet();
    }

    public static EliminationBracket doubleElimination(List<Long> seededPlayers) {
        throw new NotImplementedYet();
    }

    /** Every match created so far, byes included, ordered by id. */
    public List<BracketMatch> matches() {
        throw new NotImplementedYet();
    }

    /** Matches with both players known and no winner yet. */
    public List<BracketMatch> readyMatches() {
        throw new NotImplementedYet();
    }

    /**
     * Records the winner of a ready match and advances the bracket.
     *
     * @throws no.sjakkportal.common.error.ChessException CHESS-TOURN-006 if the match does not exist,
     *         is not ready, is already decided, or {@code winner} is not one of its players
     */
    public void report(int matchId, long winner) {
        throw new NotImplementedYet();
    }

    public boolean isFinished() {
        throw new NotImplementedYet();
    }

    public Optional<Long> champion() {
        throw new NotImplementedYet();
    }

    /** Number of bracket matches a player has lost so far. */
    public int losses(long player) {
        throw new NotImplementedYet();
    }
}
