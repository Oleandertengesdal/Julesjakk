package no.sjakkportal.tournament.engine;

import java.util.List;
import no.sjakkportal.common.NotImplementedYet;

/**
 * Arena scoring: win 2, draw 1, loss 0. After two consecutive wins a player is "on fire" and each
 * following game scores double (win 4, draw 2) until a game that is not a win ends the streak.
 * The game that ends the streak still scores double.
 */
public final class ArenaScoring {

    public enum Outcome {
        WIN, DRAW, LOSS
    }

    /** @param streak number of consecutive wins immediately before the next game */
    public record ArenaState(int points, int streak) {

        public boolean onFire() {
            return streak >= 2;
        }
    }

    private ArenaScoring() {
    }

    public static ArenaState apply(ArenaState before, Outcome outcome) {
        throw new NotImplementedYet();
    }

    public static ArenaState score(List<Outcome> outcomesInOrder) {
        throw new NotImplementedYet();
    }
}
