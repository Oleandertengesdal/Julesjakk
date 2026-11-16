package no.sjakkportal.tournament.engine;

import static no.sjakkportal.support.ChessAssertions.assertFailsWith;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.ToLongFunction;
import java.util.stream.LongStream;
import no.sjakkportal.common.error.ErrorCode;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class EliminationBracketTest {

    /** Seeds are their own ids: player 1 is the top seed. */
    private static List<Long> seeds(int count) {
        return LongStream.rangeClosed(1, count).boxed().toList();
    }

    private static Set<Long> players(BracketMatch match) {
        return Set.of(match.playerA(), match.playerB());
    }

    private static long realMatches(EliminationBracket bracket) {
        return bracket.matches().stream().filter(m -> m.playerA() != null && m.playerB() != null).count();
    }

    /** Plays the bracket to the end; returns the number of reports made. */
    private static int playOut(EliminationBracket bracket, ToLongFunction<BracketMatch> pickWinner) {
        int reports = 0;
        while (!bracket.isFinished()) {
            List<BracketMatch> ready = bracket.readyMatches();
            assertThat(ready).as("unfinished bracket must always have a playable match").isNotEmpty();
            BracketMatch next = ready.get(0);
            bracket.report(next.id(), pickWinner.applyAsLong(next));
            assertThat(++reports).as("bracket does not terminate").isLessThan(200);
        }
        return reports;
    }

    private static long favourite(BracketMatch match) {
        return Math.min(match.playerA(), match.playerB());
    }

    @Nested
    class SingleElimination {

        @Test
        void eightPlayersUseStandardSeeding() {
            EliminationBracket bracket = EliminationBracket.single(seeds(8));
            assertThat(bracket.readyMatches()).extracting(EliminationBracketTest::players)
                    .containsExactlyInAnyOrder(Set.of(1L, 8L), Set.of(4L, 5L), Set.of(2L, 7L), Set.of(3L, 6L));
        }

        @Test
        void topTwoSeedsCanOnlyMeetInTheFinal() {
            EliminationBracket bracket = EliminationBracket.single(seeds(8));
            int reports = 0;
            while (!bracket.isFinished()) {
                BracketMatch match = bracket.readyMatches().get(0);
                if (players(match).equals(Set.of(1L, 2L))) {
                    assertThat(reports).as("1 v 2 before the final").isEqualTo(6);
                }
                bracket.report(match.id(), favourite(match));
                reports++;
            }
            assertThat(reports).isEqualTo(7);
            assertThat(bracket.champion()).contains(1L);
        }

        @ParameterizedTest
        @ValueSource(ints = {2, 3, 4, 5, 6, 7, 8, 9, 13, 16})
        void anyFieldSizeNeedsExactlyPlayersMinusOneGames(int count) {
            EliminationBracket bracket = EliminationBracket.single(seeds(count));
            int reports = playOut(bracket, EliminationBracketTest::favourite);
            assertThat(reports).isEqualTo(count - 1);
            assertThat(realMatches(bracket)).isEqualTo(count - 1);
            assertThat(bracket.champion()).contains(1L);
        }

        @Test
        void byesGoToTheTopSeeds() {
            EliminationBracket bracket = EliminationBracket.single(seeds(5));
            // Bracket of 8: seeds 1, 2 and 3 have no opponent in round one, so only 4 v 5 is played there.
            // Seeds 2 and 3 both advance on byes, which makes their semi-final playable immediately.
            assertThat(bracket.readyMatches()).extracting(EliminationBracketTest::players)
                    .containsExactlyInAnyOrder(Set.of(4L, 5L), Set.of(2L, 3L));
        }

        @Test
        void aByeIsNeverCountedAsALoss() {
            EliminationBracket bracket = EliminationBracket.single(seeds(5));
            playOut(bracket, EliminationBracketTest::favourite);
            assertThat(bracket.losses(1L)).isZero();
            for (long loser = 2; loser <= 5; loser++) {
                assertThat(bracket.losses(loser)).as("player %d", loser).isEqualTo(1);
            }
        }

        @Test
        void championIsUnknownUntilTheFinalIsReported() {
            EliminationBracket bracket = EliminationBracket.single(seeds(2));
            assertThat(bracket.isFinished()).isFalse();
            assertThat(bracket.champion()).isEmpty();
            bracket.report(bracket.readyMatches().get(0).id(), 2L);
            assertThat(bracket.isFinished()).isTrue();
            assertThat(bracket.champion()).contains(2L);
            assertThat(bracket.readyMatches()).isEmpty();
        }

        @Test
        void underdogWinsPropagateCorrectly() {
            EliminationBracket bracket = EliminationBracket.single(seeds(8));
            playOut(bracket, match -> Math.max(match.playerA(), match.playerB()));
            // Lowest seed always wins: 8 beats 1, then 5 (who beat 4), then 7 (who beat 2, then 6).
            assertThat(bracket.champion()).contains(8L);
        }
    }

    @Nested
    class Reporting {

        @Test
        void rejectsUnknownMatch() {
            EliminationBracket bracket = EliminationBracket.single(seeds(4));
            assertFailsWith(ErrorCode.TOURNAMENT_PAIRING_CONFLICT, () -> bracket.report(9999, 1L));
            assertFailsWith(ErrorCode.TOURNAMENT_PAIRING_CONFLICT, () -> bracket.report(-1, 1L));
        }

        @Test
        void rejectsAWinnerWhoIsNotInTheMatch() {
            EliminationBracket bracket = EliminationBracket.single(seeds(4));
            BracketMatch match = bracket.readyMatches().get(0);
            long outsider = seeds(4).stream().filter(id -> !players(match).contains(id)).findFirst().orElseThrow();
            assertFailsWith(ErrorCode.TOURNAMENT_PAIRING_CONFLICT, () -> bracket.report(match.id(), outsider));
        }

        @Test
        void rejectsReportingTheSameMatchTwice() {
            EliminationBracket bracket = EliminationBracket.single(seeds(4));
            BracketMatch match = bracket.readyMatches().get(0);
            bracket.report(match.id(), match.playerA());
            // Even with the "other" winner: a decided match can not be silently flipped.
            assertFailsWith(ErrorCode.TOURNAMENT_PAIRING_CONFLICT, () -> bracket.report(match.id(), match.playerB()));
            assertFailsWith(ErrorCode.TOURNAMENT_PAIRING_CONFLICT, () -> bracket.report(match.id(), match.playerA()));
        }

        @Test
        void rejectsReportingAMatchWhoseFeedersAreUndecided() {
            EliminationBracket bracket = EliminationBracket.single(seeds(4));
            Set<Integer> ready = new HashSet<>();
            bracket.readyMatches().forEach(m -> ready.add(m.id()));
            BracketMatch pendingFinal = bracket.matches().stream().filter(m -> !ready.contains(m.id())).findFirst().orElseThrow();
            assertFailsWith(ErrorCode.TOURNAMENT_PAIRING_CONFLICT, () -> bracket.report(pendingFinal.id(), 1L));
        }

        @Test
        void needsAtLeastTwoPlayers() {
            assertFailsWith(ErrorCode.TOURNAMENT_TOO_FEW_PLAYERS, () -> EliminationBracket.single(seeds(1)));
            assertFailsWith(ErrorCode.TOURNAMENT_TOO_FEW_PLAYERS, () -> EliminationBracket.doubleElimination(List.of()));
        }

        @Test
        void matchesViewCannotBeUsedToMutateTheBracket() {
            EliminationBracket bracket = EliminationBracket.single(seeds(4));
            List<BracketMatch> view = bracket.matches();
            try {
                view.clear();
            } catch (UnsupportedOperationException expected) {
                // immutable view is fine
            }
            assertThat(bracket.matches()).isNotEmpty();
        }
    }

    @Nested
    class DoubleElimination {

        @Test
        void favouriteWinningEverythingNeedsNoResetFinal() {
            EliminationBracket bracket = EliminationBracket.doubleElimination(seeds(4));
            int reports = playOut(bracket, EliminationBracketTest::favourite);
            assertThat(reports).isEqualTo(6);   // 2n - 2
            assertThat(bracket.champion()).contains(1L);
            assertThat(bracket.losses(1L)).isZero();
            for (long other = 2; other <= 4; other++) {
                assertThat(bracket.losses(other)).as("player %d", other).isEqualTo(2);
            }
        }

        @Test
        void losingOnceDoesNotEliminate() {
            EliminationBracket bracket = EliminationBracket.doubleElimination(seeds(4));
            BracketMatch first = bracket.readyMatches().stream().filter(m -> players(m).contains(1L)).findFirst().orElseThrow();
            long opponent = first.playerA() == 1L ? first.playerB() : first.playerA();
            bracket.report(first.id(), opponent);

            assertThat(bracket.losses(1L)).isEqualTo(1);
            // Top seed drops to the losers bracket and can still win the whole thing.
            playOut(bracket, match -> players(match).contains(1L) ? 1L : favourite(match));
            assertThat(bracket.champion()).contains(1L);
            assertThat(bracket.losses(1L)).isEqualTo(1);
        }

        @Test
        void grandFinalIsResetWhenTheLosersBracketFinalistWinsIt() {
            EliminationBracket bracket = EliminationBracket.doubleElimination(seeds(4));
            // Player 1 loses the first match, then wins everything else, including both grand finals.
            BracketMatch first = bracket.readyMatches().stream().filter(m -> players(m).contains(1L)).findFirst().orElseThrow();
            bracket.report(first.id(), first.playerA() == 1L ? first.playerB() : first.playerA());
            int reports = 1 + playOut(bracket, match -> players(match).contains(1L) ? 1L : favourite(match));

            assertThat(reports).isEqualTo(7);   // 2n - 1: the reset game was needed
            assertThat(bracket.matches().stream().filter(m -> m.side() == BracketSide.GRAND_FINAL
                    && m.playerA() != null && m.playerB() != null).count()).isEqualTo(2);
        }

        @ParameterizedTest
        @ValueSource(ints = {2, 3, 4, 5, 6, 7, 8, 9, 11, 16})
        void everyoneButTheChampionLosesExactlyTwice(int count) {
            Random random = new Random(count * 31L);
            for (int trial = 0; trial < 60; trial++) {
                EliminationBracket bracket = EliminationBracket.doubleElimination(seeds(count));
                int reports = playOut(bracket, match -> random.nextBoolean() ? match.playerA() : match.playerB());

                long champion = bracket.champion().orElseThrow();
                assertThat(bracket.losses(champion)).isLessThanOrEqualTo(1);
                for (long player : seeds(count)) {
                    if (player != champion) {
                        assertThat(bracket.losses(player)).as("player %d of %d", player, count).isEqualTo(2);
                    }
                }
                // Each game produces exactly one loss.
                assertThat(reports).isEqualTo(2 * (count - 1) + bracket.losses(champion));
                assertThat(realMatches(bracket)).isEqualTo(reports);
            }
        }

        @ParameterizedTest
        @ValueSource(ints = {3, 5, 6, 7})
        void nobodyPlaysThemselvesAndNoMatchIsListedTwiceAsReady(int count) {
            Random random = new Random(7L + count);
            EliminationBracket bracket = EliminationBracket.doubleElimination(seeds(count));
            while (!bracket.isFinished()) {
                List<BracketMatch> ready = bracket.readyMatches();
                assertThat(ready).extracting(BracketMatch::id).doesNotHaveDuplicates();
                Set<Long> busy = new HashSet<>();
                for (BracketMatch match : ready) {
                    assertThat(match.playerA()).isNotEqualTo(match.playerB());
                    assertThat(busy.add(match.playerA())).as("player in two ready matches").isTrue();
                    assertThat(busy.add(match.playerB())).as("player in two ready matches").isTrue();
                }
                BracketMatch next = ready.get(random.nextInt(ready.size()));
                bracket.report(next.id(), random.nextBoolean() ? next.playerA() : next.playerB());
            }
        }

        @Test
        void replayingTheSameReportsRebuildsTheSameBracket() {
            Random random = new Random(99L);
            EliminationBracket original = EliminationBracket.doubleElimination(seeds(6));
            java.util.ArrayList<long[]> log = new java.util.ArrayList<>();
            while (!original.isFinished()) {
                BracketMatch next = original.readyMatches().get(0);
                long winner = random.nextBoolean() ? next.playerA() : next.playerB();
                original.report(next.id(), winner);
                log.add(new long[] {next.id(), winner});
            }
            EliminationBracket replayed = EliminationBracket.doubleElimination(seeds(6));
            log.forEach(entry -> replayed.report((int) entry[0], entry[1]));

            assertThat(replayed.matches()).isEqualTo(original.matches());
            assertThat(replayed.champion()).isEqualTo(original.champion());
        }
    }
}
