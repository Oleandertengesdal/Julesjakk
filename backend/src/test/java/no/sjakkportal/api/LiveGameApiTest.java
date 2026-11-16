package no.sjakkportal.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import no.sjakkportal.common.error.ErrorCode;
import no.sjakkportal.support.ApiIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/** Games entered move by move while spectators and TV mode follow along. */
class LiveGameApiTest extends ApiIntegrationTest {

    private long kari;
    private long ola;
    private String kariToken;
    private String olaToken;
    private String perToken;
    private String adminToken;

    @BeforeEach
    void setUp() throws Exception {
        createAdmin("admin");
        kari = createPlayer("kari");
        ola = createPlayer("ola");
        createPlayer("per");
        kariToken = login("kari");
        olaToken = login("ola");
        perToken = login("per");
        adminToken = login("admin");
    }

    private long startLive() throws Exception {
        MvcResult result = mvc.perform(as(jsonPost("/api/games/live", Map.of("whiteId", kari, "blackId", ola)), kariToken)).andReturn();
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(201);
        return body(result).get("id").asLong();
    }

    private MvcResult move(String token, long game, String move, int expectedPly) throws Exception {
        return mvc.perform(as(jsonPost("/api/games/" + game + "/moves", Map.of("move", move, "expectedPly", expectedPly)), token)).andReturn();
    }

    private MvcResult finish(String token, long game, String result, String termination) throws Exception {
        return mvc.perform(as(jsonPost("/api/games/" + game + "/finish", Map.of("result", result, "termination", termination)), token))
                .andReturn();
    }

    private int rating(long user) {
        return jdbc.queryForObject("SELECT rating FROM app_user WHERE id = ?", Integer.class, user);
    }

    @Test
    void liveGameStartsWithoutAResultAndDoesNotTouchRatings() throws Exception {
        long game = startLive();
        mvc.perform(as(get("/api/games/" + game), perToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("LIVE"))
                .andExpect(jsonPath("$.result").doesNotExist())
                .andExpect(jsonPath("$.plyCount").value(0));
        assertThat(rating(kari)).isEqualTo(1200);
    }

    @Test
    void movesAreAppliedInOrderByEitherParticipant() throws Exception {
        long game = startLive();
        MvcResult first = move(kariToken, game, "e4", 0);
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(first).get("ply").asInt()).isEqualTo(1);
        assertThat(body(first).get("san").asText()).isEqualTo("e4");
        assertThat(body(first).get("fen").asText()).startsWith("rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b");
        // One person at the board may be entering the moves for both sides.
        assertThat(move(kariToken, game, "e7e5", 1).getResponse().getStatus()).isEqualTo(200);
        assertThat(move(olaToken, game, "Nf3", 2).getResponse().getStatus()).isEqualTo(200);

        mvc.perform(as(get("/api/games/" + game), perToken))
                .andExpect(jsonPath("$.plyCount").value(3))
                .andExpect(jsonPath("$.moves[1].san").value("e5"))
                .andExpect(jsonPath("$.moves[2].uci").value("g1f3"));
    }

