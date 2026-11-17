package no.sjakkportal.tournament.engine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import no.sjakkportal.common.error.ChessException;
import no.sjakkportal.common.error.ErrorCode;

/**
 * Swiss-system pairing (Dutch-style, simplified for small fields).
 *
 * <p>Hard rules: nobody plays the same opponent twice; nobody gets a second bye; nobody gets the
 * same colour three times in a row. Soft goals, in order: pair players on equal scores,
 * top half versus bottom half within a score group, balance colours.
 *
 * <p>The search is a depth-first backtracking over the ranking list: the highest unpaired player
 * tries opponents in order of preference, and a choice is undone if the rest cannot be paired.
 * A greedy pass without backtracking dead-ends in late rounds of small tournaments.
 */
public final class SwissPairer {

    private SwissPairer() {
    }

    /** Recommended number of rounds for a field: {@code ceil(log2(players))}, at least 1. */
    public static int recommendedRounds(int players) {
        int rounds = 0;
        while ((1 << rounds) < players) {
            rounds++;
        }
        return Math.max(1, rounds);
    }

    /**
     * Pairs the next round.
     *
     * @throws ChessException CHESS-TOURN-003 for fewer than 2 players,
     *         CHESS-TOURN-002 when no pairing satisfying the hard rules exists
     */
    public static List<Pairing> pair(List<SwissPlayer> players) {
        if (players.size() < 2) {
            throw new ChessException(ErrorCode.TOURNAMENT_TOO_FEW_PLAYERS);
        }
        List<SwissPlayer> ranked = new ArrayList<>(players);
        ranked.sort(Comparator.comparingDouble(SwissPlayer::score).reversed()
                .thenComparing(Comparator.comparingInt(SwissPlayer::rating).reversed())
                .thenComparingLong(SwissPlayer::id));

        // Colour rules first; if the field is so small that they cannot all be met, a rematch-free
        // round with a colour repeat is better than no round at all.
        for (boolean strictColours : new boolean[] {true, false}) {
            List<Pairing> result = ranked.size() % 2 == 0
                    ? pairEven(ranked, strictColours)
                    : pairWithBye(ranked, strictColours);
            if (result != null) {
                return result;
            }
        }
        throw new ChessException(ErrorCode.TOURNAMENT_INVALID_STATE,
                "Det finnes ingen gyldig rundeoppsetting: alle gjenværende motstandere har møtt hverandre.");
    }

    private static List<Pairing> pairWithBye(List<SwissPlayer> ranked, boolean strictColours) {
        // The bye goes to the lowest-ranked player who has not had one, moving up if that leaves the rest unpairable.
        for (int i = ranked.size() - 1; i >= 0; i--) {
            SwissPlayer candidate = ranked.get(i);
            if (candidate.hadBye()) {
                continue;
            }
            List<SwissPlayer> rest = new ArrayList<>(ranked);
            rest.remove(i);
            List<Pairing> pairings = rest.isEmpty() ? new ArrayList<>() : pairEven(rest, strictColours);
            if (pairings != null) {
                List<Pairing> withBye = new ArrayList<>(pairings);
                withBye.add(Pairing.bye(candidate.id()));
                return withBye;
            }
        }
        return null;
    }

