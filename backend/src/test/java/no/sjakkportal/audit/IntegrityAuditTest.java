package no.sjakkportal.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import no.sjakkportal.common.error.ErrorCode;
import no.sjakkportal.support.ApiIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Audit: the rules that keep ratings and tournaments honest, from the brief and docs/ARCHITECTURE.md.
 * Written before re-reading the implementation.
 */
class IntegrityAuditTest extends ApiIntegrationTest {

    private long kari;
    private long ola;
    private long per;
    private String admin;
    private String kariT;
    private String olaT;
    private String perT;

    @BeforeEach
    void family() throws Exception {
        createAdmin("admin");
        kari = createPlayer("kari");
        ola = createPlayer("ola");
        per = createPlayer("per");
        admin = login("admin");
        kariT = login("kari");
        olaT = login("ola");
        perT = login("per");
    }

    private MvcResult perform(MockHttpServletRequestBuilder b) throws Exception {
        return mvc.perform(b).andReturn();
    }

    private static String code(MvcResult r) throws Exception {
        Matcher m = Pattern.compile("\"code\"\\s*:\\s*\"([^\"]+)\"").matcher(r.getResponse().getContentAsString());
        return m.find() ? m.group(1) : "status " + r.getResponse().getStatus();
    }

    private MvcResult postAs(String token, String path, Object body) throws Exception {
        return perform(as(jsonPost(path, body == null ? Map.of() : body), token));
    }

    private Map<String, Object> game(long white, long black, String result) {
        return new HashMap<>(Map.of("whiteId", white, "blackId", black, "result", result, "timeControl", "5+3"));
    }

    private long register(String token, Map<String, Object> body) throws Exception {
        MvcResult r = postAs(token, "/api/games", body);
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(201);
        return body(r).get("id").asLong();
    }

    private String status(long gameId) {
        return jdbc.queryForObject("SELECT status FROM game WHERE id = ?", String.class, gameId);
    }

    // --- confirmation --------------------------------------------------------------------------

    @Nested
    class Confirmation {

        @Test
        void onlyTheOpponentOrAnAdminCanConfirm() throws Exception {
            long g = register(kariT, game(kari, ola, "WHITE_WIN"));
            assertThat(code(postAs(kariT, "/api/games/" + g + "/confirm", null))).isEqualTo(ErrorCode.GAME_SELF_CONFIRMATION.code());
            assertThat(code(postAs(perT, "/api/games/" + g + "/confirm", null))).isEqualTo(ErrorCode.GAME_NOT_PARTICIPANT.code());
            assertThat(rating(kari, "BLITZ")).isEqualTo(1200);
            assertThat(postAs(olaT, "/api/games/" + g + "/confirm", null).getResponse().getStatus()).isEqualTo(200);
            assertThat(rating(kari, "BLITZ")).isEqualTo(1220);
            assertThat(rating(ola, "BLITZ")).isEqualTo(1180);
        }

        @Test
        void aThirdPlayerCannotRegisterOrRejectOtherPeoplesGames() throws Exception {
            assertThat(code(postAs(perT, "/api/games", game(kari, ola, "WHITE_WIN")))).isEqualTo(ErrorCode.GAME_NOT_PARTICIPANT.code());
            long g = register(kariT, game(kari, ola, "WHITE_WIN"));
            assertThat(code(postAs(perT, "/api/games/" + g + "/reject", Map.of()))).isEqualTo(ErrorCode.GAME_NOT_PARTICIPANT.code());
            assertThat(status(g)).isEqualTo("PENDING");
        }

        @Test
        void aGameIsRatedExactlyOnceEvenWhenConfirmedTwiceAtTheSameMoment() throws Exception {
            long g = register(kariT, game(kari, ola, "WHITE_WIN"));
            List<Callable<Integer>> both = List.of(
                    () -> postAs(olaT, "/api/games/" + g + "/confirm", null).getResponse().getStatus(),
                    () -> postAs(admin, "/api/games/" + g + "/confirm", null).getResponse().getStatus());
            List<Integer> statuses = runConcurrently(both);
            assertThat(statuses).containsOnlyOnce(200);
            assertThat(rating(kari, "BLITZ")).isEqualTo(1220);
            assertThat(ratedGames(kari, "BLITZ")).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM rating_history WHERE game_id = ?", Integer.class, g)).isEqualTo(2);
        }

