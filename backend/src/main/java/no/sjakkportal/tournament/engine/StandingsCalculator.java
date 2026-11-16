package no.sjakkportal.tournament.engine;

import java.util.List;
import no.sjakkportal.common.NotImplementedYet;

/**
 * Standings with tie-breaks, applied in this order: score, head-to-head among the tied players,
 * Buchholz, Sonneborn-Berger. Players still level after all four share a rank.
 */
public final class StandingsCalculator {

    private StandingsCalculator() {
    }

    /**
     * @param players every registered player, including those without games
     * @param games   finished games and byes
     * @return rows sorted best first
     */
    public static List<StandingRow> standings(List<Long> players, List<PlayedGame> games) {
        throw new NotImplementedYet();
    }
}
