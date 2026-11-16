package no.sjakkportal.chess;

import static no.sjakkportal.support.ChessAssertions.assertFailsWith;
import static no.sjakkportal.support.ChessAssertions.assertHandledGracefully;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Random;
import no.sjakkportal.common.error.ErrorCode;
import no.sjakkportal.rating.GameResult;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class PgnParsingTest {

    static final String SCHOLARS_MATE = "1. e4 e5 2. Qh5 Nc6 3. Bc4 Nf6 4. Qxf7# 1-0";
    /** Sam Loyd's ten-move stalemate. */
    static final String STALEMATE = "1. e3 a5 2. Qh5 Ra6 3. Qxa5 h5 4. h4 Rah6 5. Qxc7 f6 6. Qxd7+ Kf7 "
            + "7. Qxb7 Qd3 8. Qxb8 Qh7 9. Qxc8 Kg6 10. Qe6 1/2-1/2";
    static final String PROMOTION = "1. e4 d5 2. exd5 c6 3. dxc6 Nf6 4. cxb7 Nbd7 5. bxa8=Q *";

    @Nested
    class ValidGames {

        @Test
        void parsesMovesResultAndCheckmate() {
            ParsedGame game = ChessRules.parsePgn(SCHOLARS_MATE);

            assertThat(game.plyCount()).isEqualTo(7);
            assertThat(game.sanMoves()).containsExactly("e4", "e5", "Qh5", "Nc6", "Bc4", "Nf6", "Qxf7#");
            assertThat(game.uciMoves()).containsExactly("e2e4", "e7e5", "d1h5", "b8c6", "f1c4", "g8f6", "h5f7");
            assertThat(game.fens()).hasSize(7);
            assertThat(game.initialFen()).isEqualTo(ChessRules.START_FEN);
            assertThat(game.finalFen()).startsWith("r1bqkb1r/pppp1Qpp/2n2n2/4p3/2B1P3/8/PPPP1PPP/RNB1K1NR b KQkq");
            assertThat(game.declaredResult()).contains(GameResult.WHITE_WIN);
            assertThat(game.boardOutcome()).contains(GameResult.WHITE_WIN);
            assertThat(game.termination()).contains(Termination.CHECKMATE);
        }

        @Test
        void readsTagsAndIgnoresCommentsNagsAndVariations() {
            String pgn = """
                    [Event "Julesjakk 2026"]
                    [White "Bestefar"]
                    [Black "Oleander"]
                    [Result "1-0"]

                    1. e4 {kongebonden} e5 $1 2. Qh5 (2. Nf3 Nc6 3. Bb5) 2... Nc6 3. Bc4 Nf6?? ; en linjekommentar
                    4. Qxf7# 1-0
                    """;
            ParsedGame game = ChessRules.parsePgn(pgn);

            assertThat(game.plyCount()).isEqualTo(7);
            assertThat(game.tags()).containsEntry("Event", "Julesjakk 2026").containsEntry("White", "Bestefar");
            assertThat(game.declaredResult()).contains(GameResult.WHITE_WIN);
        }

        @Test
        void acceptsWindowsLineEndingsAndByteOrderMark() {
            ParsedGame game = ChessRules.parsePgn("﻿[Event \"x\"]\r\n\r\n1. e4 e5\r\n2. Nf3 *\r\n");
            assertThat(game.plyCount()).isEqualTo(3);
            assertThat(game.declaredResult()).isEmpty();
        }

        @Test
        void acceptsMovetextWithoutResultTerminator() {
            assertThat(ChessRules.parsePgn("1. d4 d5 2. c4").plyCount()).isEqualTo(3);
        }

        @Test
        void unfinishedGameHasNoOutcome() {
            ParsedGame game = ChessRules.parsePgn("1. e4 e5 2. Nf3 Nc6 *");
            assertThat(game.declaredResult()).isEmpty();
            assertThat(game.boardOutcome()).isEmpty();
            assertThat(game.termination()).isEmpty();
        }

        @Test
        void acceptsDigitZeroCastlingAndNormalisesIt() {
            ParsedGame game = ChessRules.parsePgn("1. e4 e5 2. Nf3 Nc6 3. Bc4 Bc5 4. 0-0 Nf6 *");
            assertThat(game.sanMoves().get(6)).isEqualTo("O-O");
            assertThat(game.uciMoves().get(6)).isEqualTo("e1g1");
        }

        @Test
        void startsFromFenTagWhenPresent() {
            String pgn = """
                    [SetUp "1"]
                    [FEN "4k3/8/8/8/8/8/4P3/4K3 w - - 0 1"]

                    1. e4 Kd7 *
                    """;
            ParsedGame game = ChessRules.parsePgn(pgn);
            assertThat(game.initialFen()).isEqualTo("4k3/8/8/8/8/8/4P3/4K3 w - - 0 1");
            assertThat(game.plyCount()).isEqualTo(2);
            assertThat(game.finalFen()).startsWith("8/3k4/8/8/4P3/8/8/4K3 w");
        }

        @Test
        void normalisedPgnRoundTrips() {
            ParsedGame first = ChessRules.parsePgn(SCHOLARS_MATE);
            ParsedGame second = ChessRules.parsePgn(first.normalizedPgn());
            assertThat(second.sanMoves()).isEqualTo(first.sanMoves());
            assertThat(second.declaredResult()).isEqualTo(first.declaredResult());
        }
    }

    @Nested
    class SpecialMoves {

        @Test
        void promotionIsRecordedWithThePieceInUci() {
            ParsedGame game = ChessRules.parsePgn(PROMOTION);
            assertThat(game.sanMoves().get(8)).isEqualTo("bxa8=Q");
            assertThat(game.uciMoves().get(8)).isEqualTo("b7a8q");
            assertThat(game.finalFen()).startsWith("Q1bqkb1r/");
        }

        @Test
        void underpromotionKeepsTheChosenPiece() {
            ParsedGame game = ChessRules.parsePgn(PROMOTION.replace("bxa8=Q", "bxa8=N"));
            assertThat(game.uciMoves().get(8)).isEqualTo("b7a8n");
            assertThat(game.finalFen()).startsWith("N1bqkb1r/");
        }

        @Test
        void promotionWithoutAPieceIsRejectedInsteadOfSilentlyQueening() {
            assertFailsWith(ErrorCode.PGN_ILLEGAL_MOVE, () -> ChessRules.parsePgn(PROMOTION.replace("bxa8=Q", "bxa8")));
        }

        @Test
        void promotingToAKingOrPawnIsRejected() {
            assertFailsWith(ErrorCode.PGN_ILLEGAL_MOVE, () -> ChessRules.parsePgn(PROMOTION.replace("bxa8=Q", "bxa8=K")));
            assertFailsWith(ErrorCode.PGN_ILLEGAL_MOVE, () -> ChessRules.parsePgn(PROMOTION.replace("bxa8=Q", "bxa8=P")));
        }

        @Test
        void enPassantRemovesTheCapturedPawn() {
            ParsedGame game = ChessRules.parsePgn("1. e4 a6 2. e5 d5 3. exd6 *");
            assertThat(game.uciMoves().get(4)).isEqualTo("e5d6");
            assertThat(game.finalFen()).startsWith("rnbqkbnr/1pp1pppp/p2P4/8/8/8/PPPP1PPP/RNBQKBNR b");
        }

        @Test
        void enPassantIsOnlyLegalImmediately() {
            assertFailsWith(ErrorCode.PGN_ILLEGAL_MOVE, () -> ChessRules.parsePgn("1. e4 a6 2. e5 d5 3. h3 h6 4. exd6 *"));
        }

        @Test
        void castlingAfterTheKingHasMovedIsIllegal() {
            assertFailsWith(ErrorCode.PGN_ILLEGAL_MOVE,
                    () -> ChessRules.parsePgn("1. e4 e5 2. Nf3 Nc6 3. Bc4 Bc5 4. Ke2 Nf6 5. Ke1 d6 6. O-O *"));
        }

        @Test
        void castlingThroughAnAttackedSquareIsIllegal() {
            // Black's bishop on a6 covers f1 along the open a6-f1 diagonal.
            assertFailsWith(ErrorCode.PGN_ILLEGAL_MOVE,
                    () -> ChessRules.parsePgn("1. e4 b6 2. g3 Ba6 3. Bg2 e6 4. Nf3 d6 5. O-O *"));
        }

        @Test
        void castlingOutOfCheckIsIllegal() {
            assertFailsWith(ErrorCode.PGN_ILLEGAL_MOVE,
                    () -> ChessRules.parsePgn("1. e4 e5 2. Nf3 Nc6 3. Bc4 Bc5 4. d3 Bb4+ 5. O-O *"));
        }
    }

    @Nested
    class DrawsAndGameEnd {

        @Test
        void detectsStalemate() {
            ParsedGame game = ChessRules.parsePgn(STALEMATE);
            assertThat(game.plyCount()).isEqualTo(19);
            assertThat(game.termination()).contains(Termination.STALEMATE);
            assertThat(game.boardOutcome()).contains(GameResult.DRAW);
        }

        @Test
        void stalemateDeclaredAsAWinIsAResultMismatch() {
            assertFailsWith(ErrorCode.PGN_RESULT_MISMATCH, () -> ChessRules.parsePgn(STALEMATE.replace("1/2-1/2", "1-0")));
        }

        @Test
        void checkmateDeclaredForTheWrongSideIsAResultMismatch() {
            assertFailsWith(ErrorCode.PGN_RESULT_MISMATCH, () -> ChessRules.parsePgn(SCHOLARS_MATE.replace("1-0", "0-1")));
            assertFailsWith(ErrorCode.PGN_RESULT_MISMATCH, () -> ChessRules.parsePgn(SCHOLARS_MATE.replace("1-0", "1/2-1/2")));
        }

        @Test
        void resultTagAndTerminatorMustAgree() {
            assertFailsWith(ErrorCode.PGN_RESULT_MISMATCH,
                    () -> ChessRules.parsePgn("[Result \"0-1\"]\n\n1. e4 e5 1-0"));
        }

        @Test
        void movesAfterCheckmateAreIllegal() {
            assertFailsWith(ErrorCode.PGN_ILLEGAL_MOVE, () -> ChessRules.parsePgn("1. e4 e5 2. Qh5 Nc6 3. Bc4 Nf6 4. Qxf7# Kxf7 *"));
        }

        @Test
        void threefoldRepetitionIsReportedButNotForced() {
            // The starting position occurs three times: move 0, after 2...Ng8 and after 4...Ng8.
            ParsedGame game = ChessRules.parsePgn("1. Nf3 Nf6 2. Ng1 Ng8 3. Nf3 Nf6 4. Ng1 Ng8 1/2-1/2");
            assertThat(game.termination()).contains(Termination.THREEFOLD_REPETITION);
            // A repetition draw has to be claimed, so it does not dictate the result...
            assertThat(game.boardOutcome()).isEmpty();
        }

        @Test
        void playersMayPlayOnAfterAnUnclaimedRepetition() {
            ParsedGame game = ChessRules.parsePgn("1. Nf3 Nf6 2. Ng1 Ng8 3. Nf3 Nf6 4. Ng1 Ng8 5. e4 e5 1-0");
            assertThat(game.plyCount()).isEqualTo(10);
            assertThat(game.termination()).isEmpty();
            assertThat(game.declaredResult()).contains(GameResult.WHITE_WIN);
        }

        @Test
        void twoRepetitionsAreNotThree() {
            ParsedGame game = ChessRules.parsePgn("1. Nf3 Nf6 2. Ng1 Ng8 *");
            assertThat(game.termination()).isEmpty();
        }

        @Test
        void repetitionRequiresTheSameCastlingRights() {
            // The king shuffle restores the piece placement but White has lost castling rights,
            // so the first occurrence does not count: only two identical positions exist.
            ParsedGame game = ChessRules.parsePgn(
                    "1. e4 e5 2. Ke2 Ke7 3. Ke1 Ke8 4. Ke2 Ke7 5. Ke1 Ke8 *");
            assertThat(game.termination()).isEmpty();
        }

        @Test
        void bareKingsAreADrawByInsufficientMaterial() {
            String pgn = """
                    [SetUp "1"]
                    [FEN "4k3/8/8/8/8/8/4p3/4K3 w - - 0 1"]

                    1. Kxe2 1/2-1/2
                    """;
            ParsedGame game = ChessRules.parsePgn(pgn);
            assertThat(game.termination()).contains(Termination.INSUFFICIENT_MATERIAL);
            assertThat(game.boardOutcome()).contains(GameResult.DRAW);
        }
    }

    @Nested
    class HostileInput {

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "\n\n", "hello world", "1.", "[Event \"only tags\"]", "{ unterminated comment",
                "1. e4 (1. d4", "1. e4 e5 )", "[Event \"unterminated", "1. e4 e5 2-0", "1. Zz9 *", "\u0000\u0000\u0000"})
        void garbageIsMalformed(String pgn) {
            assertFailsWith(ErrorCode.PGN_MALFORMED, () -> ChessRules.parsePgn(pgn));
        }

        @ParameterizedTest
        @ValueSource(strings = {"1. e4 e5 2. Ke3 *", "1. e5 *", "1. e4 e5 2. Nf3 Nc6 3. Nxe5 Nxe5 4. Nf3 *",
                "1. e4 e4 *", "1. O-O *", "1. e4 e5 2. Bb5 a6 3. Bxa6 bxa6 4. Bc4 *"})
        void illegalMovesAreRejected(String pgn) {
            assertFailsWith(ErrorCode.PGN_ILLEGAL_MOVE, () -> ChessRules.parsePgn(pgn));
        }

        @Test
        void ambiguousMoveWithoutDisambiguationIsRejected() {
            // Both knights can reach d2.
            assertFailsWith(ErrorCode.PGN_ILLEGAL_MOVE, () -> ChessRules.parsePgn("1. Nf3 d5 2. d3 d4 3. Nd2 *"));
        }

        @Test
        void leavingTheKingInCheckIsRejected() {
            assertFailsWith(ErrorCode.PGN_ILLEGAL_MOVE, () -> ChessRules.parsePgn("1. e4 e5 2. Qh5 Ke7 3. Qxe5+ a6 *"));
        }

        @Test
        void oversizedPgnIsRejectedBeforeParsing() {
            String huge = "1. e4 e5 " + "{x}".repeat(ChessRules.MAX_PGN_LENGTH / 3 + 1);
            assertFailsWith(ErrorCode.PGN_TOO_LARGE, () -> ChessRules.parsePgn(huge));
        }

        @Test
        void pgnAtExactlyTheSizeLimitIsAccepted() {
            String prefix = "1. e4 e5 {";
            String exact = prefix + "x".repeat(ChessRules.MAX_PGN_LENGTH - prefix.length() - 3) + "} *";
            assertThat(exact).hasSize(ChessRules.MAX_PGN_LENGTH);
            assertThat(ChessRules.parsePgn(exact).plyCount()).isEqualTo(2);
        }

        @Test
        void multipleGamesAreRejected() {
            assertFailsWith(ErrorCode.PGN_MULTIPLE_GAMES,
                    () -> ChessRules.parsePgn("[Event \"a\"]\n\n1. e4 e5 1-0\n\n[Event \"b\"]\n\n1. d4 d5 0-1\n"));
        }

        @Test
        void invalidFenTagIsReportedAsFenError() {
            assertFailsWith(ErrorCode.FEN_MALFORMED,
                    () -> ChessRules.parsePgn("[SetUp \"1\"]\n[FEN \"8/8/8/8/8/8/8/8 w - - 0 1\"]\n\n1. e4 *"));
        }

        @Test
        void markupInTagsAndCommentsNeverSurvivesIntoStoredData() {
            String pgn = """
                    [Event "<script>alert('xss')</script>"]
                    [White "Kari<img src=x onerror=alert(1)>"]

                    1. e4 {<script>alert(document.cookie)</script>} e5 *
                    """;
            ParsedGame game = ChessRules.parsePgn(pgn);

            assertThat(game.plyCount()).isEqualTo(2);
            assertThat(game.normalizedPgn()).doesNotContain("<", ">", "onerror", "document.cookie");
            assertThat(game.tags().values()).allSatisfy(value -> assertThat(value).doesNotContain("<", ">"));
        }

        @Test
        void controlCharactersAndOverlongTagsAreNeutralised() {
            String pgn = "[Event \"a\u0007b\u001Bc\"]\n[White \"" + "W".repeat(5000) + "\"]\n\n1. e4 *";
            ParsedGame game = ChessRules.parsePgn(pgn);
            assertThat(game.tags().get("Event")).doesNotContain("\u0007", "\u001B");
            assertThat(game.tags().get("White").length()).isLessThanOrEqualTo(255);
        }

        @Test
        void unknownTagNamesCannotSmuggleArbitraryKeys() {
            ParsedGame game = ChessRules.parsePgn("[__proto__ \"x\"]\n[Bad Name \"y\"]\n[Event \"ok\"]\n\n1. e4 *");
            assertThat(game.tags().keySet()).allSatisfy(key -> assertThat(key).matches("[A-Za-z0-9_]{1,40}"));
            assertThat(game.tags()).doesNotContainKey("__proto__");
        }

        @Test
        void deeplyNestedVariationsCannotOverflowTheStack() {
            String nested = "1. e4 " + "(1. d4 ".repeat(20_000) + ")".repeat(20_000) + " e5 *";
            assertHandledGracefully("20k nested variations", () -> ChessRules.parsePgn(nested));
        }

        @Test
        void absurdlyLongTokensAreHandled() {
            assertHandledGracefully("long token", () -> ChessRules.parsePgn("1. " + "N".repeat(50_000) + " *"));
            assertHandledGracefully("huge move number", () -> ChessRules.parsePgn("99999999999999999999999. e4 *"));
        }

        /** Whatever a client sends, the parser may only answer with a result or a cataloged error. */
        @Test
        void randomInputNeverEscapesTheErrorCatalog() {
            String alphabet = "abcdefgh12345678NBRQKOx+#=-. \n()[]{}\"$;*/\\\u0000æøå😀";
            Random random = new Random(20261224L);
            for (int i = 0; i < 3_000; i++) {
                StringBuilder input = new StringBuilder();
                int length = random.nextInt(120);
                for (int j = 0; j < length; j++) {
                    input.append(alphabet.charAt(random.nextInt(alphabet.length())));
                }
                String pgn = input.toString();
                assertHandledGracefully(pgn, () -> ChessRules.parsePgn(pgn));
            }
        }

        /** Mutating a valid game hits code paths that pure noise never reaches. */
        @Test
        void mutatedValidGamesNeverEscapeTheErrorCatalog() {
            String base = "[Event \"x\"]\n\n" + STALEMATE;
            Random random = new Random(42L);
            String noise = "x=+#O-018NQK()[]{}\" \n";
            for (int i = 0; i < 3_000; i++) {
                StringBuilder mutated = new StringBuilder(base);
                int edits = 1 + random.nextInt(3);
                for (int e = 0; e < edits; e++) {
                    int at = random.nextInt(mutated.length());
                    switch (random.nextInt(3)) {
                        case 0 -> mutated.deleteCharAt(at);
                        case 1 -> mutated.insert(at, noise.charAt(random.nextInt(noise.length())));
                        default -> mutated.setCharAt(at, noise.charAt(random.nextInt(noise.length())));
                    }
                }
                String pgn = mutated.toString();
                assertHandledGracefully(pgn, () -> ChessRules.parsePgn(pgn));
            }
        }
    }
}