        @Test
        void finishedGamesCannotBeRejectedAndRejectedGamesCannotBeConfirmed() throws Exception {
            long finished = register(admin, game(kari, ola, "WHITE_WIN"));
            assertThat(status(finished)).isEqualTo("FINISHED");
            assertThat(code(postAs(olaT, "/api/games/" + finished + "/reject", Map.of()))).isEqualTo(ErrorCode.GAME_INVALID_STATE.code());
            long rejected = register(kariT, game(kari, ola, "WHITE_WIN"));
            postAs(olaT, "/api/games/" + rejected + "/reject", Map.of("reason", "Feil resultat"));
            assertThat(code(postAs(admin, "/api/games/" + rejected + "/confirm", null))).isEqualTo(ErrorCode.GAME_INVALID_STATE.code());
            assertThat(rating(kari, "BLITZ")).isEqualTo(1220);  // only the admin's game counted
        }

        @Test
        void theReporterCanWithdrawTheirOwnReport() throws Exception {
            long g = register(kariT, game(kari, ola, "WHITE_WIN"));
            assertThat(postAs(kariT, "/api/games/" + g + "/reject", Map.of()).getResponse().getStatus()).isEqualTo(200);
            assertThat(status(g)).isEqualTo("REJECTED");
        }

        @Test
        void unratedGamesNeverMoveRatings() throws Exception {
            Map<String, Object> body = game(kari, ola, "WHITE_WIN");
            body.put("rated", false);
            long g = register(kariT, body);
            postAs(olaT, "/api/games/" + g + "/confirm", null);
            assertThat(status(g)).isEqualTo("FINISHED");
            assertThat(rating(kari, "BLITZ")).isEqualTo(1200);
            assertThat(ratedGames(kari, "BLITZ")).isZero();
        }

        @Test
        void clientsCannotSetTheStatusOrWhoConfirmed() throws Exception {
            Map<String, Object> body = game(kari, ola, "WHITE_WIN");
            body.put("status", "FINISHED");
            body.put("confirmedById", ola);
            body.put("registeredById", ola);
            long g = register(kariT, body);
            assertThat(status(g)).isEqualTo("PENDING");
            assertThat(jdbc.queryForObject("SELECT registered_by FROM game WHERE id = ?", Long.class, g)).isEqualTo(kari);
        }

        @Test
        void gamesAgainstAPlayerWithoutLoginNeedAnAdmin() throws Exception {
            long lille = createManagedPlayer("lillebror");
            long g = register(kariT, game(kari, lille, "BLACK_WIN"));
            assertThat(status(g)).isEqualTo("PENDING");
            assertThat(code(postAs(kariT, "/api/games/" + g + "/confirm", null))).isEqualTo(ErrorCode.GAME_SELF_CONFIRMATION.code());
            assertThat(postAs(admin, "/api/games/" + g + "/confirm", null).getResponse().getStatus()).isEqualTo(200);
            assertThat(rating(lille, "BLITZ")).isGreaterThan(1200);
        }

        @Test
        void pendingResultsShowUpForTheOpponentAndTheAdminOnly() throws Exception {
            long g = register(kariT, game(kari, ola, "DRAW"));
            assertThat(body(perform(as(get("/api/games/pending"), olaT))).toString()).contains("\"id\":" + g);
            assertThat(body(perform(as(get("/api/games/pending"), kariT))).toString()).doesNotContain("\"id\":" + g);
            assertThat(body(perform(as(get("/api/admin/games/pending"), admin))).toString()).contains("\"id\":" + g);
        }
    }

    // --- registration rules --------------------------------------------------------------------

    @Nested
    class Registration {

        @Test
        void aPlayerCannotPlayThemselves() throws Exception {
            assertThat(code(postAs(kariT, "/api/games", game(kari, kari, "DRAW")))).isEqualTo(ErrorCode.GAME_SAME_PLAYER.code());
        }

        @Test
        void gamesCannotBeDatedInTheFuture() throws Exception {
            Map<String, Object> body = game(kari, ola, "DRAW");
            body.put("playedAt", Instant.now().plus(3, ChronoUnit.DAYS).toString());
            assertThat(code(postAs(kariT, "/api/games", body))).isEqualTo(ErrorCode.VALIDATION_FAILED.code());
        }

