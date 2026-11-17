package no.sjakkportal.tournament.engine;

import java.util.List;
import java.util.Objects;

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
        Objects.requireNonNull(outcome, "outcome");
        int multiplier = before.onFire() ? 2 : 1;
        return switch (outcome) {
            case WIN -> new ArenaState(before.points() + 2 * multiplier, before.streak() + 1);
            case DRAW -> new ArenaState(before.points() + multiplier, 0);
            case LOSS -> new ArenaState(before.points(), 0);
        };
    }

    public static ArenaState score(List<Outcome> outcomesInOrder) {
        ArenaState state = new ArenaState(0, 0);
        for (Outcome outcome : outcomesInOrder) {
            state = apply(state, outcome);
        }
        return state;
    }
}