    private static List<Pairing> pairEven(List<SwissPlayer> ranked, boolean strictColours) {
        int size = ranked.size();
        // Preferred opponents per player, fixed up front from the full score groups.
        List<List<Integer>> preferences = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            preferences.add(preferencesFor(ranked, i));
        }
        int[] partner = new int[size];
        java.util.Arrays.fill(partner, -1);
        if (!search(ranked, preferences, partner, strictColours)) {
            return null;
        }
        List<Pairing> pairings = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            if (partner[i] > i) {
                pairings.add(allocateColours(ranked.get(i), ranked.get(partner[i]), pairings.size()));
            }
        }
        return pairings;
    }

    /**
     * Opponent order for the player at {@code index}: the mirror seat in the other half of the own
     * score group first (1 v 5, 2 v 6 ... in an eight-player group), then the rest of that half,
     * then the own half, then lower score groups from the top down.
     */
    private static List<Integer> preferencesFor(List<SwissPlayer> ranked, int index) {
        double score = ranked.get(index).score();
        int groupStart = index;
        while (groupStart > 0 && ranked.get(groupStart - 1).score() == score) {
            groupStart--;
        }
        int groupEnd = index;
        while (groupEnd + 1 < ranked.size() && ranked.get(groupEnd + 1).score() == score) {
            groupEnd++;
        }
        int groupSize = groupEnd - groupStart + 1;
        int half = groupSize / 2;
        int position = index - groupStart;

        List<Integer> order = new ArrayList<>();
        if (position < half) {
            int bottomSize = groupSize - half;
            for (int k = 0; k < bottomSize; k++) {
                order.add(groupStart + half + (position + k) % bottomSize);
            }
            for (int k = position + 1; k < half; k++) {
                order.add(groupStart + k);
            }
        } else {
            for (int k = position + 1; k < groupSize; k++) {
                order.add(groupStart + k);
            }
        }
        for (int k = groupEnd + 1; k < ranked.size(); k++) {
            order.add(k);
        }
        return order;
    }

    private static boolean search(List<SwissPlayer> ranked, List<List<Integer>> preferences, int[] partner, boolean strictColours) {
        int first = -1;
        for (int i = 0; i < partner.length; i++) {
            if (partner[i] == -1) {
                first = i;
                break;
            }
        }
        if (first == -1) {
            return true;
        }
        SwissPlayer player = ranked.get(first);
        for (int candidate : preferences.get(first)) {
            if (candidate == first || partner[candidate] != -1) {
                continue;
            }
            SwissPlayer opponent = ranked.get(candidate);
            if (player.opponents().contains(opponent.id()) || opponent.opponents().contains(player.id())) {
                continue;
            }
            if (strictColours && !coloursPossible(player, opponent)) {
                continue;
            }
            partner[first] = candidate;
            partner[candidate] = first;
            if (search(ranked, preferences, partner, strictColours)) {
                return true;
            }
            partner[first] = -1;
            partner[candidate] = -1;
        }
        return false;
    }

    /** A player may not take a colour they have had twice running, or that they are already two games ahead in. */
    private static boolean barredFrom(SwissPlayer player, Color colour) {
        List<Color> history = player.colors();
        int size = history.size();
        if (size >= 2 && history.get(size - 1) == colour && history.get(size - 2) == colour) {
            return true;
        }
        return imbalance(player, colour) >= 2;
    }

    /** How many more games the player has had with {@code colour} than with the other one. */
    private static int imbalance(SwissPlayer player, Color colour) {
        int same = 0;
        for (Color played : player.colors()) {
            same += played == colour ? 1 : -1;
        }
        return same;
    }

    private static boolean coloursPossible(SwissPlayer a, SwissPlayer b) {
        return (!barredFrom(a, Color.WHITE) && !barredFrom(b, Color.BLACK))
                || (!barredFrom(a, Color.BLACK) && !barredFrom(b, Color.WHITE));
    }

    /**
     * @param higher the higher-ranked player of the two
     * @param board  zero-based board number, used to alternate colours when nobody has a preference
     */
    private static Pairing allocateColours(SwissPlayer higher, SwissPlayer lower, int board) {
        boolean higherCanBeWhite = !barredFrom(higher, Color.WHITE) && !barredFrom(lower, Color.BLACK);
        boolean higherCanBeBlack = !barredFrom(higher, Color.BLACK) && !barredFrom(lower, Color.WHITE);
        boolean higherWhite;
        if (higherCanBeWhite != higherCanBeBlack) {
            higherWhite = higherCanBeWhite;
        } else {
            // Positive = this player is owed White.
            int higherWantsWhite = wantsWhite(higher);
            int lowerWantsWhite = wantsWhite(lower);
            if (higherWantsWhite != lowerWantsWhite) {
                higherWhite = higherWantsWhite > lowerWantsWhite;
            } else {
                higherWhite = board % 2 == 0;
            }
        }
        return higherWhite ? new Pairing(higher.id(), lower.id()) : new Pairing(lower.id(), higher.id());
    }

    /** Strength of the player's claim on White: colour balance first, then alternation from the last game. */
    private static int wantsWhite(SwissPlayer player) {
        int claim = 2 * imbalance(player, Color.BLACK);
        List<Color> history = player.colors();
        if (!history.isEmpty()) {
            claim += history.get(history.size() - 1) == Color.BLACK ? 1 : -1;
        }
        return claim;
    }
}