        @Test
        void nonsenseTimeControlsAndContradictoryCategoriesAreRefused() throws Exception {
            for (String tc : List.of("abc", "5+", "+3", "99999+0", "5+999", "-5+3", "5+3+1")) {
                Map<String, Object> body = game(kari, ola, "DRAW");
                body.put("timeControl", tc);
                assertThat(code(postAs(kariT, "/api/games", body))).as(tc)
                        .isIn(ErrorCode.GAME_INVALID_TIME_CONTROL.code(), ErrorCode.VALIDATION_FAILED.code());
            }
            Map<String, Object> contradiction = game(kari, ola, "DRAW");
            contradiction.put("category", "CLASSICAL");
            assertThat(code(postAs(kariT, "/api/games", contradiction))).isEqualTo(ErrorCode.GAME_INVALID_TIME_CONTROL.code());
        }

        @Test
        void unknownPlayersAreRefused() throws Exception {
            assertThat(code(postAs(admin, "/api/games", game(kari, 999_999, "DRAW")))).isEqualTo(ErrorCode.USER_NOT_FOUND.code());
        }

        @Test
        void theSameIdempotencyKeyReturnsTheSameGameButNeverSomeoneElses() throws Exception {
            String key = "3f2b8c1e-1111-4222-8333-444455556666";
            long first = body(perform(as(jsonPost("/api/games", game(kari, ola, "DRAW")), kariT).header("Idempotency-Key", key))).get("id").asLong();
            long again = body(perform(as(jsonPost("/api/games", game(kari, ola, "DRAW")), kariT).header("Idempotency-Key", key))).get("id").asLong();
            assertThat(again).isEqualTo(first);
            MvcResult other = perform(as(jsonPost("/api/games", game(per, ola, "WHITE_WIN")), perT).header("Idempotency-Key", key));
            if (other.getResponse().getStatus() < 300) {
                JsonNode b = body(other);
                assertThat(b.get("id").asLong()).isNotEqualTo(first);
                assertThat(b.at("/white/id").asLong()).isEqualTo(per);
            } else {
                assertThat(other.getResponse().getStatus()).isBetween(400, 499);
            }
            assertThat(jdbc.queryForObject("SELECT count(*) FROM game WHERE white_id = ?", Integer.class, kari)).isEqualTo(1);
        }

        @Test
        void aPgnThatContradictsTheResultIsRefused() throws Exception {
            Map<String, Object> body = game(kari, ola, "BLACK_WIN");
            body.put("pgn", "1. e4 e5 2. Bc4 Nc6 3. Qh5 Nf6 4. Qxf7# 1-0");
            assertThat(code(postAs(kariT, "/api/games", body))).isEqualTo(ErrorCode.PGN_RESULT_MISMATCH.code());
        }
    }

    // --- live games ----------------------------------------------------------------------------

    @Nested
    class LiveGames {

        private long start() throws Exception {
            MvcResult r = postAs(kariT, "/api/games/live", Map.of("whiteId", kari, "blackId", ola, "timeControl", "15+10"));
            assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isBetween(200, 201);
            return body(r).get("id").asLong();
        }

        private MvcResult move(String token, long g, String move, int ply) throws Exception {
            return postAs(token, "/api/games/" + g + "/moves", Map.of("move", move, "expectedPly", ply));
        }

        @Test
        void outsidersCannotMoveFinishOrAbort() throws Exception {
            long g = start();
            assertThat(code(move(perT, g, "e4", 0))).isEqualTo(ErrorCode.GAME_NOT_PARTICIPANT.code());
            assertThat(code(postAs(perT, "/api/games/" + g + "/finish", Map.of("result", "BLACK_WIN")))).isEqualTo(ErrorCode.GAME_NOT_PARTICIPANT.code());
            assertThat(code(postAs(perT, "/api/games/" + g + "/abort", null))).isEqualTo(ErrorCode.GAME_NOT_PARTICIPANT.code());
            assertThat(status(g)).isEqualTo("LIVE");
        }

        @Test
        void movesAreCheckedForLegalityAndForAStalePosition() throws Exception {
            long g = start();
            assertThat(code(move(kariT, g, "e5", 0))).isEqualTo(ErrorCode.GAME_ILLEGAL_MOVE.code());
            assertThat(code(move(kariT, g, "Ke2", 0))).isEqualTo(ErrorCode.GAME_ILLEGAL_MOVE.code());
            assertThat(code(move(kariT, g, "zz9", 0))).isEqualTo(ErrorCode.GAME_ILLEGAL_MOVE.code());
            assertThat(move(kariT, g, "e4", 0).getResponse().getStatus()).isEqualTo(200);
            assertThat(code(move(olaT, g, "e5", 0))).isEqualTo(ErrorCode.GAME_CONCURRENT_UPDATE.code());
            assertThat(code(move(olaT, g, "e5", 7))).isEqualTo(ErrorCode.GAME_CONCURRENT_UPDATE.code());
        }

