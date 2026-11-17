package no.sjakkportal.tournament.engine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import no.sjakkportal.rating.GameResult;

/**
 * Standings with tie-breaks, applied in this order: score, head-to-head among the tied players,
 * Buchholz, Sonneborn-Berger. Players still level after all four share a rank.
 *
 * <p>All arithmetic is done in whole half-points so that ten draws equal five wins exactly.
 */
public final class StandingsCalculator {

    private StandingsCalculator() {
    }

    private static final class Tally {
        final long player;
        int halfPoints;
        int played;
        int wins;
        int draws;
        int losses;
        /** Opponent and the half-points scored against them, one entry per game. */
        final List<long[]> meetings = new ArrayList<>();
        int headToHead;
        int buchholz;
        /** Sonneborn-Berger in quarter-points (a draw contributes half of an opponent's half-points). */
        int sonnebornBergerQuarters;

        Tally(long player) {
            this.player = player;
        }
    }

    /**
     * @param players every registered player, including those without games
     * @param games   finished games and byes
     * @return rows sorted best first
     */
    public static List<StandingRow> standings(List<Long> players, List<PlayedGame> games) {
        Map<Long, Tally> tallies = new LinkedHashMap<>();
        for (Long player : players) {
            tallies.put(player, new Tally(player));
        }
        for (PlayedGame game : games) {
            Tally white = require(tallies, game.white());
            if (game.isBye()) {
                white.halfPoints += 2;
                continue;
            }
            Tally black = require(tallies, game.black());
            record(white, black.player, game.result() == GameResult.WHITE_WIN ? 2 : game.result() == GameResult.DRAW ? 1 : 0);
            record(black, white.player, game.result() == GameResult.BLACK_WIN ? 2 : game.result() == GameResult.DRAW ? 1 : 0);
        }

        Map<Integer, Integer> groupSizes = new HashMap<>();
        for (Tally tally : tallies.values()) {
            groupSizes.merge(tally.halfPoints, 1, Integer::sum);
        }
        for (Tally tally : tallies.values()) {
            for (long[] meeting : tally.meetings) {
                Tally opponent = tallies.get(meeting[0]);
                int scored = (int) meeting[1];
                tally.buchholz += opponent.halfPoints;
                tally.sonnebornBergerQuarters += scored * opponent.halfPoints;
                // Head-to-head counts every game inside the whole tied group, which keeps A>B>C>A a tie.
                if (opponent.halfPoints == tally.halfPoints) {
                    tally.headToHead += scored;
                }
            }
        }

        Comparator<Tally> order = Comparator.<Tally>comparingInt(t -> -t.halfPoints)
                .thenComparingInt(t -> -t.headToHead)
                .thenComparingInt(t -> -t.buchholz)
                .thenComparingInt(t -> -t.sonnebornBergerQuarters);
        List<Tally> sorted = new ArrayList<>(tallies.values());
        sorted.sort(order.thenComparingLong(t -> t.player));

        List<StandingRow> rows = new ArrayList<>();
        int rank = 0;
        for (int i = 0; i < sorted.size(); i++) {
            Tally tally = sorted.get(i);
            if (i == 0 || order.compare(sorted.get(i - 1), tally) != 0) {
                rank = i + 1;
            }
            rows.add(new StandingRow(rank, tally.player, tally.halfPoints / 2.0, tally.played, tally.wins, tally.draws,
                    tally.losses, tally.headToHead / 2.0, tally.buchholz / 2.0, tally.sonnebornBergerQuarters / 4.0));
        }
        return List.copyOf(rows);
    }

    private static Tally require(Map<Long, Tally> tallies, Long player) {
        Tally tally = tallies.get(player);
        if (tally == null) {
            throw new IllegalArgumentException("Game involves a player who is not registered: " + player);
        }
        return tally;
    }

    private static void record(Tally tally, long opponent, int halfPoints) {
        tally.halfPoints += halfPoints;
        tally.played++;
        if (halfPoints == 2) {
            tally.wins++;
        } else if (halfPoints == 1) {
            tally.draws++;
        } else {
            tally.losses++;
        }
        tally.meetings.add(new long[] {opponent, halfPoints});
    }
}
