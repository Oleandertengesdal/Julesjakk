package no.sjakkportal.tournament.engine;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import no.sjakkportal.common.error.ChessException;
import no.sjakkportal.common.error.ErrorCode;

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
        if (new HashSet<>(players).size() != players.size()) {
            throw new IllegalArgumentException("Duplicate players in round-robin");
        }
        if (players.size() < 2) {
            throw new ChessException(ErrorCode.TOURNAMENT_TOO_FEW_PLAYERS);
        }
        // With an odd field a ghost takes the fixed seat; whoever meets the ghost sits out.
        List<Long> seats = new ArrayList<>(players);
        if (seats.size() % 2 == 1) {
            seats.add(null);
        }
        int size = seats.size();
        int rotating = size - 1;

        List<List<Pairing>> rounds = new ArrayList<>();
        for (int round = 0; round < rotating; round++) {
            List<Pairing> pairings = new ArrayList<>();
            for (int i = 0; i < size / 2; i++) {
                Long upper = seats.get((round + i) % rotating);
                Long lower = i == 0 ? seats.get(rotating) : seats.get(((round - i) % rotating + rotating) % rotating);
                // Seat index i moves by one every round, so colouring by its parity makes every player
                // alternate colours; the fixed seat alternates by round.
                boolean upperIsWhite = i == 0 ? round % 2 == 0 : i % 2 == 1;
                pairings.add(pairing(upper, lower, upperIsWhite));
            }
            rounds.add(List.copyOf(pairings));
        }
        if (doubleRound) {
            int firstCycle = rounds.size();
            for (int round = 0; round < firstCycle; round++) {
                List<Pairing> reversed = new ArrayList<>();
                for (Pairing pairing : rounds.get(round)) {
                    reversed.add(pairing.isBye() ? pairing : new Pairing(pairing.black(), pairing.white()));
                }
                rounds.add(List.copyOf(reversed));
            }
        }
        return List.copyOf(rounds);
    }

    private static Pairing pairing(Long upper, Long lower, boolean upperIsWhite) {
        if (upper == null) {
            return Pairing.bye(lower);
        }
        if (lower == null) {
            return Pairing.bye(upper);
        }
        return upperIsWhite ? new Pairing(upper, lower) : new Pairing(lower, upper);
    }
}
