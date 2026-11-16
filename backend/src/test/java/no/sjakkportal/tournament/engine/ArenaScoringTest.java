package no.sjakkportal.tournament.engine;

import static no.sjakkportal.tournament.engine.ArenaScoring.Outcome.DRAW;
import static no.sjakkportal.tournament.engine.ArenaScoring.Outcome.LOSS;
import static no.sjakkportal.tournament.engine.ArenaScoring.Outcome.WIN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import no.sjakkportal.tournament.engine.ArenaScoring.ArenaState;
import org.junit.jupiter.api.Test;

class ArenaScoringTest {

    @Test
    void noGamesNoPoints() {
        assertThat(ArenaScoring.score(List.of())).isEqualTo(new ArenaState(0, 0));
    }

    @Test
    void baseScoresAreTwoOneZero() {
        assertThat(ArenaScoring.score(List.of(WIN)).points()).isEqualTo(2);
        assertThat(ArenaScoring.score(List.of(DRAW)).points()).isEqualTo(1);
        assertThat(ArenaScoring.score(List.of(LOSS)).points()).isZero();
    }

    @Test
    void thirdConsecutiveWinIsDoubled() {
        assertThat(ArenaScoring.score(List.of(WIN, WIN, WIN))).isEqualTo(new ArenaState(8, 3));
        assertThat(ArenaScoring.score(List.of(WIN, WIN, WIN, WIN))).isEqualTo(new ArenaState(12, 4));
    }

    @Test
    void secondWinIsNotYetDoubled() {
        assertThat(ArenaScoring.score(List.of(WIN, WIN))).isEqualTo(new ArenaState(4, 2));
    }

    @Test
    void drawWhileOnFireIsDoubledAndEndsTheStreak() {
        assertThat(ArenaScoring.score(List.of(WIN, WIN, DRAW))).isEqualTo(new ArenaState(6, 0));
        // Streak is gone: the next win is worth 2 again.
        assertThat(ArenaScoring.score(List.of(WIN, WIN, DRAW, WIN))).isEqualTo(new ArenaState(8, 1));
    }

    @Test
    void lossEndsTheStreakAndScoresNothing() {
        assertThat(ArenaScoring.score(List.of(WIN, WIN, LOSS))).isEqualTo(new ArenaState(4, 0));
        assertThat(ArenaScoring.score(List.of(WIN, WIN, LOSS, WIN, WIN, WIN))).isEqualTo(new ArenaState(12, 3));
    }

    @Test
    void drawsNeverBuildAStreak() {
        assertThat(ArenaScoring.score(List.of(DRAW, DRAW, DRAW, DRAW))).isEqualTo(new ArenaState(4, 0));
        assertThat(ArenaScoring.score(List.of(WIN, DRAW, WIN, DRAW, WIN))).isEqualTo(new ArenaState(8, 1));
    }

    @Test
    void applyingOneGameAtATimeMatchesScoringTheWholeList() {
        List<ArenaScoring.Outcome> games = List.of(WIN, WIN, WIN, DRAW, LOSS, WIN, WIN, WIN, WIN, LOSS, DRAW);
        ArenaState state = new ArenaState(0, 0);
        for (ArenaScoring.Outcome outcome : games) {
            state = ArenaScoring.apply(state, outcome);
        }
        assertThat(state).isEqualTo(ArenaScoring.score(games));
    }

    @Test
    void rejectsMissingOutcome() {
        assertThatThrownBy(() -> ArenaScoring.apply(new ArenaState(0, 0), null))
                .isInstanceOfAny(NullPointerException.class, IllegalArgumentException.class);
    }
}
