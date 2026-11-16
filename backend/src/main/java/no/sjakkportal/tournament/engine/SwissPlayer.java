package no.sjakkportal.tournament.engine;

import java.util.List;

/**
 * A player's state going into a Swiss round.
 *
 * @param opponents ids already played, in round order
 * @param colors    colours had so far, in round order (byes excluded)
 * @param hadBye    whether the player already received a bye
 */
public record SwissPlayer(long id, int rating, double score, List<Long> opponents, List<Color> colors, boolean hadBye) {

    public static SwissPlayer fresh(long id, int rating) {
        return new SwissPlayer(id, rating, 0.0, List.of(), List.of(), false);
    }
}
