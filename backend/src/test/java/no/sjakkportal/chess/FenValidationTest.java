package no.sjakkportal.chess;

import static no.sjakkportal.support.ChessAssertions.assertFailsWith;
import static org.assertj.core.api.Assertions.assertThat;

import no.sjakkportal.common.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class FenValidationTest {

    @ParameterizedTest
    @ValueSource(strings = {
            ChessRules.START_FEN,
            "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1",
            "4k3/8/8/8/8/8/4P3/4K3 w - - 0 1",
            "r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 12 40",
            "8/8/8/8/8/5k2/8/5K1q w - - 0 1"})
    void acceptsLegalPositions(String fen) {
        assertThat(ChessRules.validateFen(fen)).isEqualTo(fen);
    }

    @Test
    void normalisesWhitespace() {
        assertThat(ChessRules.validateFen("  4k3/8/8/8/8/8/4P3/4K3   w  -  -  0   1 \n"))
                .isEqualTo("4k3/8/8/8/8/8/4P3/4K3 w - - 0 1");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            "   ",
            "not a fen",
            "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR",                    // fields missing
            "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1 extra", // too many fields
            "8/8/8/8/8/8/8/8 w - - 0 1",                                      // no kings
            "4k3/8/8/8/8/8/8/8 w - - 0 1",                                    // white king missing
            "4k3/8/8/8/8/8/8/3KK3 w - - 0 1",                                 // two white kings
            "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP w KQkq - 0 1",                // seven ranks
            "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR/8 w KQkq - 0 1",     // nine ranks
            "rnbqkbnr/ppppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",      // nine files
            "rnbqkbnr/ppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",        // seven files
            "rnbqkbnr/pppppppp/9/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",       // digit 9
            "rnbqkbnr/pppppppp/44/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1x",     // junk counter
            "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQXBNR w KQkq - 0 1",       // unknown piece
            "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR x KQkq - 0 1",       // bad side to move
            "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkqX - 0 1",      // bad castling field
            "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq e5 0 1",      // impossible en passant square
            "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - -1 1",      // negative half-move clock
            "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 0",       // move number zero
            "P3k3/8/8/8/8/8/8/4K3 w - - 0 1",                                 // pawn on the last rank
            "4k3/8/8/8/8/8/8/p3K3 w - - 0 1",                                 // pawn on the first rank
            "4k3/4Q3/8/8/8/8/8/4K3 w - - 0 1",                                // side not to move is in check
            "4k3/8/8/8/8/8/8/4K2R w Kq - 0 1",                                // castling right without the rook
            "4kK2/8/8/8/8/8/8/8 w - - 0 1"})                                  // kings adjacent
    void rejectsInvalidPositions(String fen) {
        assertFailsWith(ErrorCode.FEN_MALFORMED, () -> ChessRules.validateFen(fen));
    }

    @Test
    void rejectsOverlongInputWithoutParsingIt() {
        assertFailsWith(ErrorCode.FEN_MALFORMED, () -> ChessRules.validateFen("8/".repeat(100_000)));
    }
}
