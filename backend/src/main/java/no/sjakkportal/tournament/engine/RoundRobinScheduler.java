package no.sjakkportal.tournament.engine;

import java.util.List;
import no.sjakkportal.common.NotImplementedYet;

/** Berger-table (circle method) schedules for single and double round-robins. */
public final class RoundRobinScheduler {

    private RoundRobinScheduler() {
    }

    /**
     * @param players     player ids in seed order; at least 2
     * @param doubleRound when true every pair meets twice, once with each colour
     * @return rounds in play order; with an odd player count each round contains one bye
     */
    public static List<List<Pairing>> schedule(List<Long> players, boolean doubleRound) {
        throw new NotImplementedYet();
    }
}
