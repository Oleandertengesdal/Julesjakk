package no.sjakkportal.tournament.engine;

import static no.sjakkportal.support.ChessAssertions.assertFailsWith;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.LongStream;
import no.sjakkportal.common.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RoundRobinSchedulerTest {

    private static List<Long> players(int count) {
        return LongStream.rangeClosed(1, count).boxed().toList();
    }

    private static String unordered(Pairing pairing) {
        return Math.min(pairing.white(), pairing.black()) + "-" + Math.max(pairing.white(), pairing.black());
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12})
    void everyPairMeetsExactlyOnce(int count) {
        List<List<Pairing>> rounds = RoundRobinScheduler.schedule(players(count), false);

        assertThat(rounds).hasSize(count % 2 == 0 ? count - 1 : count);
        List<String> meetings = rounds.stream().flatMap(List::stream).filter(p -> !p.isBye())
                .map(RoundRobinSchedulerTest::unordered).toList();
        assertThat(meetings).doesNotHaveDuplicates().hasSize(count * (count - 1) / 2);
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12})
    void everyPlayerAppearsExactlyOncePerRound(int count) {
        for (List<Pairing> round : RoundRobinScheduler.schedule(players(count), false)) {
            List<Long> seen = new ArrayList<>();
            for (Pairing pairing : round) {
                seen.add(pairing.white());
                if (!pairing.isBye()) {
                    seen.add(pairing.black());
                }
            }
            assertThat(seen).containsExactlyInAnyOrderElementsOf(players(count));
            assertThat(round.stream().filter(Pairing::isBye).count()).isEqualTo(count % 2);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {3, 5, 7, 9, 11})
    void withAnOddFieldEveryPlayerSitsOutExactlyOnce(int count) {
        List<Long> byes = RoundRobinScheduler.schedule(players(count), false).stream()
                .flatMap(List::stream).filter(Pairing::isBye).map(Pairing::white).toList();
        assertThat(byes).containsExactlyInAnyOrderElementsOf(players(count));
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12})
    void coloursAreBalancedToWithinOneGame(int count) {
        Map<Long, Integer> balance = new HashMap<>();
        for (List<Pairing> round : RoundRobinScheduler.schedule(players(count), false)) {
            for (Pairing pairing : round) {
                if (!pairing.isBye()) {
                    balance.merge(pairing.white(), 1, Integer::sum);
                    balance.merge(pairing.black(), -1, Integer::sum);
                }
            }
        }
        assertThat(balance.values()).allSatisfy(diff -> assertThat(Math.abs(diff)).isLessThanOrEqualTo(1));
    }

    @ParameterizedTest
    @ValueSource(ints = {4, 6, 8, 10, 12})
    void nobodyGetsTheSameColourThreeTimesInARow(int count) {
        Map<Long, List<Color>> history = new HashMap<>();
        for (List<Pairing> round : RoundRobinScheduler.schedule(players(count), false)) {
            for (Pairing pairing : round) {
                history.computeIfAbsent(pairing.white(), k -> new ArrayList<>()).add(Color.WHITE);
                history.computeIfAbsent(pairing.black(), k -> new ArrayList<>()).add(Color.BLACK);
            }
        }
        history.forEach((player, colours) -> {
            for (int i = 2; i < colours.size(); i++) {
                assertThat(colours.get(i) == colours.get(i - 1) && colours.get(i) == colours.get(i - 2))
                        .as("player %d has three %s in a row: %s", player, colours.get(i), colours)
                        .isFalse();
            }
        });
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 3, 4, 5, 6, 7, 8})
    void doubleRoundRobinPlaysEveryPairingWithBothColours(int count) {
        List<List<Pairing>> rounds = RoundRobinScheduler.schedule(players(count), true);

        assertThat(rounds).hasSize(2 * (count % 2 == 0 ? count - 1 : count));
        Set<String> ordered = new HashSet<>();
        for (List<Pairing> round : rounds) {
            for (Pairing pairing : round) {
                if (!pairing.isBye()) {
                    assertThat(ordered.add(pairing.white() + ">" + pairing.black()))
                            .as("duplicate colour allocation %s", pairing).isTrue();
                }
            }
        }
        assertThat(ordered).hasSize(count * (count - 1));
    }

    @Test
    void scheduleIsDeterministic() {
        assertThat(RoundRobinScheduler.schedule(players(7), true)).isEqualTo(RoundRobinScheduler.schedule(players(7), true));
    }

    @Test
    void usesTheGivenPlayerIdsNotTheirPositions() {
        List<Long> ids = List.of(901L, 17L, 4242L);
        Set<Long> seen = new HashSet<>();
        RoundRobinScheduler.schedule(ids, false).stream().flatMap(List::stream).forEach(pairing -> {
            seen.add(pairing.white());
            if (!pairing.isBye()) {
                seen.add(pairing.black());
            }
        });
        assertThat(seen).containsExactlyInAnyOrderElementsOf(ids);
    }

    @Test
    void needsAtLeastTwoPlayers() {
        assertFailsWith(ErrorCode.TOURNAMENT_TOO_FEW_PLAYERS, () -> RoundRobinScheduler.schedule(List.of(), false));
        assertFailsWith(ErrorCode.TOURNAMENT_TOO_FEW_PLAYERS, () -> RoundRobinScheduler.schedule(List.of(1L), true));
    }

    @Test
    void duplicatePlayersAreAProgrammingError() {
        assertThatThrownBy(() -> RoundRobinScheduler.schedule(List.of(1L, 2L, 1L), false))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
