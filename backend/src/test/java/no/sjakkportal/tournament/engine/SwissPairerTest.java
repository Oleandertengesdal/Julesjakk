package no.sjakkportal.tournament.engine;

import static no.sjakkportal.support.ChessAssertions.assertFailsWith;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import no.sjakkportal.common.error.ErrorCode;
import no.sjakkportal.rating.GameResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SwissPairerTest {

    /** Players 1..n where player 1 is the highest rated. */
    private static List<SwissPlayer> field(int count) {
        List<SwissPlayer> players = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            players.add(SwissPlayer.fresh(i, 2000 - i * 50));
        }
        return players;
    }

    private static Set<Set<Long>> asSets(List<Pairing> pairings) {
        Set<Set<Long>> sets = new HashSet<>();
        for (Pairing pairing : pairings) {
            sets.add(pairing.isBye() ? Set.of(pairing.white()) : Set.of(pairing.white(), pairing.black()));
        }
        return sets;
    }

    @ParameterizedTest
    @CsvSource({"1,1", "2,1", "3,2", "4,2", "5,3", "8,3", "9,4", "16,4", "17,5"})
    void recommendsLogTwoRounds(int players, int rounds) {
        assertThat(SwissPairer.recommendedRounds(players)).isEqualTo(rounds);
    }

    @Test
    void firstRoundPairsTopHalfAgainstBottomHalf() {
        assertThat(asSets(SwissPairer.pair(field(8))))
                .containsExactlyInAnyOrder(Set.of(1L, 5L), Set.of(2L, 6L), Set.of(3L, 7L), Set.of(4L, 8L));
    }

    @Test
    void firstRoundAlternatesColoursDownTheBoards() {
        List<Pairing> pairings = SwissPairer.pair(field(8));
        long higherSeedsWithWhite = pairings.stream().filter(p -> p.white() <= 4).count();
        assertThat(higherSeedsWithWhite).isEqualTo(2);
    }

    @Test
    void oddFieldGivesTheByeToTheLowestRatedPlayer() {
        List<Pairing> pairings = SwissPairer.pair(field(7));
        assertThat(pairings).hasSize(4);
        assertThat(pairings.stream().filter(Pairing::isBye).map(Pairing::white)).containsExactly(7L);
    }

    @Test
    void nobodyReceivesASecondBye() {
        List<SwissPlayer> players = List.of(
                new SwissPlayer(1, 1900, 1.0, List.of(2L), List.of(Color.WHITE), false),
                new SwissPlayer(2, 1800, 0.0, List.of(1L), List.of(Color.BLACK), false),
                new SwissPlayer(3, 1700, 1.0, List.of(), List.of(), true));
        List<Pairing> pairings = SwissPairer.pair(players);
        assertThat(pairings.stream().filter(Pairing::isBye).map(Pairing::white)).doesNotContain(3L);
    }

    @Test
    void secondRoundPairsWinnersWithWinners() {
        // Round 1: 1-5, 2-6, 3-7, 4-8 and the higher seed always won.
        List<SwissPlayer> players = new ArrayList<>();
        for (long id = 1; id <= 8; id++) {
            boolean topHalf = id <= 4;
            long opponent = topHalf ? id + 4 : id - 4;
            Color colour = (Math.min(id, opponent) % 2 == 1) == topHalf ? Color.WHITE : Color.BLACK;
            players.add(new SwissPlayer(id, 2000 - (int) id * 50, topHalf ? 1.0 : 0.0, List.of(opponent), List.of(colour), false));
        }
        for (Pairing pairing : SwissPairer.pair(players)) {
            assertThat(pairing.white() <= 4).as("%s mixes score groups", pairing).isEqualTo(pairing.black() <= 4);
        }
    }

    @Test
    void floatsAPlayerDownWhenAScoreGroupIsOdd() {
        // Three players on 1 point, three on 0: one must be paired across groups, and nobody sits out.
        List<SwissPlayer> players = List.of(
                new SwissPlayer(1, 2000, 1.0, List.of(4L), List.of(Color.WHITE), false),
                new SwissPlayer(2, 1900, 1.0, List.of(5L), List.of(Color.BLACK), false),
                new SwissPlayer(3, 1800, 1.0, List.of(6L), List.of(Color.WHITE), false),
                new SwissPlayer(4, 1700, 0.0, List.of(1L), List.of(Color.BLACK), false),
                new SwissPlayer(5, 1600, 0.0, List.of(2L), List.of(Color.WHITE), false),
                new SwissPlayer(6, 1500, 0.0, List.of(3L), List.of(Color.BLACK), false));
        List<Pairing> pairings = SwissPairer.pair(players);
        assertThat(pairings).hasSize(3).noneMatch(Pairing::isBye);
        long crossGroup = pairings.stream().filter(p -> (p.white() <= 3) != (p.black() <= 3)).count();
        assertThat(crossGroup).isEqualTo(1);
    }

    @Test
    void avoidsRematchesEvenWhenTheNaturalPairingIsARematch() {
        // 1 and 2 lead but have already met; so have 3 and 4. The only legal pairing crosses the groups.
        List<SwissPlayer> players = List.of(
                new SwissPlayer(1, 2000, 1.5, List.of(2L, 3L), List.of(Color.WHITE, Color.BLACK), false),
                new SwissPlayer(2, 1900, 1.5, List.of(1L, 4L), List.of(Color.BLACK, Color.WHITE), false),
                new SwissPlayer(3, 1800, 0.5, List.of(4L, 1L), List.of(Color.WHITE, Color.WHITE), false),
                new SwissPlayer(4, 1700, 0.5, List.of(3L, 2L), List.of(Color.BLACK, Color.BLACK), false));
        assertThat(asSets(SwissPairer.pair(players))).containsExactlyInAnyOrder(Set.of(1L, 4L), Set.of(2L, 3L));
    }

    @Test
    void playerWhoHadTheSameColourTwiceGetsTheOther() {
        List<SwissPlayer> players = List.of(
                new SwissPlayer(1, 2000, 1.5, List.of(2L, 3L), List.of(Color.WHITE, Color.BLACK), false),
                new SwissPlayer(2, 1900, 1.5, List.of(1L, 4L), List.of(Color.BLACK, Color.WHITE), false),
                new SwissPlayer(3, 1800, 0.5, List.of(4L, 1L), List.of(Color.WHITE, Color.WHITE), false),
                new SwissPlayer(4, 1700, 0.5, List.of(3L, 2L), List.of(Color.BLACK, Color.BLACK), false));
        for (Pairing pairing : SwissPairer.pair(players)) {
            assertThat(pairing.white()).isNotEqualTo(3L);
            assertThat(pairing.black()).isNotEqualTo(4L);
        }
    }

    @Test
    void needsAtLeastTwoPlayers() {
        assertFailsWith(ErrorCode.TOURNAMENT_TOO_FEW_PLAYERS, () -> SwissPairer.pair(List.of()));
        assertFailsWith(ErrorCode.TOURNAMENT_TOO_FEW_PLAYERS, () -> SwissPairer.pair(field(1)));
    }

    @Test
    void reportsWhenNoLegalPairingExists() {
        List<SwissPlayer> players = List.of(
                new SwissPlayer(1, 2000, 1.0, List.of(2L), List.of(Color.WHITE), false),
                new SwissPlayer(2, 1900, 0.0, List.of(1L), List.of(Color.BLACK), false));
        assertFailsWith(ErrorCode.TOURNAMENT_INVALID_STATE, () -> SwissPairer.pair(players));
    }

    /**
     * Plays complete tournaments with random results and checks the hard rules after every round.
     * Greedy pairing algorithms pass the simple cases above and dead-end here.
     */
    @Test
    void wholeTournamentsNeverBreakTheHardRules() {
        Random random = new Random(1814L);
        for (int count = 2; count <= 13; count++) {
            for (int trial = 0; trial < 40; trial++) {
                simulate(count, SwissPairer.recommendedRounds(count), random);
            }
        }
    }

    private static void simulate(int count, int rounds, Random random) {
        Map<Long, Double> scores = new HashMap<>();
        Map<Long, List<Long>> opponents = new HashMap<>();
        Map<Long, List<Color>> colours = new HashMap<>();
        Set<Long> byes = new HashSet<>();
        List<SwissPlayer> base = field(count);
        for (SwissPlayer player : base) {
            scores.put(player.id(), 0.0);
            opponents.put(player.id(), new ArrayList<>());
            colours.put(player.id(), new ArrayList<>());
        }

        for (int round = 1; round <= rounds; round++) {
            List<SwissPlayer> state = base.stream().map(p -> new SwissPlayer(p.id(), p.rating(), scores.get(p.id()),
                    List.copyOf(opponents.get(p.id())), List.copyOf(colours.get(p.id())), byes.contains(p.id()))).toList();
            List<Pairing> pairings = SwissPairer.pair(state);
            String context = count + " players, round " + round + ": " + pairings;

            Set<Long> seen = new HashSet<>();
            int byeCount = 0;
            for (Pairing pairing : pairings) {
                assertThat(seen.add(pairing.white())).as("paired twice. %s", context).isTrue();
                if (pairing.isBye()) {
                    byeCount++;
                    assertThat(byes.add(pairing.white())).as("second bye. %s", context).isTrue();
                    scores.merge(pairing.white(), 1.0, Double::sum);
                    continue;
                }
                assertThat(seen.add(pairing.black())).as("paired twice. %s", context).isTrue();
                assertThat(opponents.get(pairing.white())).as("rematch. %s", context).doesNotContain(pairing.black());
                opponents.get(pairing.white()).add(pairing.black());
                opponents.get(pairing.black()).add(pairing.white());
                colours.get(pairing.white()).add(Color.WHITE);
                colours.get(pairing.black()).add(Color.BLACK);
                GameResult result = GameResult.values()[random.nextInt(3)];
                scores.merge(pairing.white(), result.whiteScore(), Double::sum);
                scores.merge(pairing.black(), result.blackScore(), Double::sum);
            }
            assertThat(seen).as("everyone is paired. %s", context).hasSize(count);
            assertThat(byeCount).as("bye count. %s", context).isEqualTo(count % 2);

            colours.forEach((player, history) -> {
                int size = history.size();
                if (size >= 3) {
                    assertThat(history.get(size - 1) == history.get(size - 2) && history.get(size - 2) == history.get(size - 3))
                            .as("player %d has three colours in a row %s. %s", player, history, context).isFalse();
                }
                long whites = history.stream().filter(c -> c == Color.WHITE).count();
                assertThat(Math.abs(2 * whites - size)).as("colour imbalance for %d: %s", player, history)
                        .isLessThanOrEqualTo(2);
            });
        }
    }
}