        @Test
        void aFinishedLiveGameNeedsTheOpponentAndThenAcceptsNoMoreMoves() throws Exception {
            long g = start();
            move(kariT, g, "e4", 0);
            postAs(kariT, "/api/games/" + g + "/finish", Map.of("result", "WHITE_WIN", "termination", "RESIGNATION"));
            assertThat(status(g)).isEqualTo("PENDING");
            assertThat(code(move(olaT, g, "e5", 1))).isEqualTo(ErrorCode.GAME_INVALID_STATE.code());
            assertThat(rating(kari, "RAPID")).isEqualTo(1200);
            postAs(olaT, "/api/games/" + g + "/confirm", null);
            assertThat(rating(kari, "RAPID")).isEqualTo(1220);
        }

        @Test
        void finishingAgainstTheBoardIsRefused() throws Exception {
            long g = start();
            // Fool's mate: Black has mated; the game ends by itself and cannot be "finished" as a White win.
            int ply = 0;
            for (String m : List.of("f3", "e5", "g4", "Qh4#")) {
                move(ply % 2 == 0 ? kariT : olaT, g, m, ply++);
            }
            assertThat(status(g)).isIn("PENDING", "FINISHED");
            assertThat(jdbc.queryForObject("SELECT result FROM game WHERE id = ?", String.class, g)).isEqualTo("BLACK_WIN");
            assertThat(code(postAs(kariT, "/api/games/" + g + "/finish", Map.of("result", "WHITE_WIN")))).isEqualTo(ErrorCode.GAME_INVALID_STATE.code());
        }
    }

    // --- analysis ------------------------------------------------------------------------------

    @Nested
    class Analysis {

        private long finishedWithMoves() throws Exception {
            Map<String, Object> body = game(kari, ola, "WHITE_WIN");
            body.put("pgn", "1. e4 e5 2. Bc4 Nc6 3. Qh5 Nf6 4. Qxf7# 1-0");
            return register(admin, body);
        }

        private Map<String, Object> analysis(int moves, double accuracy) {
            List<Map<String, Object>> list = new java.util.ArrayList<>();
            for (int i = 0; i < moves; i++) {
                list.add(Map.of("ply", i + 1, "san", "x", "classification", "BEST", "evalBefore", 20, "evalAfter", 20, "winBefore", 50.0,
                        "winAfter", 50.0, "accuracy", accuracy, "bestMove", "e2e4"));
            }
            return Map.of("engine", "Stockfish 19 (lite)", "depth", 14, "whiteAccuracy", accuracy, "blackAccuracy", accuracy,
                    "whiteAcpl", 10, "blackAcpl", 10, "moves", list);
        }

        @Test
        void outsidersCannotWriteAnAnalysis() throws Exception {
            long g = finishedWithMoves();
            MvcResult r = perform(as(put("/api/games/" + g + "/analysis").contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(analysis(7, 90))), perT));
            assertThat(code(r)).isEqualTo(ErrorCode.GAME_NOT_PARTICIPANT.code());
        }