    @Test
    void illegalMoveIsRejectedAndThePositionIsUnchanged() throws Exception {
        long game = startLive();
        assertThat(body(move(kariToken, game, "e5", 0)).get("code").asText()).isEqualTo(ErrorCode.GAME_ILLEGAL_MOVE.code());
        assertThat(body(move(kariToken, game, "<script>", 0)).get("code").asText()).isEqualTo(ErrorCode.GAME_ILLEGAL_MOVE.code());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM game_move", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT ply_count FROM game WHERE id = ?", Integer.class, game)).isZero();
    }

    @Test
    void staleClientIsToldToReloadInsteadOfOverwritingTheBoard() throws Exception {
        long game = startLive();
        move(kariToken, game, "e4", 0);
        // Ola's tablet still shows the starting position and tries to play "the first move".
        assertThat(body(move(olaToken, game, "d4", 0)).get("code").asText()).isEqualTo(ErrorCode.GAME_CONCURRENT_UPDATE.code());
        assertThat(body(move(olaToken, game, "e5", 7)).get("code").asText()).isEqualTo(ErrorCode.GAME_CONCURRENT_UPDATE.code());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM game_move WHERE game_id = ?", Integer.class, game)).isEqualTo(1);
    }

    @Test
    void simultaneousEntryOfTheSameMoveIsRecordedOnce() throws Exception {
        long game = startLive();
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            String token = i % 2 == 0 ? kariToken : olaToken;
            String candidate = i % 2 == 0 ? "e4" : "d4";
            tasks.add(() -> move(token, game, candidate, 0).getResponse().getStatus());
        }
        List<Integer> statuses = runConcurrently(tasks);

        assertThat(statuses.stream().filter(s -> s == 200).count()).isEqualTo(1);
        assertThat(statuses).allMatch(s -> s == 200 || s == 409);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM game_move WHERE game_id = ?", Integer.class, game)).isEqualTo(1);
        // Stored position and move list agree.
        String san = jdbc.queryForObject("SELECT san FROM game_move WHERE game_id = ? AND ply = 1", String.class, game);
        String fen = jdbc.queryForObject("SELECT current_fen FROM game WHERE id = ?", String.class, game);
        assertThat(fen).contains(san.equals("e4") ? "4P3" : "3P4");
        assertThat(jdbc.queryForObject("SELECT ply_count FROM game WHERE id = ?", Integer.class, game)).isEqualTo(1);
    }

    @Test
    void outsiderCannotMoveFinishOrAbortSomeoneElsesGame() throws Exception {
        long game = startLive();
        assertThat(body(move(perToken, game, "e4", 0)).get("code").asText()).isEqualTo(ErrorCode.GAME_NOT_PARTICIPANT.code());
        assertThat(body(finish(perToken, game, "BLACK_WIN", "RESIGNATION")).get("code").asText())
                .isEqualTo(ErrorCode.GAME_NOT_PARTICIPANT.code());
        mvc.perform(as(jsonPost("/api/games/" + game + "/abort", Map.of()), perToken)).andExpect(problem(ErrorCode.GAME_NOT_PARTICIPANT));

        assertThat(jdbc.queryForObject("SELECT status FROM game WHERE id = ?", String.class, game)).isEqualTo("LIVE");
        assertThat(rating(kari)).isEqualTo(1200);
    }

    @Test
    void outsiderCannotStartALiveGameForOthersButAnAdminCan() throws Exception {
        mvc.perform(as(jsonPost("/api/games/live", Map.of("whiteId", kari, "blackId", ola)), perToken))
                .andExpect(problem(ErrorCode.GAME_NOT_PARTICIPANT));
        mvc.perform(as(jsonPost("/api/games/live", Map.of("whiteId", kari, "blackId", ola)), adminToken))
                .andExpect(status().isCreated());
    }

    @Test
    void checkmateFinishesTheGameAndUpdatesRatingsAutomatically() throws Exception {
        long game = startLive();
        String[] moves = {"f3", "e5", "g4"};
        for (int i = 0; i < moves.length; i++) {
            assertThat(move(kariToken, game, moves[i], i).getResponse().getStatus()).isEqualTo(200);
        }
        MvcResult mate = move(olaToken, game, "Qh4", 3);

        assertThat(body(mate).get("san").asText()).isEqualTo("Qh4#");
        assertThat(body(mate).get("gameOver").asBoolean()).isTrue();
        mvc.perform(as(get("/api/games/" + game), perToken))
                .andExpect(jsonPath("$.status").value("FINISHED"))
                .andExpect(jsonPath("$.result").value("BLACK_WIN"))
                .andExpect(jsonPath("$.termination").value("CHECKMATE"));
        assertThat(rating(ola)).isEqualTo(1220);
        assertThat(rating(kari)).isEqualTo(1180);
        assertThat(body(move(kariToken, game, "a3", 4)).get("code").asText()).isEqualTo(ErrorCode.GAME_INVALID_STATE.code());
    }

    @Test
    void resignationFinishesTheGameOnce() throws Exception {
        long game = startLive();
        move(kariToken, game, "e4", 0);
        assertThat(finish(olaToken, game, "WHITE_WIN", "RESIGNATION").getResponse().getStatus()).isEqualTo(200);
        assertThat(rating(kari)).isEqualTo(1220);

        assertThat(body(finish(kariToken, game, "BLACK_WIN", "RESIGNATION")).get("code").asText())
                .isEqualTo(ErrorCode.GAME_INVALID_STATE.code());
        assertThat(rating(kari)).isEqualTo(1220);
        assertThat(jdbc.queryForObject("SELECT result FROM game WHERE id = ?", String.class, game)).isEqualTo("WHITE_WIN");
    }

    @Test
    void simultaneousFinishRequestsMoveRatingsExactlyOnce() throws Exception {
        long game = startLive();
        move(kariToken, game, "e4", 0);
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            String token = i % 2 == 0 ? kariToken : olaToken;
            // The two players even disagree about who won.
            String result = i % 2 == 0 ? "WHITE_WIN" : "BLACK_WIN";
            tasks.add(() -> finish(token, game, result, "RESIGNATION").getResponse().getStatus());
        }
        List<Integer> statuses = runConcurrently(tasks);

        assertThat(statuses.stream().filter(s -> s == 200).count()).isEqualTo(1);
        assertThat(statuses).allMatch(s -> s == 200 || s == 409);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM rating_history WHERE game_id = ?", Integer.class, game)).isEqualTo(2);
        assertThat(rating(kari) + rating(ola)).isEqualTo(2400);
        assertThat(Math.abs(rating(kari) - 1200)).isEqualTo(20);
    }

    @Test
    void finishingWithAResultThePositionForbidsIsRejected() throws Exception {
        long game = startLive();
        String[] moves = {"f3", "e5", "g4"};
        for (int i = 0; i < moves.length; i++) {
            move(kariToken, game, moves[i], i);
        }
        // Not mate yet: claiming checkmate is a lie the server can check.
        assertThat(finish(kariToken, game, "WHITE_WIN", "CHECKMATE").getResponse().getStatus()).isIn(409, 422);
        assertThat(jdbc.queryForObject("SELECT status FROM game WHERE id = ?", String.class, game)).isEqualTo("LIVE");
    }

    @Test
    void abortedGameHasNoResultAndNoRatingChange() throws Exception {
        long game = startLive();
        move(kariToken, game, "e4", 0);
        mvc.perform(as(jsonPost("/api/games/" + game + "/abort", Map.of()), olaToken)).andExpect(status().isOk());

        assertThat(jdbc.queryForObject("SELECT status FROM game WHERE id = ?", String.class, game)).isEqualTo("ABORTED");
        assertThat(rating(kari)).isEqualTo(1200);
        assertThat(body(move(kariToken, game, "e5", 1)).get("code").asText()).isEqualTo(ErrorCode.GAME_INVALID_STATE.code());
    }

    @Test
    void movesOnAnUnknownGameAreNotFound() throws Exception {
        assertThat(body(move(kariToken, 424242, "e4", 0)).get("code").asText()).isEqualTo(ErrorCode.GAME_NOT_FOUND.code());
    }
}
