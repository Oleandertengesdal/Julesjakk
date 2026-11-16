package no.sjakkportal.chess;

import static no.sjakkportal.support.ChessAssertions.assertFailsWith;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import no.sjakkportal.common.error.ErrorCode;
import no.sjakkportal.rating.GameResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** Move-by-move entry, as used when a game is relayed live to spectators and TV mode. */
class LiveMoveTest {

    private static final String START = ChessRules.START_FEN;

    @Test
    void acceptsSanAndReturnsBothNotations() {
        MoveOutcome outcome = ChessRules.applyMove(START, List.of(), "e4");
        assertThat(outcome.san()).isEqualTo("e4");
        assertThat(outcome.uci()).isEqualTo("e2e4");
        assertThat(outcome.fenAfter()).startsWith("rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq");
        assertThat(outcome.check()).isFalse();
        assertThat(outcome.boardOutcome()).isEmpty();
        assertThat(outcome.termination()).isEmpty();
    }

    @Test
    void acceptsUciAsSentByTheBoardComponent() {
        MoveOutcome outcome = ChessRules.applyMove(START, List.of("e4", "e5"), "g1f3");
        assertThat(outcome.san()).isEqualTo("Nf3");
        assertThat(outcome.uci()).isEqualTo("g1f3");
    }

    @Test
    void uciPromotionCarriesThePiece() {
        List<String> history = List.of("e4", "d5", "exd5", "c6", "dxc6", "Nf6", "cxb7", "Nbd7");
        MoveOutcome outcome = ChessRules.applyMove(START, history, "b7a8n");
        assertThat(outcome.san()).isEqualTo("bxa8=N");
        assertThat(outcome.fenAfter()).startsWith("N1bqkb1r/");
    }

    @Test
    void uciPromotionWithoutAPieceIsIllegal() {
        List<String> history = List.of("e4", "d5", "exd5", "c6", "dxc6", "Nf6", "cxb7", "Nbd7");
        assertFailsWith(ErrorCode.GAME_ILLEGAL_MOVE, () -> ChessRules.applyMove(START, history, "b7a8"));
    }

    @Test
    void detectsCheckmateAndNormalisesTheSuffix() {
        MoveOutcome outcome = ChessRules.applyMove(START, List.of("f3", "e5", "g4"), "Qh4");
        assertThat(outcome.san()).isEqualTo("Qh4#");
        assertThat(outcome.check()).isTrue();
        assertThat(outcome.boardOutcome()).contains(GameResult.BLACK_WIN);
        assertThat(outcome.termination()).contains(Termination.CHECKMATE);
    }

    @Test
    void marksCheckWithoutEndingTheGame() {
        MoveOutcome check = ChessRules.applyMove(START, List.of("e4", "d5"), "Bb5");
        assertThat(check.san()).isEqualTo("Bb5+");
        assertThat(check.check()).isTrue();
        assertThat(check.boardOutcome()).isEmpty();
    }

    @Test
    void reportsThreefoldRepetitionFromTheHistory() {
        List<String> history = List.of("Nf3", "Nf6", "Ng1", "Ng8", "Nf3", "Nf6", "Ng1");
        MoveOutcome outcome = ChessRules.applyMove(START, history, "Ng8");
        assertThat(outcome.termination()).contains(Termination.THREEFOLD_REPETITION);
        assertThat(outcome.boardOutcome()).isEmpty();
    }

    @Test
    void movingOutOfTurnIsIllegal() {
        // It is Black's move; e2e4-style input for a white piece must fail.
        assertFailsWith(ErrorCode.GAME_ILLEGAL_MOVE, () -> ChessRules.applyMove(START, List.of("e4"), "d2d4"));
    }

    @Test
    void noMovesAfterCheckmate() {
        assertFailsWith(ErrorCode.GAME_ILLEGAL_MOVE,
                () -> ChessRules.applyMove(START, List.of("f3", "e5", "g4", "Qh4#"), "a3"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "e5", "e2e5", "Ke2", "O-O", "z9z9", "e2e4e5", "<script>", "e2e4; DROP TABLE game",
            "\u0000", "e2-e4-e5", "a1a1"})
    void illegalOrGarbageMovesAreRejected(String move) {
        assertFailsWith(ErrorCode.GAME_ILLEGAL_MOVE, () -> ChessRules.applyMove(START, List.of(), move));
    }

    @Test
    void corruptStoredHistoryIsAnInvalidStateNotACrash() {
        assertFailsWith(ErrorCode.GAME_INVALID_STATE, () -> ChessRules.applyMove(START, List.of("e4", "Ke3"), "d4"));
    }

    @Test
    void invalidInitialFenIsReported() {
        assertFailsWith(ErrorCode.FEN_MALFORMED, () -> ChessRules.applyMove("garbage", List.of(), "e4"));
    }
}