        @Test
        void impossibleAnalysesAreRefused() throws Exception {
            long g = finishedWithMoves();
            for (Map<String, Object> bad : List.of(analysis(400, 90), analysis(7, 150), analysis(7, -3))) {
                MvcResult r = perform(as(put("/api/games/" + g + "/analysis").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(bad)), kariT));
                assertThat(code(r)).isIn(ErrorCode.ANALYSIS_INVALID.code(), ErrorCode.VALIDATION_FAILED.code());
            }
        }

        @Test
        void pendingGamesCannotBeAnalysedYet() throws Exception {
            Map<String, Object> body = game(kari, ola, "WHITE_WIN");
            body.put("pgn", "1. e4 e5 2. Bc4 Nc6 3. Qh5 Nf6 4. Qxf7# 1-0");
            long g = register(kariT, body);
            MvcResult r = perform(as(put("/api/games/" + g + "/analysis").contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(analysis(7, 90))), kariT));
            assertThat(code(r)).isEqualTo(ErrorCode.ANALYSIS_GAME_NOT_FINISHED.code());
        }
    }

    // --- tournaments ---------------------------------------------------------------------------

    @Nested
    class Tournaments {

        private long create(Map<String, Object> extra) throws Exception {
            Map<String, Object> body = new HashMap<>(Map.of("name", "Julecup", "format", "ROUND_ROBIN", "timeControl", "5+3"));
            body.putAll(extra);
            MvcResult r = postAs(admin, "/api/tournaments", body);
            assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isBetween(200, 201);
            return body(r).get("id").asLong();
        }

        private JsonNode detail(long t) throws Exception {
            return body(perform(as(get("/api/tournaments/" + t), admin)));
        }

        private long firstGame(long t) throws Exception {
            return detail(t).get("rounds").get(0).get("pairings").get(0).get("currentGame").get("id").asLong();
        }

        @Test
        void signUpClosesWhenTheTournamentStarts() throws Exception {
            long t = create(Map.of());
            postAs(kariT, "/api/tournaments/" + t + "/join", null);
            assertThat(code(postAs(kariT, "/api/tournaments/" + t + "/join", null))).isEqualTo(ErrorCode.TOURNAMENT_ALREADY_REGISTERED.code());
            postAs(olaT, "/api/tournaments/" + t + "/join", null);
            postAs(admin, "/api/tournaments/" + t + "/start", null);
            assertThat(code(postAs(perT, "/api/tournaments/" + t + "/join", null))).isEqualTo(ErrorCode.TOURNAMENT_INVALID_STATE.code());
            assertThat(code(postAs(kariT, "/api/tournaments/" + t + "/leave", null))).isEqualTo(ErrorCode.TOURNAMENT_INVALID_STATE.code());
        }

        @Test
        void aTournamentResultReportedByAPlayerWaitsForTheOpponent() throws Exception {
            long t = create(Map.of());
            postAs(kariT, "/api/tournaments/" + t + "/join", null);
            postAs(olaT, "/api/tournaments/" + t + "/join", null);
            postAs(admin, "/api/tournaments/" + t + "/start", null);
            long g = firstGame(t);
            JsonNode white = detail(t).get("rounds").get(0).get("pairings").get(0).get("white");
            String reporter = white.get("id").asLong() == kari ? kariT : olaT;
            String opponent = reporter.equals(kariT) ? olaT : kariT;
            assertThat(postAs(reporter, "/api/games/" + g + "/report", Map.of("result", "WHITE_WIN")).getResponse().getStatus()).isEqualTo(200);
            assertThat(status(g)).isEqualTo("PENDING");
            assertThat(detail(t).get("status").asText()).isEqualTo("ACTIVE");
            postAs(opponent, "/api/games/" + g + "/confirm", null);
            assertThat(detail(t).get("status").asText()).isEqualTo("FINISHED");
        }

        @Test
        void outsidersCannotReportOrStartTournamentGames() throws Exception {
            long t = create(Map.of());
            postAs(kariT, "/api/tournaments/" + t + "/join", null);
            postAs(olaT, "/api/tournaments/" + t + "/join", null);
            postAs(admin, "/api/tournaments/" + t + "/start", null);
            long g = firstGame(t);
            assertThat(code(postAs(perT, "/api/games/" + g + "/report", Map.of("result", "BLACK_WIN")))).isEqualTo(ErrorCode.GAME_NOT_PARTICIPANT.code());
            assertThat(code(postAs(perT, "/api/games/" + g + "/start", null))).isEqualTo(ErrorCode.GAME_NOT_PARTICIPANT.code());
            assertThat(status(g)).isEqualTo("SCHEDULED");
        }

        @Test
        void gamesOfACancelledTournamentCannotBeReported() throws Exception {
            long t = create(Map.of());
            postAs(kariT, "/api/tournaments/" + t + "/join", null);
            postAs(olaT, "/api/tournaments/" + t + "/join", null);
            postAs(admin, "/api/tournaments/" + t + "/start", null);
            long g = firstGame(t);
            postAs(admin, "/api/tournaments/" + t + "/cancel", null);
            assertThat(code(postAs(admin, "/api/games/" + g + "/report", Map.of("result", "DRAW")))).isEqualTo(ErrorCode.GAME_INVALID_STATE.code());
        }

        @Test
        void arenaGamesOnlyCountWhileTheArenaIsOpenAndBetweenParticipants() throws Exception {
            Instant start = Instant.now().minus(1, ChronoUnit.HOURS);
            long t = create(Map.of("format", "ARENA", "startsAt", start.toString(), "endsAt", start.plus(3, ChronoUnit.HOURS).toString()));
            postAs(kariT, "/api/tournaments/" + t + "/join", null);
            postAs(olaT, "/api/tournaments/" + t + "/join", null);
            assertThat(code(postAs(kariT, "/api/tournaments/" + t + "/arena-games", game(kari, ola, "WHITE_WIN"))))
                    .isIn(ErrorCode.TOURNAMENT_ARENA_CLOSED.code(), ErrorCode.TOURNAMENT_INVALID_STATE.code());
            postAs(admin, "/api/tournaments/" + t + "/start", null);
            assertThat(code(postAs(kariT, "/api/tournaments/" + t + "/arena-games", game(kari, per, "WHITE_WIN"))))
                    .isEqualTo(ErrorCode.TOURNAMENT_NOT_PARTICIPANT.code());
            assertThat(code(postAs(perT, "/api/tournaments/" + t + "/arena-games", game(kari, ola, "WHITE_WIN"))))
                    .isEqualTo(ErrorCode.GAME_NOT_PARTICIPANT.code());
            assertThat(postAs(kariT, "/api/tournaments/" + t + "/arena-games", game(kari, ola, "WHITE_WIN")).getResponse().getStatus()).isBetween(200, 201);
            postAs(admin, "/api/tournaments/" + t + "/finish", null);
            assertThat(code(postAs(kariT, "/api/tournaments/" + t + "/arena-games", game(kari, ola, "WHITE_WIN"))))
                    .isIn(ErrorCode.TOURNAMENT_ARENA_CLOSED.code(), ErrorCode.TOURNAMENT_INVALID_STATE.code());
        }

        @Test
        void aKnockoutWithoutADrawRuleIsRefused() throws Exception {
            MvcResult r = postAs(admin, "/api/tournaments", Map.of("name", "Cup", "format", "SINGLE_ELIMINATION", "timeControl", "5+3"));
            assertThat(code(r)).isEqualTo(ErrorCode.TOURNAMENT_INVALID_CONFIG.code());
        }
    }

    // --- ratings -------------------------------------------------------------------------------

    @Test
    void ratingsNeverFallBelowTheFloor() throws Exception {
        register(admin, game(kari, ola, "BLACK_WIN"));
        // Two low-rated equals: a loss costs about 20 points, which would take Kari to ~85.
        jdbc.update("UPDATE player_rating SET rating = 105 WHERE category = 'BLITZ'");
        register(admin, game(kari, ola, "BLACK_WIN"));
        assertThat(rating(kari, "BLITZ")).isEqualTo(100);
        assertThat(rating(ola, "BLITZ")).isGreaterThan(105);
    }

    @Test
    void theStakesShownBeforeAGameMatchWhatHappensAfterIt() throws Exception {
        long g = register(kariT, game(kari, ola, "WHITE_WIN"));
        JsonNode preview = body(perform(as(get("/api/games/" + g + "/preview"), kariT)));
        postAs(olaT, "/api/games/" + g + "/confirm", null);
        assertThat(preview.toString()).contains("20");
        assertThat(rating(kari, "BLITZ") - 1200).isEqualTo(20);
    }

    @Test
    void ratingHistoryOnlyContainsConfirmedRatedGames() throws Exception {
        register(kariT, game(kari, ola, "WHITE_WIN"));          // pending
        long rejected = register(kariT, game(kari, ola, "WHITE_WIN"));
        postAs(olaT, "/api/games/" + rejected + "/reject", Map.of());
        register(admin, game(kari, ola, "DRAW"));               // the only one that counts
        JsonNode history = body(perform(as(get("/api/users/" + kari + "/rating-history?category=BLITZ"), perT)));
        int gamePoints = 0;
        for (JsonNode point : history) {
            gamePoints += point.get("gameId").isNull() ? 0 : 1;   // the first point is the 1200 start
        }
        assertThat(gamePoints).isEqualTo(1);
    }

    @Test
    void featuringIsOnlyForAdminsAndOnlyChangesTv() throws Exception {
        long g = register(kariT, game(kari, ola, "DRAW"));
        assertThat(code(postAs(kariT, "/api/admin/games/" + g + "/feature", Map.of("featured", true)))).isEqualTo(ErrorCode.AUTH_ACCESS_DENIED.code());
        assertThat(perform(post("/api/admin/games/" + g + "/feature").with(fromIp(clientIp))).getResponse().getStatus()).isEqualTo(401);
    }
}
