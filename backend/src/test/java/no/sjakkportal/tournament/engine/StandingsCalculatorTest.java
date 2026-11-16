package no.sjakkportal.tournament.engine;

import static no.sjakkportal.rating.GameResult.BLACK_WIN;
import static no.sjakkportal.rating.GameResult.DRAW;
import static no.sjakkportal.rating.GameResult.WHITE_WIN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class StandingsCalculatorTest {

    private static final long A = 1, B = 2, C = 3, D = 4;

    private static Map<Long, StandingRow> byPlayer(List<StandingRow> rows) {
        return rows.stream().collect(Collectors.toMap(StandingRow::player, row -> row));
    }

    @Test
    void countsScoreWinsDrawsAndLosses() {
        List<StandingRow> rows = StandingsCalculator.standings(List.of(A, B, C),
                List.of(new PlayedGame(A, B, WHITE_WIN), new PlayedGame(C, A, BLACK_WIN), new PlayedGame(B, C, DRAW)));
        StandingRow a = byPlayer(rows).get(A);
        StandingRow b = byPlayer(rows).get(B);

        assertThat(a.score()).isEqualTo(2.0);
        assertThat(a).extracting(StandingRow::played, StandingRow::wins, StandingRow::draws, StandingRow::losses)
                .containsExactly(2, 2, 0, 0);
        assertThat(b.score()).isEqualTo(0.5);
        assertThat(b).extracting(StandingRow::played, StandingRow::wins, StandingRow::draws, StandingRow::losses)
                .containsExactly(2, 0, 1, 1);
    }

    @Test
    void computesBuchholzAndSonnebornBerger() {
        // A beats B and C; B and C draw. Scores: A 2, B 0.5, C 0.5.
        List<StandingRow> rows = StandingsCalculator.standings(List.of(A, B, C),
                List.of(new PlayedGame(A, B, WHITE_WIN), new PlayedGame(C, A, BLACK_WIN), new PlayedGame(B, C, DRAW)));
        Map<Long, StandingRow> table = byPlayer(rows);

        assertThat(table.get(A).buchholz()).isEqualTo(1.0);            // 0.5 + 0.5
        assertThat(table.get(A).sonnebornBerger()).isEqualTo(1.0);     // beat B (0.5) and C (0.5)
        assertThat(table.get(B).buchholz()).isEqualTo(2.5);            // 2 + 0.5
        assertThat(table.get(B).sonnebornBerger()).isEqualTo(0.25);    // half of C's 0.5
    }

    @Test
    void playersLevelOnEveryCriterionShareARank() {
        List<StandingRow> rows = StandingsCalculator.standings(List.of(A, B, C),
                List.of(new PlayedGame(A, B, WHITE_WIN), new PlayedGame(C, A, BLACK_WIN), new PlayedGame(B, C, DRAW)));
        assertThat(rows).extracting(StandingRow::player).first().isEqualTo(A);
        assertThat(rows).extracting(StandingRow::rank).containsExactly(1, 2, 2);
    }

    @Test
    void headToHeadDecidesBeforeBuchholz() {
        // A and B both score 2; A beat B. C and D both score 1; D beat C.
        List<PlayedGame> games = List.of(
                new PlayedGame(A, B, WHITE_WIN), new PlayedGame(C, A, WHITE_WIN), new PlayedGame(A, D, WHITE_WIN),
                new PlayedGame(B, C, WHITE_WIN), new PlayedGame(D, B, BLACK_WIN), new PlayedGame(C, D, BLACK_WIN));
        List<StandingRow> rows = StandingsCalculator.standings(List.of(D, C, B, A), games);

        assertThat(rows).extracting(StandingRow::player).containsExactly(A, B, D, C);
        assertThat(rows).extracting(StandingRow::rank).containsExactly(1, 2, 3, 4);
        assertThat(byPlayer(rows).get(A).headToHead()).isEqualTo(1.0);
        assertThat(byPlayer(rows).get(B).headToHead()).isEqualTo(0.0);
    }

    @Test
    void buchholzDecidesWhenHeadToHeadIsLevel() {
        // Swiss, two rounds. 1 and 2 finish on 1.5 after drawing each other; 1 met the stronger opposition.
        List<PlayedGame> games = List.of(
                new PlayedGame(1L, 3L, WHITE_WIN), new PlayedGame(2L, 4L, WHITE_WIN),
                new PlayedGame(1L, 2L, DRAW), new PlayedGame(3L, 4L, WHITE_WIN));
        List<StandingRow> rows = StandingsCalculator.standings(List.of(4L, 3L, 2L, 1L), games);

        assertThat(rows).extracting(StandingRow::player).containsExactly(1L, 2L, 3L, 4L);
        assertThat(byPlayer(rows).get(1L).buchholz()).isEqualTo(2.5);
        assertThat(byPlayer(rows).get(2L).buchholz()).isEqualTo(1.5);
    }

    /**
     * Three players beat each other in a circle. Head-to-head must be computed inside the whole
     * tied group (1 point each), not pairwise, or the order depends on the sort's comparison order.
     */
    @Test
    void circularHeadToHeadIsATieNotAnArbitraryOrder() {
        List<PlayedGame> games = List.of(
                new PlayedGame(A, B, WHITE_WIN), new PlayedGame(B, C, WHITE_WIN), new PlayedGame(C, A, WHITE_WIN));
        List<StandingRow> forwards = StandingsCalculator.standings(List.of(A, B, C), games);
        List<StandingRow> backwards = StandingsCalculator.standings(List.of(C, B, A), games);

        assertThat(forwards).extracting(StandingRow::rank).containsExactly(1, 1, 1);
        assertThat(forwards).extracting(StandingRow::headToHead).containsOnly(1.0);
        assertThat(backwards).extracting(StandingRow::rank).containsExactly(1, 1, 1);
    }

    @Test
    void rankSkipsAfterASharedPlace() {
        // A and B draw twice and both beat C: ranks 1, 1, 3 (not 1, 1, 2).
        List<PlayedGame> games = List.of(
                new PlayedGame(A, B, DRAW), new PlayedGame(B, A, DRAW),
                new PlayedGame(A, C, WHITE_WIN), new PlayedGame(B, C, WHITE_WIN));
        assertThat(StandingsCalculator.standings(List.of(A, B, C), games)).extracting(StandingRow::rank)
                .containsExactly(1, 1, 3);
    }

    @Test
    void aByeScoresOnePointButIsNotAPlayedGame() {
        List<StandingRow> rows = StandingsCalculator.standings(List.of(A, B, C),
                List.of(new PlayedGame(A, B, WHITE_WIN), PlayedGame.bye(C)));
        StandingRow c = byPlayer(rows).get(C);

        assertThat(c.score()).isEqualTo(1.0);
        assertThat(c.played()).isZero();
        assertThat(c.wins()).isZero();
        assertThat(c.buchholz()).isZero();
        assertThat(c.sonnebornBerger()).isZero();
    }

    @Test
    void playersWithoutGamesAreListedLastWithZeroes() {
        List<StandingRow> rows = StandingsCalculator.standings(List.of(C, A, B), List.of(new PlayedGame(A, B, DRAW)));
        assertThat(rows).hasSize(3);
        assertThat(rows.get(2).player()).isEqualTo(C);
        assertThat(rows.get(2).score()).isZero();
        assertThat(rows.get(2).rank()).isEqualTo(3);
    }

    @Test
    void emptyTournamentRanksEveryoneFirst() {
        assertThat(StandingsCalculator.standings(List.of(A, B), List.of())).extracting(StandingRow::rank)
                .containsExactly(1, 1);
        assertThat(StandingsCalculator.standings(List.of(), List.of())).isEmpty();
    }

    @Test
    void halfPointsDoNotSufferFromFloatingPointDrift() {
        // Ten draws must compare equal to five wins and five losses on score.
        List<PlayedGame> games = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) {
            games.add(new PlayedGame(A, B, DRAW));
        }
        for (int i = 0; i < 5; i++) {
            games.add(new PlayedGame(C, D, WHITE_WIN));
            games.add(new PlayedGame(C, D, BLACK_WIN));
        }
        List<StandingRow> rows = StandingsCalculator.standings(List.of(A, B, C, D), games);
        assertThat(rows).extracting(StandingRow::score).containsOnly(5.0);
    }

    @Test
    void gamesInvolvingUnregisteredPlayersAreRejected() {
        assertThatThrownBy(() -> StandingsCalculator.standings(List.of(A, B), List.of(new PlayedGame(A, 99L, WHITE_WIN))))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
